package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoRecvStatement
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.forClause
import io.github.golangsupport.semantic.psi.GoPsiUtil.rangeClause

/**
 * staticcheck S1000: a `select` with a single case is a plain send or receive; `for { select { case x := <-ch: ... } }` is
 * `for x := range ch { ... }`. Fixes move the case body out (not when it breaks out of the select or its declarations would clash).
 */
class GoSingleCaseSelectRule : GoSimpleStatementRule() {
    override val id: String get() = "S1000"
    override val title: String get() = "Use plain channel send or receive instead of single-case select"
    override val description: String get() = "Select statements with a single case can be replaced with a simple send or receive."
    override val needs: Set<GoRuleNeed> get() = SYNTAX_ONLY

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        when (statement) {
            is GoForStatement -> if (forSelect(statement) != null) {
                ctx.report(statement, GoSimplePsi.keywordRange(statement), "should use for range instead of for { select {} }",
                    *GoRewriteFix.offer("Replace with for range", statement, ctx) { e, _ -> forRange(e) })
            }
            is GoSelectStatement -> {
                if (statement.commClauseList.size != 1) return
                val loop = (statement.parent as? GoBlock)?.parent as? GoForStatement
                if (loop != null && forSelect(loop) === statement) return
                ctx.report(statement, GoSimplePsi.keywordRange(statement), "should use a simple channel send/receive instead of select with a single case",
                    *GoRewriteFix.offer("Replace with plain channel operation", statement, ctx) { e, _ -> plain(e) })
            }
        }
    }

    /** The select of `for { select { case <recv>: ... } }` (a receive, not a send). */
    private fun forSelect(loop: GoForStatement): GoSelectStatement? {
        if (loop.forClause != null || loop.rangeClause != null || loop.condition != null) return null
        val select = loop.block?.statementList?.singleOrNull() as? GoSelectStatement ?: return null
        val clause = select.commClauseList.singleOrNull() ?: return null
        val recv = clause.commCase?.statement as? GoRecvStatement ?: return null
        return select.takeIf { isReceive(recv) }
    }

    private fun isReceive(recv: GoRecvStatement): Boolean = (GoSimplePsi.unparen(recv.expression) as? GoUnaryExpr)?.arrow != null

    /** `for {select{case x := <-ch: body}}` → `for x := range ch {body}`, for `<-ch` and `x := <-ch` only. */
    private fun forRange(element: PsiElement): List<GoEditPlan.Edit>? {
        val loop = element as? GoForStatement ?: return null
        val select = forSelect(loop) ?: return null
        val clause = select.commClauseList.single()
        val recv = clause.commCase?.statement as? GoRecvStatement ?: return null
        val channel = (GoSimplePsi.unparen(recv.expression) as? GoUnaryExpr)?.expression ?: return null
        val head = when {
            recv.leftHandExprList != null -> return null
            recv.varDefinitionList.isEmpty() -> "for range ${channel.text} {"
            recv.varDefinitionList.size == 1 -> "for ${recv.varDefinitionList[0].text} := range ${channel.text} {"
            else -> return null
        }
        if (GoSimplePsi.hasOwnBreak(clause.statementList)) return null
        val body = bodyText(loop, select, clause, -1) ?: return null
        val indent = GoEditText.indentOf(loop.containingFile.node.chars, loop.textRange.startOffset)
        return GoSimplePsi.replace(loop, if (body.isEmpty()) "$head\n$indent}" else "$head\n$body\n$indent}")
    }

    private fun plain(element: PsiElement): List<GoEditPlan.Edit>? {
        val select = element as? GoSelectStatement ?: return null
        val clause = select.commClauseList.singleOrNull() ?: return null
        val case = clause.commCase ?: return null
        if (case.default != null) return null
        val comm = case.statement ?: return null
        if (comm.text.contains('\n')) return null
        val declared = (comm as? GoRecvStatement)?.varDefinitionList.orEmpty().mapNotNull { it.name }
        return GoSimplePsi.inlineClause(select, clause, comm.text, declared)
    }

    /** The case body shifted by [delta] levels, or null when comments outside it would be lost. */
    private fun bodyText(outer: PsiElement, select: GoSelectStatement, clause: GoCommClause, delta: Int): String? {
        val colon = clause.colon ?: return null
        val end = maxOf(GoEditText.contentEnd(clause), colon.textRange.endOffset)
        if (GoSimplePsi.commentsOutside(outer, listOf(TextRange(colon.textRange.endOffset, end)))) return null
        val file = outer.containingFile
        val indent = GoEditText.indentOf(file.node.chars, outer.textRange.startOffset) + "\t"
        return if (end > colon.textRange.endOffset) GoEditText.body(file, colon.textRange.endOffset, end, delta, indent) else ""
    }
}
