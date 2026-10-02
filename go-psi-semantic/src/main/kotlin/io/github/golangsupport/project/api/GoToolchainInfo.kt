package io.github.golangsupport.project.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * A Go toolchain as seen by the project model: where the standard library and the module cache
 * live and which build configuration applies. Plain data so that other implementations (for
 * example a plugin with its own SDK settings) can construct it.
 *
 * @property goroot `GOROOT`, null when no toolchain was found.
 * @property version the toolchain version from `$GOROOT/VERSION` or `go env GOVERSION`.
 * @property gopath `GOPATH` entries (first one hosts the default module cache).
 * @property gomodcache `GOMODCACHE`, defaults to `$GOPATH[0]/pkg/mod`.
 * @property goos `GOOS`.
 * @property goarch `GOARCH`.
 * @property cgoEnabled `CGO_ENABLED`.
 * @property buildTags extra build tags (for example from `GOFLAGS=-tags=...` or settings).
 * @property env the environment the values were derived from (`go env -json` output or a subset of
 *   the process environment); informational.
 * @property goBinary the `go` executable, null when none was found (pure mode only).
 */
data class GoToolchainInfo @JvmOverloads constructor(
    val goroot: Path?,
    val version: GoVersion?,
    val gopath: List<Path>,
    val gomodcache: Path?,
    val goos: String,
    val goarch: String,
    val cgoEnabled: Boolean,
    val buildTags: Set<String> = emptySet(),
    val env: Map<String, String> = emptyMap(),
    val goBinary: Path? = null,
) {
    /** `$GOROOT/src`, null without a GOROOT. */
    val gorootSrc: Path? get() = goroot?.resolve("src")

    /** The build context for constraint evaluation derived from this toolchain. */
    val buildContext: GoBuildContext
        get() = GoBuildContext(
            goos = goos,
            goarch = goarch,
            cgoEnabled = cgoEnabled,
            goVersion = version,
            buildTags = buildTags,
            toolTags = toolTags(),
        )

    /** `goexperiment.*` from `GOEXPERIMENT` and the architecture feature level (`amd64.v1` ...). */
    private fun toolTags(): Set<String> {
        val tags = LinkedHashSet<String>()
        env["GOEXPERIMENT"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("no") }
            ?.forEach { tags += "goexperiment.$it" }
        val level = when (goarch) {
            "amd64" -> (env["GOAMD64"] ?: "v1").removePrefix("v").toIntOrNull()?.let { n -> (1..n).map { "amd64.v$it" } }
            "arm64" -> env["GOARM64"]?.let { listOf("arm64.$it") }
            else -> null
        }
        level?.let { tags += it }
        return tags
    }
}

/**
 * Supplies the Go toolchain for a project. The default implementation is
 * `io.github.golangsupport.project.impl.DefaultGoToolchainProvider`; a host plugin may override
 * the application service (`overrides="true"`) to plug in its own SDK settings.
 *
 * Implementations must be fast and must never run external processes on the EDT: they return the
 * best information available now and refresh in the background.
 */
interface GoToolchainProvider {
    /** The toolchain for [project] (application default when null); null when none is known. */
    fun toolchainFor(project: Project?): GoToolchainInfo?

    companion object {
        @JvmStatic
        fun getInstance(): GoToolchainProvider = service()
    }
}
