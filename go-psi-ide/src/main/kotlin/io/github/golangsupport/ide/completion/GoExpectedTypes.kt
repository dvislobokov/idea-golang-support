package io.github.golangsupport.ide.completion

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.arguments
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.operator
import io.github.golangsupport.semantic.psi.GoPsiUtil.tag
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * The type an expression at the caret is expected to have: the assignment target, the declared
 * variable type, the call parameter, the function result, the other binary operand, the
 * composite literal element/field/key, the channel element of a send, the switch tag of a case,
 * `bool` for conditions. Null when the position has no expectation.
 */
object GoExpectedTypes {

    fun compute(context: GoCompletionContext, semantics: GoCompletionSemantics): GoType? {
        val expr: GoExpression = context.reference
            ?: (context.typeReference?.parent as? GoCompositeLit)
            ?: return null
        return expectedFor(expr, semantics, 0)
    }

    private fun expectedFor(expr: GoExpression, sem: GoCompletionSemantics, depth: Int): GoType? {
        if (depth > 4) return null
        return when (val parent: PsiElement? = expr.parent) {
            is GoAssignmentStatement -> {
                val rhs = parent.expressionList
                val lhs = parent.leftHandExprList?.let { GoPsiUtil.children(it, GoExpression::class.java) } ?: return null
                val index = rhs.indexOf(expr)
                if (index < 0 || lhs.size != rhs.size) return null
                val target = lhs[index]
                if (target is GoReferenceExpression && target.expression == null && target.identifier?.text == "_") return null
                sem.typeOf(target)
            }
            is GoVarSpec -> {
                if (parent.type == null) return null
                val index = parent.expressionList.indexOf(expr)
                parent.varDefinitionList.getOrNull(index)?.let(sem::declarationType)
            }
            is GoConstSpec -> {
                if (parent.type == null) return null
                val index = parent.expressionList.indexOf(expr)
                parent.constDefinitionList.getOrNull(index)?.let(sem::declarationType)
            }
            is GoArgumentList -> {
                val call = parent.parent as? GoCallExpr ?: return null
                val callee = call.expression ?: return null
                val signature = sem.typeOf(callee).underlying() as? GoSignatureType ?: return null
                val index = GoPsiUtil.run { call.arguments }.indexOf(expr)
                parameterType(signature, index)
            }
            is GoReturnStatement -> {
                val signature = sem.enclosingSignature ?: return null
                val index = parent.expressionList.indexOf(expr)
                signature.results.getOrNull(index)?.type
            }
            is GoBinaryExpr -> {
                val op = GoPsiUtil.run { parent.operator }
                when (op) {
                    GoTypes.LAND, GoTypes.LOR -> GoBasicType.BOOL
                    GoTypes.SHL, GoTypes.SHR -> null
                    else -> {
                        val left = parent.left
                        if (left === expr || left == null) null else sem.typeOf(left).takeIf { it !is GoUnknownType }?.let(GoTypePredicates::defaultType)
                    }
                }
            }
            is GoUnaryExpr -> if (GoPsiUtil.run { parent.operator } == GoTypes.NOT) GoBasicType.BOOL else null
            is GoParenthesesExpr -> expectedFor(parent, sem, depth + 1)
            is GoSendStatement -> {
                if (parent.expression !== expr) return null
                val channel = parent.leftHandExprList?.let { GoPsiUtil.children(it, GoExpression::class.java).firstOrNull() } ?: return null
                (sem.typeOf(channel).underlying() as? GoChanType)?.elem
            }
            is GoValue -> literalElementType(parent, sem, isKey = false)
            is GoKey -> literalElementType(parent, sem, isKey = true)
            is GoExprCaseClause -> {
                val switch = parent.parent as? GoExprSwitchStatement ?: return null
                val tag = GoPsiUtil.run { switch.tag } ?: return GoBasicType.BOOL
                sem.typeOf(tag).takeIf { it !is GoUnknownType }?.let(GoTypePredicates::defaultType)
            }
            is GoIfStatement -> if (GoPsiUtil.run { parent.condition } === expr) GoBasicType.BOOL else null
            is GoForStatement -> if (GoPsiUtil.run { parent.condition } === expr) GoBasicType.BOOL else null
            is GoForClause -> if (parent.expression === expr) GoBasicType.BOOL else null
            else -> null
        }
    }

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

    private fun literalElementType(part: PsiElement, sem: GoCompletionSemantics, isKey: Boolean): GoType? {
        val element = part.parent as? GoElement ?: return null
        val value = element.parent as? GoLiteralValue ?: return null
        val literal = sem.literalType(value)?.let(GoCompletionSemantics::derefUnderlying) ?: return null
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

    /** A field (promoted ones included) by name, breadth-first. */
    fun fieldType(struct: GoStructType, name: String): GoType? {
        var level = listOf(struct)
        var depth = 0
        while (level.isNotEmpty() && depth < 8) {
            val next = ArrayList<GoStructType>()
            for (s in level) {
                s.fields.firstOrNull { it.name == name }?.let { return it.type }
                for (f in s.fields) if (f.embedded) (GoCompletionSemantics.derefUnderlying(f.type) as? GoStructType)?.let(next::add)
            }
            level = next
            depth++
        }
        return null
    }
}
