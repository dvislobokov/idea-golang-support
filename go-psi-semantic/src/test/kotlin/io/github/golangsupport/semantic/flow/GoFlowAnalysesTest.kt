package io.github.golangsupport.semantic.flow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.AstLoadingFilter
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.flow.GoValueFate.OVERWRITTEN
import io.github.golangsupport.semantic.flow.GoValueFate.READ
import io.github.golangsupport.semantic.flow.GoValueFate.RETURNED

/** The dataflow solver and the ready analyses: liveness with value fates, reaching definitions, nil-ness. */
class GoFlowAnalysesTest : GoFlowTestBase() {

    /** The access at the reference / definition that starts at the [occurrence]-th match of [snippet] (plus [shift]). */
    private fun accessAt(file: GoFile, flow: GoControlFlow, snippet: String, occurrence: Int = 0, shift: Int = 0, write: Boolean = true): GoFlowAccess {
        var offset = -1
        repeat(occurrence + 1) { offset = file.text.indexOf(snippet, offset + 1) }
        assertTrue("no '$snippet'", offset >= 0)
        var e = file.findElementAt(offset + shift)
        while (e != null && flow.accessesAt(e).isEmpty()) e = e.parent
        return flow.accessesAt(e ?: error("no access at '$snippet'")).first { it.isWrite == write }
    }

    private fun refAt(file: GoFile, snippet: String, name: String): GoReferenceExpression {
        val offset = file.text.indexOf(snippet)
        assertTrue("no '$snippet'", offset >= 0)
        return PsiTreeUtil.getParentOfType(file.findElementAt(offset + snippet.indexOf(name, snippet.indexOf('(') + 1)), GoReferenceExpression::class.java)!!
    }

    // --- solver ---

    /** Forward reachability and backward "can reach the exit" over a two-point lattice. */
    private class Reach(override val direction: GoDataflowDirection) : GoDataflowAnalysis<Boolean> {
        override fun boundary(flow: GoControlFlow) = true
        override fun bottom(flow: GoControlFlow) = false
        override fun join(a: Boolean, b: Boolean) = a || b
        override fun transfer(node: GoFlowNode, fact: Boolean) = fact
        override fun transferEdge(edge: GoFlowEdge, fact: Boolean) = fact && edge.kind != GoFlowEdge.Kind.FALSE || fact && edge.from.kind != GoFlowNode.Kind.CONDITION
    }

    fun testSolverForwardAndBackward() {
        val flow = flow(
            """
            func f(n int) int {
            	for {
            		n++
            	}
            	return n
            }
            func g(n int) int {
            	if n > 0 {
            		return 1
            	}
            	for {
            	}
            }
            """,
        )!!
        val forward = GoDataflowSolver.solve(flow, Reach(GoDataflowDirection.FORWARD))!!
        val ret = flow.nodes.first { it.kind == GoFlowNode.Kind.RETURN }
        assertFalse("after an infinite loop", forward.before(ret))
        assertTrue(forward.before(flow.nodes.first { it.element?.text == "n++" }))
        val g = GoControlFlow.of(function(file("func g(n int) int {\n\tif n > 0 {\n\t\treturn 1\n\t}\n\tfor {\n\t}\n}"), "g"))!!
        val backward = GoDataflowSolver.solve(g, Reach(GoDataflowDirection.BACKWARD))!!
        assertTrue(backward.before(g.entry))
        assertFalse("the infinite loop never reaches the exit", backward.before(g.nodes.first { it.kind == GoFlowNode.Kind.JOIN }))
    }

    fun testSolverGivesUpOnNonMonotoneAnalysis() {
        val flow = flow("func f(n int) {\n\tfor n > 0 {\n\t\tn--\n\t}\n}")!!
        val flip = object : GoDataflowAnalysis<Int> {
            override val direction = GoDataflowDirection.FORWARD
            override fun boundary(flow: GoControlFlow) = 0
            override fun bottom(flow: GoControlFlow) = 0
            override fun join(a: Int, b: Int) = maxOf(a, b)
            override fun transfer(node: GoFlowNode, fact: Int) = fact + 1
        }
        assertNull(GoDataflowSolver.solve(flow, flip))
    }

