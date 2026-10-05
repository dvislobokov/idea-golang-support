package io.github.golangsupport.lang.psi

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.GoCodeInsightTestBase
import io.github.golangsupport.lang.psi.impl.GoCommentImpl
import io.github.golangsupport.lang.psi.impl.GoGenerateCommentImpl

/** Go comments are [GoCommentImpl]: `PsiComment` semantics without being injection hosts, except `//go:generate` lines. */
class GoCommentImplTest : GoCodeInsightTestBase() {

    private val source = """
        //go:build linux

        // Package p is documented.
        package p

        import _ "embed"

        //go:embed hello.txt
        var s string

        /* block */
        func F() { // trailing
        	_ = 1 /* inline */
        }
    """.trimIndent()

    private fun comments(): List<PsiComment> {
        val file = myFixture.configureByText("p.go", source)
        return PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java).sortedBy { it.textOffset }
    }

    fun testAllCommentsAreGoCommentsAndNotInjectionHosts() {
        val all = comments()
        assertEquals(
            listOf("//go:build linux", "// Package p is documented.", "//go:embed hello.txt", "/* block */", "// trailing", "/* inline */"),
            all.map { it.text },
        )
        for (c in all) {
            assertTrue(c.javaClass.name, c is GoCommentImpl)
            assertFalse(c.text, c is PsiLanguageInjectionHost)
            assertTrue(c.tokenType == GoTypes.LINE_COMMENT || c.tokenType == GoTypes.BLOCK_COMMENT)
            assertEquals("PsiComment(${c.tokenType})", c.toString())
            assertNull(InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(c))
        }
    }

    /** `//go:generate` lines are the only hosts (Shell Script injection); a look-alike without the separator or a block comment is not. */
    fun testGoGenerateCommentIsTheOnlyHost() {
        val file = myFixture.configureByText(
            "p.go",
            "package p\n\n//go:generate stringer -type=Kind\n//go:generate\n//go:generated no\n// go:generate no\n/*go:generate no*/\ntype Kind int\n",
        )
        val hosts = PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java).sortedBy { it.textOffset }.map { it.text to (it is PsiLanguageInjectionHost) }
        assertEquals(
            listOf("//go:generate stringer -type=Kind" to true, "//go:generate" to true, "//go:generated no" to false, "// go:generate no" to false,
                "/*go:generate no*/" to false),
            hosts,
        )
        val host = PsiTreeUtil.collectElementsOfType(file, GoGenerateCommentImpl::class.java).first()
        assertEquals("PsiComment(${GoTypes.LINE_COMMENT})", host.toString())
        WriteCommandAction.runWriteCommandAction(project) {
            assertTrue(host.updateText("//go:generate go run gen.go") is GoGenerateCommentImpl)
        }
        assertTrue(myFixture.file.text.contains("//go:generate go run gen.go\n"))
    }

    fun testVisitorSeesComments() {
        val file = myFixture.configureByText("p.go", source)
        var count = 0
        file.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            override fun visitComment(comment: PsiComment) {
                count++
            }
        })
        assertEquals(6, count)
    }

    fun testCommentsStayGoCommentsAfterEdit() {
        comments()
        WriteCommandAction.runWriteCommandAction(project) {
            val doc = myFixture.editor.document
            doc.insertString(doc.textLength, "\n// added\n/* added block */\n")
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }
        val all = PsiTreeUtil.collectElementsOfType(myFixture.file, PsiComment::class.java)
        assertEquals(8, all.size)
        assertTrue(all.all { it is GoCommentImpl })
    }

    fun testDocCommentBinderStillWorks() {
        val file = myFixture.configureByText("p.go", source) as GoFile
        assertEquals("// Package p is documented.", file.packageDoc?.text)
        assertTrue(file.packageDoc is GoCommentImpl)
    }
}
