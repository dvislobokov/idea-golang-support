package io.github.golangsupport.semantic.flow

import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoVarDefinition

/** Shapes of [GoControlFlow] per statement kind (see [GoFlowDump] for the notation) and the cases where the builder gives up. */
class GoControlFlowTest : GoFlowTestBase() {

    fun testIfElseAndShortCircuit() = assertDump(
        """
        0 ENTRY {d:x d:err d:n d:e} -> 1
        1 STATEMENT 'a := 1' {d:a} -> 2
        2 CONDITION 'x == nil' {r:x} -> 3F,4T
        3 CONDITION '*x > 0' {r:x} -> 4T,5F
        4 STATEMENT 'a = 2' {w:a} -> 8
        5 CONDITION 'a > 1' {r:a} -> 6T,7F
        6 CONDITION 'err != nil' {r:err} -> 7F,8T
        7 RETURN 'return' {r:n r:e} -> 16
        8 STATEMENT 'i := 0' {d:i} -> 9
        9 JOIN -> 10
        10 CONDITION 'i < a' {r:i r:a} -> 11T,15F
        11 CONDITION 'i == 2' {r:i} -> 12F,14T
        12 CONDITION 'i == 3' {r:i} -> 13F,15T
        13 STATEMENT 'a += i' {r:i r:a w:a} -> 14
        14 STATEMENT 'i++' {r:i w:i} -> 9
        15 RETURN 'return a, nil' {r:a w:n w:e} -> 16
        16 DEFERRED -> 17
        17 EXIT
        """,
        """
        func f(x *int, err error) (n int, e error) {
        	a := 1
        	if x == nil || *x > 0 {
        		a = 2
        	} else if !(a > 1 && err != nil) {
        		return
        	}
        	for i := 0; i < a; i++ {
        		if i == 2 { continue }
        		if i == 3 { break }
        		a += i
        	}
        	return a, nil
        }
        """,
    )

    fun testRangeLabelsSwitchesAndDefer() = assertDump(
        """
        0 ENTRY {d:xs d:v} -> 1
        1 STATEMENT 's := 0' {d:s*} -> 2
        2 JOIN 'L:' -> 3
        3 STATEMENT 'xs' {r:xs} -> 4
        4 RANGE '_, x := range xs' {d:x} -> 5,9
        5 CONDITION 'x > 1' {r:x} -> 6F,8T
        6 CONDITION 'x < -1' {r:x} -> 7F,8T
        7 CONDITION 'x == 0' {r:x} -> 4T,9F
        8 STATEMENT 's++' {r:s* w:s*} -> 4
        9 STATEMENT 't := v.(type)' {r:v} -> 10
        10 CASE 'int' -> 11F,12T
        11 CASE 'string, nil' -> 14T,16F
        12 CASE 'case int:' {d:t} -> 13
        13 STATEMENT 's += t' {r:t r:s* w:s*} -> 16
        14 CASE 'case string, nil:' {d:t} -> 15
        15 STATEMENT '_ = t' {r:t} -> 16
        16 STATEMENT 'defer func() { s++ }()' -> 17
        17 JOIN 'end:' -> 18
        18 PANIC 'panic(s)' {r:s*} -> 19
        19 DEFERRED -> 20
        20 EXIT
        """,
        """
        func f(xs []int, v any) int {
        	s := 0
        L:
        	for _, x := range xs {
        		switch {
        		case x > 1, x < -1:
        			s++
        			fallthrough
        		case x == 0:
        			continue L
        		default:
        			break L
        		}
        	}
        	switch t := v.(type) {
        	case int:
        		s += t
        	case string, nil:
        		_ = t
        	}
        	defer func() { s++ }()
        	goto end
        end:
        	panic(s)
        }
        """,
    )

    fun testSelect() = assertDump(
        """
        0 ENTRY {d:c d:d} -> 1
        1 JOIN 'select {' -> 2,4,5
        2 COMM 'case v := <-c' {r:c d:v} -> 3
        3 RETURN 'return v' {r:v} -> 6
        4 COMM 'case d <- 1' {r:d} -> 5
        5 JOIN 'select {}'
        6 DEFERRED -> 7
        7 EXIT
        """,
        """
        func f(c chan int, d chan int) int {
        	select {
        	case v := <-c:
        		return v
        	case d <- 1:
        	default:
        		break
        	}
        	select {}
        }
        """,
    )

    fun testLoopsAndTaggedSwitchWithFallthrough() = assertDump(
        """
        0 ENTRY {d:n} -> 1
        1 JOIN -> 2
        2 CONDITION 'n > 0' {r:n} -> 3T,4F
        3 STATEMENT 'n--' {r:n w:n} -> 1
        4 JOIN -> 5
        5 CONDITION 'n == 3' {r:n} -> 4F,6T
        6 STATEMENT 'n' {r:n} -> 7
        7 CASE 'case 1, 2:' -> 8F,10T
        8 CASE 'case 3:' -> 9F,12T
        9 CASE 'case 4:' -> 11F,12T
        10 STATEMENT 'n = 0' {w:n} -> 12
        11 STATEMENT 'n = 1' {w:n} -> 12
        12 RETURN '}' -> 13
        13 DEFERRED -> 14
        14 EXIT
        """,
        """
        func f(n int) {
        	for n > 0 {
        		n--
        	}
        	for {
        		if n == 3 {
        			break
        		}
        	}
        	switch n {
        	case 1, 2:
        		n = 0
        	default:
        		n = 1
        	case 3:
        		fallthrough
        	case 4:
        	}
        }
        """,
    )

