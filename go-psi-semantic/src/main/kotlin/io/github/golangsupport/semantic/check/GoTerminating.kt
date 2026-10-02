package io.github.golangsupport.semantic.check

import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.psi.*
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.expressions
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier
import io.github.golangsupport.semantic.psi.GoPsiUtil.referenceName

/**
 * Spec "Terminating statements" (go/types return.go): decides whether a statement list cannot
 * fall off its end, which is what makes a function body with results valid without a trailing
 * `return`. [isPanicCall] decides whether a call is the builtin `panic` (the resolver knows
 * whether the name is shadowed).
 */
class GoTerminating(private val isPanicCall: (GoCallExpr) -> Boolean) {

    /** go/types `isTerminating(s, label)`: [label] is the label of the enclosing labeled statement, if any. */
    fun isTerminating(s: PsiElement?, label: String? = null): Boolean = when (s) {
        null -> false
        is GoReturnStatement, is GoGotoStatement -> true
        is GoSimpleStatement -> {
            val inner = s.statement
            if (inner != null) isTerminating(inner, label)
            else {
                var x: GoExpression? = s.expressions.singleOrNull()
                while (x is GoParenthesesExpr) x = x.inner as? GoExpression
                val call = x as? GoCallExpr
                call != null && isPanicCall(call)
            }
        }
        is GoBlock -> isTerminatingList(s.statementList)
        is GoIfStatement -> {
            val elseStmt = s.elseStatement?.statement
            elseStmt != null && isTerminating(s.block) && isTerminating(elseStmt)
        }
        is GoForStatement -> s.condition == null && s.rangeClause == null && !hasBreak(s.block, label, true)
        is GoExprSwitchStatement -> !hasBreakList(s.exprCaseClauseList.flatMap { it.statementList }, label, true) &&
            s.exprCaseClauseList.any { it.default != null } &&
            s.exprCaseClauseList.all { c -> isTerminatingList(c.statementList) || hasFallthrough(c.statementList) }
        is GoTypeSwitchStatement -> !hasBreakList(s.typeCaseClauseList.flatMap { it.statementList }, label, true) &&
            s.typeCaseClauseList.any { it.default != null } &&
            s.typeCaseClauseList.all { c -> isTerminatingList(c.statementList) }
        is GoSelectStatement -> !hasBreakList(s.commClauseList.flatMap { it.statementList }, label, true) &&
            s.commClauseList.all { c -> isTerminatingList(c.statementList) }
        is GoLabeledStatement -> isTerminating(s.statement, s.labelDefinition?.name)
        else -> false
    }

    /** A statement list is terminating when its last non-empty statement is. */
    fun isTerminatingList(list: List<GoStatement>): Boolean {
        val last = list.lastOrNull() ?: return false
        return isTerminating(last)
    }

    private fun hasFallthrough(list: List<GoStatement>): Boolean {
        val last = list.lastOrNull() ?: return false
        return unlabel(last) is GoFallthroughStatement
    }

    private fun unlabel(s: GoStatement): GoStatement {
        var x: GoStatement = s
        while (x is GoLabeledStatement) x = x.statement ?: return x
        return x
    }

    /**
     * go/types `hasBreak`: true if [s] contains a `break` that refers to the enclosing statement:
     * an unlabeled break when [implicit] (not nested in another breakable statement), or a break
     * with [label]. Function literals are opaque.
     */
    fun hasBreak(s: PsiElement?, label: String?, implicit: Boolean): Boolean = when (s) {
        null -> false
        is GoBreakStatement -> {
            val ref = s.labelRef
            if (ref == null) implicit else label != null && ref.text == label
        }
        is GoBlock -> hasBreakList(s.statementList, label, implicit)
        is GoIfStatement -> hasBreak(s.block, label, implicit) || hasBreak(s.elseStatement?.statement, label, implicit)
        is GoLabeledStatement -> hasBreak(s.statement, label, implicit)
        is GoForStatement -> hasBreak(s.block, label, false)
        is GoExprSwitchStatement -> s.exprCaseClauseList.any { hasBreakList(it.statementList, label, false) }
        is GoTypeSwitchStatement -> s.typeCaseClauseList.any { hasBreakList(it.statementList, label, false) }
        is GoSelectStatement -> s.commClauseList.any { hasBreakList(it.statementList, label, false) }
        is GoSimpleStatement -> s.statement?.let { hasBreak(it, label, implicit) } ?: false
        else -> false
    }

    private fun hasBreakList(list: List<GoStatement>, label: String?, implicit: Boolean): Boolean =
        list.any { hasBreak(it, label, implicit) }

    companion object {
        /** `panic(...)` where `panic` resolves to nothing (the universe) or to the builtin declaration. */
        fun isPanicCallee(call: GoCallExpr, isBuiltin: (GoReferenceExpression) -> Boolean): Boolean {
            var callee = call.expression ?: return false
            while (callee is GoParenthesesExpr) callee = callee.inner as? GoExpression ?: return false
            val ref = callee as? GoReferenceExpression ?: return false
            return ref.qualifier == null && ref.referenceName == "panic" && isBuiltin(ref)
        }
    }
}
