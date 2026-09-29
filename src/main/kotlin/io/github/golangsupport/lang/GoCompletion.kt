package io.github.golangsupport.lang

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import io.github.golangsupport.settings.GoSettings

/**
 * The order of the completion list. gopls matches fuzzily and ranks by a score of its own: `return ni` gives `net.IP`, `net.IPAddr`,
 * `net.IPConn` and only then `nil` (seen live), and the platform keeps the order of the server. A name that begins with what is typed
 * goes first, as in GoLand; among equals the order of the server stays.
 */
object GoCompletionOrder {
    const val RETURN_VALUES = 1000.0

    /** Added to the priority of a value of the type the code wants: above the others that begin the same way, below a better beginning. */
    const val FITS = 0.5

    /** The identifier that ends at [offset]: what the completion is asked for. */
    fun typed(text: CharSequence, offset: Int): String {
        var start = offset.coerceIn(0, text.length)
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        return text.subSequence(start, offset.coerceIn(start, text.length)).toString()
    }

    fun priority(typed: String, name: String): Double = when {
        typed.isEmpty() -> 0.0
        name.startsWith(typed) -> 2.0
        name.startsWith(typed, ignoreCase = true) -> 1.0
        else -> 0.0
    }
}

/**
 * The snippets of gopls, made ready for the platform. A snippet of the protocol escapes `}` and `\` with a backslash, and gopls does:
 * the function it offers where one is expected is `func(i, j int) bool {$0\}` (checked with its answer). The converter of the
 * platform leaves the backslash in the code (seen live: `{\` and the brace on the next line), so it is taken away here. Inside a
 * placeholder (`${1:...}`) the text is left as it is: there the brace would end the placeholder.
 */
object GoSnippets {
    fun unescape(snippet: String): String {
        if ('\\' !in snippet) return snippet
        val result = StringBuilder(snippet.length)
        var depth = 0
        var i = 0
        while (i < snippet.length) {
            val c = snippet[i]
            val next = snippet.getOrNull(i + 1)
            when {
                c == '\\' && depth == 0 && (next == '}' || next == '\\') -> {
                    result.append(next)
                    i++
                }
                // an escaped character of a placeholder, and `\$` anywhere: a dollar without it would begin a variable
                c == '\\' && next != null -> {
                    result.append(c).append(next)
                    i++
                }
                c == '$' && next == '{' -> {
                    depth++
                    result.append("\${")
                    i++
                }
                c == '}' && depth > 0 -> {
                    depth--
                    result.append(c)
                }
                else -> result.append(c)
            }
            i++
        }
        return result.toString()
    }
}

/** `return` inside a function with several results: all of its values as one item, `nil, err` ([GoIdioms.returnValues]). Works without gopls. */
class GoReturnCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile !is GoFile || !GoSettings.getInstance().completeReturnValues) return
        val text = parameters.editor.document.immutableCharSequence
        val values = GoIdioms.returnValues(text, parameters.offset) ?: return
        val item = LookupElementBuilder.create(values).bold().withIcon(AllIcons.Actions.StepOut).withTypeText("return values", true)
        result.withPrefixMatcher(GoPrefixMatcher(GoCompletionOrder.typed(text, parameters.offset))).addElement(PrioritizedLookupElement.withPriority(item, GoCompletionOrder.RETURN_VALUES))
    }
}
