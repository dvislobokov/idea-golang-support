package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFile
import java.io.File

/** Move of package-level declarations over a module on disk (`example.com/app`) added as a content root. */
class GoMoveTest : GoSemanticIdeTestBase() {

    private lateinit var root: VirtualFile

    private fun inModule(files: Map<String, String>, body: () -> Unit) {
        val tmp = FileUtil.createTempDirectory("gopsi-move", null, true)
        for ((path, text) in files + ("go.mod" to "module example.com/app\n\ngo 1.22\n")) {
            File(tmp, path).also { it.parentFile.mkdirs() }.writeText(text.trimIndent().replace("    ", "\t") + "\n")
        }
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

    private fun text(path: String): String {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val vf = root.findFileByRelativePath(path) ?: error("no $path")
        return FileDocumentManager.getInstance().getDocument(vf)!!.text
    }

    private fun go(text: String): String = text.trimIndent().replace("    ", "\t") + "\n"

    private fun goFile(path: String): GoFile = psiManager.findFile(root.findFileByRelativePath(path)!!) as GoFile

    /** The package-level declaration [name] of [path] (a function, a type spec, a var or const definition). */
    private fun decl(path: String, name: String): PsiElement {
        val f = goFile(path)
        return (f.functions + f.types + f.vars + f.consts).first { it.name == name }
    }

    private fun move(path: String, names: List<String>, dir: String, file: String, methods: Boolean = true) =
        GoMoveProcessor.moveForTests(project, names.map { decl(path, it) }, GoMoveOptions(root.path + "/" + dir, file, methods))

    private fun conflicts(path: String, names: List<String>, dir: String, file: String): String {
        try {
            move(path, names, dir, file)
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            return e.messages.joinToString("\n")
        }
        fail("Expected conflicts")
        return ""
    }

    fun testFunctionToAnotherFileOfThePackage() = inModule(mapOf(
        "store/store.go" to """
            package store

            import (
                "fmt"
                "strings"
            )

            // Upper shouts.
            func Upper(s string) string { return strings.ToUpper(s) }

            func Show(s string) { fmt.Println(Upper(s)) }
        """,
    )) {
        move("store/store.go", listOf("Upper"), "store", "text.go")
        assertEquals(go("""
            package store

            import (
                "fmt"
            )

            func Show(s string) { fmt.Println(Upper(s)) }
        """), text("store/store.go"))
        assertEquals(go("""
            package store

            import "strings"

            // Upper shouts.
            func Upper(s string) string { return strings.ToUpper(s) }
        """), text("store/text.go"))
    }

    fun testSpecOfAGroupBecomesItsOwnDeclaration() = inModule(mapOf(
        "store/store.go" to """
            package store

            var (
                // Limit caps the size.
                Limit = 10
                Other = 2
            )
        """,
        "store/limits.go" to """
            package store

            var Floor = 1
        """,
    )) {
        move("store/store.go", listOf("Limit"), "store", "limits.go")
        assertEquals(go("""
            package store

            var (
                Other = 2
            )
        """), text("store/store.go"))
        assertEquals(go("""
            package store

            var Floor = 1

            // Limit caps the size.
            var Limit = 10
        """), text("store/limits.go"))
    }

    fun testTypeWithMethodsToANewPackage() = inModule(mapOf(
        "store/item.go" to """
            package store

            // Item is a thing.
            type Item struct{ Name string }

            func (i Item) Title() string { return i.Name }

            func Make() Item { return Item{Name: "x"} }
        """,
        "main.go" to """
            package main

            import "example.com/app/store"

            func main() { _ = store.Item{}.Title() }
        """,
    )) {
        move("store/item.go", listOf("Item"), "model", "item.go")
        assertEquals(go("""
            package model

            // Item is a thing.
            type Item struct{ Name string }

            func (i Item) Title() string { return i.Name }
        """), text("model/item.go"))
        assertEquals(go("""
            package store

            import "example.com/app/model"

            func Make() model.Item { return model.Item{Name: "x"} }
        """), text("store/item.go"))
        assertEquals(go("""
            package main

            import (
                "example.com/app/model"
            )

            func main() { _ = model.Item{}.Title() }
        """), text("main.go"))
    }

    fun testBackIntoThePackageThatUsedIt() = inModule(mapOf(
        "store/price.go" to """
            package store

            type Price int

            const Cheap Price = 1

            func Twice(p Price) Price { return p * 2 }
        """,
        "model/model.go" to """
            package model

            import "example.com/app/store"

            func Use() store.Price { return store.Cheap }
        """,
    )) {
        move("store/price.go", listOf("Price", "Cheap"), "model", "price.go")
        assertEquals(go("""
            package model

            func Use() Price { return Cheap }
        """), text("model/model.go"))
        assertEquals(go("""
            package model

            type Price int

            const Cheap Price = 1
        """), text("model/price.go"))
        assertEquals(go("""
            package store

            import "example.com/app/model"

            func Twice(p model.Price) model.Price { return p * 2 }
        """), text("store/price.go"))
    }

    fun testMovedCodeQualifiesWhatItUsesFromTheSource() = inModule(mapOf(
        "store/store.go" to """
            package store

            import "strings"

            type Item struct{ Name string }

            func Shout(i Item) string { return strings.ToUpper(i.Name) + Suffix }

            const Suffix = "!"
        """,
    )) {
        move("store/store.go", listOf("Shout"), "loud", "loud.go")
        assertEquals(go("""
            package loud

            import (
                "strings"

                "example.com/app/store"
            )

            func Shout(i store.Item) string { return strings.ToUpper(i.Name) + store.Suffix }
        """), text("loud/loud.go"))
        assertEquals(go("""
            package store

            type Item struct{ Name string }

            const Suffix = "!"
        """), text("store/store.go"))
    }

    fun testUnexportedNameUsedFromTheSource() = inModule(mapOf(
        "store/store.go" to """
            package store

            func helper() int { return 1 }

            func Use() int { return helper() }
        """,
        "util/util.go" to "package util\n",
    )) {
        assertTrue(conflicts("store/store.go", listOf("helper"), "util", "util.go").contains("helper is unexported and used from package store"))
        assertTrue(conflicts("store/store.go", listOf("Use"), "util", "util.go").contains("helper is unexported and used from package util"))
    }

    fun testImportCycle() = inModule(mapOf(
        "store/store.go" to """
            package store

            type Item struct{ Name string }

            func Load() Item { return Item{} }

            func Reload() Item { return Load() }
        """,
        "util/util.go" to """
            package util

            import "example.com/app/store"

            func Name(i store.Item) string { return i.Name }
        """,
    )) {
        assertTrue(conflicts("store/store.go", listOf("Load"), "util", "util.go").contains("Import cycle"))
    }

    fun testNameClashInTheTargetPackage() = inModule(mapOf(
        "store/store.go" to """
            package store

            func Load() int { return 1 }
        """,
        "util/util.go" to """
            package util

            func Load() int { return 2 }
        """,
    )) {
        assertTrue(conflicts("store/store.go", listOf("Load"), "util", "more.go").contains("'Load' is already declared in package util"))
    }

    fun testOneConstantOfAnIotaGroupIsRefused() = inModule(mapOf(
        "store/store.go" to """
            package store

            const (
                First = iota
                Second
            )
        """,
    )) {
        try {
            move("store/store.go", listOf("Second"), "store", "consts.go")
            fail("refusal expected")
        } catch (e: GoMoveRefusal) {
            assertTrue(e.message, e.message!!.contains("move the whole group"))
        }
        move("store/store.go", listOf("First", "Second"), "store", "consts.go")
        assertEquals(go("package store"), text("store/store.go"))
        assertEquals(go("""
            package store

            const (
                First = iota
                Second
            )
        """), text("store/consts.go"))
    }

    fun testSelectionTakesTheDeclarationsItTouches() = inModule(mapOf(
        "store/store.go" to """
            package store

            func One() int { return 1 }

            func Two() int { return 2 }

            type (
                A int
                B struct {
                    n int
                }
            )
        """,
    )) {
        val f = goFile("store/store.go")
        val text = f.text
        val picked = GoMoveDeclarations.inRange(f, com.intellij.openapi.util.TextRange(text.indexOf("Two") - 2, text.indexOf("A int") + 1))
        assertEquals(listOf("func Two", "type A"), GoMoveDeclarations.describe(GoMoveDeclarations.units(picked, withMethods = false, crossPackage = false)))
        GoMoveProcessor.moveForTests(project, picked, GoMoveOptions(root.path + "/store", "more.go"))
        assertEquals(go("""
            package store

            func Two() int { return 2 }

            type A int
        """), text("store/more.go"))
        assertEquals(go("""
            package store

            func One() int { return 1 }

            type (
                B struct {
                    n int
                }
            )
        """), text("store/store.go"))
    }

    fun testStructSpecOfAGroupLosesTheGroupIndent() = inModule(mapOf(
        "store/store.go" to """
            package store

            type (
                Small int
                // Box holds one.
                Box struct {
                    n int
                }
            )
        """,
    )) {
        move("store/store.go", listOf("Box"), "store", "box.go")
        assertEquals(go("""
            package store

            // Box holds one.
            type Box struct {
                n int
            }
        """), text("store/box.go"))
        assertEquals(go("""
            package store

            type (
                Small int
            )
        """), text("store/store.go"))
    }

    fun testGateOff() = inModule(mapOf("store/store.go" to "package store\n\nfunc Load() int { return 1 }")) {
        val element = decl("store/store.go", "Load")
        assertTrue(GoMoveHandler().canMove(arrayOf(element), null, null))
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.RENAME
        }, testRootDisposable)
        assertFalse(GoMoveHandler().canMove(arrayOf(element), null, null))
        assertFalse(GoMoveHandler().tryToMove(element, project, null, null, null))
    }
}
