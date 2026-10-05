package io.github.golangsupport.ide.rename

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.replaceService
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFile
import java.io.File

/** Rename Package over a module on disk (`example.com/app`) added as a content root. */
class GoRenamePackageTest : GoSemanticIdeTestBase() {

    private val base = mapOf(
        "go.mod" to "module example.com/app\n\ngo 1.22\n",
        "store/store.go" to "package store\n\ntype Item struct{}\n\nfunc Load() Item { return Item{} }\n",
        "store/store_test.go" to "package store\n\nfunc helperForTest() {}\n",
        "store/x_test.go" to "package store_test\n\nimport \"example.com/app/store\"\n\nvar _ = store.Load\n",
        "store/sub/sub.go" to "package sub\n\nfunc Sub() {}\n",
        "main.go" to MAIN,
        "alias/alias.go" to "package alias\n\nimport st `example.com/app/store`\n\nvar V = st.Load()\n",
        "cmd/tool/main.go" to "package main\n\nfunc main() {}\n",
        "util/util.go" to "package helpers\n\nfunc Help() {}\n",
        "uses/uses.go" to "package uses\n\nimport \"example.com/app/util\"\n\nvar _ = helpers.Help\n",
    )

    private lateinit var root: VirtualFile

    private fun inModule(extra: Map<String, String> = emptyMap(), body: () -> Unit) {
        val tmp = FileUtil.createTempDirectory("gopsi-rename-package", null, true)
        for ((path, text) in base + extra) File(tmp, path).also { it.parentFile.mkdirs() }.writeText(text)
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

    private fun goFile(path: String): GoFile = psiManager.findFile(root.findFileByRelativePath(path)!!) as GoFile

    private fun dir(path: String) = psiManager.findDirectory(root.findFileByRelativePath(path)!!)!!

    /** Opens [path] with the caret at the first [marker] (plus [shift]) and renames the element there to [newName]. */
    private fun renameAt(path: String, marker: String, newName: String, shift: Int = 1) {
        myFixture.configureFromExistingVirtualFile(root.findFileByRelativePath(path)!!)
        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf(marker) + shift)
        myFixture.renameElementAtCaret(newName)
    }

    private fun assertStoreRenamedToShop() {
        assertNull(root.findChild("store"))
        assertEquals("package shop\n\ntype Item struct{}\n\nfunc Load() Item { return Item{} }\n", text("shop/store.go"))
        assertEquals("package shop\n\nfunc helperForTest() {}\n", text("shop/store_test.go"))
        assertEquals("package shop_test\n\nimport \"example.com/app/shop\"\n\nvar _ = shop.Load\n", text("shop/x_test.go"))
        assertEquals("package sub\n\nfunc Sub() {}\n", text("shop/sub/sub.go"))
        assertEquals(MAIN.replace("store", "shop"), text("main.go"))
        // An aliased import keeps its alias (and the raw string quotes); only the path changes.
        assertEquals("package alias\n\nimport st `example.com/app/shop`\n\nvar V = st.Load()\n", text("alias/alias.go"))
    }

    fun testPackageClauseRenamesFilesDirectoryImportersAndQualifiers() = inModule {
        renameAt("store/store.go", "package store", "shop", shift = "package ".length + 1)
        assertStoreRenamedToShop()
    }

    fun testQualifierOfUnaliasedImportRenamesThePackage() = inModule {
        renameAt("main.go", "store.Load", "shop")
        assertStoreRenamedToShop()
    }

    fun testExternalTestClauseRenamesThePackageUnderTest() = inModule {
        renameAt("store/x_test.go", "package store_test", "shop", shift = "package ".length + 1)
        assertStoreRenamedToShop()
    }

    fun testDirectoryRenamedAfterItsPackage() = inModule {
        myFixture.renameElement(dir("store"), "shop")
        assertStoreRenamedToShop()
    }

    fun testDirectoryRenameWithNonIdentifierNameKeepsPackageClause() = inModule {
        myFixture.renameElement(dir("store"), "my-store")
        assertEquals("package store\n\ntype Item struct{}\n\nfunc Load() Item { return Item{} }\n", text("my-store/store.go"))
        assertEquals(MAIN.replace("app/store", "app/my-store"), text("main.go"))
        assertEquals("package store_test\n\nimport \"example.com/app/my-store\"\n\nvar _ = store.Load\n", text("my-store/x_test.go"))
    }

