package io.github.golangsupport.ide.navigation

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.util.TextRange
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** The "Recursive call" gutter marker: functions, methods through the receiver and method expressions, not same-named calls elsewhere. */
class GoRecursiveCallLineMarkerTest : GoSemanticIdeTestBase() {

    private fun recursiveLines(text: String): List<String> {
        myFixture.configureByText("a.go", text.trimIndent() + "\n")
        val document = myFixture.editor.document
        return myFixture.findAllGutters().filter { it.tooltipText == GoRecursiveCallLineMarkerProvider.TOOLTIP }.map { g ->
            val line = document.getLineNumber((g as LineMarkerInfo.LineMarkerGutterIconRenderer<*>).lineMarkerInfo.startOffset)
            document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
        }.sorted()
    }

    fun testFunctionRecursion() {
        assertEquals(
            listOf("return fib(n-1) + fib(n-2)", "return n * Factorial(n-1)"),
            recursiveLines(
                """
                package p

                func Factorial(n int) int {
                	if n <= 1 {
                		return 1
                	}
                	return n * Factorial(n-1)
                }

                func fib(n int) int {
                	if n < 2 {
                		return n
                	}
                	return fib(n-1) + fib(n-2)
                }
                """,
            ),
        )
    }

    fun testMethodRecursion() {
        assertEquals(
            listOf("(*Node).Walk(n.Left, visit)", "Node.Size(*n.Right)", "go func() { n.Left.Walk(visit) }()", "return 1 + n.Left.Size()"),
            recursiveLines(
                """
                package p

                type Node struct {
                	Left, Right *Node
                }

                func (n *Node) Walk(visit func(*Node)) {
                	visit(n)
                	go func() { n.Left.Walk(visit) }()
                	(*Node).Walk(n.Left, visit)
                }

                func (n Node) Size() int {
                	Node.Size(*n.Right)
                	return 1 + n.Left.Size()
                }
                """,
            ),
        )
    }

    fun testSameNameElsewhereIsNotRecursive() {
        assertEmpty(
            recursiveLines(
                """
                package p

                type A struct{}
                type B struct{ a A }

                func (A) Run() {}

                // B.Run calls A.Run: same name, another method
                func (b B) Run() { b.a.Run() }

                // a local variable shadows the function's own name
                func outer() {
                	outer := func() {}
                	outer()
                }

                func Size() int { return 0 }

                func (b B) Size() int { return Size() }
                """,
            ),
        )
    }
}
