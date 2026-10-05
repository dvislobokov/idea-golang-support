package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.codeInspection.LocalInspectionTool

/** reflect.TypeFor, errors.AsType, net.JoinHostPort: detection, the exact shapes left alone, the fix, the version gate. */
class GoFixReflectTypeForTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixReflectTypeForInspection()

    private val w = GoFixReflectTypeForInspection.MESSAGE

    fun testNilPointerElem() = fix(
        """
        package a

        import "reflect"

        var errType = <SYNTAX_UPDATE descr="$w">reflect.TypeOf((*error)(nil)).Elem()</SYNTAX_UPDATE>
        """,
        "Replace TypeOf by TypeFor",
        """
        package a

        import "reflect"

        var errType = reflect.TypeFor[error]()
        """,
    )

    fun testEmptyLiteral() = fix(
        """
        package a

        import "reflect"

        type Point struct{ X int }

        var t = <SYNTAX_UPDATE descr="$w">reflect.TypeOf(Point{})</SYNTAX_UPDATE>
        """,
        "Replace TypeOf by TypeFor",
        """
        package a

        import "reflect"

        type Point struct{ X int }

        var t = reflect.TypeFor[Point]()
        """,
    )

    fun testOtherShapesStayQuiet() = highlight(
        """
        package a

        import "reflect"

        type Point struct{ X int }

        var p Point
        var a = reflect.TypeOf(p)
        var b = reflect.TypeOf(Point{X: 1})
        var c = reflect.TypeOf((*error)(nil))
        var d = reflect.ValueOf((*error)(nil)).Elem()
        """
    )

    fun testBelowGo122() {
        goVersion("1.21")
        highlight("package a\n\nimport \"reflect\"\n\nvar errType = reflect.TypeOf((*error)(nil)).Elem()")
    }
}

class GoFixErrorsAsTypeTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixErrorsAsTypeInspection()

    fun testVarThenIf() = fix(
        """
        package a

        import (
        	"errors"
        	"io/fs"
        )

        func f(err error) string {
        	var pathErr *fs.PathError
        	if <SYNTAX_UPDATE descr="errors.As can be simplified using AsType[*fs.PathError]">errors.As</SYNTAX_UPDATE>(err, &pathErr) {
        		return pathErr.Path
        	}
        	return ""
        }
        """,
        "Replace errors.As with AsType[*fs.PathError]",
        """
        package a

        import (
        	"errors"
        	"io/fs"
        )

        func f(err error) string {
        	if pathErr, ok := errors.AsType[*fs.PathError](err); ok {
        		return pathErr.Path
        	}
        	return ""
        }
        """,
    )

    fun testTargetUsedAfterTheIfOrConditionNotBare() = highlight(
        """
        package a

        import (
        	"errors"
        	"io/fs"
        )

        func f(err error) string {
        	var pathErr *fs.PathError
        	if errors.As(err, &pathErr) {
        		return pathErr.Path
        	}
        	return pathErr.Op
        }

        func g(err error, ok bool) bool {
        	var pathErr *fs.PathError
        	if !errors.As(err, &pathErr) {
        		return false
        	}
        	var other *fs.PathError
        	if errors.As(err, &other) && ok {
        		return true
        	}
        	var third *fs.PathError
        	if errors.As(err, &third) {
        		return ok
        	}
        	return false
        }
        """
    )

    fun testBelowGo126() {
        goVersion("1.25")
        highlight(
            """
            package a

            import (
            	"errors"
            	"io/fs"
            )

            func f(err error) string {
            	var pathErr *fs.PathError
            	if errors.As(err, &pathErr) {
            		return pathErr.Path
            	}
            	return ""
            }
            """
        )
    }
}

class GoFixHostPortTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixHostPortInspection()

    fun testSprintfIntoDial() = fix(
        """
        package a

        import (
        	"fmt"
        	"net"
        )

        func dial(host string, port int) (net.Conn, error) {
        	return net.Dial("tcp", <SYNTAX_UPDATE descr="address format \"%s:%d\" does not work with IPv6">fmt.Sprintf("%s:%d", host, port)</SYNTAX_UPDATE>)
        }
        """,
        "Replace with net.JoinHostPort",
        """
        package a

        import (
        	"fmt"
        	"net"
        	"strconv"
        )

        func dial(host string, port int) (net.Conn, error) {
        	return net.Dial("tcp", net.JoinHostPort(host, strconv.Itoa(port)))
        }
        """,
    )

    fun testConcatenationThroughLocal() = fix(
        """
        package a

        import "net"

        func listen(host, port string) (net.Listener, error) {
        	addr := <SYNTAX_UPDATE descr="address format \"%s:%s\" does not work with IPv6">host + ":" + port</SYNTAX_UPDATE>
        	return net.Listen("tcp", addr)
        }
        """,
        "Replace with net.JoinHostPort",
        """
        package a

        import "net"

        func listen(host, port string) (net.Listener, error) {
        	addr := net.JoinHostPort(host, port)
        	return net.Listen("tcp", addr)
        }
        """,
    )

    fun testNotAnAddressStaysQuiet() = highlight(
        """
        package a

        import (
        	"fmt"
        	"net"
        )

        func where(file string, line int) string { return fmt.Sprintf("%s:%d", file, line) }

        func dial(host string, port int) (net.Conn, error) {
        	addr := fmt.Sprintf("%s:%d", host, port)
        	fmt.Println(addr)
        	return net.Dial("tcp", addr)
        }

        func scheme(host, port string) string { return "http://" + host + ":" + port }
        """
    )
}

class GoFixWaitGroupTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixWaitGroupInspection()

    private val w = "Goroutine creation can be simplified using WaitGroup.Go"

    fun testAddThenDeferredDone() = fix(
        """
        package a

        import "sync"

        func work(int) {}

        func f(items []int) {
        	var wg sync.WaitGroup
        	for _, it := range items {
        		wg.Add(1)
        		<SYNTAX_UPDATE descr="$w">go</SYNTAX_UPDATE> func() {
        			defer wg.Done()
        			work(it)
        		}()
        	}
        	wg.Wait()
        }
        """,
        "Simplify by using WaitGroup.Go",
        """
        package a

        import "sync"

        func work(int) {}

        func f(items []int) {
        	var wg sync.WaitGroup
        	for _, it := range items {
        		wg.Go(func() {
        			work(it)
        		})
        	}
        	wg.Wait()
        }
        """,
    )

    fun testDoneLastOnAField() = fix(
        """
        package a

        import "sync"

        type S struct{ wg sync.WaitGroup }

        func (s *S) start(work func()) {
        	s.wg.Add(1)
        	<SYNTAX_UPDATE descr="$w">go</SYNTAX_UPDATE> func() {
        		work()
        		s.wg.Done()
        	}()
        }
        """,
        "Simplify by using WaitGroup.Go",
        """
        package a

        import "sync"

        type S struct{ wg sync.WaitGroup }

        func (s *S) start(work func()) {
        	s.wg.Go(func() {
        		work()
        	})
        }
        """,
    )

    fun testOtherShapesStayQuiet() = highlight(
        """
        package a

        import "sync"

        type Group struct{}

        func (Group) Add(int) {}
        func (Group) Done()   {}

        func f(work func(int)) {
        	var wg sync.WaitGroup
        	wg.Add(2)
        	go func() {
        		defer wg.Done()
        		work(1)
        	}()
        	wg.Add(1)
        	go func(n int) {
        		defer wg.Done()
        		work(n)
        	}(3)
        	wg.Add(1)
        	go func() {
        		work(2)
        	}()
        	var g Group
        	g.Add(1)
        	go func() {
        		defer g.Done()
        	}()
        	wg.Wait()
        }
        """
    )

    fun testBelowGo125() {
        goVersion("1.24")
        highlight("package a\n\nimport \"sync\"\n\nfunc f(wg *sync.WaitGroup) {\n\twg.Add(1)\n\tgo func() {\n\t\tdefer wg.Done()\n\t}()\n}")
    }
}

class GoFixTestingContextTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixTestingContextInspection()

    fun testWithCancelAndDeferredCancel() = fix(
        """
        package a

        import (
        	"context"
        	"testing"
        )

        func use(context.Context) {}

        func TestX(t *testing.T) {
        	ctx, cancel := <SYNTAX_UPDATE descr="context.WithCancel can be modernized using t.Context">context.WithCancel(context.Background())</SYNTAX_UPDATE>
        	defer cancel()
        	use(ctx)
        }
        """,
        "Replace context.WithCancel with t.Context",
        """
        package a

        import (
        	"context"
        	"testing"
        )

        func use(context.Context) {}

        func TestX(t *testing.T) {
        	ctx := t.Context()
        	use(ctx)
        }
        """,
        "a_test.go",
    )

    fun testBackgroundInBenchmarkBody() = fix(
        """
        package a

        import (
        	"context"
        	"testing"
        )

        func use(context.Context) {}

        func BenchmarkX(b *testing.B) {
        	use(<SYNTAX_UPDATE descr="context.Background() can be replaced by b.Context() in a test">context.Background()</SYNTAX_UPDATE>)
        }
        """,
        "Replace with b.Context()",
        """
        package a

        import (
        	"context"
        	"testing"
        )

        func use(context.Context) {}

        func BenchmarkX(b *testing.B) {
        	use(b.Context())
        }
        """,
        "a_test.go",
    )

    fun testOtherPlacesStayQuiet() = highlight(
        """
        package a

        import (
        	"context"
        	"testing"
        )

        func use(context.Context) {}

        func helper(t *testing.T) { use(context.Background()) }

        func TestLiteral(t *testing.T) {
        	t.Run("x", func(t *testing.T) { use(context.TODO()) })
        	go use(context.Background())
        }

        func TestCleanup(t *testing.T) {
        	ctx := context.Background()
        	t.Cleanup(func() { use(ctx) })
        }

        func TestCancelUsedTwice(t *testing.T) {
        	ctx, cancel := context.WithCancel(t.Context())
        	defer cancel()
        	use(ctx)
        }
        """,
        "a_test.go",
    )

    fun testNotATestFile() = highlight("package a\n\nimport (\n\t\"context\"\n\t\"testing\"\n)\n\nfunc TestX(t *testing.T) { _ = context.Background() }")
}
