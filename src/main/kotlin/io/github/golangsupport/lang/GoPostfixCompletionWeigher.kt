package io.github.golangsupport.lang

import com.intellij.codeInsight.completion.CompletionLocation
import com.intellij.codeInsight.completion.CompletionWeigher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.template.postfix.completion.PostfixTemplateLookupElement
import io.github.golangsupport.lang.psi.GoFile

/**
 * Postfix keys after the members in a Go file, as in GoLand (`c.` lists the fields and methods, then `p`, `panic`, `par`…). The
 * platform's live template contributor adds the keys to the same list; without this a promoted member or an item the scope ranks low
 * could fall behind them. Registered right after `priority` (it is `first`) and before the scope ranking of go-psi; everything else
 * weighs the same here.
 */
class GoPostfixCompletionWeigher : CompletionWeigher() {
    override fun weigh(element: LookupElement, location: CompletionLocation): Comparable<*> =
        if (location.completionParameters.originalFile is GoFile && isPostfix(element)) 0 else 1

    private fun isPostfix(element: LookupElement): Boolean {
        var e: LookupElement = element
        while (true) {
            if (e is PostfixTemplateLookupElement) return true
            e = (e as? LookupElementDecorator<*>)?.delegate ?: return false
        }
    }
}
