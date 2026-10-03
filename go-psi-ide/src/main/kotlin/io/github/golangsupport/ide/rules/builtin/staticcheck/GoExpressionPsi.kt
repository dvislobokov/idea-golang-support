package io.github.golangsupport.ide.rules.builtin.staticcheck

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoExpressionRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.infer.GoExpressionTyper
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoConstant
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypeRenderer
import io.github.golangsupport.semantic.types.GoUnknownType
import java.math.BigInteger
import io.github.golangsupport.lang.psi.GoType as PsiType

/** Base of the staticcheck expression checks (`SA4xxx`, `SA5010`, `SA9006`): one rule sees every expression, so filter with `is` first. */
abstract class GoStaticcheckExpressionRule : GoExpressionRule() {
    override val linter: String get() = "staticcheck"
    override val needs: Set<GoRuleNeed> get() = TYPES

    protected companion object {
        val TYPES: Set<GoRuleNeed> = setOf(GoRuleNeed.TYPES)
    }
}

/** Base of the govet expression checks of batch B4. */
abstract class GoVetExpressionRule : GoStaticcheckExpressionRule() {
    override val linter: String get() = "govet"
}

/** PSI helpers of the expression checks: operators, rendering like go/printer, literals, builtins, side effects, type texts. */
internal object GoExpressionPsi {

    /** The operator of a binary expression as written (`==`, `&^`, `||`); null for an incomplete expression. */
    fun op(e: GoBinaryExpr): String? {
        var n = e.node.firstChildNode
        while (n != null) {
            val psi = n.psi
            if (psi !is GoExpression && psi !is PsiWhiteSpace && psi !is PsiComment) return n.text
            n = n.treeNext
        }
        return null
    }

    /** The operator of a unary expression (`!`, `&`, `*`, `-`, `<-`). */
    fun op(e: GoUnaryExpr): String? = e.node.firstChildNode?.text

