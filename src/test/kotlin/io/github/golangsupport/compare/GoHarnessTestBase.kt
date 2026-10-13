package io.github.golangsupport.compare

import com.google.gson.GsonBuilder
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.InspectionEP
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.replaceService
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import io.github.golangsupport.settings.GoSettings
import java.io.File
import java.nio.file.Paths

/**
 * The comparison stand of docs/compare: a real Go project on disk (a clone under build/compare) becomes the content root of the light
 * project, the toolchain is the local one (host GOOS/GOARCH, GOROOT, GOMODCACHE — what the IDE on this machine sees), the language
 * server is off and every Go inspection enabled by default is on, as in a fresh IDE. Silent unless GO_HARNESS=1: a normal test run
 * passes it in milliseconds. Settings: GO_HARNESS_ROOT (the clone, default build/compare/prometheus), GO_HARNESS_OUT (where dumps go,
 * default build/compare/out), GO_HARNESS_GOOS / GO_HARNESS_GOARCH / GO_HARNESS_CGO (default windows / amd64 / 0, as `go env` here).
 */
abstract class GoHarnessTestBase : BasePlatformTestCase() {
    protected val enabled: Boolean = System.getenv("GO_HARNESS") == "1"
    protected lateinit var root: VirtualFile
    protected lateinit var out: File
    protected lateinit var toolchain: GoToolchainInfo
    private var contentAdded = false
    private var languageServer = true

    override fun setUp() {
        super.setUp()
        if (!enabled) return
        languageServer = GoSettings.getInstance().languageServerEnabled
        GoSettings.getInstance().languageServerEnabled = false
        val goroot = Paths.get(System.getProperty("gopsi.goroot")?.takeIf { it.isNotBlank() } ?: "C:\\Program Files\\Go")
        val gomodcache = Paths.get(System.getenv("GOMODCACHE")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "\\go\\pkg\\mod"))
        toolchain = GoToolchainInfo(
            goroot = goroot, version = DefaultGoToolchainProvider.readVersion(goroot), gopath = emptyList(), gomodcache = gomodcache,
            goos = env("GO_HARNESS_GOOS", "windows"), goarch = env("GO_HARNESS_GOARCH", "amd64"), cgoEnabled = env("GO_HARNESS_CGO", "0") == "1",
        )
        val fixed = object : GoToolchainProvider {
            override fun toolchainFor(project: com.intellij.openapi.project.Project?): GoToolchainInfo = toolchain
        }
        ApplicationManager.getApplication().replaceService(GoToolchainProvider::class.java, fixed, testRootDisposable)
        val repo = File(System.getProperty("user.dir")).absoluteFile
        val rootDir = File(env("GO_HARNESS_ROOT", File(repo, "build/compare/prometheus").path)).absoluteFile
        out = File(env("GO_HARNESS_OUT", File(repo, "build/compare/out").path)).absoluteFile.also { it.mkdirs() }
        VfsRootAccess.allowRootAccess(testRootDisposable, rootDir.path, goroot.toString(), gomodcache.toString())
        root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(rootDir) ?: error("no clone at $rootDir")
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        val t0 = System.currentTimeMillis()
        PsiTestUtil.addContentRoot(module, root)
        contentAdded = true
        // web UI sources (JS) are not Go and their package.json scopes leak virtual pointers in a light project
        for (dir in env("GO_HARNESS_EXCLUDE", "web/ui,documentation,docs").split(',')) root.findFileByRelativePath(dir.trim())?.let { PsiTestUtil.addExcludedRoot(module, it) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        log("indexed ${root.path} in ${System.currentTimeMillis() - t0} ms")
        myFixture.enableInspections(*defaultGoInspections().toTypedArray())
        // live and postfix templates are in the completion list of a real IDE; tests switch them off unless asked
        com.intellij.codeInsight.template.impl.LiveTemplateCompletionContributor.setShowTemplatesInTests(true, testRootDisposable)
    }

    override fun tearDown() {
        try {
            if (enabled) {
                GoSettings.getInstance().languageServerEnabled = languageServer
                if (contentAdded) PsiTestUtil.removeContentEntry(module, root)
            }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            try {
                super.tearDown()
            } catch (e: Throwable) {
                // JS support of the host leaks pointers to the clone's package.json files (web/ui); the run itself is complete by now
                if (!enabled || "Virtual pointer" !in e.toString()) throw e
                log("tearDown: ignored leaked virtual pointers of the clone")
            }
        }
    }

    protected fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    protected fun log(text: String) {
        println("[harness] $text")
        System.out.flush()
    }

    /** The Go inspections a fresh IDE runs: enabled by default, for the languages Go and GoModule. */
    private fun defaultGoInspections(): List<LocalInspectionTool> =
        LocalInspectionEP.LOCAL_INSPECTION.extensionList.filter { it.enabledByDefault && it.language in setOf("Go", "GoModule") }
            .mapNotNull { ep -> runCatching { (ep as InspectionEP).instantiateTool() as? LocalInspectionTool }.getOrNull() }

    /** Whether the build context of the toolchain (GOOS/GOARCH, cgo, no extra tags) compiles [file]. */
    protected fun inBuild(file: VirtualFile): Boolean =
        io.github.golangsupport.project.api.GoBuildConstraintEvaluator.matchFile(file.name, String(file.contentsToByteArray(), file.charset), toolchain.buildContext)

    protected fun file(relative: String): VirtualFile = root.findFileByRelativePath(relative) ?: error("no $relative in ${root.path}")

    protected fun relative(file: VirtualFile): String = VfsUtil.getRelativePath(file, root) ?: file.path

    /** One problem the editor shows: what a user sees in the gutter / Problems view (no plain symbol highlighting). */
    data class Problem(val file: String, val line: Int, val col: Int, val endLine: Int, val endCol: Int, val severity: String,
                       val tool: String?, val text: String, val message: String)

    /** Every problem of WEAK_WARNING and above [doHighlighting] returns for the file now open in the fixture. */
    protected fun problems(): List<Problem> {
        val document = myFixture.editor.document
        val name = relative(myFixture.file.virtualFile)
        return myFixture.doHighlighting().filter { it.description != null && it.severity >= HighlightSeverity.WEAK_WARNING }
            .sortedWith(compareBy<HighlightInfo>({ it.startOffset }, { it.endOffset }))
            .map { info ->
                val line = document.getLineNumber(info.startOffset)
                val endLine = document.getLineNumber(info.endOffset)
                Problem(name, line + 1, info.startOffset - document.getLineStartOffset(line) + 1, endLine + 1,
                    info.endOffset - document.getLineStartOffset(endLine) + 1, info.severity.name, info.inspectionToolId,
                    document.getText(com.intellij.openapi.util.TextRange(info.startOffset, info.endOffset)).take(80), info.description!!)
            }
    }

    protected fun writeJson(name: String, value: Any) {
        File(out, name).writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(value))
        log("wrote ${File(out, name)}")
    }
}