    fun testDirectoryRenameWithDifferentPackageNameChangesPathsOnly() = inModule {
        myFixture.renameElement(dir("util"), "tools")
        assertEquals("package helpers\n\nfunc Help() {}\n", text("tools/util.go"))
        assertEquals("package uses\n\nimport \"example.com/app/tools\"\n\nvar _ = helpers.Help\n", text("uses/uses.go"))
    }

    fun testPackageRenameKeepsTheDirectoryWhenToldNever() = inModule {
        val options = io.github.golangsupport.ide.GoIdeOptions.getInstance()
        options.renamePackageDirectory = io.github.golangsupport.ide.GoRenameChoice.NEVER
        try {
            renameAt("store/store.go", "package store", "shop", shift = "package ".length + 1)
            assertNotNull(root.findChild("store"))
            assertEquals("package shop\n\ntype Item struct{}\n\nfunc Load() Item { return Item{} }\n", text("store/store.go"))
        } finally {
            options.renamePackageDirectory = io.github.golangsupport.ide.GoRenameChoice.ASK
        }
    }

    fun testDirectoryRenameKeepsThePackageWhenToldNever() = inModule {
        val options = io.github.golangsupport.ide.GoIdeOptions.getInstance()
        options.renameDirectoryPackage = io.github.golangsupport.ide.GoRenameChoice.NEVER
        try {
            myFixture.renameElement(dir("store"), "shop")
            assertEquals("package store\n\ntype Item struct{}\n\nfunc Load() Item { return Item{} }\n", text("shop/store.go"))
            assertEquals(MAIN.replace("app/store", "app/shop"), text("main.go"))
        } finally {
            options.renameDirectoryPackage = io.github.golangsupport.ide.GoRenameChoice.ASK
        }
    }

    fun testMainPackageKeepsItsName() = inModule {
        myFixture.renameElement(dir("cmd/tool"), "server")
        assertEquals("package main\n\nfunc main() {}\n", text("cmd/server/main.go"))
    }

    fun testConflictWithTopLevelNameOfImporter() = inModule(mapOf("conflict.go" to "package main\n\nfunc shop() {}\n")) {
        try {
            renameAt("store/store.go", "package store", "shop", shift = "package ".length + 1)
            fail("conflict expected")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.toString(), e.messages.any { "'shop' is already declared in package main of main.go" in it })
        }
    }

    fun testConflictWithAnotherImport() = inModule(mapOf("other/shop/shop.go" to "package shop\n\nfunc Open() {}\n",
        "two/two.go" to "package two\n\nimport (\n\t\"example.com/app/other/shop\"\n\t\"example.com/app/store\"\n)\n\nvar _, _ = shop.Open, store.Load\n")) {
        try {
            renameAt("store/store.go", "package store", "shop", shift = "package ".length + 1)
            fail("conflict expected")
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            assertTrue(e.messages.toString(), e.messages.any { "two.go already imports \"example.com/app/other/shop\" as 'shop'" in it })
        }
    }

    fun testLibraryPackageIsVetoedAndClosedGateStandsDown() = inModule {
        val processor = GoRenamePackageProcessor()
        val clause = goFile("store/store.go").packageClause!!
        assertTrue(processor.canProcessElement(clause))
        assertTrue(processor.canProcessElement(dir("store")))
        assertFalse(processor.canProcessElement(psiManager.findDirectory(root)!!)) // the module root: path comes from go.mod
        val strings = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(goroot().resolve("src/strings/strings.go"))!!
        val library = (psiManager.findFile(strings) as GoFile).packageClause!!
        assertFalse(processor.canProcessElement(library))
        assertTrue(GoLibraryPackageRenameVeto().value(library))
        ApplicationManager.getApplication().replaceService(GoIdeFeatureGate::class.java, object : GoIdeFeatureGate {
            override fun enabled(feature: GoIdeFeature, project: Project): Boolean = feature != GoIdeFeature.RENAME
        }, testRootDisposable)
        assertFalse(processor.canProcessElement(clause))
        assertFalse(processor.canProcessElement(dir("store")))
        assertFalse(GoLibraryPackageRenameVeto().value(library))
    }

    companion object {
        private val MAIN = """
            package main

            import (
            	"fmt"

            	"example.com/app/store"
            	"example.com/app/store/sub"
            )

            func main() {
            	var it store.Item = store.Load()
            	fmt.Println(it)
            	sub.Sub()
            }
        """.trimIndent() + "\n"
    }
}
