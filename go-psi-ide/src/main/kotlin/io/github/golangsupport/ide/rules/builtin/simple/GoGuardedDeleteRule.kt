package io.github.golangsupport.ide.rules.builtin.simple

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement

/** staticcheck S1033: `if _, ok := m[k]; ok { delete(m, k) }` is `delete(m, k)`: deleting a missing key (or from a nil map) does nothing. */
class GoGuardedDeleteRule : GoSimpleStatementRule() {
    override val id: String get() = "S1033"
    override val title: String get() = "Unnecessary guard around call to delete"
    override val description: String get() = "Calling <code>delete</code> on a nil map is a no-op."

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoIfStatement || call(statement, ctx) == null) return
        ctx.report(statement, GoSimplePsi.keywordRange(statement), "unnecessary guard around call to delete",
            *GoRewriteFix.offer("Remove guard", statement, ctx, ::fix))
    }

    /** `delete(m, k)` guarded by `if _, ok := m[k]; ok`. */
    private fun call(statement: GoIfStatement, ctx: GoRuleContext): GoCallExpr? {
        if (statement.elseStatement != null) return null
        val (map, key) = GoSimplePsi.mapLookupGuard(statement, ctx) ?: return null
        val call = GoSimplePsi.expressionStatement(statement.block?.statementList?.singleOrNull()) as? GoCallExpr ?: return null
        if (!GoSimplePsi.isBuiltinCall(call, "delete", ctx)) return null
        val args = GoSimplePsi.args(call)
        if (args.size != 2 || GoSimplePsi.norm(args[0]) != GoSimplePsi.norm(map) || GoSimplePsi.norm(args[1]) != GoSimplePsi.norm(key)) return null
        return call
    }

    private fun fix(element: PsiElement, ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val statement = element as? GoIfStatement ?: return null
        val call = call(statement, ctx) ?: return null
        if (GoSimplePsi.hasComments(statement) || call.text.contains('\n')) return null
        return GoSimplePsi.replace(statement, call.text)
    }
}
