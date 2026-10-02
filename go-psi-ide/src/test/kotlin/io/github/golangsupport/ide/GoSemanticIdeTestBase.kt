package io.github.golangsupport.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.replaceService
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Base of IDE tests that need resolve: pins the toolchain to the local GOROOT (`-Dgopsi.goroot`,
 * set by the Gradle build) with linux/amd64 + cgo and no `go` binary, like the semantic module's
 * test base, so references into the standard library resolve to `$GOROOT/src`.
 */
abstract class GoSemanticIdeTestBase : GoIdeTestBase() {

    protected lateinit var toolchain: GoToolchainInfo

    override fun setUp() {
        super.setUp()
        val goroot = goroot()
        toolchain = GoToolchainInfo(
            goroot = goroot,
            version = DefaultGoToolchainProvider.readVersion(goroot),
            gopath = emptyList(),
            gomodcache = gomodcache(),
            goos = "linux",
            goarch = "amd64",
            cgoEnabled = true,
        )
        val fixed = object : GoToolchainProvider {
            override fun toolchainFor(project: com.intellij.openapi.project.Project?): GoToolchainInfo = toolchain
        }
        ApplicationManager.getApplication().replaceService(GoToolchainProvider::class.java, fixed, testRootDisposable)
        VfsRootAccess.allowRootAccess(testRootDisposable, testDataRoot(), goroot.toString(), gomodcache().toString())
        TestDialogManager.setTestDialog(TestDialog.DEFAULT)
    }

    override fun tearDown() {
        try {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** Reads `<testDataPath>/<relative>` with LF line endings. */
    protected fun readTestData(relative: String): String = File(testDataPath, relative).readText().replace("\r\n", "\n")

    /** Compares [actual] with the golden file `<testDataPath>/<relative>`; creates it (and fails) when missing or updating. */
    protected fun assertGolden(relative: String, actual: String) {
        val file = File(testDataPath, relative).toPath()
        val normalized = actual.replace("\r\n", "\n").trimEnd() + "\n"
        val update = System.getProperty("gopsi.updateGoldens")?.let { it.isEmpty() || it.toBoolean() } ?: false
        if (!Files.exists(file) || update) {
            Files.createDirectories(file.parent)
            Files.writeString(file, normalized)
            if (!update) fail("Golden $file was missing and has been created; rerun to accept")
            return
        }
        assertEquals("golden $file", Files.readString(file).replace("\r\n", "\n"), normalized)
    }

    companion object {
        fun goroot(): Path = Paths.get(System.getProperty("gopsi.goroot")?.takeIf { it.isNotBlank() } ?: "C:\\Program Files\\Go")

        fun gomodcache(): Path = Paths.get(System.getProperty("gopsi.gomodcache")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home") + "\\go\\pkg\\mod")
    }
}
