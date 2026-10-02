package io.github.golangsupport.semantic.infer

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.hasEllipsis
import io.github.golangsupport.semantic.psi.GoPsiUtil.isSlice
import io.github.golangsupport.semantic.psi.GoPsiUtil.operator
import io.github.golangsupport.semantic.psi.GoPsiUtil.tag
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType
import org.jetbrains.annotations.ApiStatus

/**
 * The type an expression is expected to have from its position (`GoSemanticService.expectedTypeAt`):
 * the assignment target, the declared variable type, the call parameter, the function result, the
 * other binary operand, the composite literal element/field/key, the channel element of a send, the
 * switch tag of a case, `bool` for conditions, the key of a map index. Null when the position has no
 * expectation. Pure PSI + typer, no caching of its own: the typer caches what it computes.
 */
@ApiStatus.Internal
object GoExpectedType {

    fun expectedFor(expr: GoExpression, typer: GoExpressionTyper): GoType? = expectedFor(expr, typer, 0)

    private fun expectedFor(expr: GoExpression, typer: GoExpressionTyper, depth: Int): GoType? {
        if (depth > 4) return null
        return when (val parent: PsiElement? = expr.parent) {
            is GoAssignmentStatement -> {
                val rhs = parent.expressionList
                val lhs = parent.leftHandExprList?.let { GoPsiUtil.children(it, GoExpression::class.java) } ?: return null
                val index = rhs.indexOf(expr)
                if (index < 0 || lhs.size != rhs.size) return null
                val target = lhs[index]
                if (target is GoReferenceExpression && target.expression == null && target.identifier?.text == "_") return null
                typer.typeOf(target)
            }
            is GoVarSpec -> {
                if (parent.type == null) return null
                val index = parent.expressionList.indexOf(expr)
                parent.varDefinitionList.getOrNull(index)?.let { typer.declarationType(it, null) }
            }
            is GoConstSpec -> {
                if (parent.type == null) return null
                val index = parent.expressionList.indexOf(expr)
                parent.constDefinitionList.getOrNull(index)?.let { typer.declarationType(it, null) }
            }
            is GoArgumentList -> {
                val call = parent.parent as? GoCallExpr ?: return null
                val callee = call.expression ?: return null
                // `T(x)`: a conversion takes any convertible value, not a parameter
                if (typer.isTypeExpression(callee)) return null
                val signature = typer.typeOf(callee).underlying() as? GoSignatureType ?: return null
                val arguments = GoPsiUtil.run { call.arguments }
                val index = arguments.indexOf(expr)
                // `f(xs...)`: the spread argument is the variadic slice itself
                if (signature.variadic && index >= 0 && index == arguments.size - 1 && index == signature.params.size - 1 && GoPsiUtil.run { parent.hasEllipsis }) {
                    return signature.params.last().type
                }
                parameterType(signature, index)
            }
            is GoReturnStatement -> {
                val results = enclosingResults(parent) ?: return null
                val values = parent.expressionList
                val index = values.indexOf(expr)
                // `return f()` where f returns all the results at once
                if (values.size == 1 && results.size > 1 && expr is GoCallExpr) return GoTupleType(results)
                results.getOrNull(index)
            }
            is GoBinaryExpr -> {
                when (GoPsiUtil.run { parent.operator }) {
                    GoTypes.LAND, GoTypes.LOR -> GoBasicType.BOOL
                    GoTypes.SHL, GoTypes.SHR -> null
                    else -> {
                        val left = parent.left
                        val right = parent.right
                        when {
                            left == null -> null
                            left !== expr -> typer.typeOf(left).takeIf { it !is GoUnknownType }?.let(GoTypePredicates::defaultType)
                            // the left operand: an untyped constant on the right yields to the left side's own type
                            right == null || right === expr -> null
                            else -> typer.typeOf(right).takeIf { it !is GoUnknownType && !GoTypePredicates.isUntyped(it) }
                        }
                    }
                }
            }
            is GoUnaryExpr -> if (GoPsiUtil.run { parent.operator } == GoTypes.NOT) GoBasicType.BOOL else null
            is GoParenthesesExpr -> expectedFor(parent, typer, depth + 1)
            is GoSendStatement -> {
                if (parent.expression !== expr) return null
                val channel = parent.leftHandExprList?.let { GoPsiUtil.children(it, GoExpression::class.java).firstOrNull() } ?: return null
                (typer.typeOf(channel).underlying() as? GoChanType)?.elem
            }
            is GoValue -> literalElementType(parent, typer, isKey = false)
            is GoKey -> literalElementType(parent, typer, isKey = true)
            is GoExprCaseClause -> {
                val switch = parent.parent as? GoExprSwitchStatement ?: return null
                val tag = GoPsiUtil.run { switch.tag } ?: return GoBasicType.BOOL
                typer.typeOf(tag).takeIf { it !is GoUnknownType }?.let(GoTypePredicates::defaultType)
            }
            is GoIfStatement -> if (GoPsiUtil.run { parent.condition } === expr) GoBasicType.BOOL else null
            is GoForStatement -> if (GoPsiUtil.run { parent.condition } === expr) GoBasicType.BOOL else null
            is GoForClause -> if (parent.expression === expr) GoBasicType.BOOL else null
            is GoIndexOrSliceExpr -> {
                if (GoPsiUtil.run { parent.isSlice }) return null
                val parts = GoPsiUtil.children(parent, GoExpression::class.java)
                if (parts.size != 2 || parts[1] !== expr) return null
                (typer.typeOf(parts[0]).underlying() as? GoMapType)?.key
            }
            else -> null
        }
    }