    // --- liveness ---

    fun testValueFates() {
        val file = file(
            """
            func f(c bool) int {
            	x := 1
            	x = 2
            	y := x
            	if c {
            		y = 3
            	}
            	z := 4
            	if c {
            		z = 5
            		_ = z
            	}
            	return x
            }
            """,
        )
        val flow = GoControlFlow.of(function(file))!!
        val live = GoLiveness.of(flow)!!
        assertEquals(setOf(OVERWRITTEN), live.fatesAfter(accessAt(file, flow, "x := 1")))
        assertEquals(setOf(READ), live.fatesAfter(accessAt(file, flow, "x = 2")))
        assertEquals(setOf(RETURNED), live.fatesAfter(accessAt(file, flow, "y = 3")))
        assertEquals("never read", setOf(OVERWRITTEN, RETURNED), live.fatesAfter(accessAt(file, flow, "y := x")))
        assertEquals(setOf(OVERWRITTEN, RETURNED), live.fatesAfter(accessAt(file, flow, "z := 4")))
        assertFalse(live.isReadAfter(accessAt(file, flow, "z := 4")))
        assertTrue(live.isLiveBefore(flow.variableOf(refAt(file, "return x", "x"))!!, flow.nodeOf(refAt(file, "return x", "x"))!!))
    }

    fun testNamedResultsPanicsAndLoops() {
        val file = file(
            """
            func a() (r int) {
            	r = 1
            	return
            }
            func b() (r int) {
            	r = 1
            	return 2
            }
            func c(n int) {
            	x := n
            	if n > 0 {
            		panic("no")
            	}
            	for {
            		x = 1
            		x = 2
            		use(x)
            	}
            }
            func d() (n int) {
            	n = 3
            	defer func() { n++ }()
            	return n
            }
            func use(int) {}
            """,
        )
        fun live(name: String) = GoControlFlow.of(function(file, name))!!.let { it to GoLiveness.of(it)!! }
        live("a").let { (flow, l) -> assertEquals(setOf(READ), l.fatesAfter(accessAt(file, flow, "r = 1"))) }
        live("b").let { (flow, l) -> assertEquals(setOf(OVERWRITTEN), l.fatesAfter(accessAt(file, flow, "r = 1", occurrence = 1))) }
        live("c").let { (flow, l) ->
            assertEquals("the panic path counts for nothing", setOf(OVERWRITTEN), l.fatesAfter(accessAt(file, flow, "x := n")))
            assertEquals(setOf(OVERWRITTEN), l.fatesAfter(accessAt(file, flow, "x = 1")))
            assertEquals(setOf(READ), l.fatesAfter(accessAt(file, flow, "x = 2")))
        }
        live("d").let { (flow, l) ->
            assertTrue("captured by the deferred literal", flow.isEscaping(flow.namedResults.single()))
            assertEquals(setOf(READ), l.fatesAfter(accessAt(file, flow, "n = 3")))
        }
    }

    // --- reaching definitions ---

    fun testReachingDefinitions() {
        val file = file(
            """
            func f(c bool, p int) int {
            	x := 1
            	if c {
            		x = 2
            	}
            	q := p
            	x, q = q, x
            	return x + q
            }
            """,
        )
        val flow = GoControlFlow.of(function(file))!!
        val reaching = GoReachingDefinitions.of(flow)!!
        val swap = flow.nodeFor(PsiTreeUtil.findChildrenOfType(file, io.github.golangsupport.lang.psi.GoAssignmentStatement::class.java).last())!!
        val x = flow.variableOf(refAt(file, "return x", "x"))!!
        assertEquals(listOf("x := 1", "x = 2"), reaching.reaching(x, swap).map { it.node.element!!.text })
        val readOfQ = flow.accessesAt(refAt(file, "q := p", "p")).single()
        assertEquals("the parameter's definition at entry", flow.entry, reaching.definitionsOf(readOfQ).single().node)
        val ret = flow.accessesAt(refAt(file, "return x", "x")).single()
        assertEquals(listOf("x, q = q, x"), reaching.definitionsOf(ret).map { it.node.element!!.text })
    }

