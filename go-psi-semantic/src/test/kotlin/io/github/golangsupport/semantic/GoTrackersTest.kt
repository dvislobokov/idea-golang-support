package io.github.golangsupport.semantic

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import io.github.golangsupport.lang.psi.GoFile
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.FileBasedIndex
import io.github.golangsupport.lang.index.GoFileImportsIndex
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.scope.GoPackageModel

/**
 * Out-of-block changes are tracked per package. An edit inside a function body must not bump the
 * package tracker (regression: the platform sends a generic `childrenChanged` with the file as
 * parent after every commit, and it was classified as an out-of-block change, so every keystroke
 * flushed all package-level caches). A top-level edit bumps its own package and the packages
 * importing it, but neither unrelated project packages nor library code.
 */
class GoTrackersTest : GoSemanticTestBase() {

    override val group: String get() = "types"

    private val trackers get() = GoTrackers.getInstance(project)

    private fun edit(file: GoFile, marker: String, insert: String) {
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val offset = doc.text.indexOf(marker)
        assertTrue(offset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(offset, insert)
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }
    }

    private fun packageCount(file: GoFile): Long = trackers.forPackage(file.virtualFile.parent).modificationCount

    fun testBodyEditBumpsOnlyTheFileTracker() {
        val file = myFixture.addFileToProject(
            "p/a.go",
            "package p\n\nfunc f() int {\n\tx := 1\n\t/*BODY*/\n\treturn x\n}\n\n/*TOP*/\n",
        ) as GoFile
        val pkg = packageCount(file)
        val fileTracker = trackers.forFile(file).modificationCount

        edit(file, "/*BODY*/", "x++\n\t")
        assertEquals("a body edit must not bump the package tracker", pkg, packageCount(file))
        assertTrue("a body edit bumps the file tracker", trackers.forFile(file).modificationCount > fileTracker)

        edit(file, "/*TOP*/", "var y = 2\n")
        assertTrue("a top-level edit bumps the package tracker", packageCount(file) > pkg)
    }

    fun testSignatureEditIsOutOfBlock() {
        val file = myFixture.addFileToProject("q/a.go", "package q\n\nfunc g(/*PARAM*/) {}\n") as GoFile
        val pkg = packageCount(file)
        edit(file, "/*PARAM*/", "n int")
        assertTrue("a signature edit is out of block", packageCount(file) > pkg)
    }

    fun testTopLevelEditBumpsImportersOnly() {
        val a = myFixture.addFileToProject("t/a/a.go", "package a\n\ntype T int\n\n/*TOP*/\n") as GoFile
        val b = myFixture.addFileToProject("t/b/b.go", "package b\n\nimport \"../a\"\n\nvar V a.T\n") as GoFile
        val c = myFixture.addFileToProject("t/c/c.go", "package c\n\nimport \"../b\"\n\nvar W = b.V\n") as GoFile
        val u = myFixture.addFileToProject("t/u/u.go", "package u\n\nvar U = 1\n") as GoFile
        val counts = listOf(a, b, c, u).map(::packageCount)
        val library = trackers.library.modificationCount

        edit(a, "/*TOP*/", "var Z = 2\n")
        assertTrue("own package", packageCount(a) > counts[0])
        assertTrue("direct importer", packageCount(b) > counts[1])
        assertTrue("transitive importer", packageCount(c) > counts[2])
        assertEquals("unrelated package", counts[3], packageCount(u))
        assertEquals("library tracker", library, trackers.library.modificationCount)
    }

    fun testAddingAFileBumpsItsPackage() {
        val a = myFixture.addFileToProject("n/a.go", "package n\n") as GoFile
        val pkg = packageCount(a)
        myFixture.addFileToProject("n/b.go", "package n\n\nvar B = 1\n")
        assertTrue("a new file changes the package", packageCount(a) > pkg)
    }

    private fun count(deps: Array<Any>): Long = deps.filterIsInstance<ModificationTracker>().sumOf { it.modificationCount }

