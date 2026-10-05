package io.github.golangsupport.ide.intentions

import io.github.golangsupport.ide.GoSemanticIdeTestBase

/** The checks of the G4 intention tests: one intention by its text, applied or proven absent. */
abstract class GoIntentionTestSupport : GoSemanticIdeTestBase() {

    protected fun doTest(before: String, intention: String, after: String, fileName: String = "a.go") {
        myFixture.configureByText(fileName, before.trimIndent() + "\n")
        val offered = myFixture.availableIntentions
        myFixture.launchAction(offered.singleOrNull { it.text == intention } ?: error("$intention not in ${offered.map { it.text }}"))
        myFixture.checkResult(after.trimIndent() + "\n")
    }

    protected fun assertNotOffered(text: String, intention: String, fileName: String = "a.go") {
        myFixture.configureByText(fileName, text.trimIndent() + "\n")
        val offered = myFixture.availableIntentions.map { it.text }
        assertFalse("$intention in $offered", intention in offered)
    }

    protected fun assertOffered(text: String, intention: String, fileName: String = "a.go") {
        myFixture.configureByText(fileName, text.trimIndent() + "\n")
        val offered = myFixture.availableIntentions.map { it.text }
        assertTrue("$intention not in $offered", intention in offered)
    }
}
