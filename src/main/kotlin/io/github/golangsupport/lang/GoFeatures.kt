package io.github.golangsupport.lang

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.golangsupport.settings.GoFeature
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoLanguageServerControl
import io.github.golangsupport.settings.GoSettings

/**
 * Who answers for a feature right now: the plugin (the native PSI) or gopls. The setting ([GoSettings.source]) says who is meant to;
 * this adds what the setting cannot know: without a language server (switched off, or no LSP module in this IDE) the plugin is the
 * source of everything, since nothing else is there; and while the indices are being built the native side of a feature that needs
 * them has nothing to say, so gopls keeps answering until they are ready.
 *
 * The two sides read the same answer and are exclusive: a gopls handler returns nothing when [native] is true, a native contributor
 * returns at once when it is false. Ordering of extensions is not a way to deduplicate (MIGRATION.md, step 1).
 */
object GoFeatures {
    /** The plugin is the source of [feature] in [project] now. Cheap and thread-agnostic: asked on EDT, in read actions and in background searches. */
    fun native(feature: GoFeature, project: Project): Boolean {
        val settings = GoSettings.getInstance()
        return isNative(feature, settings.source(feature), serverAvailable(settings), DumbService.isDumb(project))
    }

    /** The source of [feature] as set, regardless of indices: for what is decided once per start of the server (the customizers of its descriptor). */
    fun source(feature: GoFeature): GoFeatureSource {
        val settings = GoSettings.getInstance()
        return if (serverAvailable(settings)) settings.source(feature) else GoFeatureSource.NATIVE
    }

    /** A language server can answer at all: the LSP part of the plugin is loaded (it is a content module, absent in some IDEs) and switched on. */
    fun serverAvailable(settings: GoSettings = GoSettings.getInstance()): Boolean =
        settings.languageServerEnabled && GoLanguageServerControl.EP.extensionList.isNotEmpty()

    /** The decision itself, pure: without a server the plugin is all there is; with one, the setting decides, except while indexing for a feature that needs indices. */
    fun isNative(feature: GoFeature, source: GoFeatureSource, serverAvailable: Boolean, dumb: Boolean): Boolean =
        !serverAvailable || (source == GoFeatureSource.NATIVE && !(feature.needsIndex && dumb))
}