    fun testGotoBackwardAndForward() = assertDump(
        """
        0 ENTRY {d:n} -> 1
        1 JOIN 'again:' -> 2
        2 STATEMENT 'n--' {r:n w:n} -> 3
        3 CONDITION 'n > 0' {r:n} -> 1T,5F
        !4 STATEMENT 'n = 7' {w:n} -> 5
        5 JOIN 'out:' -> 6
        6 RETURN 'return n' {r:n} -> 7
        7 DEFERRED -> 8
        8 EXIT
        """,
        """
        func f(n int) int {
        again:
        	n--
        	if n > 0 {
        		goto again
        	}
        	goto out
        	n = 7
        out:
        	return n
        }
        """,
    )

    fun testCallsThatDoNotReturn() = assertDump(
        """
        0 ENTRY {d:t d:n} -> 1
        1 CONDITION 'n == 0' {r:n} -> 2T,3F
        2 TERMINATE 'os.Exit(1)' -> 12
        3 CONDITION 'n == 1' {r:n} -> 4T,5F
        4 TERMINATE 'log.Fatalf("x")' -> 12
        5 CONDITION 'n == 2' {r:n} -> 6T,7F
        6 PANIC 't.Fatal("x")' {r:t} -> 11
        7 CONDITION 'n == 3' {r:n} -> 8T,9F
        8 PANIC 'runtime.Goexit()' -> 11
        9 STATEMENT 'defer fmt.Println(n)' {r:n} -> 10
        10 RETURN '}' -> 11
        11 DEFERRED -> 12
        12 EXIT
        """,
        """
        func f(t *testing.T, n int) {
        	if n == 0 {
        		os.Exit(1)
        	}
        	if n == 1 {
        		log.Fatalf("x")
        	}
        	if n == 2 {
        		t.Fatal("x")
        	}
        	if n == 3 {
        		runtime.Goexit()
        	}
        	defer fmt.Println(n)
        }
        """,
        imports = "import (\n\t\"fmt\"\n\t\"log\"\n\t\"os\"\n\t\"runtime\"\n\t\"testing\"\n)",
    )

    fun testShadowedPanicIsAnOrdinaryCall() = assertDump(
        """
        0 ENTRY {d:n} -> 1
        1 STATEMENT 'panic(n)' {r:n} -> 2
        2 RETURN '}' -> 3
        3 DEFERRED -> 4
        4 EXIT
        """,
        """
        func f(n int) {
        	panic(n)
        }
        func panic(int) {}
        """,
    )

    fun testRedeclarationsFoldIntoTheFirstDeclaration() {
        val file = file(
            """
            func f() (err error) {
            	a, err := g()
            	b, err := g()
            	if true {
            		c, err := g()
            		_, _ = c, err
            	}
            	p := &a
            	_ = p
            	return b
            }
            func g() (int, error) { return 0, nil }
            """,
        )
        val flow = GoControlFlow.of(function(file))!!
        assertEquals(
            """
            0 ENTRY {d:err} -> 1
            1 STATEMENT 'a, err := g()' {d:a* d:err} -> 2
            2 STATEMENT 'b, err := g()' {d:b d:err} -> 3
            3 CONDITION 'true' -> 4T,6F
            4 STATEMENT 'c, err := g()' {d:c d:err} -> 5
            5 STATEMENT '_, _ = c, err' {r:c r:err} -> 6
            6 STATEMENT 'p := &a' {r:a* d:p} -> 7
            7 STATEMENT '_ = p' {r:p} -> 8
            8 RETURN 'return b' {r:b w:err} -> 9
            9 DEFERRED -> 10
            10 EXIT
            """.trimIndent() + "\n",
            GoFlowDump.dump(flow),
        )
        val defs = PsiTreeUtil.findChildrenOfType(file, GoVarDefinition::class.java)
        val errs = defs.filter { it.name == "err" }
        val result = flow.namedResults.single()
        assertSame(result, flow.accessesAt(errs[0]).single().variable)
        assertSame(result, flow.accessesAt(errs[1]).single().variable)
        assertSame("the nested := declares a new variable", errs[2], flow.accessesAt(errs[2]).single().variable)
        assertTrue(flow.isEscaping(flow.accessesAt(defs.first { it.name == "a" }).single().variable))
        val b = flow.accessesAt(defs.first { it.name == "b" }).single()
        assertEquals("g()", b.value?.text)
        assertEquals(0, b.resultIndex)
    }

