package io.github.golangsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.inline.completion.DefaultInlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.IndexNotReadyException
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.scope.GoScopes

/**
 * [GoIdioms] as grey text at the caret: Enter after `f, err := os.Open(name)` shows `if err != nil { return nil, err }`, Tab accepts it,
 * typing anything else makes it go away. The mechanism is the inline completion of the platform, the one AI assistants use; here the
 * suggestions are rules, so they are instant, work offline and never invent anything. On a go-psi file the types decide where the text
 * guessed ([GoIdiomTypes]): `defer x.Close()` only for what has `Close() error`, `if err != nil` only after a call whose last result is
 * `error`, and the cases of an empty `select {`, `switch x {`, `switch v := x.(type) {` or `for {` come from what is in scope.
 *
 * The same provider shows [GoInlineSuggestions] (code from the context: `make([]string, 0, len(keys))` after `arr := `) where the idioms
 * have nothing: the platform asks only the first provider that is enabled, so the two cannot be separate providers. Accepting such a
 * suggestion adds the imports it needs. With [GoSettings.inlineSuggestionColors] the text is shown in the colours of the code, muted
 * ([GoInlineColors]): one element per run of a colour, which the partial acceptance of the platform splits as it splits the grey one.
 */
class GoInlineIdiomsProvider : InlineCompletionProvider {
    override val id: InlineCompletionProviderID get() = InlineCompletionProviderID("io.github.golangsupport.idioms")

    /** The last suggestion with imports or a caret to place, for [insertHandler]. */
    @Volatile
    private var pending: GoInlineSuggestions.Suggestion? = null

    /**
     * A new line, or a character typed at the start of one (the beginning of the suggestion: `i`, `if`, `de`...), or in a slot. Also when
     * the completion list opens or moves: while it is shown the platform hides the grey text of a typing event, and GoLand shows both
     * (seen live: `tables := ma` showed only the list).
     */
    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (event !is InlineCompletionEvent.DocumentChange && event !is InlineCompletionEvent.InlineLookupEvent) return false
        val settings = GoSettings.getInstance()
        if (!settings.inlineIdioms && !settings.inlineSuggestions) return false
        return event.toRequest()?.file is GoFile
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val settings = GoSettings.getInstance()
        val shown = readAction {
            val options = CodeStyle.getIndentOptions(request.file)
            val unit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
            // the offset of the request is where the typing started; the caret is past what was typed
            val text = request.document.immutableCharSequence
            val idiom = if (!settings.inlineIdioms) null else try {
                GoIdioms.suggest(text, request.endOffset, unit, GoIdiomTypes.of(request.file, request.document, request.endOffset))
            } catch (_: IndexNotReadyException) {
                GoIdioms.suggest(text, request.endOffset, unit)
            }
            val suggestion = idiom?.let { GoInlineSuggestions.Suggestion(it, emptySet()) }
                ?: (if (settings.inlineSuggestions) GoInlineSuggestions.suggest(request.file, text, request.endOffset, unit) else null) ?: return@readAction null
            suggestion to if (settings.inlineSuggestionColors) runs(request, text, suggestion) else null
        } ?: return InlineCompletionSuggestion.Empty
        val (suggestion, runs) = shown
        pending = if (suggestion.imports.isEmpty() && suggestion.caretBack == 0) null else suggestion
        val elements = runs?.let(GoInlineColors::elements) ?: listOf(InlineCompletionGrayTextElement(suggestion.text))
        return InlineCompletionSingleSuggestion.build { elements.forEach { emit(it) } }
    }

    /** The suggestion in runs of the colours of the code, lexed with what precedes it on its line. */
    private fun runs(request: InlineCompletionRequest, text: CharSequence, suggestion: GoInlineSuggestions.Suggestion): List<GoInlineColors.Run> {
        var lineStart = request.endOffset
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val file = request.file as? GoFile
        val names = (file?.let { GoInlineColors.namesAt(it, request.document, request.endOffset) } ?: GoInlineColors.Names.NONE)
            .plus(suggestion.imports.map { GoScopes.defaultImportName(it) })
        return GoInlineColors.runs(suggestion.text, text.subSequence(lineStart, request.endOffset).toString(), names)
    }

    override val insertHandler: InlineCompletionInsertHandler = object : InlineCompletionInsertHandler {
        override fun afterInsertion(environment: InlineCompletionInsertEnvironment, elements: List<InlineCompletionElement>) {
            DefaultInlineCompletionInsertHandler.INSTANCE.afterInsertion(environment, elements)
            val suggestion = pending ?: return
            pending = null
            val editor = environment.editor
            val document = editor.document
            val range = environment.insertedRange
            if (range.endOffset > document.textLength) return
            // what was typed over the suggestion before it was accepted is not in the inserted range
            val inserted = document.immutableCharSequence.subSequence(range.startOffset, range.endOffset).toString()
            if (inserted.isEmpty() || !suggestion.text.endsWith(inserted)) return
            // the caret goes inside what was inserted (into the quotes of `regexp.MustCompile(``)`) before the imports move it
            if (suggestion.caretBack in 1..inserted.length) editor.caretModel.moveToOffset(range.endOffset - suggestion.caretBack)
            GoInlineSuggestions.addImports(document, suggestion.imports)
        }
    }
}
