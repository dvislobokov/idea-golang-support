package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.editor.GoEditText
import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoBreakStatement
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoReturnStatement
import io.github.golangsupport.lang.psi.GoStatement

/**
 * staticcheck S1023: a `return` ending a function without results, an unlabeled `break` ending a `switch` case with other statements.
 * The fix deletes the statement (not when a comment shares its line).
 */
class GoRedundantControlFlowRule : GoSimpleStatementRule() {
    override val id: String get() = "S1023"
    override val title: String get() = "Omit redundant control flow"
    override val description: String get() =
        "Functions that have no return value do not need a return statement as the final statement of the function. Switches in Go do not have " +
            "automatic fallthrough, unlike languages like C. It is not necessary to have a break statement as the final statement in a case block."
    override val needs: Set<GoRuleNeed> get() = SYNTAX_ONLY

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        val message = when {
            statement is GoBreakStatement && isRedundantBreak(statement) -> "redundant break statement"
            statement is GoReturnStatement && isRedundantReturn(statement) -> "redundant return statement"
            else -> return
        }
        ctx.report(statement, message, *GoRewriteFix.offer("Remove redundant statement", statement, ctx) { e, _ -> delete(e) })
    }

    private fun isRedundantBreak(b: GoBreakStatement): Boolean {
        if (b.labelRef != null) return false
        val clause = b.parent
        if (!GoSimplePsi.isSwitchClause(clause)) return false
        val all = GoSimplePsi.statements(clause)
        return all.size >= 2 && all.last() === b
    }

    private fun isRedundantReturn(r: GoReturnStatement): Boolean {
        if (r.expressionList.isNotEmpty()) return false
        val body = r.parent as? GoBlock ?: return false
        val signature = when (val owner = body.parent) {
            is GoFunctionOrMethodDeclaration -> owner.signature.takeIf { owner.block === body }
            is GoFunctionLit -> owner.signature
            else -> null
        } ?: return false
        if (signature.result != null) return false
        return GoSimplePsi.statements(body).last() === r
    }

    private fun delete(element: PsiElement): List<GoEditPlan.Edit>? {
        val statement = element as? GoStatement ?: return null
        val text = statement.containingFile.node.chars
        val range = statement.textRange
        // a comment on the same line (or anything else) would stay behind: only a statement alone on its line goes
        if (!GoEditText.startsLine(text, range.startOffset) || text.subSequence(range.endOffset, GoEditText.lineEnd(text, range.endOffset)).isNotBlank()) return null
        val r = GoLintPsi.statementRange(statement, text)
        // blank lines above the last statement would end up before the closing brace, which gofmt drops
        var start = r.startOffset
        while (start > 0 && text[start - 1] == '\n') {
            val prev = GoEditText.lineStart(text, start - 1)
            if (prev == 0 || text.subSequence(prev, start - 1).isNotBlank()) break
            start = prev
        }
        return listOf(GoEditPlan.Edit(start, r.endOffset, ""))
    }
}
