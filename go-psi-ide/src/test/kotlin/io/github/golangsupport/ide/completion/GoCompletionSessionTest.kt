package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.command.WriteCommandAction

/**
 * Two completion sessions in a row on the same file must not share a context. The bug this guards
 * (context cached on the dummy-identifier leaf, which the platform reuses with the file copy between
 * sessions) only reproduces in a real IDE: the light test fixture creates a fresh copy per session,
 * so this test passes with or without the fix. The real regression check is step 6 of the UI robot
 * scenario (`tools/ui-robot/autotest.py`).
 */
class GoCompletionSessionTest : GoCompletionTestBase() {

    /**
     * Completes at [marker] with [insertBefore] typed in front of it, then removes the typed text
     * again, so the next session sees the original file (a dangling `strings.` would make the next
     * line a selector continuation: no semicolon is inserted after `.`).
     */
    private fun completeAt(marker: String, insertBefore: String = ""): List<String> {
        val doc = myFixture.editor.document
        val offset = doc.text.indexOf(marker)
        assertTrue("marker $marker not found", offset >= 0)
        WriteCommandAction.runWriteCommandAction(project) { doc.insertString(offset, insertBefore) }
        myFixture.editor.caretModel.moveToOffset(offset + insertBefore.length)
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings.orEmpty()
        LookupManager.getInstance(project).hideActiveLookup()
        WriteCommandAction.runWriteCommandAction(project) { doc.deleteString(offset, offset + insertBefore.length) }
        return items
    }

    private fun configure() {
        myFixture.configureByText(
            "main.go",
            go(
                """
                package compl

                import "strings"

                func helperFunc(n int) int { return n }

                func use() {
                	localVar := 1
                	_ = localVar
                	_ = strings.ToUpper("x")
                	/*A*/
                	/*B*/
                }
                """,
            ),
        )
    }

    fun testMemberThenStatementSession() {
        configure()
        val members = completeAt("/*A*/", "strings.")
        assertTrue("members of strings: $members", "Split" in members && "localVar" !in members)
        val statement = completeAt("/*B*/")
        assertTrue("statement position after a member session: $statement", "localVar" in statement && "helperFunc" in statement)
    }

    fun testStatementThenMemberSession() {
        configure()
        val statement = completeAt("/*B*/")
        assertTrue("statement position: $statement", "localVar" in statement && "helperFunc" in statement)
        val members = completeAt("/*A*/", "strings.")
        assertTrue("members after a statement session: $members", "Split" in members && "localVar" !in members)
    }
}