    fun testImportedPackageEditKeepsOwnDependenciesOfImporter() {
        val a = myFixture.addFileToProject("o/a/a.go", "package a\n\ntype T int\n\n/*TOP*/\n") as GoFile
        val b = myFixture.addFileToProject("o/b/b.go", "package b\n\nimport \"../a\"\n\nvar V a.T\n") as GoFile
        val model = GoPackageModel.getInstance(project)
        val scope = model.scopeOf(b)
        assertEquals(1, scope.lookup("V").size)
        val own = count(trackers.ownPackageDependencies(b))
        val closure = count(trackers.packageDependencies(b))

        edit(a, "/*TOP*/", "var Z = 2\n")
        assertEquals("an imported package edit keeps the importer's own dependencies", own, count(trackers.ownPackageDependencies(b)))
        assertTrue("an imported package edit bumps the importer's package dependencies", count(trackers.packageDependencies(b)) > closure)
        assertSame("the importer's package scope survives", scope, model.scopeOf(b))
        assertEquals("the imported package's scope sees the new name", 1, model.scopeOf(a).lookup("Z").size)
    }

    fun testTopLevelEditRebuildsOnlyThatFilesDeclarations() {
        val a = myFixture.addFileToProject("f/a.go", "package f\n\nfunc A() {\n\t/*BODY*/\n}\n\n/*TOP*/\n") as GoFile
        val b = myFixture.addFileToProject("f/b.go", "package f\n\ntype B int\n\nfunc (B) M() {}\n") as GoFile
        val model = GoPackageModel.getInstance(project)
        assertEquals(1, model.scopeOf(a).methodsOf("B").size)
        val declA = GoPackageModel.fileDeclarations(a)
        val declB = GoPackageModel.fileDeclarations(b)

        edit(a, "/*BODY*/", "_ = 1\n\t")
        assertSame("a body edit keeps the file's declarations", declA, GoPackageModel.fileDeclarations(a))

        edit(a, "/*TOP*/", "var Z = 2\n")
        assertNotSame("a top-level edit rebuilds the edited file's declarations", declA, GoPackageModel.fileDeclarations(a))
        assertSame("a top-level edit keeps the other file's declarations", declB, GoPackageModel.fileDeclarations(b))
        val scope = model.scopeOf(b)
        assertEquals("the merged scope sees the new name", 1, scope.lookup("Z").size)
        assertEquals(1, scope.lookup("A").size)
        assertEquals(1, scope.methodsOf("B").size)
    }

    fun testImportPathsFromIndexMatchPsi() {
        val a = myFixture.addFileToProject(
            "i/p/a.go",
            "package p\n\nimport (\n\t\"fmt\"\n\tq \"../q\"\n)\n\nvar _ = fmt.Sprint(q.Q)\n",
        ) as GoFile
        myFixture.addFileToProject("i/p/b.go", "package p\n\nimport \"strings\"\n\nvar _ = strings.ToUpper\n")
        myFixture.addFileToProject("i/p/c_test.go", "package p\n\nimport \"testing\"\n\nfunc TestX(t *testing.T) {}\n")
        myFixture.addFileToProject("i/q/q.go", "package q\n\nvar Q = 1\n")
        val dir = a.virtualFile.parent

        fun normalize(list: List<Pair<VirtualFile, Collection<String>>>) = list.associate { it.first.name to it.second.toSet() }
        val fromPsi = normalize(trackers.importPathsOf(dir, useIndex = false))
        assertEquals(
            mapOf("a.go" to setOf("fmt", "../q"), "b.go" to setOf("strings"), "c_test.go" to setOf("testing")),
            fromPsi,
        )
        assertEquals(fromPsi, normalize(trackers.importPathsOf(dir, useIndex = true)))
        for (vf in dir.children) {
            val indexed = FileBasedIndex.getInstance().getFileData(GoFileImportsIndex.NAME, vf, project).keys
            assertEquals("index data of ${vf.name}", fromPsi[vf.name], indexed)
        }
    }

    fun testImportAddedInUnsavedDocumentJoinsTheClosure() {
        val b = myFixture.addFileToProject("j/b/b.go", "package b\n\n/*IMPORT*/\n\nvar V = 1\n") as GoFile
        val u = myFixture.addFileToProject("j/u/u.go", "package u\n\nvar U = 1\n\n/*TOP*/\n") as GoFile
        val before = packageCount(b)
        edit(u, "/*TOP*/", "var Y = 2\n")
        assertEquals("not imported yet", before, packageCount(b))

        edit(b, "/*IMPORT*/", "import \"../u\"\n\nvar _ = u.U\n")
        val afterImport = packageCount(b)
        edit(u, "/*TOP*/", "var W = 3\n")
        assertTrue("an import added in an unsaved document is part of the closure", packageCount(b) > afterImport)
    }
}
