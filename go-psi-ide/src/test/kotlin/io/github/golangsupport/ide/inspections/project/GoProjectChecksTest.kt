package io.github.golangsupport.ide.inspections.project

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.GoProjectModelTracker
import java.io.File

/** Project-wide checks over modules on disk added as content roots: `internal/` imports, import cycles, unused exported names. */
class GoProjectChecksTest : GoSemanticIdeTestBase() {

    private lateinit var root: VirtualFile

    /**
     * Writes [files] under a temp directory (paths relative to it; `go.mod` files included by the caller), adds the directory [content]
     * (default: the whole tree) as a content root and runs [body]. A `modcache/` subtree becomes GOMODCACHE.
     */
    private fun inProject(files: Map<String, String>, content: String = "", body: () -> Unit) {
        val tmp = FileUtil.createTempDirectory("gopsi-pw9", null, true)
        for ((path, text) in files) File(tmp, path).also { it.parentFile.mkdirs() }.writeText(text.trimIndent().replace("    ", "\t") + "\n")
        if (files.keys.any { it.startsWith("modcache/") }) {
            val tc: GoToolchainInfo = toolchain.copy(gomodcache = File(tmp, "modcache").toPath())
            val provider = object : GoToolchainProvider {
                override fun toolchainFor(project: com.intellij.openapi.project.Project?): GoToolchainInfo = tc
            }
            ApplicationManager.getApplication().replaceService(GoToolchainProvider::class.java, provider, testRootDisposable)
            GoProjectModelTracker.getInstance(project).incModificationCount()
        }
        VfsRootAccess.allowRootAccess(testRootDisposable, tmp.path)
        val top = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, top)
        root = if (content.isEmpty()) top else top.findFileByRelativePath(content)!!
        PsiTestUtil.addContentRoot(myFixture.module, root)
        root = top
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            body()
        } finally {
            FileDocumentManager.getInstance().saveAllDocuments()
            PsiTestUtil.removeContentEntry(myFixture.module, if (content.isEmpty()) top else top.findFileByRelativePath(content)!!)
        }
    }

    /** `line: text: message` of the problems [tool] reports in [path]. */
    private fun problems(path: String, tool: LocalInspectionTool): List<String> {
        myFixture.enableInspections(tool)
        myFixture.configureFromExistingVirtualFile(root.findFileByRelativePath(path) ?: error("no $path"))
        val document = myFixture.editor.document
        return myFixture.doHighlighting().filter { it.inspectionToolId == tool.shortName }.sortedBy { it.startOffset }
            .map { "${document.getLineNumber(it.startOffset) + 1}: ${it.text}: ${it.description}" }
    }

    private fun mod(path: String, extra: String = "") = "module $path\n\ngo 1.22\n$extra"

    // ---- internal ----------------------------------------------------------------------------------------------------------

    fun testInternalAllowedAndForbidden() = inProject(mapOf(
        "go.mod" to mod("example.com/pw9app"),
        "a/internal/z/z.go" to "package z\n\nfunc Z() {}",
        "a/user.go" to """
            package a

            import "example.com/pw9app/a/internal/z"

            func A() { z.Z() }
            """,
        "a/sub/sub.go" to """
            package sub

            import "example.com/pw9app/a/internal/z"

            func S() { z.Z() }
            """,
        "b/b.go" to """
            package b

            import (
                "internal/abi"

                "example.com/pw9app/a/internal/z"
            )

            var _ = abi.FuncPCABI0

            func B() { z.Z() }
            """,
    )) {
        val tool = GoInternalImportInspection()
        assertEquals(emptyList<String>(), problems("a/user.go", tool))
        assertEquals(emptyList<String>(), problems("a/sub/sub.go", tool))
        assertEquals(
            listOf(
                "4: \"internal/abi\": use of internal package internal/abi not allowed",
                "6: \"example.com/pw9app/a/internal/z\": use of internal package example.com/pw9app/a/internal/z not allowed",
            ),
            problems("b/b.go", tool),
        )
    }

    fun testInternalOfModuleCacheDependency() = inProject(mapOf(
        "app/go.mod" to mod("example.com/pw9cache", "\nrequire example.com/pw9dep v1.0.0\n"),
        "app/main.go" to """
            package main

            import (
                "example.com/pw9dep/internal/x"
                "example.com/pw9dep/pub"
            )

            func main() { x.X(); pub.P() }
            """,
        "modcache/cache/download/example.com/pw9dep/@v/v1.0.0.mod" to mod("example.com/pw9dep"),
        "modcache/example.com/pw9dep@v1.0.0/go.mod" to mod("example.com/pw9dep"),
        "modcache/example.com/pw9dep@v1.0.0/internal/x/x.go" to "package x\n\nfunc X() {}",
        "modcache/example.com/pw9dep@v1.0.0/pub/p.go" to "package pub\n\nimport \"example.com/pw9dep/internal/x\"\n\nfunc P() { x.X() }",
    ), content = "app") {
        assertEquals(
            listOf("4: \"example.com/pw9dep/internal/x\": use of internal package example.com/pw9dep/internal/x not allowed"),
            problems("app/main.go", GoInternalImportInspection()),
        )
    }

    // ---- import cycles -----------------------------------------------------------------------------------------------------

    private val cycle = mapOf(
        "go.mod" to mod("example.com/pw9cyc"),
        "a/a.go" to "package a\n\nimport \"example.com/pw9cyc/b\"\n\nfunc A() { b.B() }",
        "b/b.go" to "package b\n\nimport \"example.com/pw9cyc/c\"\n\nfunc B() { c.C() }",
        "c/c.go" to "package c\n\nimport (\n    \"fmt\"\n\n    \"example.com/pw9cyc/a\"\n)\n\nfunc C() { fmt.Println(); a.A() }",
        "d/d.go" to "package d\n\nimport \"example.com/pw9cyc/a\"\n\nfunc D() { a.A() }",
        "d/d_test.go" to "package d\n\nimport \"testing\"\n\nfunc TestD(t *testing.T) { D() }",
        "leaf/leaf.go" to "package leaf\n\nfunc L() {}",
        "leaf/leaf_test.go" to "package leaf\n\nimport (\n    \"testing\"\n\n    \"example.com/pw9cyc/d\"\n)\n\nfunc TestL(t *testing.T) { d.D() }",
        "e/e.go" to "package e\n\nimport \"example.com/pw9cyc/leaf\"\n\nfunc E() { leaf.L() }",
        "e/e_test.go" to "package e\n\nimport (\n    \"testing\"\n\n    \"example.com/pw9cyc/f\"\n)\n\nfunc TestE(t *testing.T) { f.F() }",
        "e/x_test.go" to "package e_test\n\nimport (\n    \"testing\"\n\n    \"example.com/pw9cyc/f\"\n)\n\nfunc TestX(t *testing.T) { f.F() }",
        "f/f.go" to "package f\n\nimport \"example.com/pw9cyc/e\"\n\nfunc F() { e.E() }",
    )

    fun testCycleReportedOnEveryImportOfTheLoop() = inProject(cycle) {
        val tool = GoImportCycleInspection()
        assertEquals(listOf("3: \"example.com/pw9cyc/b\": import cycle: example.com/pw9cyc/a → example.com/pw9cyc/b → example.com/pw9cyc/c → example.com/pw9cyc/a"),
            problems("a/a.go", tool))
        assertEquals(listOf("6: \"example.com/pw9cyc/a\": import cycle: example.com/pw9cyc/c → example.com/pw9cyc/a → example.com/pw9cyc/b → example.com/pw9cyc/c"),
            problems("c/c.go", tool))
        // d imports the loop but is not on it
        assertEquals(emptyList<String>(), problems("d/d.go", tool))
    }

    fun testTestFiles() = inProject(cycle) {
        val tool = GoImportCycleInspection()
        // leaf's in-package test imports d, which does not lead back to leaf; the test-only edge is not part of the graph
        assertEquals(emptyList<String>(), problems("leaf/leaf_test.go", tool))
        // e_test.go (package e) imports f, which imports e: compiled into e, that is a cycle
        assertEquals(listOf("6: \"example.com/pw9cyc/f\": import cycle not allowed in test: example.com/pw9cyc/e → example.com/pw9cyc/f → example.com/pw9cyc/e"),
            problems("e/e_test.go", tool))
        // the external test package may import f
        assertEquals(emptyList<String>(), problems("e/x_test.go", tool))
        assertEquals(emptyList<String>(), problems("f/f.go", tool))
    }

    fun testCycleGoneAfterEdit() = inProject(cycle) {
        val tool = GoImportCycleInspection()
        assertEquals(1, problems("a/a.go", tool).size)
        val c = root.findFileByRelativePath("c/c.go")!!
        val document = FileDocumentManager.getInstance().getDocument(c)!!
        ApplicationManager.getApplication().runWriteAction {
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(project, {
                document.setText("package c\n\nfunc C() {}\n")
            }, null, null)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(emptyList<String>(), problems("a/a.go", tool))
    }

    // ---- unused exported ---------------------------------------------------------------------------------------------------

    fun testUnusedExportedInApplication() = inProject(mapOf(
        "go.mod" to mod("example.com/pw9use"),
        "main.go" to "package main\n\nimport \"example.com/pw9use/store\"\n\nfunc main() { store.Used(); _ = store.Kind }",
        "store/store.go" to """
            package store

            // Used is called from main.
            func Used() {}

            func Unused() { Unused() }

            func OnlyTests() {}

            type Node struct{ next *Node }

            type Shown struct{}

            func (Shown) Method() {}

            var Kind, Spare = 1, 2

            const (
                Max = 3
                min = 1
            )

            func helper() {}
            """,
        "store/store_test.go" to "package store\n\nimport \"testing\"\n\nfunc TestStore(t *testing.T) { OnlyTests(); _ = Shown{} }",
    )) {
        assertEquals(
            listOf(
                "6: Unused: Exported function 'Unused' is never used in the project",
                "10: Node: Exported type 'Node' is never used in the project",
                "16: Spare: Exported variable 'Spare' is never used in the project",
                "19: Max: Exported constant 'Max' is never used in the project",
            ),
            problems("store/store.go", GoUnusedExportedInspection()),
        )
    }

    fun testLibraryModuleSkippedButInternalChecked() = inProject(mapOf(
        "go.mod" to mod("example.com/pw9lib"),
        "api/api.go" to "package api\n\nimport \"example.com/pw9lib/internal/impl\"\n\nfunc Public() { impl.Used() }",
        "internal/impl/impl.go" to "package impl\n\nfunc Used() {}\n\nfunc Dead() {}",
    )) {
        val tool = GoUnusedExportedInspection()
        // a library: Public may be used by other modules
        assertEquals(emptyList<String>(), problems("api/api.go", tool))
        assertEquals(listOf("5: Dead: Exported function 'Dead' is never used in the project"), problems("internal/impl/impl.go", tool))
    }

    fun testSafeDeleteFix() = inProject(mapOf(
        "go.mod" to mod("example.com/pw9fix"),
        "internal/impl/impl.go" to "package impl\n\nfunc Used() {}\n\n// Dead is never called.\nfunc Dead() {}",
        "internal/impl/use.go" to "package impl\n\nfunc init() { Used() }",
    )) {
        myFixture.enableInspections(GoUnusedExportedInspection())
        myFixture.configureFromExistingVirtualFile(root.findFileByRelativePath("internal/impl/impl.go")!!)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("Dead()"))
        myFixture.launchAction(myFixture.findSingleIntention("Safe delete"))
        assertEquals("package impl\n\nfunc Used() {}\n", myFixture.editor.document.text)
    }
}
