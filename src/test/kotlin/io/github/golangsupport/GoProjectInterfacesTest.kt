package io.github.golangsupport

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.AstLoadingFilter
import junit.framework.TestCase.assertNull
import io.github.golangsupport.lang.GoProjectInterfaces

/**
 * Runs [block] with the ASTs of [files] dropped and any loading of a Go file's AST failing the test, the way go-psi checks that resolve
 * reads stubs only (`GoResolveAstLoadingTest`).
 */
fun <T> withoutAstLoading(project: Project, disposable: Disposable, files: List<PsiFile>, block: () -> T): T {
    for (file in files) {
        WriteAction.run<Throwable> { (file as PsiFileImpl).onContentReload() }
        assertNull("the AST of ${file.name} is dropped", (file as PsiFileImpl).treeElement)
    }
    PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(VirtualFileFilter { it.extension == "go" }, disposable)
    val result = AstLoadingFilter.disallowTreeLoading<T, Throwable> { block() }
    for (file in files) assertNull("${file.name} is read from its stub", (file as PsiFileImpl).treeElement)
    return result
}

/** The interfaces of the project for Implement Interface, from the stub indices. */
class GoProjectInterfacesTest : BasePlatformTestCase() {
    fun testTheInterfacesOfTheProject() {
        myFixture.addFileToProject("shop/go.mod", "module example.com/shop\n")
        val files = listOf(
            myFixture.addFileToProject("shop/store/store.go", """
                package store

                import "io"

                type Store interface {
                	Get(id int) (string, error)
                	Put(id int, value string) error
                }

                type ReadStore interface {
                	io.Reader
                	Store
                }

                type Failure interface {
                	error
                	Code() int
                }

                type Number interface {
                	~int | ~float64
                }

                type Empty interface{}

                type Memory struct{ items map[int]string }

                func local() {
                	type Hidden interface{ Hide() }
                }
            """.trimIndent()),
            myFixture.addFileToProject("shop/store/store_test.go", "package store\n\ntype fake interface {\n\tFake()\n}\n"),
        )
        val entries = withoutAstLoading(project, testRootDisposable, files) { GoProjectInterfaces.getInstance(project).entries() }
        assertEquals(
            listOf(
                "Failure example.com/shop/store store.go [Code, Error] embeds=false",
                "ReadStore example.com/shop/store store.go [] embeds=true",
                "Store example.com/shop/store store.go [Get, Put] embeds=false",
                "fake example.com/shop/store store_test.go [Fake] embeds=false",
            ),
            entries.map { "${it.name} ${it.importPath} ${it.file.name} ${it.methods} embeds=${it.embeds}" }.sorted(),
        )
        assertTrue(entries.all { it.packageName == "store" && it.directory.name == "store" })
    }

    fun testAChangedFileIsReadAgain() {
        val file = myFixture.addFileToProject("app/handler.go", "package app\n\ntype Handler interface {\n\tServe()\n}\n")
        assertEquals(listOf("Handler"), GoProjectInterfaces.getInstance(project).entries().map { it.name })
        myFixture.saveText(file.virtualFile, "package app\n\ntype Handler interface {\n\tServe()\n}\n\ntype Closer interface {\n\tClose() error\n}\n")
        assertEquals(listOf("Closer", "Handler"), GoProjectInterfaces.getInstance(project).entries().map { it.name }.sorted())
    }
}
