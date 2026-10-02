package io.github.golangsupport.project.impl

import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleVersion
import io.github.golangsupport.project.api.GoRequire
import junit.framework.TestCase
import java.nio.file.Files

/**
 * Minimal version selection over in-memory module graphs, plus one real module whose build list
 * is compared with `go list -m -json all` (golden).
 */
class MvsTest : TestCase() {

    /** A fake module cache: `"path@version" -> (go version, requirements)`. */
    private class FakeCache(vararg entries: Pair<String, Pair<String?, List<String>>>) {
        val mods: Map<GoModuleVersion, Mvs.Summary> = entries.associate { (key, value) ->
            val (path, version) = key.split('@')
            GoModuleVersion(path, version) to Mvs.Summary(value.first, value.second.map(::req))
        }
        val loads = mutableListOf<GoModuleVersion>()

        fun mvs() = Mvs { m -> loads += m; mods[m] }
    }

    companion object {
        /** `"path@v1.2.3"` or `"path@v1.2.3 // indirect"`. */
        fun req(s: String): GoRequire {
            val indirect = s.endsWith("// indirect")
            val (path, version) = s.removeSuffix("// indirect").trim().split('@')
            return GoRequire(path, version, indirect)
        }

        private fun buildList(result: Mvs.Result): List<String> = result.selected.entries.sortedBy { it.key }.map { "${it.key}@${it.value}" }
    }

    /**
     * The example graph of research.swtch.com/vgo-mvs (Figure 1): A1 needs B1.2 and C1.2; B1.2
     * needs D1.3; C1.2 needs D1.4; D1.3 and D1.4 need E1.2; C1.3 needs F1.1; F1.1 and G1.1 form a
     * cycle. Versions are written as semver (`v1.2.0` for "1.2"). No `go` directive: unpruned.
     */
    private fun articleCache() = FakeCache(
        "B@v1.1.0" to (null to listOf("D@v1.1.0")),
        "B@v1.2.0" to (null to listOf("D@v1.3.0")),
        "C@v1.1.0" to (null to emptyList()),
        "C@v1.2.0" to (null to listOf("D@v1.4.0")),
        "C@v1.3.0" to (null to listOf("F@v1.1.0")),
        "D@v1.1.0" to (null to listOf("E@v1.1.0")),
        "D@v1.2.0" to (null to listOf("E@v1.1.0")),
        "D@v1.3.0" to (null to listOf("E@v1.2.0")),
        "D@v1.4.0" to (null to listOf("E@v1.2.0")),
        "E@v1.1.0" to (null to emptyList()),
        "E@v1.2.0" to (null to emptyList()),
        "E@v1.3.0" to (null to emptyList()),
        "F@v1.1.0" to (null to listOf("G@v1.1.0")),
        "G@v1.1.0" to (null to listOf("F@v1.1.0")),
    )

    /** Algorithm 1: construct the build list. */
    fun testArticleBuildList() {
        val result = articleCache().mvs().buildList(listOf(Mvs.MainModule("A", null, listOf(req("B@v1.2.0"), req("C@v1.2.0")))))
        assertEquals(listOf("B@v1.2.0", "C@v1.2.0", "D@v1.4.0", "E@v1.2.0"), buildList(result))
        assertTrue(result.missing.isEmpty())
    }

    /** Algorithm 3 (upgrade one module): requiring C1.3 brings in the F/G cycle. */
    fun testArticleUpgradeWithCycle() {
        val result = articleCache().mvs().buildList(listOf(Mvs.MainModule("A", null, listOf(req("B@v1.2.0"), req("C@v1.3.0")))))
        assertEquals(listOf("B@v1.2.0", "C@v1.3.0", "D@v1.3.0", "E@v1.2.0", "F@v1.1.0", "G@v1.1.0"), buildList(result))
    }

    /** Algorithm 4 (downgrade): a lower root requirement is overridden by a higher indirect one. */
    fun testArticleHighestRequirementWins() {
        val result = articleCache().mvs().buildList(listOf(Mvs.MainModule("A", null, listOf(req("B@v1.2.0"), req("C@v1.2.0"), req("D@v1.2.0"), req("E@v1.3.0")))))
        assertEquals(listOf("B@v1.2.0", "C@v1.2.0", "D@v1.4.0", "E@v1.3.0"), buildList(result))
    }

    /** Excluded versions are ignored as requirements (Go 1.16+). */
    fun testExclude() {
        val result = articleCache().mvs().buildList(
            listOf(Mvs.MainModule("A", null, listOf(req("B@v1.2.0"), req("C@v1.2.0")))),
            excludes = setOf(GoModuleVersion("D", "v1.4.0")),
        )
        assertEquals(listOf("B@v1.2.0", "C@v1.2.0", "D@v1.3.0", "E@v1.2.0"), buildList(result))
    }

    /** A requirement on the main module itself never changes the selection. */
    fun testMainModuleRequirementIgnored() {
        val cache = FakeCache("B@v1.0.0" to ("1.21" to listOf("A@v9.0.0")))
        val result = cache.mvs().buildList(listOf(Mvs.MainModule("A", "1.21", listOf(req("B@v1.0.0")))))
        assertEquals(listOf("B@v1.0.0"), buildList(result))
    }