    // --- nil-ness ---

    fun testNilFactsFromConditionsAndAssignments() {
        val file = file(
            """
            func f(p *int, q *int, m map[string]int, k int) {
            	if p == nil {
            		use1(p)
            	}
            	use2(p)
            	if nil != p && *p > 0 {
            	}
            	var r *int
            	use3(r)
            	r = new(int)
            	use4(r)
            	s := r
            	use5(s)
            	ok := q != nil && use6(q)
            	if q != nil {
            		return
            	}
            	use7(q, ok)
            	m = map[string]int{}
            	use8(m, k)
            	if p == nil {
            		p = &k
            	}
            	use9(p)
            }
            func use1(any) {}
            func use2(any) {}
            func use3(any) {}
            func use4(any) {}
            func use5(any) {}
            func use6(any) bool { return true }
            func use7(any, any) {}
            func use8(any, any) {}
            func use9(any) {}
            """,
        )
        val flow = GoControlFlow.of(function(file))!!
        val nil = GoNilness.of(flow)!!
        assertEquals(GoNil.NIL, nil.at(refAt(file, "use1(p)", "p")))
        assertEquals(GoNil.UNKNOWN, nil.at(refAt(file, "use2(p)", "p")))
        assertEquals(GoNil.NOT_NIL, nil.at(refAt(file, "*p > 0", "p")))
        assertEquals(GoNil.NIL, nil.at(refAt(file, "use3(r)", "r")))
        assertEquals(GoNil.NOT_NIL, nil.at(refAt(file, "use4(r)", "r")))
        assertEquals("copied from r", GoNil.NOT_NIL, nil.at(refAt(file, "use5(s)", "s")))
        assertEquals("conditionally evaluated", GoNil.UNKNOWN, nil.at(refAt(file, "use6(q)", "q")))
        assertEquals(GoNil.NIL, nil.at(refAt(file, "use7(q, ok)", "q")))
        assertEquals(GoNil.NOT_NIL, nil.at(refAt(file, "use8(m, k)", "m")))
        assertEquals("k is not nilable", GoNil.UNKNOWN, nil.at(refAt(file, "use8(m, k)", "k")))
        assertEquals("k escapes; p is NOT_NIL on both paths", GoNil.NOT_NIL, nil.at(refAt(file, "use9(p)", "p")))
    }

    // --- stubs only for other files ---

    fun testAnalysesDoNotLoadOtherFilesAst() {
        val decl = myFixture.addFileToProject(
            "q/decl.go",
            """
            package q

            type T struct{ X int }

            func (t *T) Close() error { return nil }

            func Open(name string) (*T, error) { return &T{}, nil }

            func Fail() { panic("x") }
            """.trimIndent(),
        ) as GoFile
        val use = myFixture.addFileToProject(
            "q/use.go",
            """
            package q

            import "os"

            func use(name string) (n int, err error) {
            	t, err := Open(name)
            	if err != nil || t == nil {
            		os.Exit(1)
            	}
            	defer t.Close()
            	for i := 0; i < t.X; i++ {
            		n += i
            	}
            	Fail()
            	return n, nil
            }
            """.trimIndent(),
        ) as GoFile
        val fn = function(use, "use")
        (decl as PsiFileImpl).let { f ->
            f.calcTreeElement()
            ApplicationManager.getApplication().runWriteAction { f.onContentReload() }
            assertNotNull("decl.go must have a stub", f.stub)
        }
        com.intellij.psi.impl.PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter({ it == decl.virtualFile }, testRootDisposable)
        AstLoadingFilter.disallowTreeLoading<Throwable> {
            val flow = GoControlFlow.of(fn)!!
            assertNotNull(GoLiveness.of(flow))
            assertNotNull(GoReachingDefinitions.of(flow))
            assertNotNull(GoNilness.of(flow))
            assertTrue(flow.nodes.any { it.kind == GoFlowNode.Kind.TERMINATE })
            assertNull("decl.go's AST was loaded", (decl as PsiFileImpl).treeElement)
        }
    }
}
