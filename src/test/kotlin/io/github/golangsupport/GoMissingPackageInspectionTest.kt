package io.github.golangsupport

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import io.github.golangsupport.mod.GoMissingPackageInspection
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import io.github.golangsupport.settings.GoSettings
import java.nio.file.Files
import java.nio.file.Paths

/**
 * "Cannot find package" on an import path with the fixes GoLand offers (seen live 2026-10-08: nothing was marked). The toolchain is
 * pinned to the local GOROOT without a `go` binary; the fixes are listed, never run. The light project is on the temp file system, so
 * only a std-like path is checked (a dotted one needs a module graph on disk: the go-psi-semantic test covers it).
 */
class GoMissingPackageInspectionTest : BasePlatformTestCase() {
    private var languageServer = true

    override fun setUp() {
        super.setUp()
        languageServer = GoSettings.getInstance().languageServerEnabled
        GoSettings.getInstance().languageServerEnabled = false
        val goroot = Paths.get(System.getProperty("gopsi.goroot")?.takeIf { it.isNotBlank() } ?: "C:\\Program Files\\Go")
        val toolchain = GoToolchainInfo(
            goroot = goroot, version = DefaultGoToolchainProvider.readVersion(goroot), gopath = emptyList(),
            gomodcache = Paths.get(System.getProperty("user.home"), "go", "pkg", "mod"), goos = "linux", goarch = "amd64", cgoEnabled = true,
        )
        val fixed = object : GoToolchainProvider {
            override fun toolchainFor(project: com.intellij.openapi.project.Project?): GoToolchainInfo = toolchain
        }
        ApplicationManager.getApplication().replaceService(GoToolchainProvider::class.java, fixed, testRootDisposable)
        VfsRootAccess.allowRootAccess(testRootDisposable, goroot.toString())
        myFixture.enableInspections(GoMissingPackageInspection())
    }

    override fun tearDown() {
        try {
            GoSettings.getInstance().languageServerEnabled = languageServer
        } finally {
            super.tearDown()
        }
    }

    private val source = "package main\n\nimport (\n\t\"fmt\"\n\n\t\"nosuch/p<caret>kg\"\n)\n\nfunc main() {\n\tpkg.Run()\n\tfmt.Println()\n}\n"

    private fun missing() = myFixture.doHighlighting(HighlightSeverity.ERROR).filter { it.description?.startsWith("cannot find package") == true }

    fun testPathIsMarkedAndModuleFixesOffered() {
        myFixture.addFileToProject("go.mod", "module example.com/app\n\ngo 1.22\n")
        myFixture.configureByText("main.go", source)
        val infos = missing()
        assertEquals(listOf("cannot find package \"nosuch/pkg\""), infos.map { it.description })
        assertEquals("\"nosuch/pkg\"", myFixture.file.text.substring(infos[0].startOffset, infos[0].endOffset))
        // no cascade: the use of the package is not an error
        assertEquals(emptyList<String>(), myFixture.doHighlighting(HighlightSeverity.ERROR).filter { "pkg.Run" in it.description.orEmpty() || it.description.orEmpty().startsWith("undefined") }.map { it.description })
        val names = myFixture.availableIntentions.map { it.text }
        assertTrue(names.toString(), "Sync dependencies of example.com/app" in names)
        assertTrue(names.toString(), "Run go mod tidy" in names)
    }

    fun testNoFixesOutsideAModule() {
        myFixture.configureByText("main.go", source)
        assertEquals(1, missing().size)
        val names = myFixture.availableIntentions.map { it.text }
        assertFalse(names.toString(), names.any { it.startsWith("Sync dependencies") || it == "Run go mod tidy" })
    }

    fun testStdAndCgoImportsAreNotMarked() {
        myFixture.addFileToProject("go.mod", "module example.com/app\n\ngo 1.22\n")
        myFixture.configureByText("main.go", "package main\n\nimport \"C\"\nimport \"fmt\"\n\nfunc main() { fmt.Println() }\n")
        assertEquals(emptyList<String>(), missing().map { it.description })
    }

    fun testOnlyWellFormedImportPathsGetTheSyncFix() {
        assertTrue(GoMissingPackageInspection.isSafeImportPath("github.com/x/y"))
        assertTrue(GoMissingPackageInspection.isSafeImportPath("gopkg.in/yaml.v3"))
        assertFalse(GoMissingPackageInspection.isSafeImportPath("-u"))
        assertFalse(GoMissingPackageInspection.isSafeImportPath("a b"))
        assertFalse(GoMissingPackageInspection.isSafeImportPath("../x"))
        assertFalse(GoMissingPackageInspection.isSafeImportPath("x//y"))
        assertFalse(GoMissingPackageInspection.isSafeImportPath(""))
        assertFalse(GoMissingPackageInspection.isSafeImportPath("x\ty"))
    }

    fun testFlagLikePathGetsOnlyTidy() {
        myFixture.addFileToProject("go.mod", "module example.com/app\n\ngo 1.22\n")
        myFixture.configureByText("main.go", "package main\n\nimport _ \"-<caret>u\"\n")
        assertEquals(listOf("cannot find package \"-u\""), missing().map { it.description })
        val names = myFixture.availableIntentions.map { it.text }
        assertFalse(names.toString(), names.any { it.startsWith("Sync dependencies") })
        assertTrue(names.toString(), "Run go mod tidy" in names)
    }

    fun testImportPathOfTheMessage() {
        assertEquals("github.com/labstack/echo/v5", GoMissingPackageInspection.importPath("cannot find package \"github.com/labstack/echo/v5\""))
        assertNull(GoMissingPackageInspection.importPath("undefined: echo"))
    }

    fun testModuleCacheDirectoriesOfAnImportPath() {
        assertEquals("github.com/!burnt!sushi", GoMissingPackageInspection.escape("github.com/BurntSushi"))
        val cache = Files.createTempDirectory("gomodcache")
        try {
            Files.createDirectories(cache.resolve("github.com/labstack/echo/v5@v5.0.0"))
            Files.createDirectories(cache.resolve("github.com/labstack/echo@v4.13.0"))
            Files.createDirectories(cache.resolve("github.com/labstack/gommon@v0.4.2"))
            val found = GoMissingPackageInspection.moduleDirectories(cache, "github.com/labstack/echo/v5/middleware")
                .map { cache.relativize(it).toString().replace('\\', '/') }.sorted()
            assertEquals(listOf("github.com/labstack/echo/v5@v5.0.0", "github.com/labstack/echo@v4.13.0"), found)
        } finally {
            cache.toFile().deleteRecursively()
        }
    }
}
