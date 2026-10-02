package io.github.golangsupport.semantic

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.project.impl.GoProjectModelTracker
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeRenderer
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * Per-package invalidation of the semantic caches (`GoTrackers`): a declaration edit invalidates
 * its own package and the project packages importing it (transitively), never unrelated project
 * packages or library caches; library edits and project-model changes invalidate library caches.
 */
class GoCacheInvalidationTest : GoSemanticTestBase() {

    override val group: String get() = "types"

    private fun edit(file: GoFile, marker: String, replacement: String) {
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val offset = doc.text.indexOf(marker)
        assertTrue("marker $marker not found", offset >= 0)
        WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(offset, offset + marker.length, replacement)
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }
    }

    /** The expression whose text is [text] (the largest one), found in the current PSI of [file]. */
    private fun expr(file: GoFile, text: String): GoExpression =
        PsiTreeUtil.findChildrenOfType(file, GoExpression::class.java).firstOrNull { it.text == text }
            ?: error("no expression '$text' in ${file.name}")

    private fun typeText(file: GoFile, text: String): String = GoTypeRenderer.render(semantic.typeOf(expr(file, text)))

    private fun addPackages(): Triple<GoFile, GoFile, GoFile> {
        val a = myFixture.addFileToProject("inv/a/a.go", "package a\n\ntype T struct {\n\tX /*FIELD*/int\n}\n") as GoFile
        val b = myFixture.addFileToProject(
            "inv/b/b.go",
            "package b\n\nimport \"../a\"\n\nvar V a.T\n\nfunc F(t a.T) {\n\t_ = t.X\n}\n",
        ) as GoFile
        val c = myFixture.addFileToProject("inv/c/c.go", "package c\n\nimport \"../b\"\n\nvar W = b.V.X\n") as GoFile
        return Triple(a, b, c)
    }

    fun testExportedDeclarationEditInvalidatesImporters() {
        val (a, b, c) = addPackages()
        assertEquals("int", typeText(b, "t.X"))
        assertEquals("int", typeText(c, "b.V.X"))

        edit(a, "/*FIELD*/int", "/*FIELD*/string")
        assertEquals("a body expression of the importer sees the new field type", "string", typeText(b, "t.X"))
        assertEquals("a transitive importer sees the new field type", "string", typeText(c, "b.V.X"))
    }

    fun testEditInUnrelatedProjectPackageKeepsCaches() {
        val (_, b, c) = addPackages()
        val other = myFixture.addFileToProject("inv/other/o.go", "package other\n\ntype O struct {\n\tY /*FIELD*/int\n}\n") as GoFile
        val tracker = GoTrackers.getInstance(project).forPackage(c.virtualFile.parent)
        val count = tracker.modificationCount
        val body = expr(b, "t") // a named type: a recomputation allocates a new instance
        val before = semantic.typeOf(body)
        val packageLevel = expr(c, "b.V")
        val beforeW = semantic.typeOf(packageLevel)
        edit(other, "/*FIELD*/int", "/*FIELD*/bool")
        assertEquals("an unrelated package edit keeps the importer's tracker", count, tracker.modificationCount)
        assertSame("an unrelated package edit keeps the body cache", before, semantic.typeOf(body))
        assertSame("an unrelated package edit keeps the package-level cache", beforeW, semantic.typeOf(packageLevel))
    }

    private fun gorootFile(relative: String): GoFile {
        val vf = vfs(ProjectTestUtil.goroot().resolve("src").resolve(relative))
        return PsiManager.getInstance(project).findFile(vf) as GoFile
    }

    /** Expressions of a GOROOT file with a composite (freshly allocated) type, to compare cache identity. */
    private fun libraryProbe(): List<Pair<GoExpression, GoType>> {
        val file = gorootFile("strings/strings.go")
        return PsiTreeUtil.findChildrenOfType(file, GoExpression::class.java).asSequence()
            .map { it to semantic.typeOf(it) }
            .filter { (_, t) -> t !is GoBasicType && t !is GoUnknownType }
            .take(50).toList()
            .also { assertTrue("no library expressions with composite types", it.size >= 10) }
    }

    fun testProjectEditKeepsLibraryCaches() {
        val (a, _, _) = addPackages()
        val probe = libraryProbe()
        edit(a, "/*FIELD*/int", "/*FIELD*/string")
        for ((e, t) in probe) assertSame("library type of '${e.text}' recomputed after a project edit", t, semantic.typeOf(e))
    }

    fun testProjectModelChangeRecomputesLibraryCaches() {
        val probe = libraryProbe()
        GoProjectModelTracker.getInstance(project).incModificationCount()
        assertTrue("library caches survive a project-model change", probe.any { (e, t) -> semantic.typeOf(e) !== t })
    }

    fun testLibraryEditInvalidatesLibraryTypes() {
        // `vendor` inside the project content is library code: one shared library tracker.
        val lib = myFixture.addFileToProject("inv/vendor/lib/lib.go", "package lib\n\nvar L /*TYPE*/int\n") as GoFile
        val use = myFixture.addFileToProject("inv/use/use.go", "package use\n\nimport \"../vendor/lib\"\n\nvar U = lib.L\n") as GoFile
        assertEquals("int", typeText(use, "lib.L"))
        assertSame("vendor is library code", GoTrackers.getInstance(project).library, GoTrackers.getInstance(project).forPackage(lib.virtualFile.parent))
        val probe = libraryProbe()
        edit(lib, "/*TYPE*/int", "/*TYPE*/string")
        assertEquals("a project importer sees the library change", "string", typeText(use, "lib.L"))
        assertTrue("library caches are dropped by a library edit", probe.any { (e, t) -> semantic.typeOf(e) !== t })
    }

    fun testFirstGoFileMakesUnresolvedImportResolve() {
        // The directory exists but holds no Go file: the import does not resolve yet.
        myFixture.addFileToProject("inv/d/README.txt", "not a package yet\n")
        val use = myFixture.addFileToProject("inv/useD/u.go", "package useD\n\nimport \"../d\"\n\nvar U = d.D\n") as GoFile
        assertFalse("no package before its first Go file", typeText(use, "d.D") == "int")

        val d = myFixture.addFileToProject("inv/d/d.go", "package d\n\nvar D int\n")
        assertEquals("the importer sees the package created by its first Go file", "int", typeText(use, "d.D"))

        WriteCommandAction.runWriteCommandAction(project) { d.delete() }
        assertFalse("the importer sees the package removed with its last Go file", typeText(use, "d.D") == "int")
    }
}