    /** The result types of the function declaration or literal around [element]; null outside functions. */
    fun enclosingResults(element: PsiElement, typer: GoExpressionTyper): List<GoType>? {
        val owner = GoPsiUtil.functionOwner(element) ?: return null
        val signature = when (owner) {
            is GoFunctionOrMethodDeclaration -> typer.declarationType(owner, null) as? GoSignatureType
            is GoFunctionLit -> typer.typeOf(owner) as? GoSignatureType
            else -> null
        } ?: return null
        return signature.results.map { it.type }
    }

    private fun enclosingResults(statement: GoReturnStatement): List<GoType>? =
        enclosingResults(statement, GoExpressionTyper.getInstance(statement.project))

    /** The parameter type for argument [index] (variadic tail: the element type). */
    fun parameterType(signature: GoSignatureType, index: Int): GoType? {
        if (index < 0) return null
        val params = signature.params
        if (params.isEmpty()) return null
        if (signature.variadic && index >= params.size - 1) {
            val last = params.last().type
            return (last as? GoSliceType)?.elem ?: last
        }
        return params.getOrNull(index)?.type
    }

    private fun literalElementType(part: PsiElement, typer: GoExpressionTyper, isKey: Boolean): GoType? {
        val element = part.parent as? GoElement ?: return null
        val value = element.parent as? GoLiteralValue ?: return null
        val literal = literalType(value, typer::typeOf)?.let(::derefUnderlying) ?: return null
        return when (literal) {
            is GoStructType -> {
                if (isKey) return null
                val key = (element.key?.expression as? GoReferenceExpression)?.identifier?.text ?: return null
                fieldType(literal, key)
            }
            is GoSliceType -> if (isKey) GoBasicType.INT else literal.elem
            is GoArrayType -> if (isKey) GoBasicType.INT else literal.elem
            is GoMapType -> if (isKey) literal.key else literal.value
            else -> null
        }
    }

    /** The type a composite literal's value has, including elided nested literals (`[]T{{...}}`); [typeOf] types the outermost literal. */
    fun literalType(value: GoLiteralValue, typeOf: (GoExpression) -> GoType, depth: Int = 0): GoType? {
        if (depth > 8) return null
        val parent = value.parent
        if (parent is GoCompositeLit) return typeOf(parent)
        val element = parent?.parent as? GoElement ?: return null
        val outer = element.parent as? GoLiteralValue ?: return null
        val outerType = literalType(outer, typeOf, depth + 1)?.let(::derefUnderlying) ?: return null
        val elementType = when (outerType) {
            is GoSliceType -> outerType.elem
            is GoArrayType -> outerType.elem
            is GoMapType -> if (parent is GoKey) outerType.key else outerType.value
            is GoStructType -> {
                // A keyed struct field holding an elided literal is not valid Go, but keep the field type for robustness.
                val key = (element.key?.expression as? GoReferenceExpression)?.identifier?.text
                key?.let { k -> outerType.fields.firstOrNull { it.name == k }?.type }
            }
            else -> null
        } ?: return null
        // `[]*T{{...}}` elides `&T`.
        return if (elementType is GoPointerType) elementType.elem else elementType
    }

    /** A field (promoted ones included) by name, breadth-first. */
    fun fieldType(struct: GoStructType, name: String): GoType? {
        var level = listOf(struct)
        var depth = 0
        while (level.isNotEmpty() && depth < 8) {
            val next = ArrayList<GoStructType>()
            for (s in level) {
                s.fields.firstOrNull { it.name == name }?.let { return it.type }
                for (f in s.fields) if (f.embedded) (derefUnderlying(f.type) as? GoStructType)?.let(next::add)
            }
            level = next
            depth++
        }
        return null
    }

    fun derefUnderlying(type: GoType): GoType {
        val u = type.underlying()
        return if (u is GoPointerType) u.elem.underlying() else u
    }
}
