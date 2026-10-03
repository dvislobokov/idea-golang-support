package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import io.github.golangsupport.ide.completion.GoStructTagCompletion.Style

/** Positions, naming styles and autopopup in raw-string struct tags; the lists come from the host's GoStructTagCompletionContributor. */
class GoStructTagCompletionTest : GoCompletionTestBase() {

    private fun struct(field: String, vararg others: String) = (listOf("package main", "", "type T struct {") + others.map { "    $it" } + listOf("    $field", "}")).joinToString("\n")

    fun testPositions() {
        val key = GoStructTagCompletion.position("json:\"a\" ya", "json:\"a\" ya") as GoStructTagCompletion.Position.Key
        assertEquals("ya", key.prefix)
        assertEquals(setOf("json"), key.used)
        assertNull(GoStructTagCompletion.position("json:\"a\"x"))
        val name = GoStructTagCompletion.position("json:\"na") as GoStructTagCompletion.Position.Name
        assertEquals("json" to "na", name.key to name.prefix)
        assertNull(GoStructTagCompletion.position("validate:\"re"))
        val option = GoStructTagCompletion.position("json:\"a,omitempty,st") as GoStructTagCompletion.Position.Option
        assertEquals("st", option.prefix)
        assertEquals(setOf("omitempty"), option.used)
        assertNull(GoStructTagCompletion.position("json:"))
    }

    fun testStyles() {
        assertEquals(listOf("User", "ID"), GoStructTagCompletion.words("UserID"))
        assertEquals(listOf("HTTP", "Server"), GoStructTagCompletion.words("HTTPServer"))
        assertEquals("userId", Style.CAMEL.apply("UserID"))
        assertEquals("user_id", Style.SNAKE.apply("UserID"))
        assertEquals("userID", Style.LOWER_FIRST.apply("UserID"))
        assertEquals(Style.SNAKE, GoStructTagCompletion.detectStyle("json", listOf("FirstName" to "first_name", "ID" to "id")))
        assertEquals(Style.AS_IS, GoStructTagCompletion.detectStyle("yaml", listOf("FirstName" to "FirstName")))
        assertEquals(Style.CAMEL, GoStructTagCompletion.detectStyle("json", emptyList()))
    }

    fun testAutoPopupAfterCommaAndColon() {
        myFixture.configureByText("main.go", go(struct("B int `yaml<caret>`", "A int `json:\"a<caret>\"`")))
        val handler = GoStructTagTypedHandler()
        val carets = myFixture.editor.caretModel.allCarets.map { it.offset }
        fun check(offset: Int, c: Char): TypedHandlerDelegate.Result {
            myFixture.editor.caretModel.removeSecondaryCarets()
            myFixture.editor.caretModel.moveToOffset(offset)
            return handler.checkAutoPopup(c, project, myFixture.editor, myFixture.file)
        }
        assertEquals(TypedHandlerDelegate.Result.STOP, check(carets[0], ','))
        assertEquals(TypedHandlerDelegate.Result.CONTINUE, check(carets[0], 'x'))
    }

    fun testColonAfterKnownKeyInsertsQuotes() {
        myFixture.configureByText("main.go", go(struct("A int `json<caret>`")))
        myFixture.type(':')
        myFixture.checkResult(go(struct("A int `json:\"<caret>\"`")))
    }

    fun testColonAfterUnknownKeyKeepsPlain() {
        myFixture.configureByText("main.go", go(struct("A int `foo<caret>`")))
        myFixture.type(':')
        myFixture.checkResult(go(struct("A int `foo:<caret>`")))
    }

    fun testConfidence() {
        myFixture.configureByText("main.go", go(struct("A int `json:\"a,<caret>\"`", "S string `x`")))
        val offset = myFixture.caretOffset
        val confidence = GoCompletionConfidence()
        assertEquals(com.intellij.util.ThreeState.NO, confidence.shouldSkipAutopopup(myFixture.editor, myFixture.file.findElementAt(offset - 1)!!, myFixture.file, offset))
    }
}
