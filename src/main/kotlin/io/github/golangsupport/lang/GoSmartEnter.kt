package io.github.golangsupport.lang

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.golangsupport.settings.GoSettings

/**
 * Complete Statement (Ctrl+Shift+Enter): the braces of an `if`, `for`, `switch`, `select`, `func`, `struct`, `interface` that has none
 * yet, the parentheses of a call typed without them, and the caret inside on its own line. By the text of the line, as everything here.
 */
class GoSmartEnterProcessor : SmartEnterProcessor() {
    override fun process(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is GoFile) return false
        val document = editor.document
        val offset = editor.caretModel.offset
        val line = document.getLineNumber(offset)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.getText(com.intellij.openapi.util.TextRange(start, end))
        val completed = GoStatements.complete(text) ?: return false
        val indent = text.takeWhile { it == ' ' || it == '\t' }
        val unit = if (indent.startsWith(" ")) "    " else "\t"
        val replacement = completed.first
        document.replaceString(start, end, replacement)
        if (completed.second) {
            // `{` at the end: the body on the next line, the closing brace after it
            document.insertString(start + replacement.length, "\n$indent$unit\n$indent}")
            editor.caretModel.moveToOffset(start + replacement.length + 1 + indent.length + unit.length)
        } else {
            editor.caretModel.moveToOffset(start + replacement.length)
        }
        return true
    }
}

/** What Complete Statement adds to a line; pure, for the tests. */
object GoStatements {
    private val OPENERS = Regex("""^\s*(if|for|switch|select|func|else|type\s+\w+\s+(struct|interface)|go\s+func|defer\s+func)\b""")

    /**
     * The line with what it lacks, and whether a body was opened: `if x` -> `if x {` (body), `f(x` -> `f(x)` (no body), a line that is
     * complete -> null. A `func` without parentheses gets them; a trailing `{` that is already there is left alone.
     */
    fun complete(line: String): Pair<String, Boolean>? {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank() || trimmed.endsWith("{") || trimmed.endsWith("}")) return null
        var text = trimmed
        val opens = text.count { it == '(' } - text.count { it == ')' }
        if (opens > 0) text += ")".repeat(opens)
        val bracketsOpen = text.count { it == '[' } - text.count { it == ']' }
        if (bracketsOpen > 0) text += "]".repeat(bracketsOpen)
        val opener = OPENERS.find(text)
        if (opener == null) return if (text != trimmed) text to false else null
        val keyword = opener.groupValues[1]
        if ((keyword == "func" || keyword.endsWith("func")) && !text.contains('(')) text += "()"
        // `else` alone or `else if cond`; `for` alone is an endless loop; `select` and `switch` may stand alone
        return "$text {" to true
    }
}

/**
 * `//` typed on an empty line right above a declaration becomes `// Name `: the doc comment Go wants to start with the name. On by
 * default, off in Settings | Tools | Go, Editor.
 */
class GoDocCommentTypedHandler : TypedHandlerDelegate() {
    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (c != '/' || file !is GoFile || !GoSettings.getInstance().docCommentNames) return Result.CONTINUE
        val document = editor.document
        val offset = editor.caretModel.offset
        val text = document.immutableCharSequence
        val name = GoDocComments.nameToComment(text, offset) ?: return Result.CONTINUE
        document.insertString(offset, " $name ")
        editor.caretModel.moveToOffset(offset + name.length + 2)
        return Result.STOP
    }

    override fun beforeCharTyped(c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType): Result = Result.CONTINUE
}

object GoDocComments {
    private val DECLARATION = Regex("""^\s*(?:func\s+(?:\([^)]*\)\s*)?|type\s+|var\s+|const\s+)(\w+)""")

    /**
     * The name of the declaration on the next line when the caret has just closed `//` on a line of its own: `Server` for `type Server
     * struct`, `Start` for `func (s *Server) Start()`. Null anywhere else (a comment inside code, a second line of a comment).
     */
    fun nameToComment(text: CharSequence, offset: Int): String? {
        if (offset < 2 || text[offset - 1] != '/' || text[offset - 2] != '/') return null
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        if (text.subSequence(lineStart, offset - 2).any { it != ' ' && it != '\t' }) return null
        // the rest of this line must be empty, and the line above must not be a comment already
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        if (text.subSequence(offset, lineEnd).isNotBlank()) return null
        if (lineStart > 0) {
            val previousStart = text.lastIndexOf('\n', lineStart - 2) + 1
            if (text.subSequence(previousStart, lineStart).trim().startsWith("//")) return null
        }
        if (lineEnd >= text.length) return null
        val nextEnd = text.indexOf('\n', lineEnd + 1).let { if (it < 0) text.length else it }
        val next = text.subSequence(lineEnd + 1, nextEnd).toString()
        val name = DECLARATION.find(next)?.groupValues?.get(1) ?: return null
        return name.takeIf { it != "_" && it !in GoTokenTypes.KEYWORDS }
    }
}
