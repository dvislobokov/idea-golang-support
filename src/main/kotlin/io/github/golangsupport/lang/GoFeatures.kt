package io.github.golangsupport.lang

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import io.github.golangsupport.settings.GoFeatureSource
import io.github.golangsupport.settings.GoSettings

/** A feature gopls serves today and the plugin's own PSI is to serve instead; [needsIndexes]: the native one is blind while the IDE indexes. */
enum class GoFeature(val needsIndexes: Boolean) {
    SYNTAX_ERRORS(false),
    DIAGNOSTICS(true),
    COMPLETION(true),
    HOVER(true),
    NAVIGATION(true),
    USAGES(true),
    RENAME(true),
    SEMANTIC_COLORS(true),
    CODE_VISION(true),
    /** Alt+Enter actions that rewrite code by its types: fill struct, fill returns, fill switch, handle error. */
    CODE_ACTIONS(true),
    FORMATTING(false),
}

/**
 * One source per feature at a time: a gopls handler stands down when [native] is true, a native one when it is false.
 * Exclusive switches rather than the order of extensions, so two answers never meet.
 */
object GoFeatures {
    /**
     * Pure rule, for the tests. Without the language server whatever is built in works, there is nothing else.
     * With it, the switch decides; but while the IDE indexes a native feature that needs the indexes yields to gopls, which answers anyway.
     */
    fun native(feature: GoFeature, source: GoFeatureSource, languageServerEnabled: Boolean, dumb: Boolean): Boolean = when {
        !languageServerEnabled -> true
        source != GoFeatureSource.NATIVE -> false
        else -> !(feature.needsIndexes && dumb)
    }

    /** Settings and dumb mode only, no PSI: safe on the EDT and in the background without a read action. */
    fun native(feature: GoFeature, project: Project): Boolean = GoSettings.getInstance().let {
        native(feature, it.featureSource(feature), it.languageServerEnabled, feature.needsIndexes && DumbService.isDumb(project))
    }

    /** The settings alone, whatever the IDE is doing now: for what is decided once, like the customizers of the gopls descriptor. */
    fun configuredNative(feature: GoFeature): Boolean = GoSettings.getInstance().let { native(feature, it.featureSource(feature), it.languageServerEnabled, dumb = false) }
}
