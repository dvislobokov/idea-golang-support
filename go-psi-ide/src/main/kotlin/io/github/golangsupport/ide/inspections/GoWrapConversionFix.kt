package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoDiagnostic
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoTypeRenderer

/**
 * `cannot use x (variable of type int) as float64 value in ...`: wraps the value in a conversion
 * `float64(x)` when the value's type converts to the target type. The target comes from the
 * context (declared variable type, assignment target, function result, call parameter), the
 * value must be typed (untyped constants are representability errors) and integer-to-string
 * conversions are never offered (`string(i)` yields a rune, see vet's stringintconv). Types of
 * other packages are written with the file's import name; without an import there is no fix.
 */
class GoWrapConversionFix(private val typeText: String) : LocalQuickFix {

    override fun getFamilyName(): String = "Wrap in a type conversion"

    override fun getName(): String = "Convert to '$typeText'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile
        val document = GoImportEdits.document(file) ?: return
        val range = element.textRange
        val callee = if (typeText.startsWith("*") || typeText.startsWith("<-") || typeText.startsWith("func")) "($typeText)" else typeText
        document.replaceString(range.startOffset, range.endOffset, "$callee(${element.text})")
        GoImportEdits.commit(file, document)
    }

    companion object {
        fun create(file: GoFile, d: GoDiagnostic, anchor: PsiElement): GoWrapConversionFix? {
            if (anchor.textRange != d.range || !d.message.startsWith("cannot use ")) return null
            val expr = PsiTreeUtil.findElementOfClassAtRange(file, d.range.startOffset, d.range.endOffset, GoExpression::class.java) ?: return null
            val service = GoSemanticService.getInstance(file.project)
            val target = targetType(expr, service) ?: return null
            val value = service.typeOf(expr)
            if (!GoTypePredicates.isKnown(value) || !GoTypePredicates.isKnown(target) || GoTypePredicates.isUntyped(value)) return null
            if (target.underlying() is GoInterfaceType) return null
            if (!GoTypePredicates.convertible(value, target)) return null
            val vu = value.underlying()
            val tu = target.underlying()
            if (vu is GoBasicType && vu.kind.isInteger && tu is GoBasicType && tu.kind.isString) return null
            val text = renderInFile(target, file) ?: return null
            return GoWrapConversionFix(text)
        }

        /** The type [expr] must be assignable to, from its syntactic context; null when unknown. */
        private fun targetType(expr: GoExpression, service: GoSemanticService): GoType? {
            when (val parent = expr.parent) {
                is GoVarSpec -> {
                    if (parent.type == null) return null
                    val index = parent.expressionList.indexOf(expr)
                    val def = parent.varDefinitionList.getOrNull(index) ?: return null
                    return service.declarationType(def)
                }
                is GoAssignmentStatement -> {
                    if (parent.assignOp.assign == null) return null
                    val index = parent.expressionList.indexOf(expr)
                    val lhs = parent.leftHandExprList.expressionList
                    if (index < 0 || lhs.size != parent.expressionList.size) return null
                    return service.typeOf(lhs[index])
                }
                is GoReturnStatement -> {
                    val signature = when (val owner = GoPsiUtil.functionOwner(parent)) {
                        is GoFunctionOrMethodDeclaration -> service.declarationType(owner)
                        is GoFunctionLit -> service.typeOf(owner)
                        else -> null
                    } as? GoSignatureType ?: return null
                    val values = parent.expressionList
                    val index = values.indexOf(expr)
                    if (index < 0 || values.size != signature.results.size) return null
                    return signature.results[index].type
                }
                is GoArgumentList -> {
                    val call = parent.parent as? GoCallExpr ?: return null
                    val signature = service.calleeSignature(call) ?: return null
                    val index = parent.arguments.indexOf(expr)
                    if (index < 0) return null
                    val params = signature.params
                    if (signature.variadic && index >= params.size - 1) {
                        if (parent.hasEllipsis) return null
                        return (params.lastOrNull()?.type as? GoSliceType)?.elem
                    }
                    return params.getOrNull(index)?.type
                }
                else -> return null
            }
        }

        /** [type] as source text in [file]: named types of other packages use the file's import name. */
        private fun renderInFile(type: GoType, file: GoFile): String? {
            val myPath = GoPackageModel.getInstance(file.project).packagePathOf(file)
            var missingImport = false
            val text = GoTypeRenderer.render(type) { named ->
                val path = named.pkgPath
                if (path == null || path == myPath || GoUniverse.isBuiltinDeclaration(named.declaration)) {
                    null
                } else {
                    val spec = file.imports.firstOrNull { it.path == path && !it.isBlank && !it.isDot }
                    if (spec == null) missingImport = true
                    spec?.let(GoScopes::importName) ?: path.substringAfterLast('/')
                }
            }
            return if (missingImport || text.contains('?')) null else text
        }
    }
}