    /** The tokens of [e] without whitespace and comments: two expressions are the same code when these are equal. */
    fun tokens(e: PsiElement): List<String> {
        val out = ArrayList<String>()
        var leaf: PsiElement? = PsiTreeUtil.firstChild(e)
        val end = e.textRange.endOffset
        while (leaf != null && leaf.textRange.startOffset < end) {
            if (leaf !is PsiWhiteSpace && leaf !is PsiComment && leaf.textLength > 0) out += leaf.text
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return out
    }

    /** Whether [a] and [b] are the same code ([tokens] equal); the token lists are built only when the texts differ in spacing or comments. */
    fun sameCode(a: PsiElement, b: PsiElement): Boolean {
        val at = a.text
        val bt = b.text
        if (at == bt) return true
        if (at.none(::isLayout) && bt.none(::isLayout)) return false
        return tokens(a) == tokens(b)
    }

    private fun isLayout(c: Char): Boolean = c.isWhitespace() || c == '/'

    /** The text of [e] for messages: comments dropped, whitespace runs collapsed to one space (go/printer would also normalize spacing). */
    fun render(e: PsiElement): String {
        val sb = StringBuilder()
        var leaf: PsiElement? = PsiTreeUtil.firstChild(e)
        val end = e.textRange.endOffset
        var gap = false
        while (leaf != null && leaf.textRange.startOffset < end) {
            if (leaf is PsiWhiteSpace || leaf is PsiComment) gap = true
            else if (leaf.textLength > 0) {
                if (gap && sb.isNotEmpty()) sb.append(' ')
                sb.append(leaf.text)
                gap = false
            }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return sb.toString()
    }

    /** go/ast `BasicLit` of kind INT with the value [value] (`0`, `0x0`, `00`); a named constant or `-1` is not one. */
    fun isIntLiteral(e: GoExpression?, value: BigInteger): Boolean =
        e is GoLiteral && e.int != null && GoConstant.parseInt(e.text)?.value == value

    fun isIntLiteral(e: GoExpression?): Boolean = e is GoLiteral && e.int != null

    /** The name of the predeclared function / type / constant [e] refers to (`len`, `nil`, `float64`, `iota`), or null. */
    fun builtinName(e: GoExpression?, ctx: GoRuleContext): String? {
        val ref = e as? GoReferenceExpression ?: return null
        if (ref.expression != null) return null
        val target = ctx.resolve(ref).singleOrNull() ?: return null
        return if (GoLintPsi.isBuiltin(target)) ref.identifier.text else null
    }

    /** The predeclared `nil` (parentheses allowed). */
    fun isNil(e: GoExpression?, ctx: GoRuleContext): Boolean {
        val inner = GoLintPsi.unparen(e) as? GoReferenceExpression ?: return false
        return inner.identifier.text == "nil" && builtinName(inner, ctx) == "nil"
    }

    /** Whether [call] is a conversion `T(x)` (the callee names a type or a type parameter). */
    fun isConversion(call: GoCallExpr, ctx: GoRuleContext): Boolean {
        val ref = GoLintPsi.calleeReference(call) ?: return false
        val target = ctx.resolve(ref).singleOrNull()
        return target is GoTypeSpec || target is GoTypeParamDefinition
    }

    private val PURE_BUILTINS = setOf("len", "cap", "complex", "imag", "real", "make", "new", "max", "min")

    /**
     * x/tools `typesinternal.NoEffects`: no call other than a conversion or a pure builtin (`len`, `cap`, `min`...), no receive. Any
     * unknown construct counts as an effect, so callers skip it.
     */
    fun noEffects(e: PsiElement, ctx: GoRuleContext): Boolean {
        when (e) {
            is GoFunctionLit -> return true
            is GoCallExpr -> {
                val pure = e.expression.let { callee -> builtinName(GoLintPsi.unparen(callee), ctx) in PURE_BUILTINS } || isConversion(e, ctx)
                if (!pure) return false
            }
            is GoUnaryExpr -> if (op(e) == "<-") return false
            is GoReferenceExpression, is GoLiteral, is GoStringLiteral, is GoBinaryExpr, is GoParenthesesExpr, is GoIndexOrSliceExpr,
            is GoTypeAssertionExpr, is GoCompositeLit, is GoConversionExpr -> {}
            is GoExpression -> return false
        }
        var child = e.firstChild
        while (child != null) {
            if (!noEffects(child, ctx)) return false
            child = child.nextSibling
        }
        return true
    }

    /** The semantic type a type node denotes (`T` of `x.(T)` or of a type-switch case). */
    fun typeOf(node: PsiType): GoType = GoExpressionTyper.getInstance(node.project).builder.typeOf(node)

    /** Like go/types `TypeString(t, RelativeTo(pkg))`: named types of other packages qualified by their package name. */
    fun typeString(type: GoType, ctx: GoRuleContext): String {
        val here = GoAnalysisPsi.packagePath(ctx.file)
        return GoTypeRenderer.render(type) { named ->
            val path = named.pkgPath
            if (path == null || path == here || (named.declaration.containingFile as? GoFile)?.packageName == "builtin") null else path.substringAfterLast('/')
        }
    }

    /** The basic type under [type], or null (type parameters, composite types, unknown). */
    fun basic(type: GoType): GoBasicType? = if (type is GoTypeParamType) null else type.underlying() as? GoBasicType

    fun isKnownBasic(type: GoType): Boolean = basic(type)?.let { it.kind != GoBasicKind.INVALID } == true

    /** The types of the type set of [type]: its terms for a type parameter (null when it has none), else [type] itself. */
    fun typeSet(type: GoType): List<GoType>? = when (type) {
        is GoTypeParamType -> type.terms?.takeIf { it.isNotEmpty() }?.map { it.type }
        is GoUnknownType -> null
        else -> listOf(type)
    }

    /** staticcheck SA4000's `isFloat`: a float, or an array / struct holding one; a type parameter without terms may be one. */
    fun mayBeFloat(type: GoType, depth: Int = 0): Boolean {
        if (depth > 8) return true
        val types = if (type is GoTypeParamType) type.terms?.map { it.type } ?: return true else listOf(type)
        if (types.isEmpty()) return true
        return types.any { t ->
            when (val u = t.underlying()) {
                is GoBasicType -> u.kind == GoBasicKind.FLOAT32 || u.kind == GoBasicKind.FLOAT64
                is GoArrayType -> mayBeFloat(u.elem, depth + 1)
                is GoStructType -> u.fields.any { mayBeFloat(it.type, depth + 1) }
                is GoUnknownType -> true
                else -> false
            }
        }
    }

    /** Whether [type] is the named type `path.name`. */
    fun isNamed(type: GoType, path: String, vararg names: String): Boolean = type is GoNamedType && type.name in names && type.pkgPath == path
}
