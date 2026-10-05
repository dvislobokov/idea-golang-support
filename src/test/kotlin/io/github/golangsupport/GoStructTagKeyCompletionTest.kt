package io.github.golangsupport

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoStructTagKeyToAllFields

/** GoLand's items in struct tags (PLAN.md, G3): Add tag key to all fields… at a key, the four name styles in a value. */
class GoStructTagKeyCompletionTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            GoStructTagKeyToAllFields.chooserForTests = null
        } finally {
            super.tearDown()
        }
    }

    private fun lines(vararg lines: String) = lines.joinToString("\n") + "\n"

    fun testAddTagKeyComesFirstAtAKey() {
        myFixture.configureByText("p.go", lines("package p", "", "type Person struct {", "\tAge int `<caret>`", "}"))
        val items = myFixture.completeBasic().map { it.lookupString }
        assertEquals(GoStructTagKeyToAllFields.ITEM, items.first())
        assertTrue(items.containsAll(listOf("json", "yaml", "xml", "bson")))
    }

    fun testNoAddTagKeyInATagStillBeingTyped() {
        myFixture.configureByText("p.go", lines("package p", "", "type Person struct {", "\tAge int `<caret>", "}"))
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertFalse(items.toString(), GoStructTagKeyToAllFields.ITEM in items)
    }

    fun testAddTagKeyWritesTheChosenKeyIntoEveryField() {
        var offered: List<String>? = null
        GoStructTagKeyToAllFields.chooserForTests = { keys -> offered = keys; "json" }
        myFixture.configureByText("p.go", lines(
            "package p", "", "type Person struct {", "\tFullName string `json:\"fullName\"`", "\tUserID int64 `db:\"user_id\"`", "\tsecret string", "\tAge int `<caret>`", "}",
        ))
        val lookup = myFixture.completeBasic()
        assertNotNull(lookup)
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { it.lookupString == GoStructTagKeyToAllFields.ITEM }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        // the style of the struct (camelCase from FullName), unexported fields left out: json cannot see them
        myFixture.checkResult(lines(
            "package p", "", "type Person struct {", "\tFullName string `json:\"fullName\"`", "\tUserID int64 `db:\"user_id\" json:\"userId\"`", "\tsecret string",
            "\tAge int `json:\"age\"<caret>`", "}",
        ))
        assertEquals("json", offered?.first())
    }

    fun testNameInAJsonValueInGolandsFourStyles() {
        myFixture.configureByText("p.go", lines("package p", "", "type Person struct {", "\tFullName string `json:\"<caret>\"`", "}"))
        val items = myFixture.completeBasic().map { it.lookupString }
        assertEquals(listOf("full-name", "full_name", "FullName", "fullName"), items.take(4))
    }
}
