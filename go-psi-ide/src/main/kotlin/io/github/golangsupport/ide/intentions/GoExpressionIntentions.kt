package io.github.golangsupport.ide.intentions

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.refactoring.GoInlineSupport
import io.github.golangsupport.lang.psi.GoAndExpr
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoOrExpr
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.operator
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoTypeRenderer

/** PSI helpers of the expression intentions: the binary expression at the caret, operator texts, boolean negation. */
internal object GoExpressionText {

    /** The innermost binary expression around [offset] that satisfies [accept], not across a statement or a function literal. */
    fun binaryAt(file: GoFile, offset: Int, accept: (GoBinaryExpr) -> Boolean): GoBinaryExpr? {
        var e: PsiElement? = GoIntentionText.leafAt(file, offset)
        while (e != null && e !is PsiFile) {
            if (e is GoBinaryExpr && e.right != null && accept(e)) return e
            if (e is GoStatement || e is GoBlock || e is GoFunctionLit) return null
            e = e.parent
        }
        return null
    }

    /** The operator token of [binary]. */
    fun operatorOf(binary: GoBinaryExpr): PsiElement? = binary.operator?.let { binary.node.findChildByType(it)?.psi }

    /** A comment directly between the operands: rewriting the expression as text would lose it. */
    fun hasOwnComment(element: PsiElement): Boolean = generateSequence(element.firstChild) { it.nextSibling }.any { it is PsiComment }

    fun isBoolean(e: PsiElement?): Boolean = e is GoAndExpr || e is GoOrExpr || e is GoConditionalExpr

    class Text(val text: String, val prec: Int)

    /** The comparison operator that negates [e]: `==` ↔ `!=`; `<` → `>=` and so on only for integer and string operands (NaN). */
    fun negatedOperator(e: GoConditionalExpr): String? = when {
        e.eql != null -> "!="
        e.neq != null -> "=="
        !ordered(e) -> null
        e.lss != null -> ">="
        e.leq != null -> ">"
        e.gtr != null -> "<="
        e.geq != null -> "<"
        else -> null
    }

    private fun ordered(e: GoConditionalExpr): Boolean {
        val service = GoSemanticService.getInstance(e.project)
        return e.expressionList.all { operand ->
            val kind = (service.typeOf(operand).underlying() as? GoBasicType)?.kind
            kind != null && (kind.isInteger || kind.isString)
        }
    }

    /** The operator [e] gets when negated (`||` → `&&`, `<` → `>=`), or null when it cannot be negated exactly. */
    fun negatedOperatorText(e: GoBinaryExpr): String? = when (e) {
        is GoOrExpr -> "&&"
        is GoAndExpr -> "||"
        is GoConditionalExpr -> negatedOperator(e)
        else -> null
    }

    /**
     * The negation of the boolean binary [e]: De Morgan on `&&` / `||` (the operands negated one level, or every `&&` / `||` below when
     * [recursive]), comparisons flipped. Null when [e] cannot be negated without a `!` around it.
     */
    fun negated(e: GoBinaryExpr, recursive: Boolean): Text? = when (e) {
        is GoOrExpr -> Text(GoIfText.chain(e).joinToString(" && ") { negatedOperand(it, 2, recursive) }, 2)
        is GoAndExpr -> Text(GoIfText.chain(e).joinToString(" || ") { negatedOperand(it, 1, recursive) }, 1)
        is GoConditionalExpr -> negatedOperator(e)?.let { op -> e.right?.let { right -> Text("${e.left.text} $op ${right.text}", 3) } }
        else -> null
    }

    private fun negatedOperand(operand: GoExpression, context: Int, recursive: Boolean): String {
        val u = GoIfText.unparen(operand)
        val t = when {
            u is GoUnaryExpr && u.not != null && u.expression != null -> GoIfText.unparen(u.expression!!).let { Text(it.text, GoInlineSupport.precedence(it)) }
            u is GoConditionalExpr && u.right != null -> negated(u, recursive) ?: Text("!(${u.text})", 6)
            recursive && (u is GoAndExpr || u is GoOrExpr) -> negated(u as GoBinaryExpr, true)!!
            u is GoReferenceExpression && u.expression == null && u.text == "true" -> Text("false", 7)
            u is GoReferenceExpression && u.expression == null && u.text == "false" -> Text("true", 7)
            GoInlineSupport.precedence(u) >= 6 -> Text("!${u.text}", 6)
            else -> Text("!(${u.text})", 6)
        }
        return if (t.prec < context) "(${t.text})" else t.text
    }

