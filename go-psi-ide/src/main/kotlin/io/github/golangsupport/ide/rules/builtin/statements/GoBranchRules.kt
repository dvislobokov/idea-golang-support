package io.github.golangsupport.ide.rules.builtin.statements

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.ide.rules.GoRule
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.ide.rules.builtin.simple.GoRewriteFix
import io.github.golangsupport.ide.rules.builtin.staticcheck.GoExpressionPsi
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoElseStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoShortVarDeclaration
import io.github.golangsupport.lang.psi.GoStatement
import io.github.golangsupport.lang.psi.GoTypeAssertionExpr
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.psi.GoPsiUtil.block
import io.github.golangsupport.semantic.psi.GoPsiUtil.condition
import io.github.golangsupport.semantic.psi.GoPsiUtil.elseStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.initStatement
import io.github.golangsupport.semantic.psi.GoPsiUtil.types
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoTypeRenderer

/**
 * staticcheck SA4014: an `if` / `else if` chain testing the same side-effect-free condition twice; the second test can never be true.
 * Like staticcheck, a link with an init statement or a condition with calls splits the chain.
 */
class GoRepeatedConditionRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA4014"
    override val title: String get() =
        "An if/else if chain has repeated conditions and no side-effects; if the condition didn't match the first time, it won't match the second time, either"
    override val needs: Set<GoRuleNeed> get() = GoRule.SYNTAX_ONLY

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoIfStatement || GoStatementsPsi.isElseIf(statement)) return
        val chain = GoStatementsPsi.chain(statement)
        var start = 0
        while (start < chain.size) {
            // staticcheck visits every `if`; a link that bails starts a new chain at the link after it
            val conds = ArrayList<GoExpression>()
            var bail = -1
            for (i in start until chain.size) {
                val s = chain[i]
                val cond = s.condition
                if (s.initStatement != null || cond == null || GoStatementsPsi.mayHaveSideEffects(cond)) { bail = i; break }
                conds += cond
            }
            if (bail < 0 && GoStatementsPsi.elseBranch(chain[start]) != null && conds.size >= 2) report(conds, ctx)
            if (bail < 0) return
            start = bail + 1
        }
    }

    private fun report(conds: List<GoExpression>, ctx: GoRuleContext) {
        val counts = HashMap<String, Int>()
        for (cond in conds) {
            val n = counts.merge(GoExpressionPsi.render(cond), 1, Int::plus)
            if (n == 2) ctx.report(cond, "this condition occurs multiple times in this if/else if chain")
        }
    }
}

/**
 * staticcheck SA9003: an `if` with an empty body and no `else` (or an empty `else`), and an empty `else` block. `if c {} else { ... }`
 * and `if c {} else if ...` are left alone, like staticcheck does; so are `Example` functions of tests. The fix removes an empty `else`.
 * Off by default (a non-default check of staticcheck); on with the staticcheck linter of a configuration.
 */
class GoEmptyBranchRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA9003"
    override val title: String get() = "Empty body in an if or else branch"
    override val needs: Set<GoRuleNeed> get() = GoRule.SYNTAX_ONLY

    // staticcheck marks SA9003 NonDefault (`if err != nil { // ignored }` is common); golangci's `checks: [all]` turns it on
    override val enabledByDefault: Boolean get() = false

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoIfStatement || GoStatementsPsi.isElseIf(statement)) return
        if (inExample(statement, ctx)) return
        for (s in GoStatementsPsi.chain(statement)) check(s, ctx)
    }

    private fun check(s: GoIfStatement, ctx: GoRuleContext) {
        val otherwise = s.elseStatement
        if (otherwise != null) {
            val block = otherwise.statement as? GoBlock ?: return
            if (GoStatementsPsi.statements(block).isNotEmpty()) return
            ctx.report(otherwise, TextRange(0, otherwise.`else`.textLength), "empty branch",
                *GoRewriteFix.offer("Remove empty else branch", otherwise, ctx, ::removeElse))
        }
        if (GoStatementsPsi.statements(s.block).isNotEmpty()) return
        ctx.report(s, GoStatementsPsi.keywordRange(s), "empty branch")
    }

    private fun removeElse(element: PsiElement, @Suppress("UNUSED_PARAMETER") ctx: GoRuleContext): List<GoEditPlan.Edit>? {
        val otherwise = element as? GoElseStatement ?: return null
        val block = otherwise.statement as? GoBlock ?: return null
        if (GoStatementsPsi.statements(block).isNotEmpty() || PsiTreeUtil.findChildOfType(otherwise, PsiComment::class.java) != null) return null
        val owner = otherwise.parent as? GoIfStatement ?: return null
        val body = owner.block ?: return null
        if (body.textRange.endOffset > otherwise.textRange.startOffset) return null
        return listOf(GoEditPlan.Edit(body.textRange.endOffset, otherwise.textRange.endOffset, ""))
    }

    /** staticcheck skips `Example...` functions (and the literals in them) of `_test.go` files. */
    private fun inExample(s: PsiElement, ctx: GoRuleContext): Boolean {
        if (!ctx.file.name.endsWith("_test.go")) return false
        val fn = PsiTreeUtil.getParentOfType(s, GoFunctionOrMethodDeclaration::class.java) ?: return false
        return fn.name?.startsWith("Example") == true
    }
}

