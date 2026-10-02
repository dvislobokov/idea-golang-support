package io.github.golangsupport.benchmark

import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.project.api.GoImportResolution
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.project.impl.GoModuleCacheLayout
import io.github.golangsupport.project.impl.GoModuleGraphBuilder
import java.nio.file.Files

/**
 * Project model over `testData/project/mvs-real` (three requirements, pruned graph, module cache
 * on disk): the pure module graph build (go.mod files of the module cache, MVS) plus package
 * resolution of the imports of `main.go` through the project service. Skipped when the modules
 * are not in the local module cache.
 */
class GoProjectModelBenchmark : GoProjectModelTestBase() {

    fun testMvsReal() {
        val fixtureDir = ProjectTestUtil.projectData("mvs-real")
        val cache = GoModuleCacheLayout(ProjectTestUtil.gomodcache())
        val required = listOf("github.com/Abirdcfly/dupword" to "v0.1.8", "github.com/butuzov/mirror" to "v1.3.3", "golang.org/x/sync" to "v0.20.0")
        if (required.any { (p, v) -> !Files.isRegularFile(cache.modFile(p, v)) }) {
            println("BENCH GoProjectModelBenchmark.mvsReal: skipped, modules not in ${ProjectTestUtil.gomodcache()}")
            return
        }
        val main = fixture("mvs-real/main.go")
        val resolver = GoPackageResolver.getInstance(project)
        var modules = 0
        BenchmarkSupport.run("GoProjectModelBenchmark.mvsReal", "ms", 1.0) {
            // One build is a few milliseconds: ten builds per iteration.
            repeat(10) { modules = GoModuleGraphBuilder(cache, mapOf("GOWORK" to "off")).build(fixtureDir)!!.modules.size }
            val resolution = resolver.resolveImport("golang.org/x/sync/errgroup", main)
            check(resolution is GoImportResolution.Resolved) { "errgroup: $resolution" }
            check(resolver.resolveImport("fmt", main) is GoImportResolution.Resolved)
        }
        assertTrue("empty module graph", modules > 3)
    }
}
