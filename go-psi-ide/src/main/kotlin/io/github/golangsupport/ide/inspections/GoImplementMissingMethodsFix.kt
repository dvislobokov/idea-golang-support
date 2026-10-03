package io.github.golangsupport.ide.inspections

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.intentions.GoCreateEdits
import io.github.golangsupport.ide.intentions.GoCreatePlan
import io.github.golangsupport.ide.intentions.GoImplementStubs
import io.github.golangsupport.ide.intentions.GoSourceText
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.semantic.api.GoDiagnostic
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypeRenderer
import io.github.golangsupport.ide.intentions.GoZeroValues

/**
 * `cannot use x (variable of type T) as I value in ...: T does not implement I (missing method M)` (class `assignability`: assignment,
 * argument, return, composite literal element) and `impossible type assertion: i.(T)` (class `type-assertion`): adds the methods `T` lacks
 * ([GoImplementStubs]) after its last method, with a pointer receiver when the value is a `*T`, a value receiver for a `T`. The fix is
 * computed from the PSI and the types at the diagnostic, not from the message.
 */
class GoImplementMissingMethodsFix(private val title: String) : LocalQuickFix {

    override fun getFamilyName(): String = "Implement missing methods"

    override fun getName(): String = title

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        GoCreateEdits.apply(planAt(element) ?: return)
    }

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        val element = previewDescriptor.psiElement ?: return IntentionPreviewInfo.EMPTY
        return GoCreateEdits.preview(planAt(element), element.containingFile)
    }

    companion object {
        private class Target(val named: GoNamedType, val iface: GoInterfaceType, val pointer: Boolean, val ifaceType: GoType)

        fun create(file: GoFile, d: GoDiagnostic, element: PsiElement): GoImplementMissingMethodsFix? {
            if (d.code != "assignability" && d.code != "type-assertion") return null
            val target = targetAt(element) ?: return null
            GoImplementStubs.compute(target.named.declaration, target.iface, target.pointer) ?: return null
            return GoImplementMissingMethodsFix("Implement '${ifaceName(file, target.ifaceType)}' for ${target.named.name}: add missing methods")
        }

        private fun planAt(element: PsiElement): GoCreatePlan? {
            val target = targetAt(element) ?: return null
            return GoImplementStubs.compute(target.named.declaration, target.iface, target.pointer)?.plan("Implement missing methods")
        }

        /** The concrete type and the interface at the diagnostic's element: the asserted type of `x.(T)`, or a value and the type it is expected to have. */
        private fun targetAt(element: PsiElement): Target? {
            val service = GoSemanticService.getInstance(element.project)
            val assertion = PsiTreeUtil.getParentOfType(element, GoTypeAssertionExpr::class.java, false)
            val (concrete, expected) = if (assertion != null && assertion.type?.textRange?.contains(element.textRange) == true) {
                service.typeOf(assertion) to service.typeOf(assertion.expression ?: return null)
            } else {
                val file = element.containingFile
                val expr = element as? GoExpression
                    ?: PsiTreeUtil.findElementOfClassAtRange(file, element.textRange.startOffset, element.textRange.endOffset, GoExpression::class.java)
                    ?: return null
                service.typeOf(expr) to (service.expectedTypeAt(expr) ?: return null)
            }
            if (expected is GoTypeParamType) return null
            val iface = expected.underlying() as? GoInterfaceType ?: return null
            val (named, pointer) = when (concrete) {
                is GoNamedType -> concrete to false
                is GoPointerType -> (concrete.elem as? GoNamedType ?: return null) to true
                else -> return null
            }
            return Target(named, iface, pointer, expected)
        }

        private fun ifaceName(file: GoFile, type: GoType): String {
            if (GoZeroValues.isError(type)) return "error"
            val source = GoSourceText(file)
            return GoTypeRenderer.render(type) { source.qualifier(it) }
        }
    }
}
