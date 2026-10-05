package io.github.golangsupport.ide.refactoring

import com.intellij.refactoring.util.CommonRefactoringUtil
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Introduce Field: `<selection>` in a method body, the struct and the method after (Go written with 4-space indents, tabs in the file). */
class GoIntroduceFieldTest : GoSemanticIdeTestBase() {

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun doTest(before: String, after: String, options: GoIntroduceFieldOptions) {
        myFixture.configureByText("fi.go", go(before))
        GoIntroduceFieldHandler(options).invoke(project, myFixture.editor, myFixture.file, null)
        myFixture.checkResult(go(after))
    }

    private fun refused(text: String): String {
        myFixture.configureByText("fq.go", go(text))
        try {
            GoIntroduceFieldHandler(GoIntroduceFieldOptions()).invoke(project, myFixture.editor, myFixture.file, null)
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            return e.message!!
        }
        fail("Expected the refactoring to be refused")
        return ""
    }

    fun testInitializedInCurrentMethod() = doTest(
        """
        package fi

        type fiServer struct {
            host string
        }

        func (s *fiServer) addr() string {
            return s.host + <selection>":8080"</selection>
        }
        """,
        """
        package fi

        type fiServer struct {
            host string
            port string
        }

        func (s *fiServer) addr() string {
            s.port = ":8080"
            return s.host + s.port
        }
        """,
        GoIntroduceFieldOptions(name = "port"),
    )

    fun testLeftToCallerWithAllOccurrences() = doTest(
        """
        package fi

        type fiBox struct {
            w int
        }

        func (b fiBox) area() int {
            return b.w * <selection>3</selection> * 3
        }
        """,
        """
        package fi

        type fiBox struct {
            w int
            h int
        }

        func (b fiBox) area() int {
            return b.w * b.h * b.h
        }
        """,
        GoIntroduceFieldOptions(replaceAll = true, name = "h", initializeHere = false),
    )

    fun testEmptyStructAndSuggestedName() = doTest(
        """
        package fi

        type fiEmpty struct{}

        func (e *fiEmpty) size(items []string) int {
            return <selection>len(items)</selection>
        }
        """,
        """
        package fi

        type fiEmpty struct {
            n int
        }

        func (e *fiEmpty) size(items []string) int {
            e.n = len(items)
            return e.n
        }
        """,
        GoIntroduceFieldOptions(),
    )

    fun testFunctionIsRefused() {
        val message = refused("package fq\n\nfunc fqF() int {\n    return <selection>1</selection>\n}")
        assertTrue(message, message.contains("method with a struct receiver"))
    }

    fun testNonStructReceiverIsRefused() {
        val message = refused("package fq\n\ntype fqN int\n\nfunc (n fqN) f() int {\n    return <selection>1</selection>\n}")
        assertTrue(message, message.contains("not a struct"))
    }
}
