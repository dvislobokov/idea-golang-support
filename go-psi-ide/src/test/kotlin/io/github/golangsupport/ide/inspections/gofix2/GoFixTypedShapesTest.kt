package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.codeInspection.LocalInspectionTool
import java.io.File

/** strings.SplitSeq, strings.Builder, atomic.Int64, unsafe.Add / Slice; and the whole set over the GoLand probe file. */
class GoFixStringsSeqTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixStringsSeqInspection()

    fun testRangeOverSplit() = fix(
        """
        package a

        import "strings"

        func f(s string) {
        	for _, part := range strings.<SYNTAX_UPDATE descr="Ranging over SplitSeq is more efficient">Split</SYNTAX_UPDATE>(s, ",") {
        		println(part)
        	}
        }
        """,
        "Replace Split with SplitSeq",
        """
        package a

        import "strings"

        func f(s string) {
        	for part := range strings.SplitSeq(s, ",") {
        		println(part)
        	}
        }
        """,
    )

    fun testSliceUsedOnlyByTheLoop() = fix(
        """
        package a

        import "strings"

        func f(s string) int {
        	n := 0
        	words := strings.<SYNTAX_UPDATE descr="Ranging over FieldsSeq is more efficient">Fields</SYNTAX_UPDATE>(s)
        	for range words {
        		n++
        	}
        	return n
        }
        """,
        "Replace Fields with FieldsSeq",
        """
        package a

        import "strings"

        func f(s string) int {
        	n := 0
        	for range strings.FieldsSeq(s) {
        		n++
        	}
        	return n
        }
        """,
    )

    fun testIndexOrOtherUseStaysQuiet() = highlight(
        """
        package a

        import "strings"

        func f(s string) int {
        	for i, part := range strings.Split(s, ",") {
        		println(i, part)
        	}
        	parts := strings.Split(s, ",")
        	for _, p := range parts {
        		println(p)
        	}
        	return len(parts)
        }
        """
    )

    fun testBelowGo124() {
        goVersion("1.23")
        highlight("package a\n\nimport \"strings\"\n\nfunc f(s string) {\n\tfor _, p := range strings.Split(s, \",\") {\n\t\tprintln(p)\n\t}\n}")
    }
}

class GoFixStringsBuilderTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixStringsBuilderInspection()

    fun testConcatenationInALoop() = fix(
        """
        package a

        func join(words []string) string {
        	out := ""
        	for _, w := range words {
        		out <SYNTAX_UPDATE descr="using string += string in a loop is inefficient">+=</SYNTAX_UPDATE> w
        		out += ","
        	}
        	return out
        }
        """,
        "Replace string += string with strings.Builder",
        """
        package a

        import "strings"

        func join(words []string) string {
        	var out strings.Builder
        	for _, w := range words {
        		out.WriteString(w)
        		out.WriteString(",")
        	}
        	return out.String()
        }
        """,
    )

    fun testOtherUsesStayQuiet() = highlight(
        """
        package a

        func f(words []string) (string, string, string) {
        	a := ""
        	for _, w := range words {
        		a += w
        		println(a)
        	}
        	b := "x"
        	for _, w := range words {
        		b += w
        	}
        	var c string
        	c += "once"
        	return a, b, c
        }
        """
    )

    /** Regression: appends only inside a function literal (the first `+=` the visitor meets may not be the first reference) must not throw. */
    fun testAppendsInAFunctionLiteralStayQuiet() = highlight(
        """
        package a

        func f(words []string) string {
        	s := ""
        	add := func(w string) {
        		for i := 0; i < 2; i++ {
        			s += w
        		}
        	}
        	for _, w := range words {
        		add(w)
        	}
        	return s
        }
        """
    )
}

class GoFixAtomicTypesTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixAtomicTypesInspection()

    fun testLocalCounter() = fix(
        """
        package a

        import "sync/atomic"

        func count(n int) int64 {
        	var <SYNTAX_UPDATE descr="Variable 'hits' is accessed only through sync/atomic functions; it can be declared as atomic.Int64">hits</SYNTAX_UPDATE> int64
        	for range n {
        		atomic.AddInt64(&hits, 1)
        	}
        	return atomic.LoadInt64(&hits)
        }
        """,
        "Use atomic.Int64",
        """
        package a

        import "sync/atomic"

        func count(n int) int64 {
        	var hits atomic.Int64
        	for range n {
        		hits.Add(1)
        	}
        	return hits.Load()
        }
        """,
    )

    fun testPackageLevelCompareAndSwap() = fix(
        """
        package a

        import "sync/atomic"

        var <SYNTAX_UPDATE descr="Variable 'state' is accessed only through sync/atomic functions; it can be declared as atomic.Uint32">state</SYNTAX_UPDATE> uint32

        func start() bool { return atomic.CompareAndSwapUint32(&state, 0, 1) }
        """,
        "Use atomic.Uint32",
        """
        package a

        import "sync/atomic"

        var state atomic.Uint32

        func start() bool { return state.CompareAndSwap(0, 1) }
        """,
    )

    fun testPlainAccessOrExportedStaysQuiet() = highlight(
        """
        package a

        import "sync/atomic"

        var Exported int64
        var mixed int64
        var initialised int64 = 5

        func f() int64 {
        	atomic.AddInt64(&Exported, 1)
        	atomic.AddInt64(&mixed, 1)
        	atomic.AddInt64(&initialised, 1)
        	return mixed
        }
        """
    )
}

class GoFixUnsafeFuncsTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixUnsafeFuncsInspection()

    fun testPointerArithmetic() = fix(
        """
        package a

        import "unsafe"

        func next(p unsafe.Pointer, size uintptr) unsafe.Pointer {
        	return <SYNTAX_UPDATE descr="pointer + integer can be simplified using unsafe.Add">unsafe.Pointer(uintptr(p) + size)</SYNTAX_UPDATE>
        }
        """,
        "Simplify using unsafe.Add",
        """
        package a

        import "unsafe"

        func next(p unsafe.Pointer, size uintptr) unsafe.Pointer {
        	return unsafe.Add(p, size)
        }
        """,
    )

    fun testLargeArraySlice() = fix(
        """
        package a

        import "unsafe"

        func view(p *byte, n int) []byte {
        	return <SYNTAX_UPDATE descr="slice conversion can be simplified using unsafe.Slice">(*[1 << 30]byte)(unsafe.Pointer(p))[:n:n]</SYNTAX_UPDATE>
        }
        """,
        "Simplify using unsafe.Slice",
        """
        package a

        import "unsafe"

        func view(p *byte, n int) []byte {
        	return unsafe.Slice(p, n)
        }
        """,
    )

    fun testOtherShapesStayQuiet() = highlight(
        """
        package a

        import "unsafe"

        func f(p *byte, q unsafe.Pointer, n int) ([]byte, unsafe.Pointer, uintptr) {
        	s := (*[1 << 30]byte)(unsafe.Pointer(p))[:n]
        	r := unsafe.Pointer(uintptr(q))
        	return s, r, uintptr(q) + 1
        }
        """
    )
}

/** Every inspection of the set over the GoLand probe file: nothing to report there. */
class GoFixProbeTest : GoFixInspectionTestBase() {
    override fun tool(): LocalInspectionTool = GoFixNewExprInspection()

    fun testProbeHasNoFindings() {
        val tools = listOf(
            GoFixNewExprInspection(), GoFixOmitZeroInspection(), GoFixPlusBuildInspection(), GoFixEmbedTypedInspection(), GoFixHostPortInspection(),
            GoFixWaitGroupInspection(), GoFixTestingContextInspection(), GoFixReflectTypeForInspection(), GoFixErrorsAsTypeInspection(),
            GoFixStringsSeqInspection(), GoFixStringsBuilderInspection(), GoFixAtomicTypesInspection(), GoFixUnsafeFuncsInspection(),
        )
        myFixture.enableInspections(*tools.toTypedArray())
        val probe = File(testDataRoot(), "../tools/ui-robot/goland/probe")
        for (name in listOf("analysis.go", "analysis_test.go")) {
            myFixture.configureByText(name, File(probe, name).readText().replace("\r\n", "\n"))
            val ids = tools.map { it.shortName }.toSet()
            val found = myFixture.doHighlighting().filter { it.inspectionToolId in ids }
            assertEmpty(name, found.map { "${it.inspectionToolId}: ${it.description} at ${it.startOffset}" })
        }
    }
}
