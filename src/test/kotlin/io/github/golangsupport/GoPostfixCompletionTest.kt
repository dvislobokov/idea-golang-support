package io.github.golangsupport

import com.intellij.codeInsight.template.impl.LiveTemplateCompletionContributor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Postfix keys in the general completion list after `.`, as in GoLand: behind the members, filtered by the type of the expression.
 * The platform's live template contributor adds them; outside tests it always runs, here it has to be switched on.
 */
class GoPostfixCompletionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        LiveTemplateCompletionContributor.setShowTemplatesInTests(true, testRootDisposable)
    }

    private val declarations = "type Circle struct{ Radius float64 }\n\nfunc (c Circle) Area() float64 { return 0 }\n\ntype Square struct{ Side float64 }\n"

    /** The lookup strings after `.` at `<caret>` in a function with the parameters [signature], in the order of the list. */
    private fun items(signature: String, line: String, imports: String = ""): List<String> {
        myFixture.configureByText("a.go", "package a\n\n$imports$declarations\nfunc f($signature) {\n\t$line\n}\n")
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun postfix(items: List<String>) = items.filter { it.startsWith(".") }

    fun testPostfixKeysComeAfterTheMembers() {
        val items = items("c Circle", "c.<caret>")
        val firstPostfix = items.indexOfFirst { it.startsWith(".") }
        assertTrue(items.toString(), firstPostfix > 0 && items.indexOf("Area") in 0 until firstPostfix && items.indexOf("Radius") in 0 until firstPostfix)
        assertTrue(items.toString(), listOf(".p", ".pointer", ".panic", ".par", ".print", ".println", ".return", ".var").all { it in items })
    }

    fun testTheKeysAreFilteredByTheType() {
        val value = postfix(items("c Circle", "c.<caret>"))
        assertFalse("a struct is no bool, no pointer, no error", value.any { it in listOf(".if", ".d", ".nn", ".as", ".is") })
        val pointer = postfix(items("sq *Square", "sq.<caret>"))
        assertTrue(pointer.toString(), listOf(".d", ".dereference", ".nil", ".nn", ".notnil").all { it in pointer })
        val error = postfix(items("err error", "err.<caret>"))
        assertTrue(error.toString(), listOf(".as", ".is", ".nil", ".nn", ".notnil").all { it in error })
        val number = postfix(items("n int", "n.<caret>"))
        assertFalse(number.toString(), number.any { it in listOf(".as", ".is", ".nn", ".nil", ".wrap", ".d") })
    }

    fun testAPackageGetsItsMembersOnly() {
        val items = items("", "fmt.<caret>", "import \"fmt\"\n\n")
        assertTrue(items.toString(), postfix(items).isEmpty())
    }

    fun testSymbolKeysAreNotInTheListAfterTheDot() {
        val items = items("ok bool", "ok.<caret>")
        assertTrue(items.toString(), ".not" in items && "!" !in items && ".!" !in items)
    }
}
