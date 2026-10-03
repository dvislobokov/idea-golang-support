package io.github.golangsupport

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lsp.GoplsActionKinds
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoSettings

/**
 * The feature Code actions under the switch Language features (MIGRATION.md step 9): with Built-in the intentions of go-psi-ide answer and
 * the fill actions of gopls are hidden; with gopls it is the other way round.
 */
class GoCodeActionsSwitchTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var server = true
    private var source = GoFeatureSource.GOPLS

    override fun setUp() {
        super.setUp()
        server = settings.languageServerEnabled
        source = settings.languageFeaturesSource
    }

    override fun tearDown() {
        try {
            settings.languageServerEnabled = server
            settings.languageFeaturesSource = source
        } finally {
            super.tearDown()
        }
    }

    private fun use(source: GoFeatureSource) {
        settings.languageServerEnabled = true
        settings.languageFeaturesSource = source
    }

    fun testTheFillActionsOfGoplsAreHiddenWhenNative() {
        val titles = listOf(
            "refactor.rewrite.fillStruct" to "Fill Options",
            "refactor.rewrite.fillStruct" to "Fill anonymous struct",
            "refactor.rewrite.fillSwitch" to "Add cases for Color",
            "quickfix" to "Fill in return values",
            null to "Add cases for b.TypeB",
            "quickfix" to "Create function load",
            "quickfix" to "Create variable x",
            "quickfix" to "Declare missing methods of io.Writer",
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
}
