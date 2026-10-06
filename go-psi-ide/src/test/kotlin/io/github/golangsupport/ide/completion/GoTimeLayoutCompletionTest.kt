package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.util.ThreeState

/** GoLand's time layout rows in the layout string of the `time` calls ([GoTimeLayoutProvider]; dump probe C5). */
class GoTimeLayoutCompletionTest : GoCompletionTestBase() {

    private fun rows(): List<Pair<String, String?>> = myFixture.lookupElements.orEmpty().map { e ->
        LookupElementPresentation.renderElement(e).let { (it.itemText ?: e.lookupString) to it.typeText }
    }

    private fun format(arg: String) = """
        package main

        import "time"

        func f(t time.Time) string {
            return t.Format($arg)
        }
    """

    fun testGolandRowsInTheFormatString() {
        complete(format("\"<caret>\""))
        val rows = rows()
        assertEquals(
            listOf("YY", "YYYY", "MM", "DD", "hh", "mm", "ss", "year...", "month...", "day...", "hour...", "minute...", "second...", "zone..."),
            rows.map { it.first },
        )
        assertEquals("YYYY" to "Year (four-digit)", rows[1])
        assertEquals("hh" to "24-hour (zero-padded)", rows[4])
    }

    fun testAnAliasWritesTheReferenceElement() = checkInsert(format("\"2006-<caret>\""), "MM", format("\"2006-01<caret>\""))

    fun testATypedAliasIsReplaced() = checkInsert(format("\"YYY<caret>\""), "YYYY", format("\"2006<caret>\""))

    fun testAGroupReopensTheListWithItsElements() {
        complete(format("\"<caret>\""))
        select("month...")
        myFixture.checkResult(go(format("\"<caret>\"")))
        // the platform reopens the list itself (scheduleAutoPopup); here the next completion at the same caret stands for it
        myFixture.completeBasic()
        assertEquals(listOf("January", "Jan", "01", "1"), rows().map { it.first })
        select("Jan")
        myFixture.checkResult(go(format("\"Jan<caret>\"")))
    }

    fun testParseAppendFormatAndParseInLocation() {
        val parse = lookups("package main\n\nimport \"time\"\n\nfunc f(s string) {\n    time.Parse(\"<caret>\", s)\n}")
        assertContainsAll(parse, "YYYY", "MM", "year...")
        val append = lookups("package main\n\nimport \"time\"\n\nfunc f(t time.Time) []byte {\n    return t.AppendFormat(nil, \"<caret>\")\n}")
        assertContainsAll(append, "YYYY", "zone...")
        val location = lookups("package main\n\nimport \"time\"\n\nfunc f(s string) {\n    time.ParseInLocation(\"<caret>\", s, time.UTC)\n}")
        assertContainsAll(location, "hh", "second...")
    }

    fun testNothingInOtherStrings() {
        val other = complete("package main\n\nimport \"time\"\n\nfunc other(s string) {}\n\nfunc f(s string) {\n    other(\"<caret>\")\n    time.Parse(\"2006\", s)\n}")
        assertTrue(other.orEmpty().none { it.lookupString == "year..." })
        // the value of Parse is not the layout
        val value = complete("package main\n\nimport \"time\"\n\nfunc f() {\n    time.Parse(\"2006\", \"<caret>\")\n}")
        assertTrue(value.orEmpty().none { it.lookupString == "year..." })
    }

    fun testConfidenceLetsTheLayoutPopUp() {
        myFixture.configureByText("main.go", go(
            """
            package main

            import "time"

            func other(s string) {}

            func f(t time.Time) {
                _ = t.Format("x")
                other("y")
            }
            """,
        ))
        val text = myFixture.file.text
        val confidence = GoCompletionConfidence()
        fun at(offset: Int) = confidence.shouldSkipAutopopup(myFixture.editor, myFixture.file.findElementAt(offset - 1)!!, myFixture.file, offset)
        assertEquals(ThreeState.NO, at(text.indexOf("x\")") + 1))
        assertEquals(ThreeState.YES, at(text.indexOf("y\")") + 1))
    }

    fun testPrefix() {
        assertEquals("YY", GoTimeLayoutCompletion.prefix("2006-YY"))
        assertEquals("", GoTimeLayoutCompletion.prefix("2006-"))
        assertEquals("20", GoTimeLayoutCompletion.prefix("20"))
    }
}
