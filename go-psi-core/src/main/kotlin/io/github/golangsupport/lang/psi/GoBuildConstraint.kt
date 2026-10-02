package io.github.golangsupport.lang.psi

/**
 * The raw build constraint of a file header: the expression after `//go:build` and the
 * `// +build` lines (the text after `//`, trimmed). Evaluation belongs to the project model.
 */
data class GoBuildConstraint(val goBuild: String?, val plusBuild: List<String>) {
    val isEmpty: Boolean get() = goBuild == null && plusBuild.isEmpty()
}
