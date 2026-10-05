package io.github.golangsupport.ide.inspections.declarations

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoImportEdits
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates

/**
 * GoLand's `GoExportedFuncWithUnexportedType` (golint's "exported func returns unexported type"): an exported function, or an exported
 * method of an exported type, that returns a value of an unexported type of its own package (`T` or `*T`). Callers in other packages can
 * hold the value but cannot name its type. Skipped: `package main`, `_test.go` files, the predeclared `error`. No fix.
 */
class GoExportedFuncWithUnexportedTypeInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoFunctionDeclaration && element !is GoMethodDeclaration) return
        val decl = element as GoFunctionOrMethodDeclaration
        if (file.packageName == "main" || file.isTestFile || decl.name?.firstOrNull()?.isUpperCase() != true) return
        if (decl is GoMethodDeclaration && decl.receiverTypeName?.firstOrNull()?.isUpperCase() != true) return
        val result = decl.signature?.result ?: return
        val signature = GoSemanticService.getInstance(file.project).declarationType(decl) as? GoSignatureType ?: return
        val dir = file.originalFile.containingDirectory ?: return
        val hidden = signature.results.mapNotNull { unexported(it.type) }.firstOrNull { spec ->
            spec.containingFile.originalFile.containingDirectory == dir && PsiTreeUtil.getParentOfType(spec, GoBlock::class.java) == null
        } ?: return
        holder.registerProblem(result, "Exported ${if (decl is GoMethodDeclaration) "method" else "function"} with the unexported return type '${hidden.name}'")
    }

    private fun unexported(type: GoType): GoTypeSpec? {
        val named = (if (type is GoPointerType) type.elem else type) as? GoNamedType ?: return null
        if (named.name.firstOrNull()?.isLowerCase() != true || GoLintPsi.isBuiltin(named.declaration)) return null
        return named.declaration
    }
}

/**
 * GoLand's `GoRedundantConversion` (the `unconvert` linter): `T(x)` where `x` already has the type `T` (identical types, `byte` and `uint8`
 * included). Not reported for untyped constants (`float64(1)` gives the constant its type), unknown types and cgo's `C.T(x)`.
 * Fix: Remove redundant type conversion (parenthesizes an operator expression where the call stood inside another one).
 */
class GoRedundantConversionInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (GoRedundantConversions.redundantArgument(element) == null) return
        holder.registerProblem(element, "Redundant type conversion", ProblemHighlightType.LIKE_UNUSED_SYMBOL, GoRemoveRedundantConversionFix())
    }
}

internal object GoRedundantConversions {

    /** The converted operand of [element] when it is a conversion that changes nothing, else null. */
    fun redundantArgument(element: PsiElement): GoExpression? {
        val argument: GoExpression = when (element) {
            is GoCallExpr -> {
                val callee = GoLintPsi.calleeReference(element) ?: return null
                if (callee.expression?.text == "C") return null
                val single = element.argumentList?.expressions?.singleOrNull() ?: return null
                if (element.argumentList?.text?.contains("...") == true) return null
                val service = GoSemanticService.getInstance(element.project)
                if (service.resolve(callee).singleOrNull() !is GoTypeSpec) return null
                single
            }
            is GoConversionExpr -> PsiTreeUtil.getChildOfType(element, GoExpression::class.java) ?: return null
            else -> return null
        }
        val service = GoSemanticService.getInstance(element.project)
        val from = service.typeOf(argument)
        val to = service.typeOf(element as GoExpression)
        if ((from as? GoBasicType)?.isUntyped == true || from is GoBasicType && from.kind == io.github.golangsupport.semantic.types.GoBasicKind.INVALID) return null
        if (!GoTypePredicates.isKnown(from) || !GoTypePredicates.isKnown(to) || !GoTypePredicates.identical(from, to)) return null
        return argument
    }
}

class GoRemoveRedundantConversionFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove redundant type conversion"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val conversion = descriptor.psiElement as? GoExpression ?: return
        val argument = GoRedundantConversions.redundantArgument(conversion) ?: return
        val file = conversion.containingFile
        val document = GoImportEdits.document(file) ?: return
        val inner = argument.text
        val parent = conversion.parent
        val wrap = (argument is GoBinaryExpr || argument is GoUnaryExpr) && parent is GoExpression && parent !is GoParenthesesExpr
        document.replaceString(conversion.textRange.startOffset, conversion.textRange.endOffset, if (wrap) "($inner)" else inner)
        GoImportEdits.commit(file, document)
    }
}
