package io.github.golangsupport.project.api

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/** A module path with an optional version (`none`-free; null version means "any" or "local"). */
data class GoModuleVersion(val path: String, val version: String?) {
    override fun toString(): String = if (version == null) path else "$path@$version"
}

/** A `require` directive. */
data class GoRequire(val path: String, val version: String, val indirect: Boolean)

/**
 * A `replace` directive: `old [oldVersion] => new [newVersion]`. When [newVersion] is null the
 * replacement is a local directory ([newPath], relative to the declaring file's directory).
 */
data class GoReplace(val oldPath: String, val oldVersion: String?, val newPath: String, val newVersion: String?) {
    val isLocal: Boolean get() = newVersion == null
}

/** A `retract` directive: a single version (`low == high`) or an interval, with its rationale. */
data class GoRetract(val low: String, val high: String, val rationale: String?)

/**
 * A module of a [GoModuleGraph] (an entry of the build list).
 *
 * @property path module path.
 * @property version selected version; null for main modules and workspace members.
 * @property dir directory with the module sources: the main module directory, a local
 *   replacement, the extracted module cache directory, or the vendor directory; null when the
 *   sources are not available locally (not downloaded).
 * @property goModFile the go.mod file that was read for this module, when any.
 * @property goVersion the `go` directive of its go.mod.
 * @property isMain whether this is a main module (the module itself or a workspace member).
 * @property isWorkspaceMember whether this main module comes from a go.work `use` directive.
 * @property isIndirect whether no main module requires it directly (without `// indirect`).
 * @property replacement the effective replacement (local: version null), if any.
 * @property requires, replaces, excludes, retracts, tools: directives of its go.mod (empty if unread).
 * @property deprecated the `// Deprecated:` message of its module directive.
 */
data class GoModule @JvmOverloads constructor(
    val path: String,
    val version: String?,
    val dir: Path?,
    val goModFile: Path?,
    val goVersion: String?,
    val isMain: Boolean,
    val isWorkspaceMember: Boolean = false,
    val isIndirect: Boolean = false,
    val replacement: GoModuleVersion? = null,
    val requires: List<GoRequire> = emptyList(),
    val replaces: List<GoReplace> = emptyList(),
    val excludes: List<GoModuleVersion> = emptyList(),
    val retracts: List<GoRetract> = emptyList(),
    val tools: List<String> = emptyList(),
    val deprecated: String? = null,
) {
    override fun toString(): String = if (version == null) path else "$path@$version"
}

/**
 * The module graph of a main module or workspace: the build list after minimal version selection
 * (or the vendored module list in vendor mode).
 *
 * @property mainModules the main module, or every workspace member.
 * @property modules the build list: main modules first, then the others sorted by path.
 * @property workFile the go.work file in workspace mode.
 * @property vendorMode whether packages are loaded from [vendorDir] (`vendor/modules.txt` present
 *   and consistent with go.mod, `go` >= 1.14).
 * @property missing modules whose go.mod was needed but is not in the module cache; the graph may be
 *   incomplete (run `go mod download`).
 * @property source how the graph was computed.
 */
class GoModuleGraph(
    val mainModules: List<GoModule>,
    val modules: List<GoModule>,
    val workFile: Path?,
    val vendorMode: Boolean,
    val vendorDir: Path?,
    val missing: List<GoModuleVersion>,
    val source: Source,
) {
    enum class Source {
        /** Pure resolution over go.mod files and the module cache. */
        PURE,

        /** `vendor/modules.txt`. */
        VENDOR,

        /** `go list -m -json all`. */
        GO_LIST,
    }

    private val byPath: Map<String, GoModule> = modules.associateBy { it.path }

    /** The build-list entry for [path]. */
    fun module(path: String): GoModule? = byPath[path]

    /** Modules whose path is a prefix of [importPath], longest first (package lookup order). */
    fun modulesForImportPath(importPath: String): List<GoModule> =
        modules.filter { importPath == it.path || importPath.startsWith(it.path + "/") }.sortedByDescending { it.path.length }

    override fun toString(): String = "GoModuleGraph(${mainModules.map { it.path }}, ${modules.size} modules, $source)"
}

/**
 * Computes module graphs. The default implementation caches results on the modification stamps of
 * go.mod, go.work, go.sum and vendor/modules.txt.
 */
interface GoModuleGraphProvider {
    /**
     * The graph for the module (or workspace) containing [fileOrDirectory]: a content root, a
     * directory, a go.mod or go.work file, or any file inside the module. Null outside modules.
     */
    fun graphFor(fileOrDirectory: VirtualFile): GoModuleGraph?

    companion object {
        @JvmStatic
        fun getInstance(project: Project): GoModuleGraphProvider = project.service()
    }
}
