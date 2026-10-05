package io.github.golangsupport.ide.inspections.gofix

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.testFramework.replaceService

/** `interface{}` → `any` (GoFixAny). */
class GoFixAnyInspectionTest : GoFixTestBase() {
    override fun inspection(): LocalInspectionTool = GoFixAnyInspection()

    private val msg = "interface{} can be replaced by any"

    fun testEmptyInterfaceEverywhere() = doTest(
        """
        package p

        var values []${warn(msg, "interface{}")}

        func f(x ${warn(msg, "interface{}")}) map[string]${warn(msg, "interface{}")} { return nil }
        """,
        "Replace 'interface{}' with 'any'",
        """
        package p

        var values []any

        func f(x any) map[string]any { return nil }
        """,
    )

    fun testNonEmptyAndShadowedAnyStayQuiet() = highlight(
        """
        package p

        type Shape interface{ Area() float64 }

        var x interface {
            // a comment
        }

        func g() {
            any := 1
            var y interface{}
            _, _ = any, y
        }
        """
    )

    fun testOlderModuleIsNotReported() {
        useGoVersion("1.17")
        highlight("package p\n\nvar x interface{}")
    }

    fun testModuleAtTheVersionIsReported() {
        useGoVersion("1.18")
        highlight("package p\n\nvar x ${warn(msg, "interface{}")}")
    }

    fun testBuildLineOfTheFileWins() {
        useGoVersion("1.17")
        highlight("//go:build go1.21\n\npackage p\n\nvar x ${warn(msg, "interface{}")}")
    }
}

/** Conditional assignment → `min` / `max` (GoFixMinMax). */
class GoFixMinMaxInspectionTest : GoFixTestBase() {
    override fun inspection(): LocalInspectionTool = GoFixMinMaxInspection()

    fun testIfElseMin() = doTest(
        """
        package p

        func f(a, b int) int {
            var x int
            ${warn("if/else statement can be modernized using min", "if")} a < b {
                x = a
            } else {
                x = b
            }
            return x
        }
        """,
        "Replace with 'min'",
        """
        package p

        func f(a, b int) int {
            var x int
            x = min(a, b)
            return x
        }
        """,
    )

    fun testIfElseMaxWithGreater() = doTest(
        """
        package p

        func f(a, b string) (x string) {
            ${warn("if/else statement can be modernized using max", "if")} a > b {
                x = a
            } else {
                x = b
            }
            return
        }
        """,
        "Replace with 'max'",
        """
        package p

        func f(a, b string) (x string) {
            x = max(a, b)
            return
        }
        """,
    )

    fun testClampWithoutElse() = doTest(
        """
        package p

        const limit = 10

        func f(n int) int {
            ${warn("if statement can be modernized using min", "if")} n > limit {
                n = limit
            }
            return n
        }
        """,
        "Replace with 'min'",
        """
        package p

        const limit = 10

        func f(n int) int {
            n = min(n, limit)
            return n
        }
        """,
    )

    fun testFloatsCallsMismatchesAndShadowedNamesStayQuiet() = highlight(
        """
        package p

        func g() int { return 1 }

        func f(a, b int, x, y float64) {
            var r int
            if x < y {
                x = y
            }
            if a < g() {
                r = a
            } else {
                r = g()
            }
            if a < b {
                r = b
            } else {
                r = b
            }
            if a < b {
                r = a
            } else {
                a = b
            }
            if a < b {
                r = a
                r++
            } else {
                r = b
            }
            _ = r
        }

        func h(a, b int) int {
            min := 0
            if a < b {
                min = a
            } else {
                min = b
            }
            return min
        }
        """
    )

    fun testOlderModuleIsNotReported() {
        useGoVersion("1.20")
        highlight("package p\n\nfunc f(n int) int {\n    if n > 10 {\n        n = 10\n    }\n    return n\n}")
    }
}

/** `for i := 0; i < n; i++` → `for i := range n` (GoFixRangeInt). */
class GoFixRangeIntInspectionTest : GoFixTestBase() {
    override fun inspection(): LocalInspectionTool = GoFixRangeIntInspection()

    private val msg = "for loop can be modernized using range over int"

    fun testParameterLimit() = doTest(
        """
        package p

        func f(n int, ch chan int) {
            for ${warn(msg, "i := 0; i < n; i++")} {
                ch <- i
            }
        }
        """,
        "Replace with range over int",
        """
        package p

        func f(n int, ch chan int) {
            for i := range n {
                ch <- i
            }
        }
        """,
    )

