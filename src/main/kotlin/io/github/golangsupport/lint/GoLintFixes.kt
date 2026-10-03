package io.github.golangsupport.lint

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * What a function returns, asked of whoever knows the types: the PSI first (`lang.GoNativeSignatureProvider`), then the module with
 * the language server. "Handle the error" cannot be written without knowing how many values come before the error.
 */
interface GoSignatureProvider {
    /** The number of results of the function named at [offset] of [file]; null when unknown. Blocks: not for EDT without a progress. */
    fun resultCount(project: Project, file: VirtualFile, offset: Int): Int?

    companion object {
        val EP: ExtensionPointName<GoSignatureProvider> = ExtensionPointName.create("io.github.golangsupport.signatureProvider")

        fun resultCount(project: Project, file: VirtualFile, offset: Int): Int? = EP.extensionList.firstNotNullOfOrNull { it.resultCount(project, file, offset) }
    }
}

/** Reading a signature as gopls prints it in a hover: `func Open(name string) (*os.File, error)`, `func (f *File) Close() error`. */
object GoSignatures {
    /** The number of results of a named function or method; null when [signature] is not one (a function literal has no name to hover over). */
    fun resultCount(signature: String): Int? {
        val text = signature.trim()
        if (!text.startsWith("func")) return null
        var i = 4
        // the receiver of a method, the type parameters, then the parameters: the last group of brackets before the results is the parameters
        fun skipSpaces() { while (i < text.length && text[i] == ' ') i++ }
        skipSpaces()
        if (i < text.length && text[i] == '(') { i = closing(text, i) + 1; skipSpaces() }
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
        if (i < text.length && text[i] == '[') i = closing(text, i) + 1
        if (i < text.length && text[i] == '(') i = closing(text, i) + 1
        val results = text.substring(i.coerceAtMost(text.length)).trim()
        if (results.isEmpty()) return 0
        if (!results.startsWith("(")) return 1
        val inner = results.substring(1, closing(results, 0).coerceAtLeast(1))
        var depth = 0
        var count = 1
        for (c in inner) when (c) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ',' -> if (depth == 0) count++
        }
        return count
    }

    /** The first line of a hover that is a signature; gopls puts it into a code block, after nothing or after a line of `go`. */
    fun inHover(markdown: String): String? = markdown.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("func ") }

    private fun closing(text: String, open: Int): Int {
        var depth = 0
        for (j in open until text.length) when (text[j]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (--depth == 0) return j
        }
        return text.length - 1
    }
}

/** What the fixes of an unchecked error do to the text; pure, the editor is not needed to test them. */
object GoErrcheckFixes {
    /** The statement of a line when it is a plain call: not a `defer` or a `go` (nothing can be assigned there), not a part of something longer. */
    fun callStatement(line: String): String? {
        val statement = line.trim()
        if (statement.isEmpty() || !statement.endsWith(")") || statement.startsWith("defer ") || statement.startsWith("go ")) return null
        val head = statement.substringBefore('(')
        return statement.takeIf { head.isNotEmpty() && head.all { c -> c.isLetterOrDigit() || c == '_' || c == '.' } }
    }

    /** The offset in [line] of the name of the called function: the identifier right before the first `(`. */
    fun functionNameOffset(line: String): Int? {
        val paren = line.indexOf('(')
        if (paren <= 0) return null
        var start = paren
        while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) start--
        return start.takeIf { it < paren }
    }

    fun ignore(statement: String, results: Int): String = List(results.coerceAtLeast(1)) { "_" }.joinToString(", ") + " = " + statement

    /** The lines to put instead of the statement, without the indent of the first one; [indent] is the indent of the statement, [unit] one more level. */
    fun handle(statement: String, results: Int, indent: String, unit: String): String =
        if (results <= 1) "if err := $statement; err != nil {\n$indent${unit}return err\n$indent}"
        else List(results - 1) { "_" }.joinToString(", ") + ", err := $statement\n${indent}if err != nil {\n$indent${unit}return err\n$indent}"
}

/** `//nolint:errcheck` at the end of the line: the way to tell golangci-lint (or a custom linter that reads the directive) that this one is meant. Joins a directive that is already there. */
class GoNolintFix(private val linter: String, private val line: Int) : IntentionAction {
    override fun getText(): String = "Suppress with //nolint:$linter"
    override fun getFamilyName(): String = "Suppress linter finding"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true
    override fun startInWriteAction(): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = file?.viewProvider?.document ?: return
        if (line !in 0 until document.lineCount) return
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        document.replaceString(start, end, withNolint(document.getText(com.intellij.openapi.util.TextRange(start, end)), linter))
    }

    companion object {
        fun withNolint(line: String, linter: String): String {
            val existing = Regex("""//\s*nolint:([\w,-]+)""").find(line)
            if (existing == null) return line.trimEnd() + " //nolint:" + linter
            val linters = existing.groupValues[1].split(',')
            return if (linter in linters) line else line.replaceRange(existing.groups[1]!!.range, (linters + linter).joinToString(","))
        }
    }
}

/** The two ways out of "Error return value is not checked": deal with the error, or say in the code that it is ignored on purpose. */
class GoErrcheckFix(private val handle: Boolean, private val line: Int) : IntentionAction {
    override fun getText(): String = if (handle) "Handle error" else "Ignore error explicitly"
    override fun getFamilyName(): String = "Unchecked error"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        val document = file?.viewProvider?.document ?: return false
        return line in 0 until document.lineCount && GoErrcheckFixes.callStatement(lineText(document)) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val document = file?.viewProvider?.document ?: return
        val virtualFile = file.virtualFile ?: return
        val text = lineText(document)
        val statement = GoErrcheckFixes.callStatement(text) ?: return
        val nameOffset = document.getLineStartOffset(line) + (GoErrcheckFixes.functionNameOffset(text) ?: return)
        // how many values come before the error is a question for the language server; asked under a progress, this is EDT
        val results = ProgressManager.getInstance().runProcessWithProgressSynchronously<Int?, RuntimeException>(
            { GoSignatureProvider.resultCount(project, virtualFile, nameOffset) }, "Asking gopls What the Function Returns", true, project,
        )
        if (results == null || results == 0) {
            if (editor != null) HintManager.getInstance().showErrorHint(editor, "Cannot tell what the function returns: the language server (gopls) is needed for this fix")
            return
        }
        val indent = text.takeWhile { it == ' ' || it == '\t' }
        val unit = if (indent.startsWith(" ")) "    " else "\t"
        val replacement = indent + if (handle) GoErrcheckFixes.handle(statement, results, indent, unit) else GoErrcheckFixes.ignore(statement, results)
        WriteCommandAction.runWriteCommandAction(project, getText(), null, {
            val start = document.getLineStartOffset(line)
            document.replaceString(start, document.getLineEndOffset(line), replacement)
            // on what is to be written next: the `return err` is a guess, the function may return something else
            if (handle && editor != null) replacement.indexOf("return err").takeIf { it >= 0 }?.let { editor.caretModel.moveToOffset(start + it + "return ".length) }
        }, file)
    }

    private fun lineText(document: Document): String = document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
}
