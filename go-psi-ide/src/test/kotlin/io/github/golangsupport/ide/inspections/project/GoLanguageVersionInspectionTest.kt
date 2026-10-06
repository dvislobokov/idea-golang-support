package io.github.golangsupport.ide.inspections.project

import com.intellij.codeInspection.LocalInspectionTool
import io.github.golangsupport.ide.inspections.gofix.GoFixTestBase

/** Language features newer than the module's `go` directive (go/types' version errors). */
class GoLanguageVersionInspectionTest : GoFixTestBase() {

    override fun inspection(): LocalInspectionTool = GoLanguageVersionInspection()

    private var hint = ""

    private fun use(version: String) {
        useGoVersion(version)
        hint = " (-lang was set to go$version; check go.mod)"
    }

    private fun err(message: String, text: String) = "<error descr=\"$message$hint\">$text</error>"

    fun testBuildLineOfTheFileNamesItInTheHint() {
        useGoVersion("1.23")
        hint = " (file declares //go:build go1.21)"
        highlight("//go:build go1.21\n\npackage p\n\nfunc f() {\n    for range ${err("cannot range over 3 (untyped int constant): requires go1.22 or later", "3")} {\n    }\n}")
    }

    fun testTypeParametersBefore118() {
        use("1.17")
        highlight(
            """
            package p

            func Map[${err("type parameter requires go1.18 or later", "T")}, U any](s []T) []U { return nil }

            type List[${err("type parameter requires go1.18 or later", "E")} any] struct{ items []E }
            """
        )
    }

    fun testBuiltinsBefore121() {
        use("1.20")
        highlight(
            """
            package p

            func f(m map[string]int, a, b int) int {
                ${err("clear requires go1.21 or later", "clear")}(m)
                return ${err("built-in min requires go1.21 or later", "min")}(a, b) + ${err("built-in max requires go1.21 or later", "max")}(a, b)
            }
            """
        )
    }

    fun testRangeOverIntBefore122() {
        use("1.21")
        highlight(
            """
            package p

            func f(n int) {
                for range ${err("cannot range over 10 (untyped int constant): requires go1.22 or later", "10")} {
                }
                for i := range ${err("cannot range over n (variable of type int): requires go1.22 or later", "n")} {
                    _ = i
                }
                for range []int{1} {
                }
            }
            """
        )
    }

    fun testRangeOverFuncBefore123() {
        use("1.22")
        highlight(
            """
            package p

            func f(seq func(yield func(int) bool)) {
                for v := range ${err("cannot range over seq (variable of type func(yield func(int) bool)): requires go1.23 or later", "seq")} {
                    _ = v
                }
                for range 3 {
                }
            }
            """
        )
    }

    fun testCurrentVersionIsQuiet() {
        use("1.23")
        highlight(
            """
            package p

            func f[T any](seq func(yield func(T) bool), m map[int]int) {
                clear(m)
                for range seq {
                }
                for range min(1, 2) {
                }
            }
            """
        )
    }

    fun testShadowedBuiltinAndBuildLine() {
        use("1.20")
        highlight(
            """
            package p

            func min(a, b int) int { return a }

            var _ = min(1, 2)
            """
        )
        highlight("//go:build go1.21\n\npackage p\n\nvar _ = max(1, 2)")
    }

    fun testUnknownVersionIsQuiet() = highlight("package p\n\nfunc f[T any]() { for range 3 {} }")
}
