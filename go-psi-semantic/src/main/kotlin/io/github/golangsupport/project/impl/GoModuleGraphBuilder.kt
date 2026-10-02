package io.github.golangsupport.project.impl

import io.github.golangsupport.project.api.GoModule
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleVersion
import io.github.golangsupport.project.api.GoReplace
import io.github.golangsupport.project.api.GoVersion
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure (no `go` binary) module graph computation for a directory: locates go.mod / go.work,
 * applies workspace, replace and exclude rules, detects vendor mode, and runs [Mvs] over the
 * module cache.
 *
 * @param cache the module cache; null disables MVS over the cache (every dependency is missing).
 * @param env `GOWORK` (`off` or a file path) and `GOFLAGS` (`-mod=mod|vendor|readonly`) are honoured.
 * @param readText reads main-module files (go.mod, go.work, modules.txt); defaults to the disk.
 *   Module cache files are always read from disk.
 */
@ApiStatus.Internal
class GoModuleGraphBuilder(
    private val cache: GoModuleCacheLayout?,
    private val env: Map<String, String> = emptyMap(),
    private val readText: (Path) -> String? = { p -> if (Files.isRegularFile(p)) Files.readString(p) else null },
) {
    /** The files that define the graph of a directory. */
    data class Location(val goMod: Path?, val goWork: Path?) {
        /** Files whose modification invalidates the graph (existing or not). */
        fun inputs(workUses: List<Path> = emptyList()): List<Path> {
            val dirs = listOfNotNull(goMod?.parent, goWork?.parent) + workUses
            return (listOfNotNull(goMod, goWork) + dirs.flatMap { listOf(it.resolve("go.sum"), it.resolve("go.work.sum"), it.resolve("vendor/modules.txt"), it.resolve("go.mod")) }).distinct()
        }
    }

    /** Finds the nearest go.mod and the applicable go.work (`GOWORK`, else nearest ancestor). */
    fun locate(start: Path): Location {
        val dir = if (Files.isRegularFile(start)) start.parent else start
        val goMod = findUp(dir, "go.mod")
        val gowork = env["GOWORK"]
        val goWork = when {
            gowork == "off" -> null
            !gowork.isNullOrEmpty() -> Path.of(gowork).takeIf { Files.isRegularFile(it) }
            else -> findUp(dir, "go.work")
        }
        return Location(goMod, goWork)
    }

    private fun findUp(start: Path?, name: String): Path? {
        var d = start?.toAbsolutePath()?.normalize()
        while (d != null) {
            val f = d.resolve(name)
            if (Files.isRegularFile(f)) return f
            d = d.parent
        }
        return null
    }

    /** The graph for [start] (a directory or file), null when it is not inside a module or workspace. */
    fun build(start: Path): GoModuleGraph? = build(locate(start))

    fun build(location: Location): GoModuleGraph? {
        val work = location.goWork?.let { wf -> readText(wf)?.let { GoModFileParser.parseGoWork(it) } }
        val workDir = location.goWork?.parent
        val mains = mutableListOf<Main>()
        if (work != null && workDir != null) {
            for (use in work.uses) {
                val dir = workDir.resolve(use).normalize()
                val modFile = dir.resolve("go.mod")
                val mod = readText(modFile)?.let { GoModFileParser.parseGoMod(it) } ?: continue
                mains += Main(mod.module ?: continue, dir, modFile, mod, workspace = true)
            }
            val ownDir = location.goMod?.parent?.normalize()
            if (ownDir != null && mains.none { it.dir == ownDir }) {
                // The module is outside the workspace: `go` reports an error; treat the module alone.
                mains.clear()
            }
        }
        val workspaceMode = mains.isNotEmpty()
        if (!workspaceMode) {
            val modFile = location.goMod ?: return null
            val mod = readText(modFile)?.let { GoModFileParser.parseGoMod(it) } ?: return null
            mains += Main(mod.module ?: return null, modFile.parent.normalize(), modFile, mod, workspace = false)
        }

        // Replacements: go.work overrides go.mod; version-specific before wildcard.
        val replaces = mutableListOf<Pair<GoReplace, Path>>()
        if (workspaceMode && work != null && workDir != null) work.replaces.forEach { replaces += it to workDir }
        for (m in mains) m.mod.replaces.forEach { r ->
            if (replaces.none { it.first.oldPath == r.oldPath && it.first.oldVersion == r.oldVersion }) replaces += r to m.dir
        }
        val mainPaths = mains.map { it.path }.toSet()
        fun replacementFor(path: String, version: String?): Pair<GoReplace, Path>? {
            if (path in mainPaths) return null
            return replaces.firstOrNull { it.first.oldPath == path && it.first.oldVersion != null && it.first.oldVersion == version }
                ?: replaces.firstOrNull { it.first.oldPath == path && it.first.oldVersion == null }
        }
        val excludes = mains.flatMap { it.mod.excludes }.toSet()

        // Vendor mode.
        val vendorDir = (if (workspaceMode) workDir else mains.single().dir)?.resolve("vendor")
        val vendorList = vendorDir?.resolve("modules.txt")?.let(readText)?.let { GoModFileParser.parseVendorModulesTxt(it) }
        val vendorMode = vendorList != null && vendorModeEnabled(workspaceMode, work?.go, mains, vendorList)

        val directPaths = mains.flatMap { m -> m.mod.requires.filter { !it.indirect }.map { it.path } }.toSet()
        val mainModules = mains.map { m ->
            GoModule(
                path = m.path, version = null, dir = m.dir, goModFile = m.goModFile, goVersion = m.mod.go,
                isMain = true, isWorkspaceMember = m.workspace, requires = m.mod.requires, replaces = m.mod.replaces,
                excludes = m.mod.excludes, retracts = m.mod.retracts, tools = m.mod.tools, deprecated = m.mod.deprecated,
            )
        }

        if (vendorMode) {
            val vendored = vendorList.filter { it.version != null && it.path !in mainPaths }.map { v ->
                GoModule(
                    path = v.path, version = v.version, dir = vendorDir.resolve(v.path), goModFile = null, goVersion = v.goVersion,
                    isMain = false, isIndirect = v.path !in directPaths, replacement = v.replacement,
                )
            }.sortedBy { it.path }
            return GoModuleGraph(mainModules, mainModules + vendored, location.goWork.takeIf { workspaceMode }, true, vendorDir, emptyList(), GoModuleGraph.Source.VENDOR)
        }

        val goModPaths = HashMap<GoModuleVersion, Path>()
        val goMods = HashMap<GoModuleVersion, GoModFile>()
        val localWithoutGoMod = HashSet<GoModuleVersion>()
        /** Reads the go.mod of [m] after replacement; records it in [goModPaths]/[goMods]. */
        fun readModule(m: GoModuleVersion): GoModFile? {
            goMods[m]?.let { return it }
            val rep = replacementFor(m.path, m.version)
            val (file, text) = when {
                rep == null -> cache?.readGoMod(m.path, m.version!!) ?: return null
                rep.first.isLocal -> {
                    val f = rep.second.resolve(rep.first.newPath).normalize().resolve("go.mod")
                    f to (readText(f) ?: run { localWithoutGoMod += m; return null })
                }
                else -> cache?.readGoMod(rep.first.newPath, rep.first.newVersion!!) ?: return null
            }
            val mod = GoModFileParser.parseGoMod(text)
            goModPaths[m] = file
            goMods[m] = mod
            return mod
        }
        val mvs = Mvs { m ->
            val mod = readModule(m)
            when {
                mod != null -> Mvs.Summary(mod.go, mod.requires)
                // A local replacement without go.mod has no requirements.
                m in localWithoutGoMod -> Mvs.Summary(null, emptyList())
                else -> null
            }
        }
        val result = mvs.buildList(mains.map { Mvs.MainModule(it.path, it.mod.go, it.mod.requires) }, excludes, workspaceMode)

        val others = result.selected.entries.sortedBy { it.key }.map { (path, version) ->
            val key = GoModuleVersion(path, version)
            val rep = replacementFor(path, version)
            val dir = when {
                rep == null -> cache?.extractedDir(path, version)?.takeIf { Files.isDirectory(it) }
                rep.first.isLocal -> rep.second.resolve(rep.first.newPath).normalize()
                else -> cache?.extractedDir(rep.first.newPath, rep.first.newVersion!!)?.takeIf { Files.isDirectory(it) }
            }
            // Metadata only: a pruned module's go.mod is read for its directives, never for graph edges.
            val mod = readModule(key)
            GoModule(
                path = path, version = version, dir = dir, goModFile = goModPaths[key],
                goVersion = mod?.go, isMain = false, isIndirect = path !in directPaths,
                replacement = rep?.first?.let { GoModuleVersion(it.newPath, it.newVersion) },
                requires = mod?.requires.orEmpty(), replaces = mod?.replaces.orEmpty(), excludes = mod?.excludes.orEmpty(),
                retracts = mod?.retracts.orEmpty(), tools = mod?.tools.orEmpty(), deprecated = mod?.deprecated,
            )
        }
        return GoModuleGraph(
            mainModules, mainModules + others, location.goWork.takeIf { workspaceMode }, false, null,
            result.missing.toList(), GoModuleGraph.Source.PURE,
        )
    }

    /**
     * `modload.mustUseModules`/`checkVendorConsistency` simplified: vendor mode is the default when
     * vendor/modules.txt exists and the main module (workspace: go.work) declares go >= 1.14
     * (1.22 for workspaces), unless `GOFLAGS` says `-mod=mod|readonly`; with go >= 1.17 the
     * explicit requirements must match the `## explicit` entries.
     */
    private fun vendorModeEnabled(workspace: Boolean, workGo: String?, mains: List<Main>, vendored: List<GoVendoredModule>): Boolean {
        val modFlag = env["GOFLAGS"]?.split(' ')?.firstNotNullOfOrNull { f -> f.removePrefix("-").takeIf { it.startsWith("mod=") }?.removePrefix("mod=") }
        if (modFlag == "mod" || modFlag == "readonly") return false
        if (modFlag == "vendor") return true
        if (workspace) return workGo?.let { GoVersion.parse(it) }?.let { it >= GoVersion("1.22") } == true
        val main = mains.single()
        val go = main.mod.go?.let { GoVersion.parse(it) } ?: return false
        if (go < GoVersion("1.14")) return false
        if (go < GoVersion("1.17")) return true
        val byPath = vendored.associateBy { it.path }
        for (r in main.mod.requires) {
            val v = byPath[r.path] ?: return false
            if (v.version != r.version || !v.explicit) return false
        }
        val required = main.mod.requires.map { it.path }.toSet()
        return vendored.filter { it.explicit && it.version != null }.all { it.path in required }
    }

    private class Main(val path: String, val dir: Path, val goModFile: Path, val mod: GoModFile, val workspace: Boolean)
}