    /** The outermost boolean binary expression that contains [e] through boolean operators, parentheses and `!`. */
    fun topmost(e: GoBinaryExpr): GoBinaryExpr {
        var top = e
        var p: PsiElement? = e.parent
        while (p != null) {
            when {
                p is GoParenthesesExpr || (p is GoUnaryExpr && p.not != null) -> {}
                isBoolean(p) && (p as GoBinaryExpr).right != null -> top = p
                else -> break
            }
            p = p.parent
        }
        return top
    }

    /** `!(E)` around [target] → its negation; otherwise [target] → `!(negation)`: the value stays the same. */
    fun negationEdit(target: GoBinaryExpr, recursive: Boolean): GoEditPlan.Edit? {
        val negation = negated(target, recursive) ?: return null
        var outer: PsiElement = target
        while (outer.parent is GoParenthesesExpr) outer = outer.parent
        val not = outer.parent as? GoUnaryExpr
        if (not != null && not.not != null) {
            return GoEditPlan.Edit(not.textRange.startOffset, not.textRange.endOffset, GoInlineSupport.placed(not, negation.text, negation.prec))
        }
        return GoEditPlan.Edit(target.textRange.startOffset, target.textRange.endOffset, "!(${negation.text})")
    }
}

/**
 * Flip binary operator: the operands of the binary expression at the caret change places, comparisons mirrored (`x >= 0` → `0 <= x`).
 * Operators whose result depends on the order (`-`, `/`, `<<`, string `+`, …) are flipped too, named "(changes semantics)" as GoLand does.
 */
class GoFlipBinaryOperatorIntention : GoCodeActionIntention() {
    override val defaultText: String = "Flip binary operator"

    private val mirrored = mapOf("<" to ">", ">" to "<", "<=" to ">=", ">=" to "<=")
    private val commutative = setOf("==", "!=", "&&", "||", "*", "&", "|", "^", "+")

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val binary = GoExpressionText.binaryAt(file, offset) { true } ?: return null
        if (GoExpressionText.hasOwnComment(binary)) return null
        val left = binary.left
        val right = binary.right ?: return null
        val token = GoExpressionText.operatorOf(binary) ?: return null
        val op = token.text
        val flipped = mirrored[op] ?: op
        val changes = op !in mirrored && (op !in commutative || (op == "+" && isString(binary)))
        val name = when {
            op in mirrored -> "Flip '$op' to '$flipped'"
            changes -> "Flip '$op' (changes semantics)"
            else -> "Flip '$op'"
        }
        val prec = GoInlineSupport.precedence(binary)
        val chained = (op == "&&" || op == "||") && left.javaClass == binary.javaClass
        val newRight = if (GoInlineSupport.precedence(left) <= prec && !chained) "(${left.text})" else left.text
        val newLeft = if (GoInlineSupport.precedence(right) < prec) "(${right.text})" else right.text
        val text = file.viewProvider.contents
        val before = text.subSequence(left.textRange.endOffset, token.textRange.startOffset)
        val after = text.subSequence(token.textRange.endOffset, right.textRange.startOffset)
        return GoEditPlan(listOf(GoEditPlan.Edit(binary.textRange.startOffset, binary.textRange.endOffset, "$newLeft$before$flipped$after$newRight")), text = name)
    }

    private fun isString(binary: GoBinaryExpr): Boolean =
        (GoSemanticService.getInstance(binary.project).typeOf(binary).underlying() as? GoBasicType)?.kind?.isString == true
}

/**
 * The four negations of GoLand: the current (innermost) or the topmost boolean binary expression at the caret becomes `!(negation)`
 * (or loses its `!` when it has one), the negation one level deep or [recursive]. The recursive variants and the topmost ones are offered
 * only where they write something else than the plain "Negate expression".
 */
