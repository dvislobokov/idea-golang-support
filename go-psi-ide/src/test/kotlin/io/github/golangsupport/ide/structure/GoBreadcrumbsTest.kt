package io.github.golangsupport.ide.structure

import io.github.golangsupport.ide.GoIdeTestBase

class GoBreadcrumbsTest : GoIdeTestBase() {

    fun testMethodWithControlFlowAndFuncLit() = doTest(
        """
        package p

        func (s *Server) Start() {
        	for {
        		if ok {
        			go func() {
        				<caret>run()
        			}()
        		}
        	}
        }
        """,
        "(Server) Start()", "for", "if", "func()",
    )

    fun testFunctionSwitchCase() = doTest(
        """
        package p

        func handle(x int) {
        	switch x {
        	case 1:
        		<caret>a()
        	default:
        	}
        }
        """,
        "handle()", "switch", "case",
    )

    fun testSelectDefault() = doTest(
        """
        package p

        func f() {
        	select {
        	default:
        		<caret>a()
        	}
        }
        """,
        "f()", "select", "default",
    )

    fun testStructField() = doTest(
        """
        package p

        type Point struct {
        	X<caret> int
        }
        """,
        "Point", "X",
    )

    fun testInterfaceMethod() = doTest(
        """
        package p

        type I interface {
        	Read(p []by<caret>te) error
        }
        """,
        "I", "Read()",
    )

    private fun doTest(text: String, vararg expected: String) {
        myFixture.configureByText("a.go", text.trimIndent())
        assertEquals(expected.toList(), myFixture.breadcrumbsAtCaret.map { it.text })
    }
}
