package io.github.golangsupport.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import java.nio.file.Path

/**
 * Base for project model tests: pins the toolchain to the local GOROOT/GOMODCACHE with
 * linux/amd64 + cgo, without a `go` binary (pure resolution only), and allows VFS access to the
 * fixture directories, GOROOT and the module cache.
 */
abstract class GoProjectModelTestBase : BasePlatformTestCase() {

    protected lateinit var toolchain: GoToolchainInfo

    override fun setUp() {
        super.setUp()
        val goroot = ProjectTestUtil.goroot()
        toolchain = GoToolchainInfo(
            goroot = goroot,
            version = DefaultGoToolchainProvider.readVersion(goroot),
            gopath = emptyList(),
            gomodcache = ProjectTestUtil.gomodcache(),
            goos = "linux",
            goarch = "amd64",
            cgoEnabled = true,
        )
        val fixed = object : GoToolchainProvider {
            override fun toolchainFor(project: com.intellij.openapi.project.Project?): GoToolchainInfo = toolchain
        }
        ApplicationManager.getApplication().replaceService(GoToolchainProvider::class.java, fixed, testRootDisposable)
        VfsRootAccess.allowRootAccess(
            testRootDisposable,
            ProjectTestUtil.testDataPath().toString(),
            goroot.toString(),
            ProjectTestUtil.gomodcache().toString(),
        )
    }

    protected fun vfs(path: Path): VirtualFile =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: error("not found: $path")

    protected fun fixture(relative: String): VirtualFile = vfs(ProjectTestUtil.projectData(relative))
}
