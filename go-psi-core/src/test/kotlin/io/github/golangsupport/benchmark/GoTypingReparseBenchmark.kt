package io.github.golangsupport.benchmark

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.GoCodeInsightTestBase
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoReferenceExpression

/**
 * Editing performance of the parser: a copy of `net/http/server.go` (and of `go/types/expr.go`) is
 * open in an editor, one character is typed through the document and the commit
 * (`PsiDocumentManager.commitDocument`, i.e. the incremental re-parse) is timed.
 *
 * - `*Body`: 'x' typed into a name used in a function body, at 50 positions spread over the file.
 * - `*TopLevel`: 'x' typed on an empty line between top-level declarations (an error element),
 *   50 positions spread over the file (expr.go has fewer gaps: positions repeat).
 *
 * Each keystroke is undone untimed (delete + commit), so every keystroke starts from the original
 * text. An iteration returns the median commit time of its 50 keystrokes (unit: ms per keystroke).
 * A report line gives the PSI tree change events per keystroke and how many unrelated function
 * bodies (25 positions away) kept their PSI identity: today the whole file is re-parsed and merged
 * by the diff (DiffTree), which keeps the identity but costs a full parse. Target: lazy
 * re-parseable function bodies (only the edited body is re-parsed).
 */
class GoTypingReparseBenchmark : GoCodeInsightTestBase() {

    fun testServerBody() = measure("serverBody", "net/http/server.go", topLevel = false)

    fun testServerTopLevel() = measure("serverTopLevel", "net/http/server.go", topLevel = true)

    fun testExprBody() = measure("exprBody", "go/types/expr.go", topLevel = false)

    fun testExprTopLevel() = measure("exprTopLevel", "go/types/expr.go", topLevel = true)

    private fun measure(case: String, relative: String, topLevel: Boolean) {
        val text = BenchmarkSupport.readGoroot(relative)
        myFixture.configureByText(relative.substringAfterLast('/'), text)
        val document = myFixture.editor.document
        val manager = PsiDocumentManager.getInstance(project)
        manager.commitAllDocuments()
        val file = myFixture.file
        file.node // initial full parse

        val functions = PsiTreeUtil.getChildrenOfTypeAsList(file, GoFunctionOrMethodDeclaration::class.java).filter { it.block != null }
        val offsets: List<Int> = if (topLevel) {
            // Empty lines right before a top-level declaration (functions, types, vars, consts).
            file.children.filter { it !is PsiWhiteSpace && it !is PsiComment }.map { it.textRange.startOffset }
                .filter { it >= 2 && text[it - 1] == '\n' && text[it - 2] == '\n' }.map { it - 1 }
        } else {
            // After the first character of a name used in a body ("c.serve" -> "cx.serve").
            functions.flatMap { f -> PsiTreeUtil.findChildrenOfType(f.block, GoReferenceExpression::class.java).map { it.textRange.startOffset + 1 } }
        }.distinct().sorted().let(::spread)
        assertEquals("keystroke positions", KEYSTROKES, offsets.size)

        val events = EventCounter()
        PsiManager.getInstance(project).addPsiTreeChangeListener(events, testRootDisposable)
        var eventsTotal = 0
        var unrelatedKept = 0
        var keystrokes = 0
        BenchmarkSupport.runTimed("GoTypingReparseBenchmark.$case", "ms/keystroke", 1.0) {
            val samples = ArrayList<Double>(offsets.size)
            eventsTotal = 0
            unrelatedKept = 0
            keystrokes = 0
            for ((k, offset) in offsets.withIndex()) {
                // The body of the function at the keystroke position 25 away: does it survive the re-parse?
                val far = offsets[(k + KEYSTROKES / 2) % KEYSTROKES]
                val unrelated: PsiElement? = PsiTreeUtil.getParentOfType(file.findElementAt(far + 1), GoFunctionOrMethodDeclaration::class.java)?.block
                write { document.insertString(offset, "x") }
                events.count = 0
                samples += BenchmarkSupport.timed { write { manager.commitDocument(document) } }
                eventsTotal += events.count
                if (unrelated != null && unrelated.isValid) unrelatedKept++
                keystrokes++
                write { document.deleteString(offset, offset + 1) }
                write { manager.commitDocument(document) }
            }
            BenchmarkSupport.median(samples)
        }
        assertEquals(text, document.text)
        BenchmarkSupport.report(
            "GoTypingReparseBenchmark.$case.psi",
            "events/keystroke=${BenchmarkSupport.format(eventsTotal.toDouble() / keystrokes)} unrelatedBodyKept=$unrelatedKept/$keystrokes",
        )
    }

    private fun write(action: () -> Unit) = WriteCommandAction.runWriteCommandAction(project, action)

    /** [KEYSTROKES] positions spread evenly over [all] (cycling when there are fewer). */
    private fun spread(all: List<Int>): List<Int> {
        assertTrue("too few positions: ${all.size}", all.size >= 10)
        return if (all.size >= KEYSTROKES) List(KEYSTROKES) { all[it * all.size / KEYSTROKES] } else List(KEYSTROKES) { all[it % all.size] }
    }

    private class EventCounter : PsiTreeChangeAdapter() {
        var count = 0
        override fun childAdded(event: PsiTreeChangeEvent) { count++ }
        override fun childRemoved(event: PsiTreeChangeEvent) { count++ }
        override fun childReplaced(event: PsiTreeChangeEvent) { count++ }
        override fun childMoved(event: PsiTreeChangeEvent) { count++ }
        override fun childrenChanged(event: PsiTreeChangeEvent) { count++ }
    }

    private companion object {
        const val KEYSTROKES = 50
    }
}
