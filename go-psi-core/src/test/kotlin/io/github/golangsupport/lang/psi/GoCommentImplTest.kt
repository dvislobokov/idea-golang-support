package io.github.golangsupport.lang.psi

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.GoCodeInsightTestBase
import io.github.golangsupport.lang.psi.impl.GoCommentImpl

/** Go comments are [GoCommentImpl]: `PsiComment` semantics without being injection hosts. */
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