    fun testFunctionLiteralHasItsOwnGraph() {
        val code = """
            func f(n int) func() int {
            	m := 1
            	return func() int {
            		k := n
            		return k + m
            	}
            }
            """
        assertDump(
            """
            0 ENTRY {d:n*} -> 1
            1 STATEMENT 'm := 1' {d:m*} -> 2
            2 RETURN 'return func() int {' -> 3
            3 DEFERRED -> 4
            4 EXIT
            """,
            code,
        )
        assertEquals(
            """
            0 ENTRY -> 1
            1 STATEMENT 'k := n' {d:k} -> 2
            2 RETURN 'return k + m' {r:k} -> 3
            3 DEFERRED -> 4
            4 EXIT
            """.trimIndent() + "\n",
            GoFlowDump.dump(literalFlow(file(code))!!),
        )
    }

    fun testAddressAndMethodCallsOnValuesEscape() = assertDump(
        """
        0 ENTRY -> 1
        1 STATEMENT 'var b strings.Builder' {d:b*} -> 2
        2 STATEMENT 'b.WriteString("x")' {r:b*} -> 3
        3 STATEMENT 'var a [2]int' {d:a*} -> 4
        4 STATEMENT 's := a[:]' {r:a* d:s} -> 5
        5 STATEMENT 'p := &T{}' {d:p} -> 6
        6 STATEMENT 'p.M()' {r:p} -> 7
        7 STATEMENT 'var t T' {d:t*} -> 8
        8 STATEMENT 't.M()' {r:t*} -> 9
        9 STATEMENT 'u := T{}' {d:u} -> 10
        10 STATEMENT '_ = u.X' {r:u} -> 11
        11 STATEMENT '_ = s' {r:s} -> 12
        12 RETURN '}' -> 13
        13 DEFERRED -> 14
        14 EXIT
        """,
        """
        type T struct{ X int }
        func (t *T) M() {}
        func f() {
        	var b strings.Builder
        	b.WriteString("x")
        	var a [2]int
        	s := a[:]
        	p := &T{}
        	p.M()
        	var t T
        	t.M()
        	u := T{}
        	_ = u.X
        	_ = s
        }
        """,
        imports = "import \"strings\"",
    )

    fun testUnresolvedNameMakesTheVariableEscaping() = assertDump(
        """
        0 ENTRY -> 1
        1 STATEMENT '_ = x' -> 2
        2 STATEMENT 'x := 1' {d:x*} -> 3
        3 STATEMENT 'x = 2' {w:x*} -> 4
        4 STATEMENT '_ = x' {r:x*} -> 5
        5 RETURN '}' -> 6
        6 DEFERRED -> 7
        7 EXIT
        """,
        """
        func f() {
        	_ = x
        	x := 1
        	x = 2
        	_ = x
        }
        """,
    )

    fun testGivesUpOnGotoIntoBlock() = assertNull(
        flow(
            """
            func f(n int) {
            	goto in
            	{
            	in:
            		n++
            	}
            }
            """,
        ),
    )

    fun testGivesUpOnSyntaxErrors() = assertNull(flow("func f(n int) {\n\tn = = 2\n}"))

    fun testGivesUpOnJumpsWithoutTarget() {
        assertNull(flow("func f(n int) {\n\tbreak\n}"))
        assertNull(flow("func f(n int) {\n\tswitch n {\n\tcase 1:\n\t\tcontinue\n\t}\n}"))
        assertNull(flow("func f(n int) {\n\tgoto nowhere\n}"))
    }

    fun testNodeLookupAndConditionalEvaluation() {
        val file = file(
            """
            func f(p *int) bool {
            	ok := p != nil && *p > 0
            	if p != nil && *p > 1 {
            		return true
            	}
            	return ok
            }
            """,
        )
        val flow = GoControlFlow.of(function(file))!!
        val refs = PsiTreeUtil.findChildrenOfType(file, GoReferenceExpression::class.java).filter { it.text == "p" }
        assertFalse(flow.isConditionallyEvaluated(refs[0]))
        assertTrue("right operand of && inside an assignment", flow.isConditionallyEvaluated(refs[1]))
        assertFalse("split into its own condition node", flow.isConditionallyEvaluated(refs[3]))
        assertEquals(GoFlowNode.Kind.CONDITION, flow.nodeOf(refs[3])!!.kind)
        assertEquals("*p > 1", flow.nodeOf(refs[3])!!.element!!.text)
        assertEquals(GoFlowNode.Kind.STATEMENT, flow.nodeOf(refs[1])!!.kind)
    }

    fun testGraphAndAnalysesAreCached() {
        val fn = function(file("func f(n int) int {\n\treturn n\n}"))
        val first = GoControlFlow.of(fn)!!
        assertSame(first, GoControlFlow.of(fn))
        assertSame(GoLiveness.of(first), GoLiveness.of(first))
        assertSame(GoNilness.of(first), GoNilness.of(first))
        assertSame(GoReachingDefinitions.of(first), GoReachingDefinitions.of(first))
    }
}
