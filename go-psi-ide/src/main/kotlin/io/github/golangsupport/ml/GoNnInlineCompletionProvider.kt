package io.github.golangsupport.ml

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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.psi.GoFile

/** The network behind [GoNnInlineCompletionProvider]; [GoMlModels] implements it, tests fake it. */
interface GoNnEngine {
    /** The line at the caret of [editor] (the key of its KV-cache session), or null while the network is not ready. Suspends until its thread is free. */
    suspend fun complete(editor: Any, context: GoNnInline.Context): GoNnInline.Answer?
    /** A shown suggestion was accepted. */
    fun accepted() {}
}

/** The pure part of the grey text: what the network gets from the document and what of its answer is shown. */
object GoNnInline {
    /** The text before the caret the network sees (its prompt cuts 40 KB forward to a line start). */
    const val PREFIX_BYTES = 40_000
    /** The text after the line of the caret the network sees. */
    const val SUFFIX_BYTES = 16_000

    /** UTF-8 as the document has it (`\n` line ends), [path] relative to the project root like in training. */
    class Context(val path: ByteArray, val before: ByteArray, val after: ByteArray)
    class Answer(val text: String, val show: Boolean, val confProd: Double)

    /** [PREFIX_BYTES] before [offset]; after it the rest of its line and [SUFFIX_BYTES] more. */
    fun context(text: CharSequence, offset: Int, path: String): Context {
        // a char is at least one UTF-8 byte: PREFIX_BYTES chars hold enough bytes, the byte cut is exact
        var from = maxOf(0, offset - PREFIX_BYTES)
        if (from > 0 && Character.isLowSurrogate(text[from])) from++
        val before = utf8(text.subSequence(from, offset)).let { if (it.size > PREFIX_BYTES) it.copyOfRange(it.size - PREFIX_BYTES, it.size) else it }
        var lineEnd = offset
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        var to = minOf(text.length, lineEnd + SUFFIX_BYTES)
        if (to < text.length && to > lineEnd && Character.isHighSurrogate(text[to - 1])) to--
        val line = utf8(text.subSequence(offset, lineEnd))
        val rest = utf8(text.subSequence(lineEnd, to)).let { if (it.size > SUFFIX_BYTES) it.copyOf(SUFFIX_BYTES) else it }
        return Context(path.toByteArray(Charsets.UTF_8), before, line + rest)
    }

    /** [filePath] relative to [basePath] with `/`, or the file name when it lies outside. */
    fun relativePath(basePath: String?, filePath: String): String {
        val base = basePath?.replace('\\', '/')?.trimEnd('/')
        val path = filePath.replace('\\', '/')
        return if (base != null && base.isNotEmpty() && path.startsWith("$base/")) path.substring(base.length + 1) else path.substringAfterLast('/')
    }

    /**
     * The grey text of [answer]: only what the model's own policy shows, without the tail that the line already has after the caret.
     * The model writes the line to its end and sees what is there: at `Validate() (int⟨⟩) {` it answered `, error) {` (seen live, accepted
     * as `(int, error) {) {`); the engine drops such a tail only when it is closers, `{` is not one.
     */
    fun text(answer: Answer?, after: ByteArray = ByteArray(0)): String? {
        val text = answer?.takeIf { it.show }?.text?.takeIf { it.isNotEmpty() } ?: return null
        return trimOverlap(text, restOfLine(after)).takeIf { it.isNotEmpty() }
    }

    /** [text] without its longest tail that is also the start of [rest] (whitespace included: `, error) {` before `) {` → `, error`). */
    fun trimOverlap(text: String, rest: String): String {
        for (n in minOf(text.length, rest.length) downTo 1) if (text.regionMatches(text.length - n, rest, 0, n)) return text.dropLast(n)
        return text
    }

    /** The current line of [after] (what follows the caret up to the line end). */
    fun restOfLine(after: ByteArray): String {
        var n = 0
        while (n < after.size && after[n] != '\n'.code.toByte()) n++
        return String(after, 0, n, Charsets.UTF_8)
    }