    /** go >= 1.17 everywhere: the requirements of a dependency's dependencies are pruned out. */
    fun testPruning() {
        val cache = FakeCache(
            "B@v1.0.0" to ("1.17" to listOf("C@v1.0.0")),
            "C@v1.0.0" to ("1.17" to listOf("D@v1.0.0")),
            "D@v1.0.0" to ("1.17" to emptyList()),
        )
        val result = cache.mvs().buildList(listOf(Mvs.MainModule("A", "1.21", listOf(req("B@v1.0.0")))))
        assertEquals(listOf("B@v1.0.0", "C@v1.0.0"), buildList(result))
        assertEquals("C's go.mod is never read", listOf(GoModuleVersion("B", "v1.0.0")), cache.loads)
    }

    /** An unpruned (go < 1.17) dependency pulls in its whole transitive graph. */
    fun testUnprunedDependencyLoadsTransitively() {
        val cache = FakeCache(
            "B@v1.0.0" to ("1.16" to listOf("C@v1.0.0")),
            "C@v1.0.0" to ("1.17" to listOf("D@v1.0.0")),
            "D@v1.0.0" to ("1.17" to listOf("E@v1.0.0")),
            "E@v1.0.0" to (null to emptyList()),
        )
        val result = cache.mvs().buildList(listOf(Mvs.MainModule("A", "1.21", listOf(req("B@v1.0.0")))))
        assertEquals(listOf("B@v1.0.0", "C@v1.0.0", "D@v1.0.0", "E@v1.0.0"), buildList(result))
    }

    /** A main module below 1.17 runs classic (unpruned) MVS. */
    fun testUnprunedMainModule() {
        val cache = FakeCache(
            "B@v1.0.0" to ("1.21" to listOf("C@v1.0.0")),
            "C@v1.0.0" to ("1.21" to listOf("D@v1.0.0")),
            "D@v1.0.0" to ("1.21" to emptyList()),
        )
        val result = cache.mvs().buildList(listOf(Mvs.MainModule("A", "1.16", listOf(req("B@v1.0.0")))))
        assertEquals(listOf("B@v1.0.0", "C@v1.0.0", "D@v1.0.0"), buildList(result))
    }

    /**
     * Pruned main module whose root is selected at a higher version through another root: the
     * root is raised (tidy semantics): the selected version's requirements are loaded and the
     * stale version's requirements disappear.
     */
    fun testPrunedRootRaisedToSelectedVersion() {
        val cache = FakeCache(
            "B@v1.0.0" to ("1.21" to listOf("X@v1.0.0")),
            "B@v1.1.0" to ("1.21" to listOf("Y@v1.0.0")),
            "C@v1.0.0" to ("1.21" to listOf("B@v1.1.0")),
        )
        val result = cache.mvs().buildList(listOf(Mvs.MainModule("A", "1.21", listOf(req("B@v1.0.0"), req("C@v1.0.0")))))
        assertEquals(listOf("B@v1.1.0", "C@v1.0.0", "Y@v1.0.0"), buildList(result))
    }

    /** Workspace: every member's requirements are roots; members are never selected from requirements. */
    fun testWorkspaceMains() {
        val cache = FakeCache(
            "D@v1.0.0" to ("1.21" to listOf("E@v1.0.0")),
            "D@v1.2.0" to ("1.21" to emptyList()),
        )
        val result = cache.mvs().buildList(
            listOf(
                Mvs.MainModule("A", "1.22", listOf(req("B@v1.0.0"), req("D@v1.0.0"))),
                Mvs.MainModule("B", "1.22", listOf(req("D@v1.2.0"))),
            ),
            workspace = true,
        )
        assertEquals(listOf("D@v1.2.0", "E@v1.0.0"), buildList(result))
    }

    fun testMissingModulesReported() {
        val cache = FakeCache("B@v1.0.0" to ("1.21" to listOf("C@v1.0.0")))
        val result = cache.mvs().buildList(listOf(Mvs.MainModule("A", "1.16", listOf(req("B@v1.0.0")))))
        assertEquals(listOf("B@v1.0.0", "C@v1.0.0"), buildList(result))
        assertEquals(setOf(GoModuleVersion("C", "v1.0.0")), result.missing)
    }

    // ---- graph builder over a fake module cache on disk -----------------------------------------

