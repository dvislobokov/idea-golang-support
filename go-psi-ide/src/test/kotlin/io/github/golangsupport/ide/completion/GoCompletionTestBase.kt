package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** Helpers of the completion tests: Go sources written with 4-space indents become tab-indented. */
abstract class GoCompletionTestBase : GoSemanticIdeTestBase() {

    /** [text] with `trimIndent`, each leading group of 4 spaces turned into a tab, and a final newline. */
    protected fun go(text: String): String = text.trimIndent().lines().joinToString("\n") { line ->
        val n = line.takeWhile { it == ' ' }.length
        "\t".repeat(n / 4) + " ".repeat(n % 4) + line.substring(n)
    } + "\n"

    /** Configures `main.go` (or [fileName]) with [text] and returns the lookup strings; fails when one item was inserted directly. */
    protected fun lookups(text: String, fileName: String = "main.go"): List<String> {
        myFixture.configureByText(fileName, go(text))
        val items = myFixture.completeBasic()
        assertNotNull("a single candidate was inserted directly:\n${myFixture.editor.document.text}", items)
        return myFixture.lookupElementStrings.orEmpty()
    }

    /** Configures and completes; returns the lookup elements, or null when a single item was inserted. */
    protected fun complete(text: String, fileName: String = "main.go"): Array<LookupElement>? {
        myFixture.configureByText(fileName, go(text))
        return myFixture.completeBasic()
    }

    /** Completes [before], selects the item [item] (unless it was inserted directly) and checks the result. */
    protected fun checkInsert(before: String, item: String?, after: String, fileName: String = "main.go") {
        myFixture.configureByText(fileName, go(before))
        val items = myFixture.completeBasic()
        if (items != null && item != null) select(item)
        myFixture.checkResult(go(after))
    }

    protected fun select(lookupString: String, completionChar: Char = Lookup.NORMAL_SELECT_CHAR) {
        val lookup = myFixture.lookup ?: return
        val element = lookup.items.firstOrNull { it.lookupString == lookupString }
            ?: error("no item '$lookupString' in ${lookup.items.map { it.lookupString }}")
        lookup.currentItem = element
        myFixture.finishLookup(completionChar)
    }

    protected fun presentation(lookupString: String): LookupElementPresentation {
        val element = myFixture.lookupElements?.firstOrNull { it.lookupString == lookupString }
            ?: error("no item '$lookupString' in ${myFixture.lookupElementStrings}")
        return LookupElementPresentation.renderElement(element)
    }

    protected fun assertContainsAll(actual: List<String>, vararg expected: String) {
        val missing = expected.filter { it !in actual }
        assertTrue("missing $missing in $actual", missing.isEmpty())
    }

    protected fun assertContainsNone(actual: List<String>, vararg unexpected: String) {
        val present = unexpected.filter { it in actual }
        assertTrue("unexpected $present in $actual", present.isEmpty())
    }
}
