package io.github.golangsupport.ide.navigation

import com.intellij.codeInsight.codeVision.settings.CodeVisionSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.lang.psi.GoFile

/**
 * Gutter markers and implementation search work from stubs: computing "implemented by" for an
 * interface must not load the AST of the files declaring the implementations, and the use scope of
 * a package-level declaration is computed without loading its file's AST.
 */
class GoImplementationsAstLoadingTest : GoSemanticIdeTestBase() {

    fun testMarkersAndSearchDoNotLoadImplementationFiles() {
        // the highlighting pass also runs the code vision; its usages count resolves references and loads the implementing file
        // (the nature of the word-index search, not of the markers): off for this test, the implementations hint has its own guard
        val settings = CodeVisionSettings.getInstance()
        val wasEnabled = settings.codeVisionEnabled
        settings.codeVisionEnabled = false
        Disposer.register(testRootDisposable) { settings.codeVisionEnabled = wasEnabled }
        val impl = myFixture.addFileToProject("p/impl.go", """
            package p

            type File struct{ name string }

            func (f *File) Read(b []byte) (int, error) { return 0, nil }
            func (f *File) Close() error { return nil }

            type Half struct{}

            func (Half) Read(b []byte) (int, error) { return 0, nil }
        """.trimIndent()) as GoFile
        unloadAst(impl)
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter { it == impl.virtualFile }, testRootDisposable)

        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("p/iface.go", """
            package p

            type ReadCloser interface {
                Read(b []byte) (int, error)
                Close() error
            }
        """.trimIndent()).virtualFile)
        val tooltips = myFixture.findAllGutters().mapNotNull { it.tooltipText }.sorted()
        assertEquals(listOf("Is implemented by", "Is implemented by", "Is implemented by"), tooltips)
        val iface = (myFixture.file as GoFile).types.single()
        assertEquals(listOf("File"), GoImplementations.implementingTypes(iface, GlobalSearchScope.projectScope(project)).map { it.name })
        assertNull("implementation search loaded impl.go's AST", (impl as PsiFileImpl).treeElement)
        // Use scope of an unexported stub-backed declaration: the package directory, no AST needed.
        val file = impl.types.first { it.name == "File" }
        assertNotNull(file.useScope)
        assertNull("use scope loaded impl.go's AST", impl.treeElement)
    }

    private fun unloadAst(file: GoFile) {
        val impl = file as PsiFileImpl
        ApplicationManager.getApplication().runWriteAction { impl.onContentReload() }
        assertNotNull("${file.name} must have a stub", impl.stub)
        assertNull(impl.treeElement)
    }
}
