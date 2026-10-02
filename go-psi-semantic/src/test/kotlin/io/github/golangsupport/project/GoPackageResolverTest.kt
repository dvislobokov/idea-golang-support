package io.github.golangsupport.project

import com.intellij.openapi.vfs.VirtualFile
import io.github.golangsupport.project.api.GoBuildContext
import io.github.golangsupport.project.api.GoImportResolution
import io.github.golangsupport.project.api.GoModuleGraph
import io.github.golangsupport.project.api.GoModuleGraphProvider
import io.github.golangsupport.project.api.GoPackage
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.project.impl.GoModuleCacheLayout
import java.nio.file.Files

/** Fixtures under `testData/project/{simple,workspace,vendor,replace-local,nested-module,buildtags}`. */
class GoPackageResolverTest : GoProjectModelTestBase() {

    private val resolver: GoPackageResolver get() = GoPackageResolver.getInstance(project)

    private fun resolve(importPath: String, from: VirtualFile): GoImportResolution = resolver.resolveImport(importPath, from)

    private fun resolved(importPath: String, from: VirtualFile): GoPackage {
        val r = resolve(importPath, from)
        assertTrue("$importPath from ${from.path}: $r", r is GoImportResolution.Resolved)
        return (r as GoImportResolution.Resolved).pkg
    }

    private fun names(files: List<VirtualFile>) = files.map { it.name }.sorted()

    fun testStdImportResolvesIntoGoroot() {
        val main = fixture("simple/main.go")
        val fmt = resolved("fmt", main)
        assertEquals(vfs(ProjectTestUtil.goroot().resolve("src/fmt")), fmt.directory)
        assertTrue(fmt.isStd)
        assertEquals("fmt", fmt.importPath)
        assertEquals("fmt", fmt.name)
        assertNull(fmt.module)
        assertTrue(fmt.goFiles.isNotEmpty())
        assertTrue("print.go" in names(fmt.goFiles))
        assertTrue(fmt.xTestFiles.isNotEmpty()) // fmt_test package
        assertEquals("net/http", resolved("net/http", main).importPath)
    }

    fun testModuleImportResolvesIntoModuleCache() {
        val main = fixture("simple/main.go")
        val dir = GoModuleCacheLayout(ProjectTestUtil.gomodcache()).extractedDir("golang.org/x/sync", "v0.20.0")
        if (!Files.isDirectory(dir)) {
            println("skipped: $dir not in the module cache")
            return
        }
        val errgroup = resolved("golang.org/x/sync/errgroup", main)
        assertEquals(vfs(dir.resolve("errgroup")), errgroup.directory)
        assertEquals("golang.org/x/sync/errgroup", errgroup.importPath)
        assertEquals("golang.org/x/sync", errgroup.module?.path)
        assertEquals("v0.20.0", errgroup.module?.version)
        assertFalse(errgroup.isStd)
        assertEquals("golang.org/x/sync/errgroup", resolver.importPathOf(errgroup.goFiles.first()))
    }

    fun testOwnModulePackagesAndTestPartitioning() {
        val main = fixture("simple/main.go")
        val util = resolved("example.com/simple/internal/util", main)
        assertEquals("util", util.name)
        assertEquals(listOf("util.go"), names(util.goFiles))
        assertEquals(listOf("util_test.go"), names(util.testFiles))
        assertEquals(listOf("util_x_test.go"), names(util.xTestFiles))
        assertEquals("example.com/simple", util.module?.path)
        assertTrue(util.module!!.isMain)
        assertFalse(util.isCommand)
        assertTrue(resolver.packageOf(fixture("simple"))!!.isCommand)
        assertEquals("example.com/simple/internal/util", resolver.importPathOf(fixture("simple/internal/util/util_test.go")))
        assertEquals("example.com/simple", resolver.importPathOf(main))
    }

    fun testBuildContextPartitioning() {
        val dir = fixture("simple/pkg/lib")
        val linux = resolver.packageOf(dir, GoBuildContext.LINUX_AMD64)!!
        assertEquals(listOf("lib.go", "lib_linux.go"), names(linux.goFiles))
        assertEquals(listOf("_hidden.go", "ignored.go", "lib_windows.go", "other_package.go"), names(linux.ignoredFiles))
        val windows = resolver.packageOf(dir, GoBuildContext("windows", "amd64"))!!
        assertEquals(listOf("lib.go", "lib_windows.go"), names(windows.goFiles))
        assertTrue("lib_linux.go" in names(windows.ignoredFiles))
        // The default context comes from the (pinned linux/amd64) toolchain.
        assertEquals(names(linux.goFiles), names(resolver.packageOf(dir)!!.goFiles))
    }

