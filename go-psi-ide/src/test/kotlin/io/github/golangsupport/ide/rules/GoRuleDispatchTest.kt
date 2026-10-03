package io.github.golangsupport.ide.rules

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.ExtensionTestUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoTypeSpec
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Performance sanity without wall time: 200 subscribed rules over a 2k-line file. The tree is walked once (no element reaches the
 * visitor twice), every rule sees exactly the nodes of its scope once, and a disabled rule is never called.
 */
class GoRuleDispatchTest : GoSemanticIdeTestBase() {

    private class Dummy(val n: Int, override val scope: GoRuleScope, private val on: Boolean = true) : GoRule() {
        val calls = AtomicInteger()
        val seen: MutableSet<PsiElement> = Collections.newSetFromMap(Collections.synchronizedMap(IdentityHashMap()))
        var duplicates = AtomicInteger()
        override val id: String get() = "dummy$n"
        override val linter: String get() = "dummy"
        override val title: String get() = "Dummy $n"
        override val enabledByDefault: Boolean get() = on
        override fun check(element: PsiElement, ctx: GoRuleContext) {
            calls.incrementAndGet()
            if (!seen.add(element)) duplicates.incrementAndGet()
        }
    }

    private fun source(): String = buildString {
        append("package rd\n\n")
        for (i in 0 until 250) {
            append("type rdT$i struct{ a int }\n\n")
            append("func rdF$i(x int) int {\n\tif x > $i {\n\t\treturn rdF$i(x - 1)\n\t}\n\tg := func() int { return x }\n\treturn g()\n}\n")
        }
    }

    /** Registers [rules] alone and counts the visits (lambdas live here: JUnit 3 takes `test*$lambda` methods for tests). */
    private fun install(rules: List<GoRule>): Map<PsiElement, AtomicInteger> {
        ExtensionTestUtil.maskExtensions(GoRule.EP_NAME, rules, testRootDisposable)
        GoRuleSet.getInstance(project).invalidate()
        Disposer.register(testRootDisposable) { GoRuleSet.getInstance(project).invalidate() }
        val visits = ConcurrentHashMap<PsiElement, AtomicInteger>()
        GoRules.visitListener = { visits.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() }
        Disposer.register(testRootDisposable) { GoRules.visitListener = null }
        return visits
    }

    fun testOneWalkAndExactDispatch() {
        val scopes = listOf(GoRuleScope.CALL, GoRuleScope.EXPRESSION, GoRuleScope.STATEMENT, GoRuleScope.FUNCTION, GoRuleScope.TYPE_SPEC)
        val dummies = (0 until 200).map { Dummy(it, scopes[it % scopes.size]) }
        val disabled = Dummy(999, GoRuleScope.CALL, on = false)
        val fileRule = Dummy(1000, GoRuleScope.FILE)
        val visits = install(dummies + disabled + fileRule)

        val text = source()
        assertTrue(text.lines().size >= 2000)
        myFixture.enableInspections(GoRules())
        myFixture.configureByText("rd.go", text)
        myFixture.doHighlighting()

        assertTrue(visits.isNotEmpty())
        assertEquals("elements visited more than once", 0, visits.values.count { it.get() > 1 })
        val all = SyntaxTraverser.psiTraverser(myFixture.file).toList()
        val expected = mapOf(
            GoRuleScope.CALL to all.count { it is GoCallExpr },
            GoRuleScope.EXPRESSION to all.count { it is GoExpression },
            GoRuleScope.FUNCTION to all.count { it is GoFunctionOrMethodDeclaration || it is GoFunctionLit },
            GoRuleScope.TYPE_SPEC to all.count { it is GoTypeSpec },
        )
        val statements = dummies.first { it.scope == GoRuleScope.STATEMENT }.calls.get()
        assertEquals(250 * 5, statements) // if, return in if, g :=, return in the literal, return
        for (dummy in dummies) {
            assertEquals("${dummy.id} saw a node twice", 0, dummy.duplicates.get())
            assertEquals("${dummy.id} (${dummy.scope})", expected[dummy.scope] ?: statements, dummy.calls.get())
        }
        assertEquals(0, disabled.calls.get())
        assertEquals(1, fileRule.calls.get())
    }
}
