package io.github.golangsupport

import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.lsp.GoplsDiagnosticsFilter
import io.github.golangsupport.settings.GoFeature
import io.github.golangsupport.settings.GoFeatureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One source per feature: gopls or the plugin, as set, with the two cases the setting cannot know about (MIGRATION.md, step 1). */
class GoFeaturesTest {
    private fun native(feature: GoFeature, source: GoFeatureSource, server: Boolean = true, dumb: Boolean = false) = GoFeatures.isNative(feature, source, server, dumb)

    @Test fun theSettingDecidesWithAServer() {
        for (feature in GoFeature.entries) {
            assertFalse(feature.name, native(feature, GoFeatureSource.GOPLS))
            assertTrue(feature.name, native(feature, GoFeatureSource.NATIVE))
        }
    }

    @Test fun withoutAServerThePluginIsAllThereIs() {
        for (feature in GoFeature.entries) for (source in GoFeatureSource.entries) for (dumb in listOf(false, true)) {
            assertTrue("$feature $source dumb=$dumb", native(feature, source, server = false, dumb = dumb))
        }
    }

    /** The native side of a feature that needs the indices has nothing to say while they are built: gopls answers then. */
    @Test fun whileIndexingGoplsAnswersForWhatNeedsIndices() {
        for (feature in GoFeature.entries) {
            assertEquals(feature.name, !feature.needsIndex, native(feature, GoFeatureSource.NATIVE, dumb = true))
            assertFalse(feature.name, native(feature, GoFeatureSource.GOPLS, dumb = true))
        }
        assertFalse(GoFeature.SYNTAX_ERRORS.needsIndex)
        assertTrue(GoFeature.NAVIGATION.needsIndex)
    }

    @Test fun theDefaultsAreGopls() {
        // the stored form of the enum stays English whatever the language of the page (the serializer reads it)
        assertEquals("gopls", GoFeatureSource.GOPLS.toString())
        assertEquals("Plugin", GoFeatureSource.NATIVE.toString())
    }

    @Test fun theDiagnosticsOfGoplsNextToANativeSource() {
        val none = GoplsDiagnosticsFilter(syntaxNative = false, diagnosticsNative = false)
        for (source in listOf("syntax", "compiler", "printf", "SA1019", "go list", null)) assertTrue(source.toString(), none.shown(source))

        val syntax = GoplsDiagnosticsFilter(syntaxNative = true, diagnosticsNative = false)
        assertFalse(syntax.shown("syntax"))
        assertTrue(syntax.shown("compiler"))
        assertTrue(syntax.shown("printf"))

        // the type checker reports the errors of the parser as well: both go when it is the source
        val diagnostics = GoplsDiagnosticsFilter(syntaxNative = false, diagnosticsNative = true)
        assertFalse(diagnostics.shown("syntax"))
        assertFalse(diagnostics.shown("compiler"))
        // the analyzers have no native side yet
        assertTrue(diagnostics.shown("printf"))
        assertTrue(diagnostics.shown("SA1019"))
        assertTrue(diagnostics.shown("go list"))
        assertTrue(diagnostics.shown(null))
    }
}
