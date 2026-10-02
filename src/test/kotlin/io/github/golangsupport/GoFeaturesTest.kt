package io.github.golangsupport

import io.github.golangsupport.lang.GoFeature
import io.github.golangsupport.lang.GoFeatures
import io.github.golangsupport.settings.GoFeatureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoFeaturesTest {
    @Test fun withoutTheServerEverythingIsNative() {
        for (feature in GoFeature.entries) for (source in GoFeatureSource.entries) for (dumb in listOf(false, true)) {
            assertTrue("$feature $source dumb=$dumb", GoFeatures.native(feature, source, languageServerEnabled = false, dumb = dumb))
        }
    }

    @Test fun goplsSourceIsNotNative() {
        for (feature in GoFeature.entries) for (dumb in listOf(false, true)) {
            assertFalse("$feature dumb=$dumb", GoFeatures.native(feature, GoFeatureSource.GOPLS, languageServerEnabled = true, dumb = dumb))
        }
    }

    @Test fun whileIndexingOnlyWhatNeedsNoIndexesIsNative() {
        val native = GoFeature.entries.filter { GoFeatures.native(it, GoFeatureSource.NATIVE, languageServerEnabled = true, dumb = true) }
        assertEquals(listOf(GoFeature.SYNTAX_ERRORS, GoFeature.FORMATTING), native)
    }

    @Test fun nativeSourceInSmartModeIsNative() {
        for (feature in GoFeature.entries) assertTrue("$feature", GoFeatures.native(feature, GoFeatureSource.NATIVE, languageServerEnabled = true, dumb = false))
    }

    @Test fun settingsFileKeepsEnglishNames() {
        assertEquals("gopls", GoFeatureSource.GOPLS.toString())
        assertEquals("Built-in", GoFeatureSource.NATIVE.toString())
    }
}