/**
 * staticcheck SA9008: `if x, ok := x.(T); ok { ... } else { use(x) }`: in the `else` branch `x` is the zero value of the failed
 * assertion, not the value that was asserted. Reads of the new `x` are reported unless it is written, address-taken or captured.
 */
class GoShadowedAssertionElseRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA9008"
    override val title: String get() = "else branch of a type assertion is probably not reading the right value"

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoIfStatement) return
        val init = GoStatementsPsi.unwrap(statement.initStatement) as? GoShortVarDeclaration ?: return
        val defs = init.varDefinitionList
        if (defs.size != 2) return
        val (x, ok) = defs
        val assert = init.expressionList.singleOrNull() as? GoTypeAssertionExpr ?: return
        val operand = assert.expression as? GoReferenceExpression ?: return
        if (operand.expression != null || operand.identifier.text != x.name) return
        val cond = statement.condition as? GoReferenceExpression ?: return
        if (cond.expression != null || cond.identifier.text != ok.name) return
        val branch = GoStatementsPsi.elseBranch(statement) ?: return
        val all = GoStatementsPsi.references(statement, x as GoVarDefinition, ctx)
        if (all.any { GoStatementsPsi.insideLiteral(it, statement) || GoStatementsPsi.isWriteOrAddress(it, ctx) && (it.isInside(branch) || isAddress(it)) }) return
        for (ref in all) {
            if (!ref.isInside(branch)) continue
            ctx.report(ref, "${ref.identifier.text} refers to the result of a failed type assertion and is a zero value, not the value that was being type-asserted")
        }
    }

    private fun PsiElement.isInside(e: PsiElement): Boolean = PsiTreeUtil.isAncestor(e, this, false)

    /** `&x` or a pointer-method call (the variable escapes, staticcheck gives up), as opposed to a plain write in the other branch. */
    private fun isAddress(ref: GoReferenceExpression): Boolean {
        var top: PsiElement = ref
        while (top.parent is GoParenthesesExpr) top = top.parent
        val p = top.parent
        return p is GoUnaryExpr && p.and != null || p is GoReferenceExpression && p.expression === top
    }
}

/**
 * staticcheck SA4020: a type switch case that can never be reached because an earlier case lists an interface that every type of the
 * later case implements (`case io.Reader:` before `case *os.File:` or `case io.ReadCloser:`).
 */
class GoUnreachableTypeCaseRule : GoSuspiciousStatementRule() {
    override val id: String get() = "SA4020"
    override val title: String get() = "Unreachable case clause in a type switch"

    override fun checkStatement(statement: GoStatement, ctx: GoRuleContext) {
        if (statement !is GoTypeSwitchStatement) return
        val clauses = statement.typeCaseClauseList.filter { it.default == null }
            .map { c -> c to c.types.filter { it.text != "nil" }.map { GoExpressionPsi.typeOf(it) } }
        if (clauses.size <= 1) return
        for (i in 0 until clauses.size - 1) {
            for (j in i + 1 until clauses.size) {
                val (t, v) = subsumesAny(clauses[i].second, clauses[j].second) ?: continue
                val clause = clauses[j].first
                ctx.report(clause, TextRange(0, clause.firstChild.textLength), "unreachable case clause: ${render(t)} will always match before ${render(v)}")
            }
        }
    }

    private fun subsumesAny(ts: List<GoType>, vs: List<GoType>): Pair<GoType, GoType>? {
        for (t in ts) for (v in vs) if (subsumes(t, v)) return t to v
        return null
    }

    /** [t] is an interface whose method set every value of [v] has. Unknown or generic types never subsume. */
    private fun subsumes(t: GoType, v: GoType): Boolean {
        if (t is GoTypeParamType || v is GoTypeParamType) return false
        val iface = t.underlying() as? GoInterfaceType ?: return false
        if (iface.isConstraintOnly) return false
        if (!GoTypePredicates.isKnown(t) || !GoTypePredicates.isKnown(v) || generic(t) || generic(v)) return false
        if (iface.allMethods.any { !GoTypePredicates.isKnown(it.signature) }) return false
        return GoTypePredicates.implements(v, iface)
    }

    private fun generic(t: GoType): Boolean = t is GoNamedType && t.isGeneric

    /** go/types `Type.String()`: named types qualified by their full package path. */
    private fun render(t: GoType): String = GoTypeRenderer.render(t) { named ->
        named.pkgPath?.takeIf { (named.declaration.containingFile as? GoFile)?.packageName != "builtin" }
    }
}
