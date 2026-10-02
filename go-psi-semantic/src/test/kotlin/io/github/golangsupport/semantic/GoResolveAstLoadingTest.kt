package io.github.golangsupport.semantic

import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.AstLoadingFilter
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.semantic.api.GoSemanticService

/**
 * Cross-file resolution must work from stubs: resolving references in `use.go` to declarations
 * in `decl.go` must not load `decl.go`'s AST while it has a stub.
 */
class GoResolveAstLoadingTest : GoProjectModelTestBase() {

    fun testCrossFileResolveDoesNotLoadAst() {
        val decl = myFixture.addFileToProject("pkg/decl.go", """
            package pkg

            type Point struct{ X, Y int }

            func (p *Point) Move(dx int) { p.X += dx }

            func New(x int) *Point { return &Point{X: x} }

            const Origin = 0

            var Default = New(Origin)
        """.trimIndent()) as GoFile
        val use = myFixture.addFileToProject("pkg/use.go", """
            package pkg

            func use() int {
                p := New(1)
                p.Move(Origin)
                var q Point = *Default
                return p.X + q.Y
            }
        """.trimIndent()) as GoFile

        // Drop decl.go's AST and make sure a stub exists for it.
        (decl as PsiFileImpl).let { f ->
            f.calcTreeElement()
            // Force stub-based mode: unload the tree and verify the stub is used from now on.
            com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction { f.onContentReload() }
            assertNotNull("decl.go must have a stub", f.stub)
        }

        val semantic = GoSemanticService.getInstance(project)
        val refs = PsiTreeUtil.findChildrenOfType(use, GoReferenceExpression::class.java)
        // Any AST load of decl.go now throws with the loading stack.
        com.intellij.psi.impl.PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(
            com.intellij.openapi.vfs.VirtualFileFilter { it == decl.virtualFile }, testRootDisposable)
        AstLoadingFilter.disallowTreeLoading<Throwable> {
            for (ref in refs) {
                val targets = semantic.resolve(ref)
                assertTrue("${ref.text} must resolve", targets.isNotEmpty())
                if (targets[0].containingFile == decl) {
                    assertNull("resolving '${ref.text}' loaded decl.go's AST", (decl as PsiFileImpl).treeElement)
                }
            }
            val fn = decl.functions.first { it.name == "New" }
            assertEquals("func(x int) *Point", semantic.render(semantic.declarationType(fn)))
            assertNull("computing a stub-based function type loaded the AST", (decl as PsiFileImpl).treeElement)
            val pt = decl.types.first { it.name == "Point" }
            assertEquals("Point", semantic.render(semantic.declarationType(pt)))
        }
    }
}
