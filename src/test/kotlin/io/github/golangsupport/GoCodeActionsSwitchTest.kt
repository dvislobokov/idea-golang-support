package io.github.golangsupport

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.GoAddMissingReturnIntention
import io.github.golangsupport.lang.GoCheckErrorIntention
import io.github.golangsupport.lang.GoHandleErrorIntention
import io.github.golangsupport.lsp.GoplsActionKinds
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoSettings

/**
 * The switch Code actions (MIGRATION.md step 9): with Built-in the intentions of go-psi-ide answer, the fill actions of gopls are
 * hidden and the text intentions of the plugin stand down; with gopls it is the other way round.
 */
class GoCodeActionsSwitchTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var server = true
    private var source = GoFeatureSource.GOPLS

    override fun setUp() {
        super.setUp()
        server = settings.languageServerEnabled
        source = settings.codeActionsSource
    }

    override fun tearDown() {
        try {
            settings.languageServerEnabled = server
            settings.codeActionsSource = source
        } finally {
            super.tearDown()
        }
    }

    private fun use(source: GoFeatureSource) {
        settings.languageServerEnabled = true
        settings.codeActionsSource = source
    }

    fun testTheFillActionsOfGoplsAreHiddenWhenNative() {
        val titles = listOf(
            "refactor.rewrite.fillStruct" to "Fill Options",
            "refactor.rewrite.fillStruct" to "Fill anonymous struct",
            "refactor.rewrite.fillSwitch" to "Add cases for Color",
            "quickfix" to "Fill in return values",
            null to "Add cases for b.TypeB",
        )
        for ((kind, title) in titles) {
            assertTrue("$kind $title", GoplsActionKinds.isNativeCodeAction(kind, title))
            assertTrue("$kind $title", GoplsActionKinds.isOffered(kind, title, emptyList(), nativeCodeActions = true))
            assertFalse("$kind $title", GoplsActionKinds.isOffered(kind, title, emptyList(), nativeCodeActions = false))
        }
        for ((kind, title) in listOf("refactor.extract.variable" to "Extract variable", "refactor.rewrite.invertIf" to "Invert if condition", "source.addTest" to "Add test for f")) {
            assertFalse("$kind $title", GoplsActionKinds.isNativeCodeAction(kind, title))
            assertFalse("$kind $title", GoplsActionKinds.isOffered(kind, title, emptyList(), nativeCodeActions = true))
        }
    }

    private fun available(intention: IntentionAction, text: String): Boolean {
        myFixture.configureByText("a.go", text)
        return intention.isAvailable(project, myFixture.editor, myFixture.file)
    }

    fun testTheTextIntentionsStandDownWhenNative() {
        val cases = listOf(
            GoHandleErrorIntention() to "package p\n\nimport \"os\"\n\nfunc f(p string) error {\n\tos.Remove(p)<caret>\n\treturn nil\n}\n",
            GoCheckErrorIntention() to "package p\n\nimport \"os\"\n\nfunc f(p string) error {\n\t_, err := os.Open(p)<caret>\n\treturn nil\n}\n",
            GoAddMissingReturnIntention() to "package p\n\nfunc f() (int, error) {\n\tx := 1<caret>\n\t_ = x\n}\n",
        )
        use(GoFeatureSource.GOPLS)
        assertFalse(GoIdeFeatureGate.enabled(GoIdeFeature.CODE_ACTIONS, project))
        for ((intention, text) in cases) assertTrue(intention.text, available(intention, text))
        use(GoFeatureSource.NATIVE)
        assertTrue(GoIdeFeatureGate.enabled(GoIdeFeature.CODE_ACTIONS, project))
        for ((intention, text) in cases) assertFalse(intention.text, available(intention, text))
    }
}
