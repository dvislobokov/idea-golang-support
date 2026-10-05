package io.github.golangsupport.ide.injection

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeTestBase
import io.github.golangsupport.ide.injection.sh.GoGenerateCommand

/** Shell Script (com.jetbrains.sh) injected into the command of `//go:generate` lines; plain comments get nothing. */
class GoGenerateShellInjectionTest : GoIdeTestBase() {

    private val shell: Language? get() = Language.findLanguageByID("Shell Script")

    /** The injected (language id, text) per comment of the file, in order. */
    private fun injections(text: String): List<Pair<String, List<String>>> {
        myFixture.configureByText("a.go", text)
        val manager = InjectedLanguageManager.getInstance(project)
        return PsiTreeUtil.collectElementsOfType(myFixture.file, PsiComment::class.java).sortedBy { it.textOffset }.map { comment ->
            comment.text to manager.getInjectedPsiFiles(comment).orEmpty().map { "${it.first.language.id}: ${it.first.text}" }
        }
    }

    fun testShellInGoGenerate() {
        if (shell == null) {
            System.err.println("GoGenerateShellInjectionTest: the Shell Script plugin is not in the test IDE, injection not checked")
            return
        }
        assertEquals(
            listOf(
                "//go:generate stringer -type=Kind -output kind_string.go" to listOf("Shell Script: stringer -type=Kind -output kind_string.go"),
                "//go:generate -command yacc go tool yacc" to listOf("Shell Script: go tool yacc"),
                "//go:generate yacc -o gopher.go -p parser gopher.y" to listOf("Shell Script: yacc -o gopher.go -p parser gopher.y"),
                "//go:generate" to emptyList(),
                "// go:generate is mentioned here" to emptyList(),
                "// a plain comment" to emptyList(),
            ),
            injections(
                "package p\n\n//go:generate stringer -type=Kind -output kind_string.go\n//go:generate -command yacc go tool yacc\n" +
                    "//go:generate yacc -o gopher.go -p parser gopher.y\n//go:generate\n// go:generate is mentioned here\n// a plain comment\ntype Kind int\n",
            ),
        )
    }

    fun testCommandRange() {
        fun command(comment: String) = GoGenerateCommand.commandRange(comment)?.substring(comment)
        assertEquals("go run gen.go", command("//go:generate go run gen.go"))
        assertEquals("go run gen.go", command("//go:generate\t go run gen.go  "))
        assertEquals("go tool yacc", command("//go:generate -command yacc go tool yacc"))
        assertEquals("-commandx a", command("//go:generate -commandx a"))
        assertNull(command("//go:generate"))
        assertNull(command("//go:generate   "))
        assertNull(command("//go:generate -command yacc"))
        assertNull(command("// plain"))
        assertEquals(TextRange(14, 16), GoGenerateCommand.commandRange("//go:generate ls"))
    }
}
