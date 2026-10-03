package io.github.golangsupport.ide.rules.builtin.statements

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoCallRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.ide.rules.GoStatementRule
import io.github.golangsupport.ide.rules.builtin.simple.GoSimplePsi
import io.github.golangsupport.lang.psi.GoRecvStatement
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoAssignmentStatement
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConversionExpr
import io.github.golangsupport.lang.psi.GoElseStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoForClause
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoIncDecStatement
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoRangeClause
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause

/** Base of the staticcheck statement checks of batch B5 (`SA2xxx`, `SA4011`, `SA5002`...): types needed unless overridden. */
abstract class GoSuspiciousStatementRule : GoStatementRule() {
    override val linter: String get() = "staticcheck"
    override val needs: Set<GoRuleNeed> get() = GoStatementsPsi.TYPES
}

/** Base of the govet statement checks of batch B5 (`atomic`, `defers`). */
abstract class GoVetStatementRule : GoSuspiciousStatementRule() {
    override val linter: String get() = "govet"
}

/** [GoSuspiciousStatementRule] for the checks of batch B5 that look at single calls (`append(x)`, `regexp.Match` in a loop). */
abstract class GoSuspiciousCallRule : GoCallRule() {
    override val linter: String get() = "staticcheck"
    override val needs: Set<GoRuleNeed> get() = GoStatementsPsi.TYPES
}

/** PSI helpers of the statement checks: statement shapes, if/else chains, loops, side effects, variable uses. */
internal object GoStatementsPsi {
    val TYPES: Set<GoRuleNeed> = setOf(GoRuleNeed.TYPES)

    /** `x := ...` comes as a [GoSimpleStatement] around the declaration in some positions: the statement inside, else [s]. */
    fun unwrap(s: PsiElement?): PsiElement? = if (s is GoSimpleStatement) s.statement ?: s else s

    /** The call of an expression statement `f(...)` (parentheses allowed around it, like go/ast `Unparen`). */
    fun callStatement(s: PsiElement?): GoCallExpr? = GoLintPsi.unparen(GoSimplePsi.expressionStatement(unwrap(s))) as? GoCallExpr

    /** The statements of a block or clause [list]. */
    fun statements(list: PsiElement?): List<GoStatement> = if (list == null) emptyList() else GoEditText.statements(list)

    /** The statement list [s] is in, or null. */
    fun list(s: PsiElement): PsiElement? = s.parent?.takeIf { GoEditText.isStatementList(it) }

    // ---- if / else chains

    /** An `if` that is the `else if` of another one. */
    fun isElseIf(s: GoIfStatement): Boolean = s.parent is GoElseStatement || s.parent is GoIfStatement

    /** The `else` branch: the nested `if` or the block; null without else. */
    fun elseBranch(s: GoIfStatement): GoStatement? = s.elseStatement?.statement

    /** [head] and its `else if`s, in order. */
    fun chain(head: GoIfStatement): List<GoIfStatement> = generateSequence(head) { elseBranch(it) as? GoIfStatement }.toList()

    // ---- loops

    /** `for {}` / `for ;; {}`: no init, condition or post. */
    fun isBareLoop(loop: GoForStatement): Boolean {
        if (loop.rangeClause != null) return false
        val clause = loop.forClause ?: return GoPsiUtil.children(loop, GoExpression::class.java).isEmpty()
        val parts = GoSimplePsi.forParts(clause) ?: return false
        return parts.init == null && parts.cond == null && parts.post == null
    }

    /** The loop condition of a non-range `for`: `for cond {}` or the middle of a three-clause loop. */
    fun condition(loop: GoForStatement): GoExpression? {
        if (loop.rangeClause != null) return null
        val clause = loop.forClause ?: return GoPsiUtil.children(loop, GoExpression::class.java).firstOrNull()
        return GoSimplePsi.forParts(clause)?.cond
    }

    /**
     * Whether [element] runs on every iteration of a `for` loop of its function: in a loop body, condition or post statement (not in
     * a range expression or a for-clause init, which run once); function literals stop the search.
     */
    fun inLoop(element: PsiElement): Boolean {
        var child = element
        var parent = element.parent
        while (parent != null) {
            when (parent) {
                is GoFunctionLit, is GoFunctionOrMethodDeclaration -> return false
                is GoForStatement -> when (child) {
                    is GoBlock -> return true
                    is GoForClause -> if (GoSimplePsi.forParts(child)?.init?.let { PsiTreeUtil.isAncestor(it, element, false) } != true) return true
                    is GoRangeClause -> {}
                    is GoExpression -> return true
                }
            }
            child = parent
            parent = parent.parent
        }
        return false
    }

    // ---- expressions

    /**
     * staticcheck `code.MayHaveSideEffects` without purity facts: any call (a conversion is a call too), receive or address-of in
     * [e], function literals not entered.
     */
    fun mayHaveSideEffects(e: PsiElement?): Boolean {
        if (e == null) return false
        if (e is GoFunctionLit) return false
        if (e is GoCallExpr || e is GoConversionExpr) return true
        if (e is GoUnaryExpr && (e.arrow != null || e.and != null)) return true
        var child = e.firstChild
        while (child != null) {
            if (mayHaveSideEffects(child)) return true
            child = child.nextSibling
        }
        return false
    }

    /** The first token of [e] as a range inside it (go/analysis `ShortRange` of a statement: its keyword). */
    fun keywordRange(e: PsiElement): TextRange = GoSimplePsi.keywordRange(e)

    // ---- variables

    /** References to [variable] (by name, then resolved) inside [scope]. */
    fun references(scope: PsiElement, variable: GoNamedElement, ctx: GoRuleContext): List<GoReferenceExpression> {
        val name = variable.name ?: return emptyList()
        if (!scope.text.contains(name)) return emptyList()
        return PsiTreeUtil.findChildrenOfType(scope, GoReferenceExpression::class.java)
            .filter { it.expression == null && it.identifier.text == name && ctx.resolve(it).singleOrNull() == variable }
    }

    /** The function literal between [ref] and [top] (exclusive), if any: a use there is a capture. */
    fun insideLiteral(ref: PsiElement, top: PsiElement): Boolean {
        var e = ref.parent
        while (e != null && e !== top) {
            if (e is GoFunctionLit) return true
            e = e.parent
        }
        return false
    }

    /**
     * Whether [ref] writes or may alias its variable: the root of an assignment / inc-dec / range target (`x = `, `x.f = `, `x[i]++`),
     * `&x`, or the receiver of a pointer method `x.M()`.
     */
    fun isWriteOrAddress(ref: GoReferenceExpression, ctx: GoRuleContext): Boolean {
        var top: PsiElement = ref
        while (true) {
            val p = top.parent
            if (p is GoReferenceExpression && p.expression === top) {
                if (p.parent is GoCallExpr && (p.parent as GoCallExpr).expression === p) {
                    val m = ctx.resolve(p).singleOrNull()
                    if (m is GoMethodDeclaration && m.isPointerReceiver) return true
                }
                top = p
                continue
            }
            if (p is GoIndexOrSliceExpr && p.expression === top) { top = p; continue }
            if (p is GoParenthesesExpr) { top = p; continue }
            break
        }
        val parent = top.parent
        if (parent is GoUnaryExpr && parent.and != null) return true
        val lhs = parent as? GoLeftHandExprList ?: return false
        return when (val owner = lhs.parent) {
            is GoAssignmentStatement, is GoIncDecStatement, is GoRangeClause -> true
            is GoRecvStatement -> owner.leftHandExprList === lhs
            else -> false
        }
    }
}
