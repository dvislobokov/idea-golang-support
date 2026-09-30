package io.github.golangsupport

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoHttpStatuses
import io.github.golangsupport.lang.GoTimeLayouts
import io.github.golangsupport.settings.GoSettings

/** HTTP statuses by their numbers and the layouts of time inside their strings. */
class GoValueCompletionTest : BasePlatformTestCase() {
    private var languageServer = true

    override fun setUp() {
        super.setUp()
        languageServer = GoSettings.getInstance().languageServerEnabled
        GoSettings.getInstance().languageServerEnabled = false
    }

    override fun tearDown() {
        try {
            GoSettings.getInstance().languageServerEnabled = languageServer
        } finally {
            super.tearDown()
        }
    }

    private fun labels(): List<String> = myFixture.lookupElements.orEmpty().map { LookupElementPresentation.renderElement(it).itemText ?: it.lookupString }

    fun testTheStatusesThatBeginWithTheNumber() {
        assertEquals(listOf(400, 401, 402, 403, 404, 405, 406, 407, 408, 409), GoHttpStatuses.matching("\tw.WriteHeader(40", "40"))
        assertEquals(listOf(404), GoHttpStatuses.matching("\tif resp.StatusCode == 404 {", "404"))
        assertEquals(listOf(500, 501, 502, 503, 504, 505, 506, 507, 508, 510, 511), GoHttpStatuses.matching("\thttp.Error(w, err.Error(), 5)", "5"))
        assertEquals(listOf(201), GoHttpStatuses.matching("\tcase 201:", "201"))
        assertEquals(listOf(429), GoHttpStatuses.matching("\tc.JSON(429, gin.H{})", "429"))
        assertTrue("not a status place", GoHttpStatuses.matching("\tx := 404", "404").isEmpty())
        assertTrue("not a number", GoHttpStatuses.matching("\tw.WriteHeader(ab", "ab").isEmpty())
        assertTrue("too long", GoHttpStatuses.matching("\tw.WriteHeader(4040", "4040").isEmpty())
    }

    fun testTheStringOfALayoutCall() {
        val text = "\tt, err := time.Parse(\"2006-01\", s)\n\tfmt.Println(t.Format(\"15:04\"), other(\"x\"))\n"
        val parse = text.indexOf("2006")
        assertEquals(parse - 1, GoTimeLayouts.quoteBefore(text, parse))
        assertTrue(GoTimeLayouts.isLayoutString(text, parse - 1))
        val format = text.indexOf("15:04")
        assertTrue(GoTimeLayouts.isLayoutString(text, format - 1))
        val other = text.indexOf("x\"")
        assertFalse("not a call of time", GoTimeLayouts.isLayoutString(text, other - 1))
        assertNull("outside a string", GoTimeLayouts.quoteBefore(text, text.indexOf("s)")))
        assertEquals("01", GoTimeLayouts.partTyped(text, parse - 1, parse + 7))
        assertEquals("2006", GoTimeLayouts.partTyped(text, parse - 1, parse + 4))
    }

    fun testAStatusBecomesItsConstantWithTheImport() {
        myFixture.configureByText("h.go", "package main\n\nfunc handle(w ResponseWriter) {\n\tw.WriteHeader(40<caret>)\n}\n")
        myFixture.completeBasic()
        val labels = labels()
        assertTrue(labels.toString(), "http.StatusNotFound" in labels && "http.StatusBadRequest" in labels)
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { LookupElementPresentation.renderElement(it).itemText == "http.StatusNotFound" }
        myFixture.finishLookup('\n')
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("w.WriteHeader(http.StatusNotFound)") && text.contains("import \"net/http\""))
    }

    fun testTheLayoutsInsideTheString() {
        myFixture.configureByText("t.go", "package main\n\nimport \"time\"\n\nfunc parse(s string) {\n\ttime.Parse(\"20<caret>\", s)\n}\n")
        myFixture.completeBasic()
        val labels = labels()
        assertTrue(labels.toString(), "2006-01-02" in labels && "2006-01-02T15:04:05Z07:00" in labels && "2006" in labels)
        assertFalse("a layout that does not begin so", labels.any { it.startsWith("Mon") })
        assertFalse("a part that does not begin so", "Jan" in labels)
    }

    fun testNothingInAnOrdinaryString() {
        myFixture.configureByText("s.go", "package main\n\nfunc f() string {\n\treturn other(\"20<caret>\")\n}\n")
        myFixture.completeBasic()
        assertFalse(labels().toString(), "2006-01-02" in labels())
    }
}
