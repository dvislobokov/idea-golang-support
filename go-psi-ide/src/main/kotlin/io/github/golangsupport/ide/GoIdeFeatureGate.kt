package io.github.golangsupport.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/** A group of IDE features of go-psi-ide a host may switch off as a whole, when another source (a language server) serves it instead. */
enum class GoIdeFeature {
    /** Go to Type Declaration / Implementation / Super, the target element of the caret. */
    NAVIGATION,
    /** Find Usages, reference search, usage types, read/write access, exit points. */
    USAGES,
    /** The implementation gutter markers. */
    IMPLEMENTATION_MARKERS,
    /** Code completion: the contributor and its confidence. */
    COMPLETION,
    /** Quick documentation, parameter info, expression type. */
    HOVER,
    /** The inspections over the semantic check, with their quick fixes, and the import optimizer. */
    DIAGNOSTICS,
    /** The semantic highlighting annotator (colours by resolve). */
    SEMANTIC_COLORS,
}

/**
 * Application service that tells whether a feature group of go-psi-ide answers in [Project] now. In a standalone go-psi everything
 * is on ([DefaultGoIdeFeatureGate]); the host plugin overrides the service with its feature switches (one source per feature at a
 * time: the extensions registered by `go-psi-ide-navigation.xml` ask the gate first and stand down when their group is off).
 */
interface GoIdeFeatureGate {
    fun enabled(feature: GoIdeFeature, project: Project): Boolean

    companion object {
        fun getInstance(): GoIdeFeatureGate = ApplicationManager.getApplication().getService(GoIdeFeatureGate::class.java)

        fun enabled(feature: GoIdeFeature, project: Project): Boolean = getInstance().enabled(feature, project)
    }
}

/** Everything on: the default of a go-psi without a host. */
class DefaultGoIdeFeatureGate : GoIdeFeatureGate {
    override fun enabled(feature: GoIdeFeature, project: Project): Boolean = true
}
