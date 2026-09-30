package io.github.golangsupport

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.settings.GoSettings

/** The items of [io.github.golangsupport.lang.GoKeywordTemplates] in the list, and the template they expand to when chosen. */
class GoKeywordCompletionTest : BasePlatformTestCase() {
    private var languageServer = true

    // a file opened in an editor starts gopls, where it is installed: not in a test
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

    fun testAStructTemplateIsExpandedInPlaceOfTheKeyword() {
        myFixture.configureByText("a.go", "package main\n\nty<caret>\n\nfunc main() {}\n")
        myFixture.completeBasic()
        val labels = labels()
        assertTrue(labels.toString(), "type Name struct {...}" in labels && "type (...)" in labels)
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { LookupElementPresentation.renderElement(it).itemText == "type Name struct {...}" }
        myFixture.finishLookup('\n')
        val text = myFixture.editor.document.text
        assertTrue(text, text.contains("package main\n\ntype ") && text.contains(" struct {\n\t\n}\n\nfunc main() {}"))
        assertFalse("the keyword is not left behind", text.contains("ty\n"))
    }

    fun testABodyOffersTheLoopOverTheSliceInSight() {
        myFixture.configureByText("b.go", "package main\n\nfunc run(items []string) {\n\tfo<caret>\n}\n")
        myFixture.completeBasic()
        val labels = labels()
        assertTrue(labels.toString(), "for i, item := range items {...}" in labels)
        assertFalse("a declaration is not a statement", "type Name struct {...}" in labels)
        myFixture.lookup.currentItem = myFixture.lookupElements!!.first { LookupElementPresentation.renderElement(it).itemText == "for i, item := range items {...}" }
        myFixture.finishLookup('\n')
        val text = myFixture.editor.document.text
        // the stops keep their defaults; the lines of the template are indented as the line of the caret was
        assertTrue(text, text.contains("func run(items []string) {\n\tfor _, item := range items {\n\t\t\n\t}\n}"))
    }

    fun testNothingInAComment() {
        myFixture.configureByText("c.go", "package main\n\n// ty<caret>\n")
        myFixture.completeBasic()
        assertFalse(labels().toString(), "type Name struct {...}" in labels())
    }
}
