package io.github.golangsupport.ide.hints

import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoTimeLayout
import io.github.golangsupport.ide.inspections.GoTimeLayoutCalls
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoConstant

/**
 * `t.Format("2006-01-02 15:04"/*→ 2026-03-07 15:09*/)`: the layout argument of `Time.Format`, `AppendFormat`, `time.Parse` and
 * `ParseInLocation` (found by resolve, see [GoTimeLayoutCalls]) rendered with the fixed [GoTimeLayout.SAMPLE]. A string literal or a
 * reference to a string constant (`time.RFC3339`, a constant of the project); nothing for other arguments. Gated like the other hints.
 */
class GoTimeLayoutHintsProvider : GoHintsProvider() {
    override fun collector(file: GoFile, editor: Editor): InlayHintsCollector = object : SharedBypassCollector {
        private val semantic = GoSemanticService.getInstance(file.project)

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            val call = element as? GoCallExpr ?: return
            val argument = GoTimeLayoutCalls.layoutArgument(call) ?: return
            val layout = (semantic.constantValue(argument) as? GoConstant.Str)?.value ?: return
            if (layout.isEmpty()) return
            inline(sink, argument.textRange.endOffset, "→ " + GoTimeLayout.render(layout), relatedToPrevious = true, tooltip = "Rendered for Saturday 2026-03-07 15:09:08.123456789 +03:00 MSK")
        }
    }
}
