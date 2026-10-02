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

/** The feature groups of go-psi-ide follow the switches of the plugin: the gate is the plugin's, one source answers at a time. */
class GoIdeFeatureGateTest : BasePlatformTestCase() {
    private val settings get() = GoSettings.getInstance()
    private var server = true
    private var navigation = GoFeatureSource.GOPLS
    private var usages = GoFeatureSource.GOPLS
    private var codeVision = GoFeatureSource.GOPLS

    override fun setUp() {
        super.setUp()
        server = settings.languageServerEnabled
        navigation = settings.navigationSource
        usages = settings.usagesSource
        codeVision = settings.codeVisionSource
    }

    override fun tearDown() {
        try {
            settings.languageServerEnabled = server
            settings.navigationSource = navigation
            settings.usagesSource = usages
            settings.codeVisionSource = codeVision
        } finally {
            super.tearDown()
        }
    }

    fun testThePluginsGateIsTheService() {
        assertTrue(GoIdeFeatureGate.getInstance().javaClass.name, GoIdeFeatureGate.getInstance() is GoIgsIdeFeatureGate)
    }

    fun testWithGoplsTheNativeNavigationStandsDown() {
        settings.languageServerEnabled = true
        settings.navigationSource = GoFeatureSource.GOPLS
        assertFalse(GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, project))
    }

    fun testWithTheBuiltInSourceTheNativeNavigationAnswers() {
        settings.languageServerEnabled = true
        settings.navigationSource = GoFeatureSource.NATIVE
        // the light project is in smart mode: a feature that needs the indexes is on
        assertTrue(GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, project))
    }

    fun testWithoutTheLanguageServerEverythingIsNative() {
        settings.languageServerEnabled = false
        for (feature in GoIdeFeature.entries) assertTrue(feature.name, GoIdeFeatureGate.enabled(feature, project))
    }

    fun testEachSwitchDrivesItsOwnGroup() {
        assertEquals(GoFeature.NAVIGATION, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.NAVIGATION))
        assertEquals(GoFeature.USAGES, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.USAGES))
        assertEquals(GoFeature.CODE_VISION, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.IMPLEMENTATION_MARKERS))
        assertEquals(GoFeature.COMPLETION, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.COMPLETION))
        assertEquals(GoFeature.HOVER, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.HOVER))
        assertEquals(GoFeature.DIAGNOSTICS, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.DIAGNOSTICS))
        assertEquals(GoFeature.SEMANTIC_COLORS, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.SEMANTIC_COLORS))
        assertEquals(GoFeature.RENAME, GoIgsIdeFeatureGate.featureOf(GoIdeFeature.RENAME))
        settings.languageServerEnabled = true
        settings.navigationSource = GoFeatureSource.NATIVE
        settings.usagesSource = GoFeatureSource.GOPLS
        settings.codeVisionSource = GoFeatureSource.GOPLS
        assertTrue(GoIdeFeatureGate.enabled(GoIdeFeature.NAVIGATION, project))
        assertFalse(GoIdeFeatureGate.enabled(GoIdeFeature.USAGES, project))
        assertFalse(GoIdeFeatureGate.enabled(GoIdeFeature.IMPLEMENTATION_MARKERS, project))
    }

    /** The platform takes one target element evaluator and one Go to Super handler per language: the PSI ones go first and defer when off. */
    fun testThePsiEvaluatorAndGotoSuperHandlerComeFirst() {
        assertInstanceOf(TargetElementUtilBase.TARGET_ELEMENT_EVALUATOR.forLanguage(GoLanguage), GoTargetElementEvaluator::class.java)
        assertInstanceOf(CodeInsightActions.GOTO_SUPER.forLanguage(GoLanguage), GoGotoSuperHandler::class.java)
        assertTrue(TargetElementUtilBase.TARGET_ELEMENT_EVALUATOR.allForLanguage(GoLanguage).size >= 2)
        assertTrue(CodeInsightActions.GOTO_SUPER.allForLanguage(GoLanguage).size >= 2)
    }
}