    fun testBuilderReplaceAndIndirect() {
        val tmp = Files.createTempDirectory("gopsi-mvs")
        try {
            val cache = GoModuleCacheLayout(tmp.resolve("modcache"))
            fun cached(path: String, version: String, goMod: String) {
                val f = cache.modFile(path, version)
                Files.createDirectories(f.parent)
                Files.writeString(f, goMod)
            }
            cached("example.com/dep", "v1.0.0", "module example.com/dep\n\ngo 1.21\n\nrequire example.com/leaf v1.0.0\n")
            cached("example.com/fork", "v2.0.0", "module example.com/fork\n\ngo 1.21\n\nrequire example.com/leaf v1.5.0\n")
            cached("example.com/leaf", "v1.5.0", "module example.com/leaf\n\ngo 1.21\n")
            Files.createDirectories(cache.extractedDir("example.com/fork", "v2.0.0"))
            val main = tmp.resolve("main")
            Files.createDirectories(main.resolve("local"))
            Files.writeString(main.resolve("local/go.mod"), "module example.com/local\n\ngo 1.21\n")
            Files.writeString(
                main.resolve("go.mod"),
                """
                module example.com/main

                go 1.21

                require (
                	example.com/dep v1.0.0
                	example.com/local v0.0.0
                	example.com/leaf v1.5.0 // indirect
                )

                replace example.com/dep v1.0.0 => example.com/fork v2.0.0

                replace example.com/local => ./local
                """.trimIndent(),
            )
            val graph = GoModuleGraphBuilder(cache).build(main)!!
            assertEquals(GoModuleGraph.Source.PURE, graph.source)
            assertEquals(listOf("example.com/main", "example.com/dep", "example.com/leaf", "example.com/local"), graph.modules.map { it.path })
            val dep = graph.module("example.com/dep")!!
            assertEquals("v1.0.0", dep.version)
            assertEquals(GoModuleVersion("example.com/fork", "v2.0.0"), dep.replacement)
            assertEquals(cache.extractedDir("example.com/fork", "v2.0.0"), dep.dir)
            assertEquals("v1.5.0", graph.module("example.com/leaf")!!.version)
            assertTrue(graph.module("example.com/leaf")!!.isIndirect)
            assertFalse(dep.isIndirect)
            assertEquals(main.resolve("local").normalize(), graph.module("example.com/local")!!.dir)
            assertTrue(graph.missing.isEmpty())
            assertEquals(listOf("example.com/dep"), graph.modulesForImportPath("example.com/dep/sub").map { it.path })
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    // ---- real module cache --------------------------------------------------------------------

    /**
     * `testData/project/mvs-real` requires github.com/Abirdcfly/dupword v0.1.8,
     * github.com/butuzov/mirror v1.3.3 and golang.org/x/sync v0.20.0 (go 1.25, pruned). The golden
     * build list was recorded once from `go list -m -json all` (GOFLAGS=-mod=readonly, GOPROXY=off);
     * regenerate with -Dgopsi.updateGoldens=true when the toolchain is available.
     */
    fun testRealModuleMatchesGoList() {
        val fixture = ProjectTestUtil.projectData("mvs-real")
        val golden = fixture.resolve("go-list-m-all.golden.txt")
        val cacheRoot = ProjectTestUtil.gomodcache()
        val cache = GoModuleCacheLayout(cacheRoot)
        val required = listOf("github.com/Abirdcfly/dupword" to "v0.1.8", "github.com/butuzov/mirror" to "v1.3.3", "golang.org/x/sync" to "v0.20.0")
        if (required.any { (p, v) -> !Files.isRegularFile(cache.modFile(p, v)) }) {
            println("MvsTest.testRealModuleMatchesGoList: skipped, modules not in $cacheRoot")
            return
        }
        if (ProjectTestUtil.updateGoldens || !Files.exists(golden)) {
            val binary = ProjectTestUtil.goBinary() ?: error("go binary required to record $golden")
            val out = GoListModuleGraph.runGo(
                binary, fixture, listOf("list", "-m", "-json", "all"),
                mapOf("GOFLAGS" to "-mod=readonly", "GOPROXY" to "off", "GOWORK" to "off", "GOMODCACHE" to cacheRoot.toString(), "GOTOOLCHAIN" to "local"),
            ) ?: error("go list failed")
            assertEquals(out.stderr, 0, out.exitCode)
            ProjectTestUtil.assertGolden(golden, describe(GoListModuleGraph.parse(out.stdout)))
        }
        val pure = GoModuleGraphBuilder(cache, mapOf("GOWORK" to "off")).build(fixture)!!
        assertTrue("missing: ${pure.missing}", pure.missing.isEmpty())
        assertEquals(Files.readString(golden).replace("\r\n", "\n"), describe(pure))
        // Extracted modules point into the cache; go.mod-only ones have no directory.
        assertEquals(cache.extractedDir("golang.org/x/sync", "v0.20.0"), pure.module("golang.org/x/sync")!!.dir)
        val tools = pure.module("golang.org/x/tools")!!
        assertEquals(Files.isDirectory(cache.extractedDir("golang.org/x/tools", "v0.45.0")), tools.dir != null)
    }

    /**
     * One line per module: path, version, flags, go version. Directories are not compared: with
     * -mod=readonly `go list -m` omits `Dir` for modules whose zip hash is not in go.sum.
     */
    private fun describe(graph: GoModuleGraph): String = graph.modules.joinToString("\n", postfix = "\n") { m ->
        listOf(m.path, m.version ?: "-", if (m.isMain) "main" else "dep", if (m.isIndirect) "indirect" else "direct", "go=${m.goVersion ?: "-"}").joinToString(" ")
    }
}
