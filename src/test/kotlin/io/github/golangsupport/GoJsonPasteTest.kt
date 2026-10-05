package io.github.golangsupport

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.format.GoFormattingService
import io.github.golangsupport.lang.GoJsonPaste
import io.github.golangsupport.lang.GoJsonTypes
import io.github.golangsupport.settings.GoFormatter
import io.github.golangsupport.settings.GoPasteJson
import io.github.golangsupport.settings.GoSettings
import java.awt.datatransfer.StringSelection

/** PLAN.md G8: JSON pasted into a Go file becomes a Go type (Settings | Go | Editor and Completion, When JSON is pasted). */
class GoJsonPasteTest : BasePlatformTestCase() {
    private val json = """{"user_name": "x", "age": 3, "address": {"city": "c"}}"""

    override fun tearDown() {
        try {
            GoSettings.getInstance().pasteJson = GoPasteJson.ASK
        } finally {
            super.tearDown()
        }
    }

    private fun paste(text: String, before: String): String {
        myFixture.configureByText("a.go", before)
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_PASTE)
        return myFixture.editor.document.text
    }

    fun testBetweenDeclarationsTheTypesAreDeclared() {
        GoSettings.getInstance().pasteJson = GoPasteJson.CONVERT
        assertEquals("package a\n\ntype Generated struct {\n\tUserName string  `json:\"user_name\"`\n\tAge      int     `json:\"age\"`\n\tAddress  Address `json:\"address\"`\n}\n\n" +
            "type Address struct {\n\tCity string `json:\"city\"`\n}\n\n", paste(json, "package a\n\n<caret>\n"))
    }

    fun testAfterTypeNameTheStructTypeFollows() {
        GoSettings.getInstance().pasteJson = GoPasteJson.CONVERT
        val text = paste("""{"id": 1}""", "package a\n\ntype User <caret>\n")
        assertTrue(text, text.contains("type User struct {\n\tID int `json:\"id\"`\n}"))
    }

    fun testInsideAStructTheFieldsAreInserted() {
        GoSettings.getInstance().pasteJson = GoPasteJson.CONVERT
        val text = paste("""{"id": 1, "tags": ["a"]}""", "package a\n\ntype User struct {\n\t<caret>\n}\n")
        assertTrue(text, text.contains("type User struct {\n\tID   int      `json:\"id\"`\n\tTags []string `json:\"tags\"`\n") ||
            text.contains("type User struct {\n\tID int `json:\"id\"`\n\tTags []string `json:\"tags\"`\n"))
        assertFalse(text, text.contains("type Generated"))
    }

    fun testAsIsAndOtherPlacesKeepTheJson() {
        GoSettings.getInstance().pasteJson = GoPasteJson.AS_IS
        assertTrue(paste(json, "package a\n\n<caret>\n").contains(json))
        GoSettings.getInstance().pasteJson = GoPasteJson.CONVERT
        assertTrue("inside a function", paste(json, "package a\n\nfunc f() {\n\ts := `<caret>`\n}\n").contains(json))
        assertTrue("not an object", paste("[1, 2]", "package a\n\n<caret>\n").contains("[1, 2]"))
    }

    fun testPureConversion() {
        assertTrue(GoJsonTypes.looksLikeJsonObject(" {\"a\": 1} "))
        assertFalse(GoJsonTypes.looksLikeJsonObject("[1]"))
        assertEquals("\tCity string `json:\"city\"`\n\tGeo  struct {\n\t\tLat float64 `json:\"lat\"`\n\t} `json:\"geo\"`\n".replace("  struct", " struct"),
            GoJsonTypes.fields("""{"city": "x", "geo": {"lat": 1.5}}""").code.replace("  struct", " struct"))
        assertEquals("struct {\n\tID int `json:\"id\"`\n}", GoJsonPaste.convert(GoJsonPaste.Place.AfterTypeName("User"), """{"id": 1}""", "type User ")?.trimEnd())
        assertNull(GoJsonPaste.convert(GoJsonPaste.Place.Declarations, "not json", ""))
    }

    fun testGoimportsGetsTheLocalPrefixes() {
        assertEquals(listOf("-local", "corp.io,example.com/app"), GoFormattingService.arguments(GoFormatter.GOIMPORTS, listOf("corp.io", "example.com/app")))
        assertEquals(emptyList<String>(), GoFormattingService.arguments(GoFormatter.GOIMPORTS))
        assertEquals(emptyList<String>(), GoFormattingService.arguments(GoFormatter.GOFMT, listOf("corp.io")))
    }
}
