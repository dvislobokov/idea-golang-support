package io.github.golangsupport.ide.inspections.lint

import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ex.QuickFixWrapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** errcheck over the PSI: what is reported (and what errcheck's defaults leave alone), the two fixes, and the gopls mode. */
class GoUncheckedErrorInspectionTest : GoSemanticIdeTestBase() {

    private fun doHighlight(text: String) {
        myFixture.enableInspections(GoUncheckedErrorInspection())
        myFixture.configureByText("ue9.go", text.trimIndent() + "\n")
        myFixture.checkHighlighting(true, false, true)
    }

    private inline fun <reified F : LocalQuickFix> doFix(before: String, after: String) {
        myFixture.enableInspections(GoUncheckedErrorInspection())
        myFixture.configureByText("ue9fix.go", before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        val fix = offered.singleOrNull { QuickFixWrapper.unwrap(IntentionActionDelegate.unwrap(it)) is F } ?: error("${F::class.simpleName} not in ${offered.map { it.text }}")
        myFixture.launchAction(fix)
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    /** Every feature group given to another source: what the host does with Language features = gopls. */
    private fun goplsMode() {
        val gate = object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project) = false
        }
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, gate, testRootDisposable)
    }

    private val reported = """
        package ue9a

        import (
        	"bytes"
        	"fmt"
        	"hash"
        	"io"
        	"os"
        	"strings"
        )

        type ue9Store struct{}

        func (ue9Store) Save() error { return nil }
        func (ue9Store) Count() int  { return 0 }

        func ue9Calls(c io.Closer, b *bytes.Buffer, sb *strings.Builder, h hash.Hash, s ue9Store, fn func() error) {
        	<warning descr="Error return value of `os.Open` is not checked">os.Open("123")</warning>
        	f, _ := os.Create("x")
        	<warning descr="Error return value of `f.Close` is not checked">f.Close()</warning>
        	<warning descr="Error return value of `c.Close` is not checked">c.Close()</warning>
        	<warning descr="Error return value of `s.Save` is not checked">s.Save()</warning>
        	<warning descr="Error return value of `fn` is not checked">fn()</warning>
        	<warning descr="Error return value of `fmt.Fprintln` is not checked">fmt.Fprintln(os.Stdout, "x")</warning>
        	<warning descr="Error return value of `os.Remove` is not checked">os.Remove("z")</warning> //nolint:govet
        	s.Count()
        	fmt.Println("x")
        	fmt.Printf("%d", 1)
        	fmt.Fprintf(b, "x")
        	fmt.Fprint(sb, "x")
        	fmt.Fprintln(os.Stderr, "x")
        	b.WriteString("x")
        	b.Write(nil)
        	sb.WriteByte('x')
        	h.Write(nil)
        	defer f.Close()
        	go f.Close()
        	_ = f.Close()
        	_, _ = os.Open("y")
        	if err := f.Close(); err != nil {
        		return
        	}
        	os.Remove("z") //nolint:errcheck
        	os.Remove("z") //nolint:unused,errcheck
        	os.Remove("z") //nolint
        }
    """

    fun testReportedAndExcluded() = doHighlight(reported)

    /** gopls has no errcheck: the check stays on when the host gives the diagnostics (and every other group) to gopls. */
    fun testReportedInGoplsMode() {
        goplsMode()
        doHighlight(reported)
    }

    fun testHandleErrorWithResults() = doFix<GoHandleUncheckedErrorFix>(
        """
        package ue9b

        import "os"

        func ue9Count() (int, error) {
        	<caret>os.Open("123")
        	return 0, nil
        }
        """,
        """
        package ue9b

        import "os"

        func ue9Count() (int, error) {
        	_, err := os.Open("123")
        	if err != nil {
        		return 0, err
        	}
        	return 0, nil
        }
        """,
    )

    fun testHandleErrorOnlyErrorWithoutResults() = doFix<GoHandleUncheckedErrorFix>(
        """
        package ue9c

        import "os"

        func ue9Nothing() {
        	<caret>os.Remove("x")
        }
        """,
        """
        package ue9c

        import "os"

        func ue9Nothing() {
        	if err := os.Remove("x"); err != nil {
        		return
        	}
        }
        """,
    )

    fun testHandleErrorWhenErrIsDeclared() = doFix<GoHandleUncheckedErrorFix>(
        """
        package ue9d

        import "os"

        func ue9Declared() error {
        	err := os.Remove("a")
        	<caret>os.Open("b")
        	return err
        }
        """,
        """
        package ue9d

        import "os"

        func ue9Declared() error {
        	err := os.Remove("a")
        	_, err1 := os.Open("b")
        	if err1 != nil {
        		return err1
        	}
        	return err
        }
        """,
    )

    fun testHandleErrorWhenErrIsANamedResult() = doFix<GoHandleUncheckedErrorFix>(
        """
        package ue9e

        import "os"

        func ue9Named() (n int, err error) {
        	<caret>os.Open("b")
        	return
        }
        """,
        """
        package ue9e

        import "os"

        func ue9Named() (n int, err error) {
        	_, err1 := os.Open("b")
        	if err1 != nil {
        		return 0, err1
        	}
        	return
        }
        """,
    )

    /** An `err` of an outer block may be shadowed: `:=` in the inner block declares a new one. */
    fun testHandleErrorWhenErrIsInOuterBlock() = doFix<GoHandleUncheckedErrorFix>(
        """
        package ue9f

        import "os"

        func ue9Outer() error {
        	err := os.Remove("a")
        	if err == nil {
        		<caret>os.Open("b")
        	}
        	return err
        }
        """,
        """
        package ue9f

        import "os"

        func ue9Outer() error {
        	err := os.Remove("a")
        	if err == nil {
        		_, err := os.Open("b")
        		if err != nil {
        			return err
        		}
        	}
        	return err
        }
        """,
    )

    fun testAssignToBlank() = doFix<GoAssignToBlankFix>(
        """
        package ue9g

        import "os"

        func ue9Blank() {
        	<caret>os.Open("123")
        }
        """,
        """
        package ue9g

        import "os"

        func ue9Blank() {
        	_, _ = os.Open("123")
        }
        """,
    )

    fun testAssignToBlankSingleError() = doFix<GoAssignToBlankFix>(
        """
        package ue9h

        import "io"

        func ue9BlankClose(c io.Closer) {
        	<caret>c.Close()
        }
        """,
        """
        package ue9h

        import "io"

        func ue9BlankClose(c io.Closer) {
        	_ = c.Close()
        }
        """,
    )

    /** The fixes are there in gopls mode, where the Handle error intention of the CODE_ACTIONS group is off. */
    fun testHandleErrorInGoplsMode() {
        goplsMode()
        doFix<GoHandleUncheckedErrorFix>(
            """
            package ue9i

            import "os"

            func ue9Gopls() error {
            	<caret>os.Remove("x")
            	return nil
            }
            """,
            """
            package ue9i

            import "os"

            func ue9Gopls() error {
            	if err := os.Remove("x"); err != nil {
            		return err
            	}
            	return nil
            }
            """,
        )
    }

    fun testIsUncheckedApi() {
        myFixture.configureByText("ue9api.go", "package ue9j\n\nimport \"os\"\n\nfunc ue9Api() {\n\tos.Remove(\"x\")\n\tdefer os.Remove(\"y\")\n}\n")
        val calls = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.file, io.github.golangsupport.lang.psi.GoCallExpr::class.java).toList()
        assertEquals(listOf(true, false), calls.map { GoUncheckedErrorInspection.isUnchecked(it) })
    }
}
