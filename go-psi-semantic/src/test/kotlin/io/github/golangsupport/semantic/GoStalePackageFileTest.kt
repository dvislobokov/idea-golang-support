package io.github.golangsupport.semantic

import com.intellij.openapi.application.WriteAction
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.api.GoPackage
import io.github.golangsupport.semantic.scope.GoPackageModel

/**
 * A cached [GoPackage] may hold a file that was deleted and created again at the same path (seen live on `~/.aws/main.go`:
 * InvalidVirtualFileAccessException from `PsiManager.findFile`). The package scope finds the new file by path instead.
 */
class GoStalePackageFileTest : GoSemanticTestBase() {
    override val group: String get() = "types"

    fun testFileCreatedAgainAtTheSamePath() {
        val first = myFixture.addFileToProject("stale/main.go", "package main\n\nfunc old() {}\n") as GoFile
        val stale = first.virtualFile
        val dir = stale.parent
        WriteAction.run<Throwable> { stale.delete(this) }
        assertFalse(stale.isValid)
        myFixture.addFileToProject("stale/main.go", "package main\n\nfunc fresh() {}\n")
        val pkg = GoPackage("stale", "main", dir, null, listOf(stale), emptyList(), emptyList(), emptyList(), false)
        val scope = GoPackageModel.getInstance(project).scopeOf(pkg)
        assertEquals(1, scope.files.size)
        assertTrue(scope.files.single().text.contains("fresh"))
    }

    fun testFileDeletedForGoodIsSkipped() {
        val file = myFixture.addFileToProject("gone/a.go", "package gone\n") as GoFile
        val keep = myFixture.addFileToProject("gone/b.go", "package gone\n") as GoFile
        val stale = file.virtualFile
        WriteAction.run<Throwable> { stale.delete(this) }
        val pkg = GoPackage("gone", "gone", keep.virtualFile.parent, null, listOf(stale, keep.virtualFile), emptyList(), emptyList(), emptyList(), false)
        assertEquals(listOf(keep.virtualFile), GoPackageModel.getInstance(project).scopeOf(pkg).files.map { it.virtualFile })
    }
}
