package io.github.golangsupport.project.impl

import io.github.golangsupport.project.api.GoModuleVersion
import io.github.golangsupport.project.api.GoRequire
import io.github.golangsupport.project.api.GoVersion
import org.jetbrains.annotations.ApiStatus

/**
 * Minimal version selection (research.swtch.com/vgo-mvs, go.dev/ref/mod#minimal-version-selection)
 * with module graph pruning (go.dev/ref/mod#graph-pruning), following
 * `cmd/go/internal/modload.readModGraph`:
 *
 * - The roots are the requirements of the main module(s).
 * - A module whose go.mod says `go 1.17` or later is *pruned*: when it is reached through a pruned
 *   path, its requirements become graph edges but are not loaded further.
 * - A module below 1.17 (or without a `go` directive) is *unpruned*: its full transitive
 *   requirement graph is loaded, and everything below it is loaded unpruned too.
 * - A main module below 1.17 loads the whole graph (classic MVS).
 * - Requirements on excluded versions are ignored (Go 1.16+ semantics); requirements on a main
 *   module path are ignored (the main module is always selected).
 *
 * The selected version of each path is the maximum over all versions appearing in the graph.
 * For pruned main modules, a root whose selected version is higher than the version the main
 * module lists is raised to the selected version and the graph is recomputed until a fixed point
 * (the requirements `go mod tidy` / `-mod=mod` would record; `go list` with `-mod=readonly`
 * rejects such an untidy go.mod instead).
 *
 * [loader] returns the go.mod summary of a module version, applying replacements; null means the
 * go.mod is not available (reported in [Result.missing]).
 */
@ApiStatus.Internal
class Mvs(private val loader: (GoModuleVersion) -> Summary?) {

    /** The parts of a go.mod that MVS needs. */
    data class Summary(val goVersion: String?, val requires: List<GoRequire>)

    /** A main module: its path, `go` version and requirements. */
    data class MainModule(val path: String, val goVersion: String?, val requires: List<GoRequire>)

    class Result(
        /** Selected version per non-main module path. */
        val selected: Map<String, String>,
        /** Summaries of every module version that was loaded. */
        val loaded: Map<GoModuleVersion, Summary>,
        /** Module versions whose go.mod could not be loaded. */
        val missing: Set<GoModuleVersion>,
    )

    /**
     * @param workspace workspace mode: the members' go.mod files are not tidied, so roots are
     *   never raised.
     */
    fun buildList(mains: List<MainModule>, excludes: Set<GoModuleVersion> = emptySet(), workspace: Boolean = false): Result {
        val summaries = HashMap<GoModuleVersion, Summary?>()
        var roots = mains
        while (true) {
            val result = buildOnce(roots, excludes, summaries)
            if (workspace) return result
            // Roots of pruned main modules are raised to their selected versions (what `go mod tidy`
            // or -mod=mod records), and the graph is recomputed from the updated roots.
            var changed = false
            roots = roots.map { main ->
                if (!isPruned(main.goVersion)) return@map main
                val raised = main.requires.map { r ->
                    val sel = result.selected[r.path]
                    if (sel != null && r.version != "none" && SemVer.compare(sel, r.version) > 0) {
                        changed = true
                        r.copy(version = sel)
                    } else r
                }
                main.copy(requires = raised)
            }
            if (!changed) return result
        }
    }

    private fun buildOnce(mains: List<MainModule>, excludes: Set<GoModuleVersion>, summaries: HashMap<GoModuleVersion, Summary?>): Result {
        val mainPaths = mains.map { it.path }.toSet()
        val selected = LinkedHashMap<String, String>()
        val missing = LinkedHashSet<GoModuleVersion>()
        val enqueuedPruned = HashSet<GoModuleVersion>()
        val enqueuedUnpruned = HashSet<GoModuleVersion>()
        val used = LinkedHashMap<GoModuleVersion, Summary>()
        val queue = ArrayDeque<Pair<GoModuleVersion, Boolean>>()

        fun usable(r: GoRequire): Boolean =
            r.path !in mainPaths && r.version != "none" && GoModuleVersion(r.path, r.version) !in excludes

        fun note(r: GoRequire) {
            val current = selected[r.path]
            selected[r.path] = if (current == null) r.version else SemVer.max(current, r.version)
        }

        fun enqueue(m: GoModuleVersion, unpruned: Boolean) {
            val added = if (unpruned) enqueuedUnpruned.add(m) else enqueuedPruned.add(m)
            if (added) queue.addLast(m to unpruned)
        }

        for (main in mains) {
            val unpruned = !isPruned(main.goVersion)
            for (r in main.requires.filter(::usable)) {
                note(r)
                enqueue(GoModuleVersion(r.path, r.version), unpruned)
            }
        }
        while (queue.isNotEmpty()) {
            val (m, unprunedMode) = queue.removeFirst()
            val summary = summaries.getOrPut(m) { loader(m) }
            if (summary == null) {
                missing += m
                continue
            }
            used[m] = summary
            val reqs = summary.requires.filter(::usable)
            reqs.forEach(::note)
            if (unprunedMode || !isPruned(summary.goVersion)) {
                reqs.forEach { enqueue(GoModuleVersion(it.path, it.version), unpruned = true) }
            }
        }
        return Result(selected, used, missing)
    }

    companion object {
        private val PRUNING_VERSION = GoVersion("1.17")

        /** Whether a go.mod with this `go` directive supports graph pruning (>= 1.17). */
        @JvmStatic
        fun isPruned(goVersion: String?): Boolean {
            val v = goVersion?.let { GoVersion.parse(it) } ?: return false
            return v >= PRUNING_VERSION
        }
    }
}