    /**
     * The model's confidence over its code only: the tokens inside a string literal or a line comment are free text, each word of it is
     * unlikely on its own and the product over the line never reaches the gate (seen live: `fmt.` → `Errorf("store: no items")` at 0.016
     * while `Errorf("` alone is near-certain). Counted: every token that starts and ends outside a literal and the token that ends the line; [lineBefore] (the current line up to the healed boundary) gives the state the caret is in.
     */
    fun codeConfidence(lineBefore: ByteArray, tokens: List<ByteArray>, logProbs: FloatArray, stopLogProb: Float): Double {
        val state = LiteralState().apply { scan(lineBefore) }
        var sum = 0.0
        for (i in tokens.indices) {
            // the tokens that open and close the literal are part of the guess too: `("` splits its mass with `(` + `"` (0.73 at
            // `fmt.Errorf`), and the closing `")` is as unsure as the message (0.27); counted with them: 0.64 / 0.18, without: 0.89
            val code = !state.inText
            state.scan(tokens[i])
            if (code && !state.inText) sum += logProbs[i]
        }
        if (!stopLogProb.isNaN() && !state.inText) sum += stopLogProb
        return Math.exp(sum)
    }

    /**
     * The certain start of a suggestion whose whole line is not: the longest run of its tokens, cut at a word boundary, whose product
     * of probabilities passes [gate] — without the [typed] bytes the first tokens reproduce. Null when nothing worth showing is left
     * (a lone first word on a fresh line, fewer than three word bytes, an open bracket or a trailing comma). Seen live: `if le` → ` len(o.items) == 0 {` at 0.47 with every token ≥ 0.96 but
     * ` ==` at 0.53 — `n(o.items)` is what the model knows, the comparison is the user's.
     */
    fun certainPrefix(tokens: List<ByteArray>, logProbs: FloatArray, typed: Int, gate: Double): ByteArray? {
        var sum = 0.0
        var best = -1
        for (i in tokens.indices) {
            sum += logProbs[i]
            if (Math.exp(sum) < gate) break
            best = i
        }
        // the longest cut that reads as a finished piece: at a word boundary, never inside an identifier, ending with a word or a
        // closing bracket (not `for _,` — seen live, Tab inserted ` _,`), brackets balanced, at least three word bytes beyond what is typed
        val raw = ByteArray(tokens.sumOf { it.size })
        var n = 0
        for (t in tokens) { System.arraycopy(t, 0, raw, n, t.size); n += t.size }
        val ends = IntArray(tokens.size); var end = 0
        for (i in tokens.indices) { end += tokens[i].size; ends[i] = end }
        for (i in best downTo 0) {
            if (typed == 0 && i == 0) break   // a lone first word is no suggestion (seen in the log: `if` alone for `if len(o.items) == 0 {`)
            if (i < tokens.lastIndex && isWordByte(tokens[i + 1][0]) && isWordByte(tokens[i].last())) continue
            if (ends[i] <= typed) break
            if (finished(raw, typed, ends[i])) return raw.copyOfRange(typed, ends[i])
        }
        return null
    }

    private fun finished(raw: ByteArray, from: Int, to: Int): Boolean {
        val last = raw[to - 1].toInt().toChar()
        if (!isWordByte(raw[to - 1]) && last != ')' && last != ']' && last != '}') return false
        var depth = 0; var words = 0
        for (i in from until to) {
            when (raw[i].toInt().toChar()) { '(', '[', '{' -> depth++; ')', ']', '}' -> depth-- }
            if (isWordByte(raw[i])) words++
        }
        return depth == 0 && words >= 3
    }

    private fun isWordByte(b: Byte): Boolean {
        val c = b.toInt() and 0xff
        return c in 65..90 || c in 97..122 || c in 48..57 || c == 95 || c >= 128
    }

    /** True when the caret's line has nothing but indentation before it: the line just opened by Enter, where the whole statement is a guess. */
    fun blankLine(before: ByteArray): Boolean {
        var i = before.size
        while (i > 0 && before[i - 1] != '\n'.code.toByte()) { val c = before[i - 1].toInt(); if (c != ' '.code && c != '\t'.code) return false; i-- }
        return true
    }

    /** The current line of [before] without its last [typed] bytes (the healed remainder the model reproduced). */
    fun lineBefore(before: ByteArray, typed: Int): ByteArray {
        val end = maxOf(0, before.size - typed)
        var start = end
        while (start > 0 && before[start - 1] != '\n'.code.toByte()) start--
        return before.copyOfRange(start, end)
    }

    /** Where in a Go line a byte stream stands: inside `"…"` (with `\` escapes), inside `` `…` ``, after `//`, or in code. */
    private class LiteralState {
        private var quote = 0   // 0, '"' or '`'
        private var escaped = false
        private var comment = false
        private var lastSlash = false
        val inText: Boolean get() = quote != 0 || comment

