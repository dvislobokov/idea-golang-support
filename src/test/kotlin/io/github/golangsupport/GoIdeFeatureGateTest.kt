package io.github.golangsupport

import com.intellij.codeInsight.TargetElementUtilBase
import com.intellij.lang.CodeInsightActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.navigation.GoGotoSuperHandler
import io.github.golangsupport.ide.navigation.GoTargetElementEvaluator
import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoIgsIdeFeatureGate
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoSettings

/** The feature groups of go-psi-ide follow the switch of the plugin: the gate is the plugin's, one source answers at a time. */
class GoIdeFeatureGateTest : BasePlatformTestCase() {
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

    fun testThePluginsGateIsTheService() {
        assertTrue(GoIdeFeatureGate.getInstance().javaClass.name, GoIdeFeatureGate.getInstance() is GoIgsIdeFeatureGate)
    }

    fun testWithGoplsTheNativeNavigationStandsDown() {
        settings.languageServerEnabled = true
        settings.languageFeaturesSource = GoFeatureSource.GOPLS
        assertFalse(GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, project))
    }

    fun testWithTheBuiltInSourceTheNativeNavigationAnswers() {
        settings.languageServerEnabled = true
        settings.languageFeaturesSource = GoFeatureSource.NATIVE
        // the light project is in smart mode: a feature that needs the indexes is on
        assertTrue(GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, project))
    }

    fun testWithoutTheLanguageServerEverythingIsNative() {
        settings.languageServerEnabled = false
        for (feature in GoIdeFeature.entries) assertTrue(feature.name, GoIdeFeatureGate.enabled(feature, project))
    }

    /** Each group of go-psi-ide maps to its own feature; the one switch of the page drives all of them together. */
    fun testTheOneSwitchDrivesEveryGroup() {
        assertEquals(GoFeature.NAVIGATION, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.NAVIGATION))
        assertEquals(GoFeature.USAGES, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.USAGES))
        assertEquals(GoFeature.CODE_VISION, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.IMPLEMENTATION_MARKERS))
        assertEquals(GoFeature.COMPLETION, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.COMPLETION))
        assertEquals(GoFeature.HOVER, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.HOVER))
        assertEquals(GoFeature.DIAGNOSTICS, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.DIAGNOSTICS))
        assertEquals(GoFeature.SEMANTIC_COLORS, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.SEMANTIC_COLORS))
        assertEquals(GoFeature.CODE_ACTIONS, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.CODE_ACTIONS))
        assertEquals(GoFeature.RENAME, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.RENAME))
        assertEquals(GoFeature.INLAY_HINTS, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.INLAY_HINTS))
        settings.languageServerEnabled = true
        settings.languageFeaturesSource = GoFeatureSource.NATIVE
        // the light project is in smart mode: the groups that need the indexes are on too
        for (feature in GoIdeFeature.entries) assertTrue(feature.name, GoIdeFeatureGate.enabled(feature, project))
        settings.languageFeaturesSource = GoFeatureSource.GOPLS
        for (feature in GoIdeFeature.entries) assertFalse(feature.name, GoIdeFeatureGate.enabled(feature, project))
        for (feature in GoFeature.entries.filter { it != GoFeature.FORMATTING }) assertEquals(feature.name, GoFeatureSource.GOPLS, settings.featureSource(feature))
    }

    /** The platform takes one target element evaluator and one Go to Super handler per language: the PSI ones go first and defer when off. */
    fun testThePsiEvaluatorAndGotoSuperHandlerComeFirst() {
        assertInstanceOf(TargetElementUtilBase.TARGET_ELEMENT_EVALUATOR.forLanguage(GoLanguage), GoTargetElementEvaluator::class.java)
        assertInstanceOf(CodeInsightActions.GOTO_SUPER.forLanguage(GoLanguage), GoGotoSuperHandler::class.java)
        assertTrue(TargetElementUtilBase.TARGET_ELEMENT_EVALUATOR.allForLanguage(GoLanguage).size >= 2)
        assertTrue(CodeInsightActions.GOTO_SUPER.allForLanguage(GoLanguage).size >= 2)
    }
}
