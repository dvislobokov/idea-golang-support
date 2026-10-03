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

/** Introduce Variable and Introduce Constant: `<selection>` or `<caret>` before, the text after (Go written with 4-space indents, tabs in the file). */
class GoIntroduceTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun variable(before: String, after: String, replaceAll: Boolean = false, name: String? = null) {
        myFixture.configureByText("iv.go", go(before))
        GoIntroduceVariableHandler(GoIntroduceOptions(replaceAll, name)).invoke(project, myFixture.editor, myFixture.file, null)
        myFixture.checkResult(go(after))
    }

    private fun constant(before: String, after: String, replaceAll: Boolean = false) {
        myFixture.configureByText("ic.go", go(before))
        GoIntroduceConstantHandler(GoIntroduceOptions(replaceAll)).invoke(project, myFixture.editor, myFixture.file, null)
        myFixture.checkResult(go(after))
    }

    private fun variableUnavailable(text: String) = unavailable(text) { GoIntroduceVariableHandler(GoIntroduceOptions()) }

    private fun unavailable(text: String, handler: () -> GoIntroduceHandlerBase) {
        myFixture.configureByText("iu.go", go(text))
        try {
            handler().invoke(project, myFixture.editor, myFixture.file, null)
            fail("Expected the refactoring to be refused")
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            assertTrue(e.message, e.message!!.startsWith("Cannot perform refactoring"))
        }
    }

    // --- Introduce Variable ---

    fun testSimpleSelection() = variable(
        """
        package iv

        func sum(a, b int) int {
            return <selection>a + b</selection>
        }
        """,
        """
        package iv

        func sum(a, b int) int {
            n := a + b
            return n
        }
        """,
    )

    fun testIfHeaderGoesBeforeIf() = variable(
        """
        package iv

        func check(s string) bool {
            if <selection>len(s)</selection> > 3 {
                return true
            }
            return false
        }
        """,
        """
        package iv

        func check(s string) bool {
            n := len(s)
            if n > 3 {
                return true
            }
            return false
        }
        """,
    )

    fun testAllOccurrencesInFunction() = variable(
        """
        package iv

        func area(w, h int) int {
            x := w * h
            y := <selection>w * h</selection> + 1
            return w*h + x + y
        }
        """,
        """
        package iv

        func area(w, h int) int {
            n := w * h
            x := n
            y := n + 1
            return n + x + y
        }
        """,
        replaceAll = true,
    )

    fun testOnlyThisOccurrence() = variable(
        """
        package iv

        func twice(w, h int) int {
            x := w * h
            return <selection>w * h</selection> + x
        }
        """,
        """
        package iv

        func twice(w, h int) int {
            x := w * h
            n := w * h
            return n + x
        }
        """,
    )

    fun testErrorNamedErr() = variable(
        """
        package iv

        import "errors"

        func fail() error {
            return <selection>errors.New("boom")</selection>
        }
        """,
        """
        package iv

        import "errors"

        func fail() error {
            err := errors.New("boom")
            return err
        }
        """,
    )

    fun testContextNamedCtx() = variable(
        """
        package iv

        import "context"

        func run(f func(context.Context)) {
            f(<selection>context.Background()</selection>)
        }
        """,
        """
        package iv

        import "context"

        func run(f func(context.Context)) {
            ctx := context.Background()
            f(ctx)
        }
        """,
    )

    fun testCallLastWordAndClash() = variable(
        """
        package iv

        import "strings"

        func shout(s string) string {
            upper := 1
            _ = upper
            return <selection>strings.ToUpper(s)</selection>
        }
        """,
        """
        package iv

        import "strings"

        func shout(s string) string {
            upper := 1
            _ = upper
            upper1 := strings.ToUpper(s)
            return upper1
        }
        """,
    )

    fun testCaretPicksInnermostCall() = variable(
        """
        package iv

        func computeTotal() int { return 1 }

        func show() int {
            return computeTo<caret>tal() + 1
        }
        """,
        """
        package iv

        func computeTotal() int { return 1 }

        func show() int {
            total := computeTotal()
            return total + 1
        }
        """,
    )

    fun testMultiValueCall() = variable(
        """
        package iv

        func split(s string) (head, tail string) { return s, s }

        func use(s string) {
            println(<selection>split(s)</selection>)
        }
        """,
        """
        package iv

        func split(s string) (head, tail string) { return s, s }

        func use(s string) {
            head, tail := split(s)
            println(head, tail)
        }
        """,
    )

    fun testInCaseClause() = variable(
        """
        package iv

        func kind(n int) int {
            switch n {
            case 1:
                return <selection>n * 10</selection>
            }
            return 0
        }
        """,
        """
        package iv

        func kind(n int) int {
            switch n {
            case 1:
                n1 := n * 10
                return n1
            }
            return 0
        }
        """,
    )

    fun testRangeExpressionGoesBeforeFor() = variable(
        """
        package iv

        func each(m map[string]int) {
            for k := range <selection>m</selection> {
                println(k)
            }
        }
        """,
        """
        package iv

        func each(m map[string]int) {
            m1 := m
            for k := range m1 {
                println(k)
            }
        }
        """,
    )

    fun testGivenName() = variable(
        """
        package iv

        func half(x int) int {
            return <selection>x / 2</selection>
        }
        """,
        """
        package iv

        func half(x int) int {
            h := x / 2
            return h
        }
        """,
        name = "h",
    )

    fun testUnavailableInElseIfHeader() = variableUnavailable(
        """
        package iv

        func pick(p *int) int {
            if p == nil {
                return 0
            } else if <selection>*p</selection> > 0 {
                return 1
            }
            return 2
        }
        """,
    )

    fun testUnavailableInForCondition() = variableUnavailable(
        """
        package iv

        func loop(n int) {
            for i := 0; i < <selection>n * 2</selection>; i++ {
            }
        }
        """,
    )

    fun testUnavailableRightOfAnd() = variableUnavailable(
        """
        package iv

        func ok(p *int) bool {
            return p != nil && <selection>*p</selection> > 0
        }
        """,
    )

    fun testUnavailableUsesIfInitVariable() = variableUnavailable(
        """
        package iv

        func get(m map[string]int) int {
            if v, ok := m["a"]; ok && <selection>v</selection> > 0 {
                return v
            }
            return 0
        }
        """,
    )

    fun testUnavailableAssignmentTarget() = variableUnavailable(
        """
        package iv

        func set() {
            x := 1
            <selection>x</selection> = 2
            _ = x
        }
        """,
    )

    fun testUnavailableShortVarLeftSide() = variableUnavailable(
        """
        package iv

        func decl() {
            <selection>x</selection> := 1
            _ = x
        }
        """,
    )

    fun testUnavailablePackageQualifier() = variableUnavailable(
        """
        package iv

        import "strings"

        func q() string {
            return <selection>strings</selection>.ToLower("A")
        }
        """,
    )

    fun testUnavailableType() = variableUnavailable(
        """
        package iv

        type Celsius float64

        func conv(f float64) Celsius {
            return <selection>Celsius</selection>(f)
        }
        """,
    )

    fun testUnavailableVoidCall() = variableUnavailable(
        """
        package iv

        func nothing() {}

        func call() {
            if true {
                defer <selection>nothing()</selection>
            }
        }
        """,
    )

    fun testUnavailableAtPackageLevel() = variableUnavailable(
        """
        package iv

        var total = <selection>1 + 2</selection>
        """,
    )

    fun testThroughTheAction() {
        myFixture.configureByText("ia.go", go(
            """
            package iv

            func double(x int) int {
                return <selection>x * 2</selection>
            }
            """,
        ))
        myFixture.performEditorAction("IntroduceVariable")
        myFixture.checkResult(go(
            """
            package iv

            func double(x int) int {
                n := x * 2
                return n
            }
            """,
        ))
    }

    // --- Introduce Constant ---

    fun testConstantFromString() = constant(
        """
        package ic

        import "fmt"

        func greet() {
            fmt.Println(<selection>"hello world"</selection>)
        }
        """,
        """
        package ic

        import "fmt"

        const helloWorld = "hello world"

        func greet() {
            fmt.Println(helloWorld)
        }
        """,
    )

    fun testConstantAllOccurrencesWithoutImports() = constant(
        """
        package ic

        func seconds(h int) int {
            return <selection>60 * 60</selection> * h
        }

        func hours(s int) int {
            return s / (60 * 60)
        }
        """,
        """
        package ic

        const n = 60 * 60

        func seconds(h int) int {
            return n * h
        }

        func hours(s int) int {
            return s / (n)
        }
        """,
        replaceAll = true,
    )

    fun testConstantTypedExpression() = constant(
        """
        package ic

        import "time"

        func wait() time.Duration {
            return <selection>5 * time.Second</selection>
        }
        """,
        """
        package ic

        import "time"

        const duration = 5 * time.Second

        func wait() time.Duration {
            return duration
        }
        """,
    )

    fun testConstantUnavailableForVariable() = unavailable(
        """
        package ic

        func inc(x int) int {
            return <selection>x + 1</selection>
        }
        """,
    ) { GoIntroduceConstantHandler(GoIntroduceOptions()) }

    fun testConstantUnavailableForLocalConstant() = unavailable(
        """
        package ic

        func scaled() int {
            const k = 3
            return <selection>k * 2</selection>
        }
        """,
    ) { GoIntroduceConstantHandler(GoIntroduceOptions()) }

    // --- the gate ---

    fun testGateOff() {
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.RENAME
        }, testRootDisposable)
        val text = go(
            """
            package iv

            func unused() {}

            func double(x int) int {
                return <selection>x * 2</selection>
            }
            """,
        )
        myFixture.configureByText("ig.go", text)
        val provider = LanguageRefactoringSupport.getInstance().forLanguage(GoLanguage)!!
        val leaf = myFixture.file.findElementAt(myFixture.editor.caretModel.offset)!!
        assertFalse(provider.isAvailable(leaf))
        assertNull(provider.getIntroduceVariableHandler(leaf))
        val fn = myFixture.findElementByText("unused", io.github.golangsupport.lang.psi.GoFunctionDeclaration::class.java)
        assertFalse(provider.isSafeDeleteAvailable(fn))
        assertFalse(GoSafeDeleteProcessor().handlesElement(fn))
        GoIntroduceVariableHandler(GoIntroduceOptions()).invoke(project, myFixture.editor, myFixture.file, null)
        GoIntroduceConstantHandler(GoIntroduceOptions()).invoke(project, myFixture.editor, myFixture.file, null)
        assertEquals(text.replace("<selection>", "").replace("</selection>", ""), myFixture.editor.document.text)
    }
}
