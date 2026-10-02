package io.github.golangsupport.semantic

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.semantic.api.GoSemanticService

/**
 * The public semantic API must not throw `IndexNotReadyException` in dumb mode (docs/API.md, "Dumb mode").
 * go-psi-semantic uses no stub or file-based index: other files are read through their stubs/PSI via
 * the VFS, so the answers in dumb mode must equal the smart-mode answers.
 */
class GoDumbModeTest : GoProjectModelTestBase() {
    private val a = """
        package dm

        import (
            "fmt"
            str "strings"
        )

        type Local struct{ Other }

        func useAll() {
            var o Other
            n := helper(o.N)
            fmt.Println(str.ToUpper("x"), n, Konst, Local{}.N)
            unused := 1
        }
    """.trimIndent()
    private val b = """
        package dm

        type Other struct{ N int }

        const Konst = 1

        func helper(n int) string { return "" }
    """.trimIndent()

    /** One line per query; dumb and smart runs must agree. */
    private fun snapshot(file: GoFile): List<String> {
        val semantic = GoSemanticService.getInstance(project)
        val out = ArrayList<String>()
        for (expr in PsiTreeUtil.findChildrenOfType(file, GoExpression::class.java)) {
            out += "typeOf ${expr.text}: ${semantic.render(semantic.typeOf(expr))}"
        }
        for (ref in PsiTreeUtil.findChildrenOfType(file, GoReferenceExpression::class.java)) {
            out += "resolve ${ref.text}: ${semantic.resolve(ref).map(::describe)}"
        }
        for (ref in PsiTreeUtil.findChildrenOfType(file, GoTypeReferenceExpression::class.java)) {
            out += "resolveType ${ref.text}: ${semantic.resolve(ref)?.let(::describe)}"
        }
        for (decl in PsiTreeUtil.findChildrenOfType(file, GoNamedElement::class.java)) {
            out += "declType ${decl.name}: ${semantic.render(semantic.declarationType(decl))}"
        }
        out += "check: ${semantic.check(file).map { it.code + ":" + it.message }}"
        out += "packageOf(file): ${semantic.packageOf(file)?.importPath}"
        val dir = file.virtualFile.parent
        out += "packageOf(dir): ${GoPackageResolver.getInstance(project).packageOf(dir)?.importPath}"
        out += "resolveImport: ${GoPackageResolver.getInstance(project).resolveImport("fmt", file.virtualFile)}"
        out += "importPathOf: ${GoPackageResolver.getInstance(project).importPathOf(dir)}"
        return out
    }

    private fun describe(e: PsiElement): String = "${e.containingFile?.name}:${(e as? GoNamedElement)?.name ?: e.text.take(20)}"

    fun testSemanticApiDoesNotThrowInDumbMode() {
        val file = myFixture.addFileToProject("dm/a.go", a) as GoFile
        myFixture.addFileToProject("dm/b.go", b)
        val smart = snapshot(file)
        assertTrue(smart.joinToString("\n"), smart.any { it.startsWith("typeOf") && !it.endsWith(": unknown") })

        var dumb: List<String>? = null
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            assertTrue(com.intellij.openapi.project.DumbService.isDumb(project))
            dumb = snapshot(file)
        }
        assertEquals(smart.joinToString("\n"), dumb!!.joinToString("\n"))
    }

    fun testFirstQueriesInDumbMode() {
        // Nothing has been computed or cached before the dumb section starts.
        val file = myFixture.addFileToProject("dm/a.go", a) as GoFile
        myFixture.addFileToProject("dm/b.go", b)
        var result: List<String>? = null
        DumbModeTestUtils.runInDumbModeSynchronously(project) { result = snapshot(file) }
        assertTrue(result!!.any { it.startsWith("typeOf") })
    }
}
