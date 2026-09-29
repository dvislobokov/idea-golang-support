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

/** `return` inside a function with several results: all of its values as one item, `nil, err` ([GoIdioms.returnValues]). Works without gopls. */
class GoReturnCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile !is GoFile || !GoSettings.getInstance().completeReturnValues) return
        val text = parameters.editor.document.immutableCharSequence
        val values = GoIdioms.returnValues(text, parameters.offset) ?: return
        val item = LookupElementBuilder.create(values).bold().withIcon(AllIcons.Actions.StepOut).withTypeText("return values", true)
        result.withPrefixMatcher(GoCompletionOrder.typed(text, parameters.offset)).addElement(PrioritizedLookupElement.withPriority(item, GoCompletionOrder.RETURN_VALUES))
    }
}
