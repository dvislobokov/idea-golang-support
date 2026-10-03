package io.github.golangsupport.ide.refactoring

import com.intellij.lang.LanguageRefactoringSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.GoLanguage

/** Extract Function / Method: `<selection>` before, the text after (Go written with 4-space indents, tabs in the file). */
class GoExtractFunctionTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun extract(before: String, after: String, name: String? = null) {
        myFixture.configureByText("ef.go", go(before))
        GoExtractFunctionHandler(GoExtractFunctionOptions(name)).invoke(project, myFixture.editor, myFixture.file, null)
        myFixture.checkResult(go(after))
    }

    private fun refused(text: String, reason: String) {
        myFixture.configureByText("eu.go", go(text))
        try {
            GoExtractFunctionHandler(GoExtractFunctionOptions()).invoke(project, myFixture.editor, myFixture.file, null)
            fail("Expected the refactoring to be refused")
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            assertTrue(e.message, e.message!!.startsWith("Cannot perform refactoring"))
            assertTrue(e.message, e.message!!.contains(reason))
        }
    }

    fun testStatementsWithInputs() = extract(
        """
        package ef

        import "fmt"

        func report(name string, n int) {
            <selection>fmt.Println(name)
            fmt.Println(n * 2)</selection>
        }
        """,
        """
        package ef

        import "fmt"

        func report(name string, n int) {
            extracted(name, n)
        }

        func extracted(name string, n int) {
            fmt.Println(name)
            fmt.Println(n * 2)
        }
        """,
    )

    fun testResultVariableUsedAfter() = extract(
        """
        package ef

        func total(xs []int) int {
            sum := 0
            <selection>for _, x := range xs {
                sum += x
            }</selection>
            return sum
        }
        """,
        """
        package ef

        func total(xs []int) int {
            sum := 0
            sum = extracted(xs, sum)
            return sum
        }

        func extracted(xs []int, sum int) int {
            for _, x := range xs {
                sum += x
            }
            return sum
        }
        """,
    )

    fun testDeclaredInsideUsedAfter() = extract(
        """
        package ef

        func split(x int) int {
            <selection>a := x / 2
            b := x - a</selection>
            return a * b
        }
        """,
        """
        package ef

        func split(x int) int {
            a, b := extracted(x)
            return a * b
        }

        func extracted(x int) (int, int) {
            a := x / 2
            b := x - a
            return a, b
        }
        """,
    )

    fun testDeclaredInsideNotUsedAfterIsNoResult() = extract(
        """
        package ef

        import "fmt"

        func show(x int) {
            <selection>y := x + 1
            fmt.Println(y)</selection>
            fmt.Println(x)
        }
        """,
        """
        package ef

        import "fmt"

        func show(x int) {
            extracted(x)
            fmt.Println(x)
        }

        func extracted(x int) {
            y := x + 1
            fmt.Println(y)
        }
        """,
    )

    fun testReadAndWrittenVariable() = extract(
        """
        package ef

        func bump(n int) int {
            count := n
            <selection>if count > 10 {
                count = 10
            }</selection>
            return count
        }
        """,
        """
        package ef

        func bump(n int) int {
            count := n
            count = extracted(count)
            return count
        }

        func extracted(count int) int {
            if count > 10 {
                count = 10
            }
            return count
        }
        """,
    )

    fun testUnconditionalWriteIsNotAParameter() = extract(
        """
        package ef

        func pick(a, b int) int {
            var m int
            <selection>m = a
            if b > m {
                m = b
            }</selection>
            return m
        }
        """,
        """
        package ef

        func pick(a, b int) int {
            var m int
            m = extracted(a, b)
            return m
        }

        func extracted(a int, b int) int {
            var m int
            m = a
            if b > m {
                m = b
            }
            return m
        }
        """,
    )

    fun testWrittenButNotUsedAfterIsNoResult() = extract(
        """
        package ef

        import "fmt"

        func echo(x int) {
            y := 0
            <selection>y = x * 3
            fmt.Println(y)</selection>
        }
        """,
        """
        package ef

        import "fmt"

        func echo(x int) {
            y := 0
            extracted(x)
        }

        func extracted(x int) {
            var y int
            y = x * 3
            fmt.Println(y)
        }
        """,
    )

    fun testMethodReceiverBecomesMethod() = extract(
        """
        package ef

        type counter struct{ n int }

        func (c *counter) add(d int) int {
            <selection>c.n += d</selection>
            return c.n
        }
        """,
        """
        package ef

        type counter struct{ n int }

        func (c *counter) add(d int) int {
            c.extracted(d)
            return c.n
        }

        func (c *counter) extracted(d int) {
            c.n += d
        }
        """,
    )

    fun testMethodNameAvoidsExistingMembers() = extract(
        """
        package ef

        type box struct{ v int }

        func (b box) extracted() {}

        func (b box) twice() int {
            return <selection>b.v * 2</selection>
        }
        """,
        """
        package ef

        type box struct{ v int }

        func (b box) extracted() {}

        func (b box) twice() int {
            return b.extracted1()
        }

        func (b box) extracted1() int {
            return b.v * 2
        }
        """,
    )

    fun testExpression() = extract(
        """
        package ef

        func area(w, h float64) float64 {
            return <selection>w * h</selection> / 2
        }
        """,
        """
        package ef

        func area(w, h float64) float64 {
            return extracted(w, h) / 2
        }

        func extracted(w float64, h float64) float64 {
            return w * h
        }
        """,
    )

    fun testExpressionOfSeveralResults() = extract(
        """
        package ef

        import "strconv"

        func parse(s string) (int, error) {
            n, err := <selection>strconv.Atoi(s)</selection>
            return n, err
        }
        """,
        """
        package ef

        import "strconv"

        func parse(s string) (int, error) {
            n, err := extracted(s)
            return n, err
        }

        func extracted(s string) (int, error) {
            return strconv.Atoi(s)
        }
        """,
    )

    fun testAllPathsReturn() = extract(
        """
        package ef

        func sign(x int) string {
            <selection>if x < 0 {
                return "neg"
            }
            return "pos"</selection>
        }
        """,
        """
        package ef

        func sign(x int) string {
            return extracted(x)
        }

        func extracted(x int) string {
            if x < 0 {
                return "neg"
            }
            return "pos"
        }
        """,
    )

    fun testNameFromOptions() = extract(
        """
        package ef

        func double(x int) int {
            return <selection>x * 2</selection>
        }
        """,
        """
        package ef

        func double(x int) int {
            return twice(x)
        }

        func twice(x int) int {
            return x * 2
        }
        """,
        name = "twice",
    )

    fun testGenericFunction() = extract(
        """
        package ef

        func firstOr[T any, K comparable](xs []T, keys []K, def T) T {
            <selection>if len(xs) == 0 {
                return def
            }
            return xs[0]</selection>
        }
        """,
        """
        package ef

        func firstOr[T any, K comparable](xs []T, keys []K, def T) T {
            return extracted(xs, def)
        }

        func extracted[T any](xs []T, def T) T {
            if len(xs) == 0 {
                return def
            }
            return xs[0]
        }
        """,
    )

    fun testInsideLoopReadWrittenGoesBack() = extract(
        """
        package ef

        func run(xs []int) {
            acc := 0
            for _, x := range xs {
                <selection>acc = acc*2 + x</selection>
            }
        }
        """,
        """
        package ef

        func run(xs []int) {
            acc := 0
            for _, x := range xs {
                acc = extracted(acc, x)
            }
        }

        func extracted(acc int, x int) int {
            acc = acc*2 + x
            return acc
        }
        """,
    )

    fun testMixedResultsInSameScope() = extract(
        """
        package ef

        import "strconv"

        func conv(s string) (int, error) {
            var err error
            <selection>n, err := strconv.Atoi(s)</selection>
            return n, err
        }
        """,
        """
        package ef

        import "strconv"

        func conv(s string) (int, error) {
            var err error
            n, err := extracted(s)
            return n, err
        }

        func extracted(s string) (int, error) {
            n, err := strconv.Atoi(s)
            return n, err
        }
        """,
    )

    fun testMixedResultsInInnerBlockDeclareFirst() = extract(
        """
        package ef

        func mix(xs []int) int {
            total := 0
            if len(xs) > 0 {
                <selection>first := xs[0]
                total = first</selection>
                return total + first
            }
            return total
        }
        """,
        """
        package ef

        func mix(xs []int) int {
            total := 0
            if len(xs) > 0 {
                var first int
                first, total = extracted(xs)
                return total + first
            }
            return total
        }

        func extracted(xs []int) (int, int) {
            var total int
            first := xs[0]
            total = first
            return first, total
        }
        """,
    )

    fun testFieldWriteThroughPointerIsNoResult() = extract(
        """
        package ef

        type cell struct{ v int }

        func set(p *cell, x int) int {
            <selection>p.v = x</selection>
            return p.v
        }
        """,
        """
        package ef

        type cell struct{ v int }

        func set(p *cell, x int) int {
            extracted(p, x)
            return p.v
        }

        func extracted(p *cell, x int) {
            p.v = x
        }
        """,
    )

    fun testInsideFunctionLiteral() = extract(
        """
        package ef

        func each(xs []int, f func(int)) {}

        func walk(xs []int, k int) {
            each(xs, func(x int) {
                <selection>_ = x * k</selection>
            })
        }
        """,
        """
        package ef

        func each(xs []int, f func(int)) {}

        func walk(xs []int, k int) {
            each(xs, func(x int) {
                extracted(x, k)
            })
        }

        func extracted(x int, k int) {
            _ = x * k
        }
        """,
    )

    fun testRawStringKeepsItsLines() = extract(
        """
        package ef

        import "fmt"

        func banner() {
            <selection>fmt.Println(`a
        b`)</selection>
        }
        """,
        """
        package ef

        import "fmt"

        func banner() {
            extracted()
        }

        func extracted() {
            fmt.Println(`a
        b`)
        }
        """,
    )

    // --- refusals ---

    fun testValueReceiverFieldWriteRefused() = refused(
        """
        package ef

        type val struct{ n int }

        func (v val) inc() int {
            <selection>v.n++</selection>
            return v.n
        }
        """,
        "assigns the receiver",
    )

    fun testGotoOutRefused() = refused(
        """
        package ef

        func jump(x int) {
            <selection>if x > 0 {
                goto done
            }</selection>
            x--
        done:
            _ = x
        }
        """,
        "'goto' leaves the selection",
    )

    fun testBareReturnRefused() = refused(
        """
        package ef

        func named(x int) (n int) {
            <selection>n = x
            return</selection>
        }
        """,
        "bare 'return'",
    )

    fun testBreakOutRefused() = refused(
        """
        package ef

        func loop(xs []int) {
            for _, x := range xs {
                <selection>if x > 3 {
                    break
                }</selection>
            }
        }
        """,
        "'break' leaves the selection",
    )

    fun testBreakInsideIsFine() = extract(
        """
        package ef

        func find(xs []int) {
            <selection>for _, x := range xs {
                if x > 3 {
                    break
                }
            }</selection>
        }
        """,
        """
        package ef

        func find(xs []int) {
            extracted(xs)
        }

        func extracted(xs []int) {
            for _, x := range xs {
                if x > 3 {
                    break
                }
            }
        }
        """,
    )

    fun testDeferRefused() = refused(
        """
        package ef

        import "os"

        func open(name string) {
            <selection>f, _ := os.Open(name)
            defer f.Close()</selection>
        }
        """,
        "defer",
    )

    fun testPartialStatementRefused() = refused(
        """
        package ef

        func part(a, b int) int {
            c := a<selection> + b
            return c</selection>
        }
        """,
        "whole statements",
    )

    fun testReturnOnSomePathsRefused() = refused(
        """
        package ef

        func some(x int) int {
            <selection>if x > 0 {
                return 1
            }
            x++</selection>
            return x
        }
        """,
        "Not every path",
    )

    fun testContinueOutRefused() = refused(
        """
        package ef

        func skip(xs []int) {
            for _, x := range xs {
                <selection>if x == 0 {
                    continue
                }</selection>
                _ = x
            }
        }
        """,
        "'continue' leaves the selection",
    )

    fun testClosureAssigningOuterRefused() = refused(
        """
        package ef

        func later(xs []int) func() {
            n := 0
            <selection>f := func() { n = len(xs) }</selection>
            _ = n
            return f
        }
        """,
        "function literal",
    )

    fun testOutsideFunctionRefused() = refused(
        """
        package ef

        var <selection>top = 1</selection>
        """,
        "",
    )

    fun testGateOff() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.RENAME
        }, testRootDisposable)
        val text = go(
            """
            package ef

            func gated(x int) int {
                return <selection>x * 2</selection>
            }
            """,
        )
        myFixture.configureByText("eg.go", text)
        val provider = LanguageRefactoringSupport.getInstance().forLanguage(GoLanguage)!!
        assertNotNull(provider.extractMethodHandler)
        GoExtractFunctionHandler(GoExtractFunctionOptions()).invoke(project, myFixture.editor, myFixture.file, null)
        assertEquals(text.replace("<selection>", "").replace("</selection>", ""), myFixture.editor.document.text)
    }
}