    fun testUnusedIndexAndLenLimitAndConstant() = doTest(
        """
        package p

        const N = 3

        func f(s []int) int {
            total := 0
            for ${warn(msg, "i := 0; i < len(s); i++")} {
                total += s[i]
            }
            for ${warn(msg, "j := 0; j < N; j++")} {
                total++
            }
            return total
        }
        """,
        "Replace with range over int",
        """
        package p

        const N = 3

        func f(s []int) int {
            total := 0
            for i := range len(s) {
                total += s[i]
            }
            for range N {
                total++
            }
            return total
        }
        """,
    )

    fun testPreviewShowsTheRewrite() {
        myFixture.enableInspections(inspection())
        myFixture.configureByText("a.go", go(loop.replace("for i", "for <caret>i")))
        val action = myFixture.findSingleIntention("Replace with range over int")
        assertEquals(go(loop.replace("i := 0; i < n; i++", "i := range n")).trimEnd(), myFixture.getIntentionPreviewText(action)?.trimEnd())
    }

    private val loop = """
        package p

        func f(n int) {
            for i := 0; i < n; i++ {
                _ = i
            }
        }
    """

    fun testClosedDiagnosticsGateSilencesIt() {
        com.intellij.openapi.application.ApplicationManager.getApplication().replaceService(io.github.golangsupport.ide.GoIdeFeatureGate::class.java,
            object : io.github.golangsupport.ide.GoIdeFeatureGate {
                override fun enabled(feature: io.github.golangsupport.ide.GoIdeFeature, project: com.intellij.openapi.project.Project): Boolean =
                    feature != io.github.golangsupport.ide.GoIdeFeature.DIAGNOSTICS
            }, testRootDisposable)
        highlight(loop)
    }

    fun testWritesAndOtherShapesStayQuiet() = highlight(
        """
        package p

        type B struct{ N int }

        var global = 3

        func f(n int, s []int, m int64, b *B) {
            for i := 0; i < n; i++ {
                i++
            }
            for i := 0; i < n; i++ {
                n--
            }
            for i := 0; i < len(s); i++ {
                s = append(s, i)
            }
            for i := 1; i < n; i++ {
            }
            for i := 0; i <= n; i++ {
            }
            for i := 0; i < n; i += 2 {
            }
            for i := int64(0); i < m; i++ {
            }
            for i := 0; i < int(m); i++ {
            }
            for i := 0; i < b.N; i++ {
            }
            for i := 0; i < global; i++ {
            }
            for i := 0; /* keep */ i < n; i++ {
            }
            p := &n
            for i := 0; i < n; i++ {
                *p = 0
            }
            for i := 0; i < n; i++ {
                inc(&i)
            }
        }

        func inc(p *int) { *p++ }
        """
    )

    fun testOlderModuleIsNotReported() {
        useGoVersion("1.21")
        highlight("package p\n\nfunc f(n int) {\n    for i := 0; i < n; i++ {\n        _ = i\n    }\n}")
    }
}

/** `v := v` in a range loop (GoFixForVar). */
class GoFixForVarInspectionTest : GoFixTestBase() {
    override fun inspection(): LocalInspectionTool = GoFixForVarInspection()

    private val msg = "copying variable is unneeded"

    fun testCopyOfRangeVariables() = doTest(
        """
        package p

        func f(m map[string]int, use func(string, int)) {
            for k, v := range m {
                ${warn(msg, "k, v := k, v")}
                go use(k, v)
            }
        }
        """,
        "Remove the redundant copy",
        """
        package p

        func f(m map[string]int, use func(string, int)) {
            for k, v := range m {
                go use(k, v)
            }
        }
        """,
    )

    fun testOtherCopiesStayQuiet() = highlight(
        """
        package p

        func f(s []int, x int) {
            for _, v := range s {
                x := x
                w := v
                _, _ = x, w
                v := v + 1
                _ = v
            }
            for i := 0; i < 3; i++ {
                i := i
                _ = i
            }
            var v int
            for _, v = range s {
                v := v
                _ = v
            }
        }
        """
    )

    fun testOlderModuleIsNotReported() {
        useGoVersion("1.21")
        highlight("package p\n\nfunc f(s []int) {\n    for _, v := range s {\n        v := v\n        _ = v\n    }\n}")
    }
}
