package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.completion.GoImportInserter
import io.github.golangsupport.ide.intentions.GoSourceText
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Misuse of the `errors` package:
 * - vet `errorsas`: the target of `errors.As(err, target)` must be a non-nil pointer to a type implementing `error` or to an interface
 *   (`any` is accepted as forwarded); `*error` is reported on its own. Fix: "Take the address of target" for a variable whose address
 *   is a valid target.
 * - `err == ErrX` / `err != ErrX` against a package-level `error` variable (a sentinel): false for wrapped errors (weak warning, like
 *   staticcheck / err113; `nil` is never a sentinel, and `Is` methods compare directly by design). Fix: `errors.Is(err, ErrX)`.
 */
class GoErrorsPackageInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        when (element) {
            is GoCallExpr -> checkAs(element, holder)
            is GoConditionalExpr -> checkComparison(element, holder)
        }
    }

    private fun checkAs(call: GoCallExpr, holder: ProblemsHolder) {
        val callee = call.expression as? GoReferenceExpression ?: return
        if (callee.identifier.text != "As" || callee.expression == null) return
        val args = call.argumentList.expressions
        if (args.size != 2) return
        val service = GoSemanticService.getInstance(call.project)
        val function = service.resolve(callee).firstOrNull() as? GoFunctionDeclaration ?: return
        if (GoAnalysisPsi.packagePath(function) != "errors") return
        val errorType = service.calleeSignature(call)?.params?.firstOrNull()?.type ?: return
        val errorInterface = errorType.underlying() as? GoInterfaceType ?: return
        val target = args[1]
        val type = service.typeOf(target)
        if (type is GoUnknownType) return
        val underlying = type.underlying()
        if (underlying is GoInterfaceType && underlying.allMethods.isEmpty() && !underlying.hasTypeTerms) return
        if (underlying is GoPointerType) {
            val elem = underlying.elem
            if (GoAnalysisPsi.isError(elem)) {
                holder.registerProblem(target, "second argument to errors.As should not be *error")
                return
            }
            if (elem is GoUnknownType || elem.underlying() is GoInterfaceType || service.implements(elem, errorInterface)) return
            holder.registerProblem(target, AS_MESSAGE, *addressFix(target, type, errorInterface, service))
            return
        }
        holder.registerProblem(target, AS_MESSAGE, *addressFix(target, type, errorInterface, service))
    }

    /** `&target` when target is a variable and a pointer to its type is a valid target. */
    private fun addressFix(target: GoExpression, type: GoType, errorInterface: GoInterfaceType, service: GoSemanticService): Array<LocalQuickFix> {
        val reference = target as? GoReferenceExpression ?: return emptyArray()
        val variable = service.resolve(reference).firstOrNull()
        if (variable !is GoVarDefinition && variable !is GoParamDefinition) return emptyArray()
        if (type.underlying() !is GoInterfaceType && !service.implements(type, errorInterface)) return emptyArray()
        if (GoAnalysisPsi.isError(type)) return emptyArray()
        return arrayOf(GoTakeAddressFix())
    }

    private fun checkComparison(expr: GoConditionalExpr, holder: ProblemsHolder) {
        if (expr.eql == null && expr.neq == null) return
        val left = expr.left
        val right = expr.right ?: return
        val service = GoSemanticService.getInstance(expr.project)
        val (value, sentinel) = when {
            isSentinel(right, service) -> left to right as GoReferenceExpression
            isSentinel(left, service) -> right to left as GoReferenceExpression
            else -> return
        }
        if (!GoAnalysisPsi.isError(service.typeOf(value))) return
        val owner = GoPsiUtil.functionOwner(expr)
        if (owner is GoMethodDeclaration && owner.name == "Is") return
        val negated = expr.neq != null
        val call = "errors.Is(${value.text}, ${sentinel.text})"
        holder.registerProblem(
            expr, "Comparison with sentinel error '${sentinel.text}' is false for wrapped errors; use errors.Is",
            ProblemHighlightType.WEAK_WARNING, GoUseErrorsIsFix(if (negated) "!$call" else call),
        )
    }

    /** A reference to a package-level variable of type `error`. */
    private fun isSentinel(expr: GoExpression, service: GoSemanticService): Boolean {
        if (expr !is GoReferenceExpression || expr.identifier.text == "nil") return false
        val variable = service.resolve(expr).firstOrNull() as? GoVarDefinition ?: return false
        val declaration = variable.parent?.parent as? GoVarDeclaration ?: return false
        if (declaration.parent !is GoFile) return false
        return GoAnalysisPsi.isError(service.declarationType(variable))
    }

    companion object {
        const val AS_MESSAGE = "second argument to errors.As must be a non-nil pointer to either a type that implements error, or to any interface type"
    }
}

/** `errors.As(err, target)` → `errors.As(err, &target)`. */
class GoTakeAddressFix : LocalQuickFix {
    override fun getFamilyName(): String = "Take the address of target"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val target = descriptor.psiElement ?: return
        val document = GoImportEdits.document(target.containingFile) ?: return
        document.insertString(target.textRange.startOffset, "&")
        GoImportEdits.commit(target.containingFile, document)
    }
}

/** `err == ErrX` → `errors.Is(err, ErrX)` (`!=` → `!errors.Is(...)`), importing `errors` when needed. */
class GoUseErrorsIsFix(private val replacement: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with errors.Is"

    override fun getName(): String = "Replace with $replacement"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val expr = descriptor.psiElement as? GoConditionalExpr ?: return
        val file = expr.containingFile as? GoFile ?: return
        val source = GoSourceText(file)
        val prefix = source.prefix("errors", "errors")
        val document = GoImportEdits.document(file) ?: return
        document.replaceString(expr.textRange.startOffset, expr.textRange.endOffset, replacement.replace("errors.Is(", "${prefix}Is("))
        GoImportEdits.commit(file, document)
        if (source.imports.isEmpty()) return
        for (path in source.imports) GoImportInserter.addImport(file, document, path)
        GoImportEdits.commit(file, document)
    }
}
