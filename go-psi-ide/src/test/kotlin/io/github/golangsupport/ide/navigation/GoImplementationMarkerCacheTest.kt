package io.github.golangsupport.ide.navigation

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.openapi.command.WriteCommandAction
import io.github.golangsupport.ide.GoSemanticIdeTestBase
import io.github.golangsupport.semantic.cache.GoTrackers

/** The existence check of the implementation gutter markers is cached per owner and recomputed on relevant changes. */
class GoImplementationMarkerCacheTest : GoSemanticIdeTestBase() {

    private fun passes(): Int {
        DaemonCodeAnalyzerImpl.getInstance(project).restart(myFixture.file)
        val before = GoImplementationLineMarkerProvider.existenceComputations.get()
        myFixture.findAllGutters()
        return GoImplementationLineMarkerProvider.existenceComputations.get() - before
    }

    private fun tooltips() = myFixture.findAllGutters().mapNotNull { it.tooltipText }.sorted()

    private fun open() {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("p/iface.go", """
            package p

            type Closer interface {
                Close() error
            }
        """.trimIndent()).virtualFile)
    }

    fun testExistenceComputedOncePerFile() {
        myFixture.addFileToProject("p/impl.go", "package p\n\ntype File struct{}\n\nfunc (f *File) Close() error { return nil }\n")
        open()
        val first = passes()
        assertTrue("first pass must compute", first > 0)
        assertEquals("second pass must hit the cache", 0, passes())
        assertEquals(listOf("Is implemented by", "Is implemented by"), tooltips())
    }

    fun testRecomputedWhenImplementationAddedAndRemoved() {
        open()
        assertEquals(emptyList<String>(), tooltips())
        assertEquals(0, passes())
        val impl = myFixture.addFileToProject("p/impl.go", "package p\n\ntype File struct{}\n\nfunc (f *File) Close() error { return nil }\n")
        assertEquals(listOf("Is implemented by", "Is implemented by"), tooltips())
        assertEquals(0, passes())
        WriteCommandAction.runWriteCommandAction(project) { impl.delete() }
        assertEquals(emptyList<String>(), tooltips())
    }

    fun testImplementsDirectionRecomputedOnProjectAndLibraryChange() {
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("p/impl.go",
            "package p\n\ntype File struct{}\n\nfunc (f *File) Close() error { return nil }\n").virtualFile)
        assertEquals(emptyList<String>(), tooltips())
        assertEquals(0, passes())
        myFixture.addFileToProject("p/iface.go", "package p\n\ntype Closer interface {\n\tClose() error\n}\n")
        assertEquals(listOf("Implements", "Implements method in"), tooltips())
        assertEquals(0, passes())
        GoTrackers.getInstance(project).library.incModificationCount()
        assertTrue("library change must invalidate", passes() > 0)
        assertEquals(0, passes())
    }
}
