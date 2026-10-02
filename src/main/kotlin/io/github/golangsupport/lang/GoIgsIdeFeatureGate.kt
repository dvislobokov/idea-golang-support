package io.github.golangsupport.lang

import com.intellij.openapi.project.Project
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate

/**
 * The feature groups of go-psi-ide follow the switches of Settings | Tools | Go | Language Server ([GoFeatures]): the navigation group the
 * Navigation switch, the usages group the Usages switch, the implementation gutter markers the Code vision switch, the documentation group
 * (quick documentation, parameter info, type info) the Documentation switch, the rename group the Rename switch (MIGRATION.md steps 8c, 8f, 8h, 8i).
 * Replaces `DefaultGoIdeFeatureGate` of go-psi (the service is overridden in plugin.xml). While the IDE indexes, a native group that needs
 * the indexes is off and gopls answers.
 */
class GoIgsIdeFeatureGate : GoIdeFeatureGate {
    override fun enabled(feature: GoIdeFeature, project: Project): Boolean = GoFeatures.native(featureOf(feature), project)

    companion object {
        fun featureOf(feature: GoIdeFeature): GoFeature = when (feature) {
            GoIdeFeature.NAVIGATION -> GoFeature.NAVIGATION
            GoIdeFeature.USAGES -> GoFeature.USAGES
            GoIdeFeature.IMPLEMENTATION_MARKERS -> GoFeature.CODE_VISION
            GoIdeFeature.COMPLETION -> GoFeature.COMPLETION
            GoIdeFeature.HOVER -> GoFeature.HOVER
            GoIdeFeature.DIAGNOSTICS -> GoFeature.DIAGNOSTICS
            GoIdeFeature.SEMANTIC_COLORS -> GoFeature.SEMANTIC_COLORS
            GoIdeFeature.CODE_ACTIONS -> GoFeature.CODE_ACTIONS
            GoIdeFeature.RENAME -> GoFeature.RENAME
            GoIdeFeature.INLAY_HINTS -> GoFeature.INLAY_HINTS
        }
    }
}
