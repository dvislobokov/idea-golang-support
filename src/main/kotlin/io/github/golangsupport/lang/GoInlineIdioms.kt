package io.github.golangsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.IndexNotReadyException
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.lang.psi.GoFile

/**
 * [GoIdioms] as grey text at the caret: Enter after `f, err := os.Open(name)` shows `if err != nil { return nil, err }`, Tab accepts it,
 * typing anything else makes it go away. The mechanism is the inline completion of the platform, the one AI assistants use; here the
 * suggestions are rules, so they are instant, work offline and never invent anything. On a go-psi file the types decide where the text
 * guessed ([GoIdiomTypes]): `defer x.Close()` only for what has `Close() error`, `if err != nil` only after a call whose last result is
 * `error`, and the cases of an empty `select {`, `switch x {`, `switch v := x.(type) {` or `for {` come from what is in scope.
 */
class GoInlineIdiomsProvider : InlineCompletionProvider {
    override val id: InlineCompletionProviderID get() = InlineCompletionProviderID("io.github.golangsupport.idioms")

    /** A new line, or a character typed at the start of one (the beginning of the suggestion: `i`, `if`, `de`...). */
    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (event !is InlineCompletionEvent.DocumentChange || !GoSettings.getInstance().inlineIdioms) return false
        return event.toRequest()?.file is GoFile
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val text = readAction {
            val options = CodeStyle.getIndentOptions(request.file)
            val unit = if (options.USE_TAB_CHARACTER) "\t" else " ".repeat(options.INDENT_SIZE)
            // the offset of the request is where the typing started; the caret is past what was typed
            val text = request.document.immutableCharSequence
            try {
                GoIdioms.suggest(text, request.endOffset, unit, GoIdiomTypes.of(request.file, request.document, request.endOffset))
            } catch (_: IndexNotReadyException) {
                GoIdioms.suggest(text, request.endOffset, unit)
            }
        } ?: return InlineCompletionSuggestion.Empty
        return InlineCompletionSingleSuggestion.build { emit(InlineCompletionGrayTextElement(text)) }
    }
}
