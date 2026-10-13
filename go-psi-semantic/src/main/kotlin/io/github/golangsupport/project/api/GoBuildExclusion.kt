package io.github.golangsupport.project.api

/**
 * Why the build of the project's context leaves a Go file out, as GoLand names it (docs/goland-analysis/dumps/excluded-files.txt):
 * "'mmap_arm64.go' is ignored by the build tool because of the architecture mismatch". GoLand checks such a file for syntax only and
 * offers to rename it (a name suffix) or to go to its build constraints, and to edit the build settings.
 */
class GoBuildExclusion(val reason: Reason, val byName: Boolean) {

    enum class Reason(val text: String) { OS("OS mismatch"), ARCHITECTURE("architecture mismatch"), CUSTOM_FLAG("custom flag") }

    /** The banner text of GoLand for [fileName]. */
    fun message(fileName: String): String = "'$fileName' is ignored by the build tool because of the ${reason.text}"

    companion object {
        /** Null when [name] / [content] build under [context]. */
        @JvmStatic
        fun of(name: String, content: CharSequence, context: GoBuildContext): GoBuildExclusion? {
            if (!name.endsWith(".go") || GoBuildConstraintEvaluator.matchFile(name, content, context)) return null
            if (!GoBuildConstraintEvaluator.goodOSArchFile(name, context)) {
                // `_linux_386` on windows/amd64: GoLand names the architecture
                val parts = name.removeSuffix(".go").removeSuffix("_test").split('_').drop(1)
                val arch = parts.lastOrNull()?.takeIf { it in GoPlatforms.KNOWN_ARCH }
                return GoBuildExclusion(if (arch != null && arch != context.goarch) Reason.ARCHITECTURE else Reason.OS, byName = true)
            }
            val builds = GoFileBuildContext.contextFor(name, content, context)
            val reason = when {
                builds == null -> Reason.CUSTOM_FLAG
                builds.goarch != context.goarch -> Reason.ARCHITECTURE
                builds.goos != context.goos -> Reason.OS
                else -> Reason.CUSTOM_FLAG
            }
            return GoBuildExclusion(reason, byName = false)
        }
    }
}