        fun scan(bytes: ByteArray) {
            for (b in bytes) {
                val c = b.toInt() and 0xff
                when {
                    comment -> if (c == '\n'.code) comment = false
                    quote == '"'.code -> when {
                        escaped -> escaped = false
                        c == '\\'.code -> escaped = true
                        c == '"'.code || c == '\n'.code -> quote = 0
                    }
                    quote == '`'.code -> if (c == '`'.code) quote = 0
                    c == '"'.code || c == '`'.code -> quote = c
                    c == '/'.code && lastSlash -> comment = true
                }
                lastSlash = c == '/'.code && quote == 0 && !comment
            }
        }
    }

    fun suggestion(text: String?): InlineCompletionSuggestion =
        if (text == null) InlineCompletionSuggestion.Empty else InlineCompletionSingleSuggestion.build { emit(InlineCompletionGrayTextElement(text)) }

    private fun utf8(s: CharSequence): ByteArray = s.toString().toByteArray(Charsets.UTF_8)
}

/**
 * Grey text to the end of the line from our transformer ([GoMlModels], `NnCompletion` of the engine) while typing in a Go file or on an
 * explicit call; Tab accepts it. Only what the model's policy shows (`confProd ≥` [GoMlSettings.inlineThreshold], no lone closers unless
 * [GoMlSettings.inlineShowClosers]), otherwise nothing.
 *
 * The platform asks only the first enabled provider, and the idioms of the host (`GoInlineIdiomsProvider`: `if err != nil` after Enter,
 * suggestions from the context) are enabled on every change in a Go file. So this provider is registered `order="first"` and asks the
 * next enabled provider first: its rules are exact and instant, the network fills where they have nothing; the insert handler of whoever
 * answered applies (the idioms add imports on accept).
 */
class GoNnInlineCompletionProvider internal constructor(private val engine: () -> GoNnEngine) : InlineCompletionProvider {
    constructor() : this({ GoMlModels.getInstance() })

    override val id: InlineCompletionProviderID get() = InlineCompletionProviderID("io.github.golangsupport.nn")

    /** The next enabled provider for an event, found in [isEnabled] (on the platform's thread, where they may look at the editor). */
    @Volatile private var next: Pair<InlineCompletionEvent, InlineCompletionProvider>? = null
    /** Who answered the last request when it was not the network, for [insertHandler]. */
    @Volatile private var answered: InlineCompletionProvider? = null

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        // lookup events too: while the completion list is open the platform hides the grey text of a typing event, and GoLand shows both
        // (seen live: `return le` with the list showed no grey text until the list closed)
        if (event !is InlineCompletionEvent.DocumentChange && event !is InlineCompletionEvent.DirectCall && event !is InlineCompletionEvent.InlineLookupEvent) return false
        if (!GoMlSettings.getInstance().inlineEnabled || event.toRequest()?.file !is GoFile) return false
        val providers = InlineCompletionProvider.EP_NAME.extensionList
        next = providers.indexOfFirst { it === this }.takeIf { it >= 0 }
            ?.let { i -> providers.subList(i + 1, providers.size).firstOrNull { it.isEnabled(event) } }?.let { event to it }
        return true
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        next?.takeIf { it.first === request.event }?.second?.let { provider ->
            val suggestion = provider.getSuggestion(request)
            if (suggestion !== InlineCompletionSuggestion.Empty) { answered = provider; return suggestion }
        }
        answered = null
        val context = readAction { GoNnInline.context(request.document.immutableCharSequence, request.endOffset, path(request.file)) }
        return GoNnInline.suggestion(GoNnInline.text(engine().complete(request.editor, context), context.after))
    }

    private fun path(file: PsiFile): String =
        GoNnInline.relativePath(file.project.basePath, file.virtualFile?.path ?: file.originalFile.virtualFile?.path ?: file.name)

    override val insertHandler: InlineCompletionInsertHandler = object : InlineCompletionInsertHandler {
        override fun afterInsertion(environment: InlineCompletionInsertEnvironment, elements: List<InlineCompletionElement>) {
            val other = answered
            if (other != null) return other.insertHandler.afterInsertion(environment, elements)
            DefaultInlineCompletionInsertHandler.INSTANCE.afterInsertion(environment, elements)
            engine().accepted()
        }
    }
}

/** Loads and warms up the network when the first Go editor opens (the first call costs up to a second); frees an editor's KV cache when it closes. */
class GoNnEditorListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val settings = GoMlSettings.getInstance()
        if (!settings.inlineEnabled || !GoMlModels.isNnBundled && settings.modelDirectory.isBlank()) return
        if (FileDocumentManager.getInstance().getFile(event.editor.document)?.fileType != GoFileType) return
        GoMlModels.getInstance().nn(settings.modelDirectory)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        ApplicationManager.getApplication().serviceIfCreated<GoMlModels>()?.release(event.editor)
    }
}