abstract class GoNegateIntentionBase(private val topmost: Boolean, private val recursive: Boolean) : GoCodeActionIntention() {

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val current = GoExpressionText.binaryAt(file, offset) { GoExpressionText.isBoolean(it) } ?: return null
        val target = if (topmost) GoExpressionText.topmost(current).takeIf { it != current } ?: return null else current
        if (GoExpressionText.hasOwnComment(target)) return null
        val edit = GoExpressionText.negationEdit(target, recursive) ?: return null
        if (recursive && GoExpressionText.negationEdit(target, false)?.text == edit.text) return null
        val op = GoExpressionText.operatorOf(target)?.text ?: return null
        val negatedOp = GoExpressionText.negatedOperatorText(target) ?: return null
        val name = "Negate ${if (topmost) "topmost " else ""}'$op' to '$negatedOp'${if (recursive) " recursively" else ""}"
        return GoEditPlan(listOf(edit), text = name)
    }
}

/** Negate expression: `x < 0 || 0 < x && x < 10` → `!(x >= 0 && !(0 < x && x < 10))`. */
class GoNegateExpressionIntention : GoNegateIntentionBase(topmost = false, recursive = false) {
    override val defaultText: String = "Negate expression"
}

/** Negate expression recursively: `x < 0 || 0 < x && x < 10` → `!(x >= 0 && (0 >= x || x >= 10))`. */
class GoNegateExpressionRecursivelyIntention : GoNegateIntentionBase(topmost = false, recursive = true) {
    override val defaultText: String = "Negate expression recursively"
}

/** Negate topmost expression: the same on the outermost boolean expression around the caret. */
class GoNegateTopmostExpressionIntention : GoNegateIntentionBase(topmost = true, recursive = false) {
    override val defaultText: String = "Negate topmost expression"
}

/** Negate topmost expression recursively. */
class GoNegateTopmostExpressionRecursivelyIntention : GoNegateIntentionBase(topmost = true, recursive = true) {
    override val defaultText: String = "Negate topmost expression recursively"
}

/**
 * Specify type explicitly: `var i = 1` → `var i int = 1`, `const c = "s"` → `const c string = "s"` (the default type of an untyped
 * constant); several names only when they all get the same type. Types as the file names them, imports added; not for a type the file
 * cannot name or untyped `nil`.
 */
class GoSpecifyTypeIntention : GoCodeActionIntention() {
    override val defaultText: String = "Specify type explicitly"

    override fun plan(file: GoFile, offset: Int): GoEditPlan? {
        val spec = specAt(file, offset) ?: return null
        val definitions = when (spec) {
            is GoVarSpec -> if (spec.type != null || spec.expressionList.isEmpty()) return null else spec.varDefinitionList
            is GoConstSpec -> if (spec.type != null || spec.expressionList.isEmpty()) return null else spec.constDefinitionList
            else -> return null
        }
        if (definitions.isEmpty()) return null
        val service = GoSemanticService.getInstance(file.project)
        val types = definitions.map { GoTypePredicates.defaultType(service.declarationType(it)) }
        val type = types.first()
        if (!GoTypePredicates.isKnown(type) || GoTypePredicates.isUntyped(type)) return null
        if (types.any { !GoTypePredicates.isKnown(it) || !GoTypePredicates.identical(it, type) }) return null
        val source = GoSourceText(file)
        var writable = true
        GoTypeRenderer.render(type) { named -> if (!nameable(named, source)) writable = false; null }
        if (!writable) return null
        val end = definitions.last().textRange.endOffset
        return GoEditPlan(listOf(GoEditPlan.Edit(end, end, " ${source.type(type)}")), source.imports)
    }

    private fun specAt(file: GoFile, offset: Int): PsiElement? {
        var e: PsiElement? = GoIntentionText.leafAt(file, offset)
        while (e != null && e !is PsiFile) {
            if (e is GoVarSpec || e is GoConstSpec) return e
            if (e is GoBlock || e is GoFunctionLit) return null
            e = e.parent
        }
        return null
    }

    private fun nameable(named: GoNamedType, source: GoSourceText): Boolean {
        val exported = named.name.firstOrNull()?.isUpperCase() == true
        return exported || source.isOwnPackage(named.declaration) || (named.declaration.containingFile as? GoFile)?.packageName == "builtin"
    }
}
