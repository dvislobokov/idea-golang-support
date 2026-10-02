package io.github.golangsupport.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.types.GoTypeRenderer

/**
 * `GoSemanticService.expectedTypeAt` and `enclosingResultTypes`. In the fixtures `expr /*E: type*/` checks the
 * expected type of the smallest expression ending right before the marker (`/*E: -*/`: no expectation), and
 * `/*R: types*/` the enclosing result types at the marker (`-` for null, `()` for none).
 */
class GoExpectedTypeTest : GoSemanticTestBase() {
    override val group: String = "types"

    private fun check(text: String) {
        val file = myFixture.addFileToProject("exp${counter++}/a.go", text) as GoFile
        val failures = ArrayList<String>()
        var checks = 0
        for (m in MARKER.findAll(text)) {
            val (kind, expected) = m.destructured
            checks++
            if (kind == "E") {
                val expr = expressionEndingAt(file, m.range.first) ?: run { failures += "@${m.range.first}: no expression"; null } ?: continue
                val actual = semantic.expectedTypeAt(expr)?.let(GoTypeRenderer::render) ?: "-"
                if (actual != expected.trim()) failures += "'${expr.text}': expected '${expected.trim()}', got '$actual'"
            } else {
                val leaf: PsiElement = file.findElementAt(m.range.first) ?: continue
                val actual = semantic.enclosingResultTypes(leaf)?.joinToString(", ", "(", ")") { GoTypeRenderer.render(it) } ?: "-"
                if (actual != expected.trim()) failures += "results at ${m.range.first}: expected '${expected.trim()}', got '$actual'"
            }
        }
        assertTrue("no checks", checks > 0)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun expressionEndingAt(file: GoFile, markerStart: Int): GoExpression? {
        var end = markerStart
        while (end > 0 && file.text[end - 1] == ' ') end--
        // the smallest one: in `l == 2 /*E*/` the operand is checked, not the comparison
        var e: PsiElement? = file.findElementAt(end - 1)
        while (e != null && e !is GoFile) {
            if (e is GoExpression && e.textRange.endOffset == end) return e
            e = e.parent
        }
        return null
    }

    fun testAssignmentAndDeclarations() = check(
        """
        package p

        type Celsius float64

        func f() {
            var c Celsius
            c = 3 /*E: Celsius*/
            var s string = "x" /*E: string*/
            _ = 1 /*E: -*/
            n := 2 /*E: -*/
            _, _ = c, n
            _ = s
        }
        """.trimIndent(),
    )

    fun testCallArguments() = check(
        """
        package p

        type T struct{}

        func (T) Put(key string, value int) {}
        func join(sep string, parts ...int) {}

        type F func(int)

        func f(t T, xs []int) {
            t.Put("k" /*E: string*/, 1 /*E: int*/)
            join("," /*E: string*/, 1, 2 /*E: int*/)
            join(",", xs /*E: []int*/...)
            _ = F(nil /*E: -*/)
        }
        """.trimIndent(),
    )

    fun testReturnValues() = check(
        """
        package p

        type T struct{}

        func two() (T, error) { return T{} /*E: T*/, nil /*E: error*/ }

        func one() (T, error) {
            return two() /*E: (T, error)*/
        }

        func lit() {
            _ = func() (int, string) { return 0 /*E: int*/, "" /*E: string*/ }
        }
        """.trimIndent(),
    )

    fun testCompositeLiterals() = check(
        """
        package p

        type Point struct{ X, Y int; Name string }

        func f() {
            _ = Point{X: 1 /*E: int*/, Name: "a" /*E: string*/}
            _ = []Point{{Name: "b" /*E: string*/}}
            _ = map[string]bool{"k" /*E: string*/: true /*E: bool*/}
            _ = []float64{1 /*E: float64*/}
        }
        """.trimIndent(),
    )

    fun testOperandsSendsCasesAndConditions() = check(
        """
        package p

        type Level int

        func f(l Level, ch chan string, m map[Level]string, ok bool) {
            _ = l == 2 /*E: Level*/
            _ = l /*E: -*/ == 2
            _ = 2 + 3 /*E: int*/
            ch <- "x" /*E: string*/
            v := <-ch /*E: -*/
            switch l {
            case 1 /*E: Level*/:
            }
            if ok /*E: bool*/ {
            }
            _ = !ok /*E: bool*/
            _ = ok && ok /*E: bool*/
            _ = m[3 /*E: Level*/]
            _ = v
        }
        """.trimIndent(),
    )

    fun testEnclosingResultTypes() = check(
        """
        package p

        /*R: -*/
        type S struct{}

        func (s *S) Load(name string) (n int, err error) {
            /*R: (int, error)*/
            f := func() bool {
                /*R: (bool)*/
                return true
            }
            _ = f
            return 0, nil
        }

        func none() {
            /*R: ()*/
        }
        """.trimIndent(),
    )

    private var counter = 0

    private companion object {
        val MARKER = Regex("""/\*([ER]):([^*]*)\*/""")
    }
}
