package io.github.golangsupport.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoPackageModel

/**
 * Completion and intentions run on a non-physical copy of the file (no directory). Package-level names
 * from sibling files and imports must resolve from the copy exactly as from the original.
 */
class GoFileCopyTest : GoProjectModelTestBase() {
    private val a = """
        package cp

        import (
            "fmt"
            str "strings"
        )

        func useAll() {
            fmt.Println(str.ToUpper(helper()), Other{}.N, Konst)
        }
    """.trimIndent()
    private val b = """
        package cp

        type Other struct{ N int }

        const Konst = 1

        func helper() string { return "" }
    """.trimIndent()

    private fun resolveAll(file: GoFile): Map<String, String?> {
        val result = LinkedHashMap<String, String?>()
        for (ref in PsiTreeUtil.findChildrenOfType(file, GoReferenceExpression::class.java)) {
            val target: PsiElement? = ref.reference?.resolve()
            result[ref.text] = target?.let { it.containingFile?.originalFile?.name + ":" + it.textOffset }
        }
        return result
    }

    fun testCopyResolvesLikeOriginal() {
        val original = myFixture.addFileToProject("cp/a.go", a) as GoFile
        myFixture.addFileToProject("cp/b.go", b)
        val copy = original.copy() as GoFile
        assertNotSame(original, copy)
        assertSame(original, copy.originalFile)
        assertEquals(original.virtualFile, GoPsiUtil.originalVirtualFile(copy))

        val expected = resolveAll(original)
        assertNotNull("helper resolves in original", expected["helper"])
        assertNotNull("Konst resolves in original", expected["Konst"])
        assertNotNull("fmt resolves in original", expected["fmt"])
        assertEquals(expected, resolveAll(copy))

        val model = GoPackageModel.getInstance(project)
        assertEquals(model.packagePathOf(original), model.packagePathOf(copy))
        assertEquals(model.scopeOf(original).files.map { it.name }.sorted(), model.scopeOf(copy).files.map { it.name }.sorted())
        assertTrue(model.scopeOf(copy).files.map { it.name }.containsAll(listOf("a.go", "b.go")))
        assertNotNull(model.resolveImport("fmt", copy))
        assertEquals(model.resolveImport("strings", original)?.directory, model.resolveImport("strings", copy)?.directory)
    }
}
