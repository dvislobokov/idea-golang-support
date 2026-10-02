package io.github.golangsupport.lang.parser

import com.intellij.lang.FileASTNode
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.impl.BlockSupportImpl
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.GoCodeInsightTestBase
import io.github.golangsupport.GoTestUtil
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import java.nio.file.Files
import kotlin.random.Random

/**
 * Randomized edits over a few GOROOT files (fixed seed): characters that change the lexer state or
 * the brace structure (`{`, `}`, quotes, backquotes, comment openers, newlines, `func f`, `var`) and
 * plain text are inserted or deleted inside function bodies (mostly) and anywhere else; after every
 * commit the re-parsed tree must equal a fresh parse of the document text. Each step starts from the
 * previous text (errors accumulate), every [STEPS_PER_RESET] steps the original text is restored.
 * Runtime is bounded by [STEPS] per file.
 */
class GoLazyBodyRandomEditTest : GoCodeInsightTestBase() {

    private val files = listOf("src/strings/strings.go", "src/bytes/buffer.go", "src/fmt/print.go", "src/text/template/exec.go")

    private val insertions = listOf(
        "{", "}", "{}", "\"", "`", "'", "/*", "*/", "//", "\n", ";", "(", ")", "x", " ", "func f", "\nfunc g() {", "var", "\nvar v = 1",
        "if x {", "}\n}", "go", ":", "case 1:", "func() {", "\"}\"", "`{`",
    )

    fun testRandomEditsGiveTheSameTreeAsAFreshParse() {
        val random = Random(20261002)
        var steps = 0
        var incremental = 0
        val started = System.currentTimeMillis()
        for ((index, path) in files.withIndex()) {
            val original = Files.readString(GoTestUtil.goroot().resolve(path)).replace("\r\n", "\n")
            val file = myFixture.configureByText("f$index.go", original)
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            // Half of the files start fully expanded (as in an editor), half with collapsed bodies.
            if (index % 2 == 0) DebugUtil.psiToString(file, true)
            val log = ArrayList<String>()
            repeat(STEPS) { step ->
                if (step % STEPS_PER_RESET == 0 && step > 0) {
                    edit { document.setText(original) }
                    log.clear()
                    if (random.nextBoolean()) DebugUtil.psiToString(file, true)
                }
                val text = document.text
                val (range, replacement) = mutation(file, text, random)
                log += "$range -> ${replacement.replace("\n", "\\n")}"
                val newText = text.substring(0, range.startOffset) + replacement + text.substring(range.endOffset)
                val root = BlockSupportImpl.findReparseableNodeAndReparseIt(file as PsiFileImpl, file.node as FileASTNode, range, newText)
                if (root?.first != null) incremental++
                edit { document.replaceString(range.startOffset, range.endOffset, replacement) }
                steps++
                val actual = DebugUtil.psiToString(file, true, true)
                val expected = DebugUtil.psiToString(fresh(file.name, document.text), true, true)
                if (actual != expected) {
                    fail("$path step $step: re-parsed tree differs from a fresh parse after edits:\n${log.joinToString("\n")}\n" + firstDifference(expected, actual))
                }
                // Expand the bodies the next edits may hit, sometimes.
                if (random.nextInt(4) == 0) DebugUtil.psiToString(file, true)
            }
        }
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        println("lazy body random edits: $steps steps, $incremental incremental body re-parses, ${"%.1f".format(seconds)} s")
        assertTrue("some edits are incremental: $incremental of $steps", incremental * 4 > steps)
    }

    /** A random edit: 75% inside a function body (declarations and literals), otherwise anywhere. */
    private fun mutation(file: PsiFile, text: String, random: Random): Pair<TextRange, String> {
        val bodies = PsiTreeUtil.getChildrenOfTypeAsList(file, GoFunctionOrMethodDeclaration::class.java).mapNotNull { it.block?.textRange } +
            if (random.nextInt(3) == 0) PsiTreeUtil.findChildrenOfType(file, GoFunctionLit::class.java).mapNotNull { it.lastChild?.textRange } else emptyList()
        val area = if (bodies.isNotEmpty() && random.nextInt(4) != 0) bodies[random.nextInt(bodies.size)] else TextRange(0, text.length)
        val offset = area.startOffset + random.nextInt(area.length + 1)
        return if (random.nextInt(3) == 0 && offset < text.length) {
            val end = minOf(text.length, offset + 1 + random.nextInt(3))
            TextRange(offset, end) to ""
        } else {
            TextRange(offset, offset) to insertions[random.nextInt(insertions.size)]
        }
    }

    private fun fresh(name: String, text: String): PsiFile = PsiFileFactory.getInstance(project).createFileFromText(name, GoLanguage, text)

    private fun edit(action: () -> Unit) = WriteCommandAction.runWriteCommandAction(project) {
        action()
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun firstDifference(expected: String, actual: String): String {
        val e = expected.lines()
        val a = actual.lines()
        val i = e.indices.firstOrNull { it >= a.size || e[it] != a[it] } ?: e.size
        val from = maxOf(0, i - 5)
        return "expected:\n" + e.subList(from, minOf(e.size, i + 5)).joinToString("\n") + "\nactual:\n" + a.subList(from, minOf(a.size, i + 5)).joinToString("\n")
    }

    private companion object {
        const val STEPS = 60
        const val STEPS_PER_RESET = 20
    }
}
