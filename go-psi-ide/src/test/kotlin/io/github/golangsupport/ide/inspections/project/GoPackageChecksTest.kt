package io.github.golangsupport.ide.inspections.project

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.AstLoadingFilter
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.ide.inspections.GoDuplicateDeclarationInspection
import io.github.golangsupport.lang.psi.GoFile
import java.io.File

/** Package-level build errors over packages on disk (the project model partitions them for linux/amd64): main, package names, init cycles, build tags. */
class GoPackageChecksTest : GoSemanticIdeTestBase() {

    private lateinit var root: VirtualFile

    private fun inProject(files: Map<String, String>, body: () -> Unit) {
        val tmp = FileUtil.createTempDirectory("gopsi-pkg", null, true)
        for ((path, text) in files) File(tmp, path).also { it.parentFile.mkdirs() }.writeText(text.trimIndent().replace("    ", "\t") + "\n")
        VfsRootAccess.allowRootAccess(testRootDisposable, tmp.path)
        root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)!!
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
        PsiTestUtil.addContentRoot(myFixture.module, root)
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            body()
        } finally {
            FileDocumentManager.getInstance().saveAllDocuments()
            PsiTestUtil.removeContentEntry(myFixture.module, root)
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

    private fun goFile(path: String): GoFile = PsiManager.getInstance(project).findFile(root.findFileByRelativePath(path)!!) as GoFile

    private val mod = "go.mod" to "module example.com/pkgchecks\n\ngo 1.22\n"

    // ---- package main ------------------------------------------------------------------------------------------------------

    private val mains = mapOf(
        mod,
        "nomain/a.go" to "package main\n\nfunc helper() {}",
        "nomain/b.go" to "package main\n\nvar main2 = 1",
        "nomain/a_test.go" to "package main\n\nimport \"testing\"\n\nfunc TestA(t *testing.T) { helper() }",
        "ok/main.go" to "package main\n\nfunc main() { run() }",
        "ok/run.go" to "package main\n\nfunc run() {}",
        "other/main_windows.go" to "package main\n\nfunc main() {}",
        "other/run.go" to "package main\n\nfunc run() {}",
        "lib/lib.go" to "package lib\n\nfunc L() {}",
    )

    fun testMissingMain() = inProject(mains) {
        val tool = GoMissingMainFunctionInspection()
        assertEquals(listOf("1: main: function main is undeclared in the main package"), problems("nomain/a.go", tool))
        assertEquals(listOf("1: main: function main is undeclared in the main package"), problems("nomain/b.go", tool))
        assertEquals(emptyList<String>(), problems("nomain/a_test.go", tool))
        assertEquals(emptyList<String>(), problems("ok/run.go", tool))
        assertEquals(emptyList<String>(), problems("lib/lib.go", tool))
        // main only in a windows file: on linux the package has no main; the excluded file itself is not checked
        assertEquals(listOf("1: main: function main is undeclared in the main package"), problems("other/run.go", tool))
        assertEquals(emptyList<String>(), problems("other/main_windows.go", tool))
    }

    /** `func main` of another file is read from its stub. */
    fun testMainFromStubs() = inProject(mains) {
        val run = goFile("ok/run.go")
        val main = goFile("ok/main.go") as PsiFileImpl
        val (dir, pkg) = GoPackageChecks.projectPackageOf(run)!!
        WriteAction.run<Throwable> { main.onContentReload() }
        assertNull(main.treeElement)
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter { it == main.virtualFile }, testRootDisposable)
        AstLoadingFilter.disallowTreeLoading<Throwable> { assertTrue(GoPackageChecks.hasMain(project, dir, pkg)) }
        assertNull("main.go's AST was loaded", main.treeElement)
    }

    // ---- package names -----------------------------------------------------------------------------------------------------

    fun testMultiplePackages() = inProject(mapOf(
        mod,
        "mix/a.go" to "package a",
        "mix/b.go" to "package b",
        "mix/c_windows.go" to "package c",
        "mix/d.go" to "//go:build ignore\n\npackage main",
        "mix/doc.go" to "package documentation",
        "mix/a_test.go" to "package a_test",
        "mix/e_test.go" to "package e_test",
        "same/x.go" to "package same",
        "same/x_test.go" to "package same_test",
        "same/y_test.go" to "package same",
    )) {
        val tool = GoMultiplePackagesInspection()
        val dir = root.findFileByRelativePath("mix")!!.presentableUrl
        assertEquals(emptyList<String>(), problems("mix/a.go", tool))
        assertEquals(listOf("1: b: found packages a (a.go) and b (b.go) in $dir"), problems("mix/b.go", tool))
        assertEquals(emptyList<String>(), problems("mix/a_test.go", tool))
        assertEquals(listOf("1: e_test: found packages a (a.go) and e (e_test.go) in $dir"), problems("mix/e_test.go", tool))
        // excluded by build constraints, or package documentation
        assertEquals(emptyList<String>(), problems("mix/c_windows.go", tool))
        assertEquals(emptyList<String>(), problems("mix/d.go", tool))
        assertEquals(emptyList<String>(), problems("mix/doc.go", tool))
        for (f in listOf("same/x.go", "same/x_test.go", "same/y_test.go")) assertEquals(f, emptyList<String>(), problems(f, tool))
    }

    // ---- build constraints and redeclarations ------------------------------------------------------------------------------

    fun testRedeclarationAcrossExclusiveBuildTags() = inProject(mapOf(
        mod,
        "tags/linux.go" to "//go:build linux\n\npackage tags\n\nconst Name = \"linux\"\n\nfunc open() int { return 1 }",
        "tags/windows.go" to "//go:build windows\n\npackage tags\n\nconst Name = \"windows\"\n\nfunc open() int { return 2 }",
        "tags/use.go" to "package tags\n\nvar _ = open() + len(Name)",
        "tags/dup.go" to "package tags\n\nconst Twice = 1",
        "tags/dup2.go" to "package tags\n\nconst Twice = 2",
    )) {
        val tool = GoDuplicateDeclarationInspection()
        assertEquals(emptyList<String>(), problems("tags/linux.go", tool))
        assertEquals(emptyList<String>(), problems("tags/windows.go", tool))
        assertEquals(emptyList<String>(), problems("tags/use.go", tool))
        // files of the same build still clash
        assertEquals(listOf("3: Twice: Twice redeclared in this block"), problems("tags/dup2.go", tool))
    }

    /**
     * Regression (gomodcache corpus, golang.org/x/arch `*spec/spec.go`): `//go:build ignore` programs are never given a context with
     * `ignore` set, so each keeps the old scope (the default package plus itself) and its imports resolve as before.
     */
    fun testIgnoreFilesKeepTheDefaultScope() = inProject(mapOf(
        mod,
        "gen/lib.go" to "package gen\n\nfunc L() {}",
        "gen/one.go" to "//go:build ignore\n\npackage main\n\nimport \"strings\"\n\nfunc main() { _ = strings.ToUpper(\"a\") }",
        "gen/two.go" to "//go:build ignore\n\npackage main\n\nfunc main() {}",
        "tags/x_windows.go" to "package tags\n\nconst Name = 1",
        "tags/x_linux.go" to "package tags\n\nconst Name = 2",
    )) {
        val model = io.github.golangsupport.semantic.scope.GoPackageModel.getInstance(project)
        val one = goFile("gen/one.go")
        assertEquals(listOf("lib.go", "one.go"), model.scopeOf(one).files.map { it.name }.sorted())
        assertNotNull(model.resolveImport("strings", one))
        assertEquals(emptyList<String>(), problems("gen/one.go", GoDuplicateDeclarationInspection()))
        // an OS-excluded file still gets the package of its own platform
        assertEquals(listOf("x_windows.go"), model.scopeOf(goFile("tags/x_windows.go")).files.map { it.name })
    }

    // ---- initialization cycles across files --------------------------------------------------------------------------------

    private val cycles = mapOf(
        mod,
        "ic/a.go" to "package ic\n\nvar x = f()\n\nfunc g() int { return y }",
        "ic/b.go" to "package ic\n\nfunc f() int { return x + 1 }\n\nvar y = g()",
        "ic/c.go" to "package ic\n\nvar self = h()\n\nfunc h() int { return self }",
        "ic/d.go" to "package ic\n\nvar ok = f2()\n\nfunc f2() int { return 1 }",
        "ic/e.go" to "package ic\n\nvar z = k()",
        "ic/f.go" to "package ic\n\nfunc k() int { return 3 }",
    )

    fun testInitializationCycleAcrossFiles() = inProject(cycles) {
        val tool = GoInitializationCycleInspection()
        assertEquals(listOf("3: x: initialization cycle for x; x refers to f; f refers to x"), problems("ic/a.go", tool))
        assertEquals(listOf("5: y: initialization cycle for y; y refers to g; g refers to y"), problems("ic/b.go", tool))
        // a cycle inside one file is the checker's
        assertEquals(emptyList<String>(), problems("ic/c.go", tool))
        assertEquals(emptyList<String>(), problems("ic/e.go", tool))
    }

    /** A walk that never reaches another file's declaration keeps that file's AST unloaded. */
    fun testInitializationCycleKeepsOtherFilesUnloaded() = inProject(cycles) {
        val d = goFile("ic/d.go")
        val others = listOf("ic/a.go", "ic/b.go", "ic/c.go", "ic/e.go", "ic/f.go").map { goFile(it) as PsiFileImpl }
        WriteAction.run<Throwable> { others.forEach { it.onContentReload() } }
        val names = others.map { it.virtualFile }.toSet()
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter { it in names }, testRootDisposable)
        AstLoadingFilter.disallowTreeLoading<Throwable> { assertEquals(emptyMap<Any, String>(), GoInitializationCycleInspection.cycles(d)) }
        for (f in others) assertNull("${f.name}'s AST was loaded", f.treeElement)
    }
}
