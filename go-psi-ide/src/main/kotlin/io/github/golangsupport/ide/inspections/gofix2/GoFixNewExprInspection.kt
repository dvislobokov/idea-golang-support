package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoPointerType
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoUnknownType
import io.github.golangsupport.semantic.types.GoPointerType as PointerType

/**
 * modernize `newexpr` (go1.26, where `new` takes an expression):
 * - a call of a pointer helper `func ptr[T any](x T) *T { return &x }` (or a non-generic one) → `new(arg)`; an untyped constant
 *   argument of a non-generic helper over a predeclared type keeps its type: `int64Ptr(5)` → `new(int64(5))`;
 * - `func() *T { v := e; return &v }()` → `new(e)`;
 * - `x := e` right before `p := &x` / `p = &x` / `return &x`, with `x` used nowhere else → `p := new(e)`.
 */
class GoFixNewExprInspection : GoFix2InspectionBase() {
    override val minVersion = "1.26"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? = when (element) {
        is GoCallExpr -> helperCall(element) ?: immediateLiteral(element)
        is GoUnaryExpr -> addressOfTemporary(element)
        else -> null
    }

    private fun helperCall(call: GoCallExpr): GoFixFinding? {
        val callee = call.expression as? GoReferenceExpression ?: return null
        val argument = GoFixPsi.args(call).singleOrNull() ?: return null
        if (GoFixPsi.hasEllipsis(call)) return null
        val function = GoFixPsi.resolve(callee) as? GoFunctionDeclaration ?: return null
        val signature = function.signature ?: return null
        val parameter = signature.parameters.parameterDeclarationList.singleOrNull()?.takeIf { it.paramDefinitionList.size == 1 } ?: return null
        val result = signature.result?.type as? GoPointerType ?: return null
        val paramType = parameter.type ?: return null
        if (result.type?.text != paramType.text) return null
        val ret = function.block?.statementList?.singleOrNull() as? GoReturnStatement ?: return null
        val address = ret.expressionList.singleOrNull() as? GoUnaryExpr ?: return null
        if (address.and == null || (address.expression as? GoReferenceExpression)?.let { it.expression == null && it.identifier.text == parameter.paramDefinitionList[0].name } != true) return null
        val generic = function.typeParameters != null
        val service = GoSemanticService.getInstance(call.project)
        val argumentType = service.typeOf(argument)
        val value = when {
            generic -> argument.text
            argumentType == service.declarationType(parameter.paramDefinitionList[0]) -> argument.text
            (argumentType as? GoBasicType)?.isUntyped == true && GoBasicType.byName(paramType.text) != null -> "${paramType.text}(${argument.text})"
            else -> return null
        }
        val name = callee.identifier.text
        return GoFixFinding(call, "call of $name(x) can be simplified to new(x)", listOf("Simplify call of $name to new" to { _ -> listOf(GoFixEdit.replace(call, "new($value)")) }))
    }

    private fun immediateLiteral(call: GoCallExpr): GoFixFinding? {
        val literal = call.expression as? GoFunctionLit ?: return null
        if (GoFixPsi.args(call).isNotEmpty() || literal.signature?.parameters?.parameterDeclarationList?.isNotEmpty() != false) return null
        if (literal.signature?.result?.type !is GoPointerType) return null
        val statements = literal.block?.statementList ?: return null
        if (statements.size != 2) return null
        val declaration = statements[0] as? GoShortVarDeclaration ?: return null
        val variable = declaration.varDefinitionList.singleOrNull() ?: return null
        val value = declaration.expressionList.singleOrNull() ?: return null
        val ret = statements[1] as? GoReturnStatement ?: return null
        if (!isAddressOf(ret.expressionList.singleOrNull(), variable)) return null
        val service = GoSemanticService.getInstance(call.project)
        val pointee = service.declarationType(variable).takeIf { it != GoUnknownType } ?: return null
        val resultType = (service.typeOf(literal) as? GoSignatureType)?.results?.singleOrNull()?.type as? PointerType ?: return null
        if (resultType.elem != pointee) return null
        return GoFixFinding(call, "function literal can be simplified to new(${value.text})", listOf("Simplify to new(expr)" to { _ -> listOf(GoFixEdit.replace(call, "new(${value.text})")) }))
    }

    private fun addressOfTemporary(address: GoUnaryExpr): GoFixFinding? {
        if (address.and == null) return null
        val ref = address.expression as? GoReferenceExpression ?: return null
        if (ref.expression != null) return null
        val statement = when (val p = address.parent) {
            is GoShortVarDeclaration -> p.takeIf { it.expressionList.singleOrNull() === address }
            is GoAssignmentStatement -> p.takeIf { it.expressionList.singleOrNull() === address && it.assignOp.assign != null }
            is GoReturnStatement -> p.takeIf { it.expressionList.singleOrNull() === address }
            else -> null
        } ?: return null
        if (!GoFixPsi.isStatementList(statement.parent)) return null
        val variable = GoFixPsi.resolve(ref) as? GoVarDefinition ?: return null
        val declaration = variable.parent as? GoShortVarDeclaration ?: return null
        if (declaration.varDefinitionList.size != 1 || GoFixPsi.previousStatement(statement) !== declaration) return null
        val value = declaration.expressionList.singleOrNull() ?: return null
        val body = GoFixPsi.enclosingBody(declaration) ?: return null
        if (GoFixPsi.references(body, variable).singleOrNull() !== ref) return null
        if (immediateLiteralBody(declaration)) return null
        val name = variable.name ?: return null
        return GoFixFinding(address, "variable '$name' is used only for its address; it can be created with new(${value.text})", listOf("Simplify to new(expr)" to { _ ->
            listOf(GoFixPsi.deleteStatement(declaration), GoFixEdit.replace(address, "new(${value.text})"))
        }))
    }

    /** The declaration is the first statement of `func() *T { v := e; return &v }()`: that shape is reported on the call. */
    private fun immediateLiteralBody(declaration: GoShortVarDeclaration): Boolean {
        val literal = declaration.parent?.parent as? GoFunctionLit ?: return false
        val call = literal.parent as? GoCallExpr ?: return false
        return immediateLiteral(call) != null
    }

    private fun isAddressOf(e: GoExpression?, variable: GoVarDefinition): Boolean {
        val address = e as? GoUnaryExpr ?: return false
        val ref = address.expression as? GoReferenceExpression ?: return false
        return address.and != null && ref.expression == null && GoFixPsi.resolve(ref) == variable && GoFixPsi.references(variable.parent.parent, variable).size == 1
    }
}
