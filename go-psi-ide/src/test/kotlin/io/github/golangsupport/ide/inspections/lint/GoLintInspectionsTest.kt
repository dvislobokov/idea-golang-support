package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleGraphProvider

/** The lint checks without data flow: self-assignment, unused result, defer in a loop, copied locks, loop variable capture, testing from goroutines. */
class GoLintInspectionsTest : GoSemanticIdeTestBase() {

    private fun doHighlight(text: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        myFixture.checkHighlighting(true, false, true)
    }

    private fun doFix(before: String, fix: String, after: String, vararg tools: LocalInspectionTool) {
        myFixture.enableInspections(*tools)
        myFixture.configureByText("a.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == fix } ?: error("$fix not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    private fun useGoVersion(version: String?) {
        val module = GoModule("example.com/m", null, null, null, version, isMain = true)
        val graph = GoModuleGraph(listOf(module), listOf(module), null, false, null, emptyList(), GoModuleGraph.Source.PURE)
        project.replaceService(GoModuleGraphProvider::class.java, object : GoModuleGraphProvider {
            override fun graphFor(fileOrDirectory: VirtualFile): GoModuleGraph = graph
        }, testRootDisposable)
    }

    // --- self-assignment ---

    fun testSelfAssignment() = doHighlight(
        """
        package p

        type S struct{ f int }

        func f(x int, s S, a []int, i int, a2, b2 int) {
        	<warning descr="self-assignment of x to x">x = x</warning>
        	<warning descr="self-assignment of s.f to s.f">s.f = s.f</warning>
        	<warning descr="self-assignment of a[i] to a[i]">a[i] = a[i]</warning>
        	<warning descr="self-assignment of a2 to a2">a2</warning>, <warning descr="self-assignment of b2 to b2">b2</warning> = a2, b2
        }
        """,
        GoSelfAssignmentInspection(),
    )

    fun testSelfAssignmentNegatives() = doHighlight(
        """
        package p

        func next() int { return 0 }

        func f(x, y int, a []int, i int) {
        	x = y
        	x += x
        	x, y = y, x
        	a[next()] = a[next()]
        	a[i] = a[i+1]
        	a[i], a[i+1] = a[i+1], a[i]
        	x := x
        	_ = x
        }
        """,
        GoSelfAssignmentInspection(),
    )

    fun testSelfAssignmentFix() = doFix(
        """
        package p

        func f(x int) {
        	println(1)
        	<caret>x = x
        	println(2)
        }
        """,
        "Remove self-assignment",
        """
        package p

        func f(x int) {
        	println(1)
        	println(2)
        }
        """,
        GoSelfAssignmentInspection(),
    )

    // --- unused result ---

    fun testUnusedResult() = doHighlight(
        """
        package p

        import (
        	"context"
        	"errors"
        	"fmt"
        	"strings"
        	"time"
        )

        func f(s string, xs []int, t time.Time, ctx context.Context) {
        	<warning descr="result of strings.ReplaceAll is not used">strings.ReplaceAll(s, "a", "b")</warning>
        	<warning descr="result of fmt.Sprintf is not used">fmt.Sprintf("%d", 1)</warning>
        	<warning descr="result of errors.New is not used">errors.New("x")</warning>
        	<warning descr="result of context.WithCancel is not used; the returned context and cancel are lost">context.WithCancel(ctx)</warning>
        	<warning descr="result of time.Time.Add is not used">t.Add(time.Second)</warning>
        	<warning descr="result of time.Since is not used">time.Since(t)</warning>
        }
        """,
        GoUnusedResultInspection(),
    )

    fun testUnusedResultNegatives() = doHighlight(
        """
        package p

        import (
        	"fmt"
        	"strings"
        )

        type T struct{}

        func (T) Replace(a string) string { return a }

        func Replace(a string) string { return a }

        func f(s string, xs []int, t T) (string, []int) {
        	s = strings.ToUpper(s)
        	xs = append(xs, 1)
        	_ = strings.ToLower(s)
        	t.Replace("a")
        	Replace("a")
        	fmt.Println(s)
        	defer strings.ToUpper(s)
        	var b strings.Builder
        	b.WriteString("a")
        	return strings.TrimSpace(s), append(xs, 2)
        }
        """,
        GoUnusedResultInspection(),
    )

    fun testUnusedResultFixAppend() = doFix(
        """
        package p

        func f(xs []int) {
        	<caret>append(xs, 1, 2)
        }
        """,
        "Assign the result to xs",
        """
        package p

        func f(xs []int) {
        	xs = append(xs, 1, 2)
        }
        """,
        io.github.golangsupport.ide.inspections.GoCheckerInspection(),
    )

    fun testUnusedResultFixStrings() = doFix(
        """
        package p

        import "strings"

        func f(s string) {
        	<caret>strings.ReplaceAll(s, "a", "b")
        }
        """,
        "Assign the result to s",
        """
        package p

        import "strings"

        func f(s string) {
        	s = strings.ReplaceAll(s, "a", "b")
        }
        """,
        GoUnusedResultInspection(),
    )

    // --- defer in a loop ---

    fun testDeferInLoop() = doHighlight(
        """
        package p

        func f(names []string, open func(string) func()) {
        	for _, n := range names {
        		<weak_warning descr="defer in a loop runs only when the function returns">defer</weak_warning> open(n)()
        	}
        	for i := 0; i < 3; i++ {
        		if i > 1 {
        			<weak_warning descr="defer in a loop runs only when the function returns">defer</weak_warning> println(i)
        		}
        	}
        }
        """,
        GoDeferInLoopInspection(),
    )

    fun testDeferInLoopNegatives() = doHighlight(
        """
        package p

        func f(names []string) {
        	defer println("done")
        	for range names {
        		func() {
        			defer println("each")
        		}()
        		go func() {
        			defer println("g")
        		}()
        	}
        }
        """,
        GoDeferInLoopInspection(),
    )

    // --- copylocks ---

    fun testCopyLocks() = doHighlight(
        """
        package p

        import "sync"

        type C struct {
        	mu sync.Mutex
        	n  int
        }

        type D struct{ c [2]C }

        func use(c <warning descr="use passes lock by value: C contains sync.Mutex">C</warning>) {}

        func (c <warning descr="Inc passes lock by value: C contains sync.Mutex">C</warning>) Inc() {}

        func byValue(wg <warning descr="byValue passes lock by value: sync.WaitGroup contains sync.noCopy">sync.WaitGroup</warning>) {}

        func g(c *C, ds []D) C {
        	a := <warning descr="assignment copies lock value to a: C contains sync.Mutex">*c</warning>
        	var b = <warning descr="variable declaration copies lock value to b: C contains sync.Mutex">a</warning>
        	use(<warning descr="call of use copies lock value: C contains sync.Mutex">b</warning>)
        	for _, <warning descr="range var d copies lock: D contains C contains sync.Mutex">d</warning> := range ds {
        		_ = d
        	}
        	return <warning descr="return copies lock value: C contains sync.Mutex">a</warning>
        }
        """,
        GoCopyLocksInspection(),
    )

    fun testCopyLocksNegatives() = doHighlight(
        """
        package p

        import "sync"

        type C struct {
        	mu sync.Mutex
        	p  *sync.Mutex
        	s  []sync.Mutex
        	m  map[string]sync.Mutex
        }

        func (c *C) Inc() {}

        // internal/gate: Lock() but Unlock(set bool) is no sync.Locker, copying it is fine
        type Gate struct{ set chan struct{} }

        func (g *Gate) Lock()            {}
        func (g *Gate) Unlock(set bool) {}

        func newGate() Gate {
        	g := Gate{}
        	return g
        }

        func make1() C { return C{} }

        func f(c *C, cs []*C) {
        	var mu sync.Mutex
        	mu.Lock()
        	x := C{}
        	y := &mu
        	z := make1()
        	w := new(sync.WaitGroup)
        	p := &x
        	_, _, _, _ = y, z, w, p
        	for _, q := range cs {
        		q.Inc()
        	}
        	ptr(&mu)
        	n := len(c.s)
        	_ = n
        }

        func ptr(m *sync.Mutex) {}

        """,
        GoCopyLocksInspection(),
    )

    fun testCopyLocksFixReceiver() = doFix(
        """
        package p

        import "sync"

        type C struct{ mu sync.Mutex }

        func (c <caret>C) Inc() {}
        """,
        "Use a pointer receiver",
        """
        package p

        import "sync"

        type C struct{ mu sync.Mutex }

        func (c *C) Inc() {}
        """,
        GoCopyLocksInspection(),
    )

    // --- loop variable capture ---

    fun testLoopClosureBeforeGo122() {
        useGoVersion("1.21")
        doHighlight(
            """
            package p

            func f(xs []int) {
            	for _, v := range xs {
            		go func() {
            			println(<warning descr="loop variable v captured by func literal">v</warning>)
            		}()
            	}
            	for i := 0; i < 3; i++ {
            		defer func() {
            			println(<warning descr="loop variable i captured by func literal">i</warning>)
            		}()
            	}
            }
            """,
            GoLoopClosureInspection(),
        )
    }

    fun testLoopClosureTestRunParallel() {
        useGoVersion("1.20")
        doHighlight(
            """
            package p

            import "testing"

            func TestX(t *testing.T) {
            	for _, tc := range []string{"a"} {
            		t.Run(tc, func(t *testing.T) {
            			t.Parallel()
            			println(<warning descr="loop variable tc captured by func literal">tc</warning>)
            		})
            	}
            	for _, tc := range []string{"a"} {
            		t.Run(tc, func(t *testing.T) {
            			println(tc)
            		})
            	}
            }
            """,
            GoLoopClosureInspection(),
        )
    }

    fun testLoopClosureNegatives() {
        useGoVersion("1.21")
        doHighlight(
            """
            package p

            func f(xs []int) {
            	for _, v := range xs {
            		v := v
            		go func() { println(v) }()
            	}
            	for _, v := range xs {
            		go func() { println(v) }()
            		println("after")
            	}
            	for _, v := range xs {
            		go func(v int) { println(v) }(v)
            	}
            	for _, v := range xs {
            		func() { println(v) }()
            	}
            }
            """,
            GoLoopClosureInspection(),
        )
    }

    fun testLoopClosureGo122AndUnknownModule() {
        useGoVersion("1.22")
        doHighlight(
            """
            package p

            func f(xs []int) {
            	for _, v := range xs {
            		go func() { println(v) }()
            	}
            }
            """,
            GoLoopClosureInspection(),
        )
    }

    fun testLoopClosureNoModuleDirective() {
        useGoVersion(null)
        doHighlight(
            """
            package p

            func f(xs []int) {
            	for _, v := range xs {
            		go func() { println(v) }()
            	}
            }
            """,
            GoLoopClosureInspection(),
        )
    }

    fun testLoopClosureFix() {
        useGoVersion("1.21")
        doFix(
            """
            package p

            func f(xs []int) {
            	for _, v := range xs {
            		go func() {
            			println(<caret>v)
            		}()
            	}
            }
            """,
            "Insert 'v := v'",
            """
            package p

            func f(xs []int) {
            	for _, v := range xs {
            		v := v
            		go func() {
            			println(v)
            		}()
            	}
            }
            """,
            GoLoopClosureInspection(),
        )
    }

    // --- testing from a goroutine ---

    fun testTestingGoroutine() = doHighlight(
        """
        package p

        import "testing"

        func TestX(t *testing.T) {
        	go func() {
        		<warning descr="call to (*T).Fatal from a non-test goroutine">t.Fatal("x")</warning>
        		<warning descr="call to (*T).FailNow from a non-test goroutine">t.FailNow()</warning>
        	}()
        }

        func BenchmarkX(b *testing.B) {
        	go func() {
        		<warning descr="call to (*B).Skipf from a non-test goroutine">b.Skipf("x")</warning>
        	}()
        }

        func helper(tb testing.TB) {
        	go func() {
        		<warning descr="call to (testing.TB).Fatalf from a non-test goroutine">tb.Fatalf("x")</warning>
        	}()
        }
        """,
        GoTestingGoroutineInspection(),
    )

    fun testTestingGoroutineNegatives() = doHighlight(
        """
        package p

        import "testing"

        type fake struct{}

        func (fake) Fatal(args ...any) {}

        func TestX(t *testing.T) {
        	t.Fatal("main goroutine")
        	go func() {
        		t.Error("fine")
        		var f fake
        		f.Fatal("not testing")
        	}()
        	func() {
        		t.Fatal("same goroutine")
        	}()
        }
        """,
        GoTestingGoroutineInspection(),
    )
}
