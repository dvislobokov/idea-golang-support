package io.github.golangsupport

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoSyntaxErrorFilter
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoSettings

/**
 * The error elements of the parser are highlighted only when the parser is the source of the syntax errors (MIGRATION.md step 8a).
 * The highlighting pass runs with the language server off: on, the platform starts the real gopls of this machine for the opened file,
 * and its refresh restarts the daemon mid-pass (seen in this test); the cases with the server on ask the filter directly.
 */
class GoSyntaxErrorFilterTest : BasePlatformTestCase() {
    private var languageServer = true
    private var source = GoFeatureSource.GOPLS

    override fun setUp() {
        super.setUp()
        languageServer = GoSettings.getInstance().languageServerEnabled
        source = GoSettings.getInstance().syntaxErrorsSource
        GoSettings.getInstance().languageServerEnabled = false
    }

    override fun tearDown() {
        try {
            GoSettings.getInstance().languageServerEnabled = languageServer
            GoSettings.getInstance().syntaxErrorsSource = source
        } finally {
            super.tearDown()
        }
    }

    private fun errorElements(): Collection<PsiErrorElement> {
        myFixture.configureByText("main.go", "package main\nfunc main() {\n")
        return PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java).also { assertTrue("the parser reports the unclosed brace", it.isNotEmpty()) }
    }

    private fun shown(languageServer: Boolean, source: GoFeatureSource): Boolean {
        val errors = errorElements()
        GoSettings.getInstance().languageServerEnabled = languageServer
        GoSettings.getInstance().syntaxErrorsSource = source
        val filter = GoSyntaxErrorFilter()
        return errors.map { filter.shouldHighlightErrorElement(it) }.distinct().single() // one answer for all of them
    }

    fun testWithGoplsOnDutyTheErrorElementsAreHidden() = assertFalse(shown(languageServer = true, source = GoFeatureSource.GOPLS))

    fun testTheNativeSourceShowsTheErrorElements() = assertTrue(shown(languageServer = true, source = GoFeatureSource.NATIVE))

    fun testWithoutTheServerTheErrorElementsShowWhateverTheSwitch() {
        errorElements()
        GoSettings.getInstance().syntaxErrorsSource = GoFeatureSource.GOPLS
        assertTrue(myFixture.doHighlighting().any { it.severity == HighlightSeverity.ERROR })
    }

    fun testTheFilterIsRegisteredWithThePlatform() = assertTrue(HighlightErrorFilter.EP_NAME.getExtensions(project).any { it is GoSyntaxErrorFilter })
}