    fun testBuildTagsFixture() {
        val dir = fixture("buildtags")
        val linux = resolver.packageOf(dir, GoBuildContext.LINUX_AMD64.copy(goVersion = toolchain.version))!!
        assertEquals(listOf("a.go", "a_linux.go", "cgo.go", "plus_build.go", "unix.go"), names(linux.goFiles))
        assertEquals(listOf("a_linux_test.go"), names(linux.testFiles))
        val integration = resolver.packageOf(dir, GoBuildContext.LINUX_AMD64.copy(goVersion = toolchain.version, buildTags = setOf("integration"), cgoEnabled = false))!!
        assertEquals(listOf("a.go", "a_linux.go", "integration.go", "plus_build.go", "unix.go"), names(integration.goFiles))
        val windows = resolver.packageOf(dir, GoBuildContext("windows", "amd64", cgoEnabled = false, goVersion = toolchain.version))!!
        assertEquals(listOf("a.go", "a_windows_amd64.go"), names(windows.goFiles))
        assertTrue(windows.testFiles.isEmpty())
        val darwinArm = resolver.packageOf(dir, GoBuildContext("darwin", "arm64", cgoEnabled = false, goVersion = toolchain.version))!!
        assertEquals(listOf("a.go", "a_arm64.go", "plus_build.go", "unix.go"), names(darwinArm.goFiles))
    }

    fun testInternalVisibility() {
        val tool = fixture("simple/cmd/tool/tool.go")
        val denied = resolve("example.com/simple/pkg/lib/internal/secret", tool)
        assertTrue("$denied", denied is GoImportResolution.InternalDenied)
        assertEquals("example.com/simple/pkg/lib/internal/secret", denied.packageOrNull?.importPath)
        // Allowed from inside the parent of "internal".
        resolved("example.com/simple/pkg/lib/internal/secret", fixture("simple/pkg/lib/lib.go"))
        // Std internal packages are not importable from user code, but are from GOROOT.
        assertTrue(resolve("internal/cpu", tool) is GoImportResolution.InternalDenied)
        resolved("internal/cpu", vfs(ProjectTestUtil.goroot().resolve("src/runtime/proc.go")))
    }

    fun testCAndRelativeImports() {
        val tool = fixture("simple/cmd/tool/tool.go")
        assertSame(GoImportResolution.CPseudoPackage, resolve("C", tool))
        assertEquals("relative", resolved("./relative", tool).name)
        assertTrue(resolve("./missing", tool) is GoImportResolution.Unresolved)
        val unresolved = resolve("example.com/nope/pkg", tool)
        assertTrue("$unresolved", unresolved is GoImportResolution.Unresolved)
    }

    fun testReplaceToLocalPath() {
        val main = fixture("replace-local/main.go")
        val dep = resolved("example.com/dep", main)
        assertEquals(fixture("replace-local/third_party/dep"), dep.directory)
        assertEquals("example.com/dep", dep.importPath)
        val graph = GoModuleGraphProvider.getInstance(project).graphFor(main)!!
        val module = graph.module("example.com/dep")!!
        assertEquals("v1.0.0", module.version)
        assertEquals("./third_party/dep", module.replacement?.path)
        assertNull(module.replacement?.version)
        assertTrue(graph.missing.isEmpty())
    }

    fun testVendorMode() {
        val main = fixture("vendor/main.go")
        val graph = GoModuleGraphProvider.getInstance(project).graphFor(main)!!
        assertTrue(graph.vendorMode)
        assertEquals(GoModuleGraph.Source.VENDOR, graph.source)
        assertEquals(listOf("example.com/vend", "example.com/dep"), graph.modules.map { it.path })
        val dep = resolved("example.com/dep", main)
        assertEquals(fixture("vendor/vendor/example.com/dep"), dep.directory)
        assertEquals("example.com/dep", dep.importPath)
        assertEquals("example.com/dep", dep.module?.path)
        assertEquals(fixture("vendor/vendor/example.com/dep/sub"), resolved("example.com/dep/sub", main).directory)
        assertEquals("example.com/dep/sub", resolver.importPathOf(fixture("vendor/vendor/example.com/dep/sub/sub.go")))
    }

    fun testWorkspaceMember() {
        val a = fixture("workspace/a/a.go")
        val graph = GoModuleGraphProvider.getInstance(project).graphFor(a)!!
        assertEquals(listOf("example.com/a", "example.com/b"), graph.mainModules.map { it.path })
        assertTrue(graph.mainModules.all { it.isWorkspaceMember })
        assertNotNull(graph.workFile)
        val bpkg = resolved("example.com/b/bpkg", a)
        assertEquals(fixture("workspace/b/bpkg"), bpkg.directory)
        assertTrue(bpkg.module!!.isMain)
        assertTrue(resolve("example.com/b/internal/hidden", a) is GoImportResolution.InternalDenied)
        resolved("example.com/b/internal/hidden", fixture("workspace/b/bpkg/b.go"))
    }

    fun testNestedModule() {
        val root = fixture("nested-module/root.go")
        resolved("example.com/root/lib", root)
        val nested = resolve("example.com/root/nested/x", root)
        assertTrue("$nested", nested is GoImportResolution.Unresolved)
        assertEquals("example.com/root/nested/x", resolver.importPathOf(fixture("nested-module/nested/x/x.go")))
        assertEquals("example.com/root/nested", GoModuleGraphProvider.getInstance(project).graphFor(fixture("nested-module/nested/x"))!!.mainModules.single().path)
    }

    fun testGraphOfSimpleModule() {
        val graph = GoModuleGraphProvider.getInstance(project).graphFor(fixture("simple/go.mod"))!!
        assertEquals("example.com/simple", graph.mainModules.single().path)
        assertEquals("1.22", graph.mainModules.single().goVersion)
        assertFalse(graph.vendorMode)
        assertSame("cached", graph, GoModuleGraphProvider.getInstance(project).graphFor(fixture("simple/internal/util")))
    }
}
