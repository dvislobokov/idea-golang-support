package io.github.golangsupport.lang

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.smartEnter.SmartEnterProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoElseStatement
import io.github.golangsupport.lang.psi.GoExprSwitchStatement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoForStatement
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIfStatement
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoSelectStatement
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypeDeclaration
import io.github.golangsupport.lang.psi.GoTypeSwitchStatement
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.settings.GoSettings

/**
 * Complete Statement (Ctrl+Shift+Enter) by the PSI of the caret line ([GoSmartEnter]): the braces of an `if`, `for`, `switch`, `select`,
 * `else`, `func`, `struct`, `interface` that has none yet with the caret inside, the `)`, `]`, `}` the line has opened and not closed,
 * the `,` of an element of a multi-line literal or call, the caret on the next line of a complete statement, `// Name` on an empty
 * comment above a declaration.
 */
class GoSmartEnterProcessor : SmartEnterProcessor() {
    override fun process(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is GoFile) return false
        val document = editor.document
        // the action runs in a write action: the PSI of what has just been typed is one commit away
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(document) as? GoFile ?: return false
        val edit = GoSmartEnter.plan(psi, editor.caretModel.offset) ?: return false
        document.replaceString(edit.start, edit.end, edit.text)
        editor.caretModel.moveToOffset(edit.start + edit.caret)
        editor.selectionModel.removeSelection()
        return true
    }
}

/** What Complete Statement does to the caret line, from the committed PSI; pure over the PSI, for the tests. */
object GoSmartEnter {
    /** Replace [start]..[end] with [text]; the caret goes to [caret] within [text]. */
    class Edit(val start: Int, val end: Int, val text: String, val caret: Int)

    private val CLOSERS = mapOf(GoTypes.LPAREN to GoTypes.RPAREN, GoTypes.LBRACK to GoTypes.RBRACK, GoTypes.LBRACE to GoTypes.RBRACE)

    fun plan(file: GoFile, offset: Int): Edit? {
        val text = file.viewProvider.contents
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
        val line = text.subSequence(lineStart, lineEnd).toString()
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val unit = if (indent.startsWith(" ")) "    " else "\t"
        if (line.trim() == "//") return docComment(text, lineStart + line.indexOf("//") + 2)
        val leaves = codeLeaves(file, lineStart, lineEnd)
        if (leaves.isEmpty()) return null
        val last = leaves.last()
        val codeEnd = last.textRange.endOffset
        // the rest of the line goes away only when it is white space; a trailing comment stays where it is
        val end = if (text.subSequence(codeEnd, lineEnd).isBlank()) lineEnd else codeEnd
        fun edit(inserted: String, caret: Int = inserted.length) = Edit(codeEnd, end, inserted, caret)
        fun body(head: String): Edit = edit("$head\n$indent$unit\n$indent}", head.length + 1 + indent.length + unit.length)

        val closers = closers(leaves, last)
        // `go func`, `defer func`, `x := func`: a function literal begun with its keyword alone
        if (last.node.elementType == GoTypes.FUNC) {
            val called = leaves.first().node.elementType.let { it == GoTypes.GO || it == GoTypes.DEFER }
            return Edit(codeEnd, end, "() {\n$indent$unit\n$indent}" + (if (called) "()" else ""), 5 + indent.length + unit.length)
        }
        val header = header(leaves)
        if (header != null) {
            val brace = ownBrace(header)
            if (brace == null || !onLine(brace, lineStart, lineEnd)) {
                val parens = if (header is GoFunctionOrMethodDeclaration && header.signature == null && closers.isEmpty()) "()" else ""
                return body("$closers$parens {")
            }
        }
        if (last.node.elementType == GoTypes.LBRACE && closers.isEmpty()) {
            val close = last.parent?.node?.findChildByType(GoTypes.RBRACE)?.psi
            val closed = close != null && close !is PsiErrorElement && indentOf(text, close.textRange.startOffset) == indent && !onLine(close, lineStart, lineEnd)
            return if (closed) edit("\n$indent$unit") else body("")
        }
        val comma = if (closers.isEmpty() && needsComma(last, lineStart, lineEnd)) "," else ""
        return edit("$closers$comma\n$indent")
    }

    /** The leaves of code on the line: no white space, no comments, no inserted semicolons. */
    private fun codeLeaves(file: GoFile, lineStart: Int, lineEnd: Int): List<PsiElement> {
        val out = ArrayList<PsiElement>()
        var leaf = file.findElementAt(lineStart)
        while (leaf != null && leaf.textRange.startOffset < lineEnd) {
            val type = leaf.node.elementType
            if (leaf !is PsiWhiteSpace && type !in GoTokenSets.COMMENTS && type != GoTypes.SEMICOLON_SYNTHETIC && leaf.textLength > 0 &&
                leaf.textRange.startOffset >= lineStart) out += leaf
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return out
    }

    /**
     * What the line has opened and the PSI has not seen closed, innermost first: `)` of a call, parameters or parentheses, `]` of an
     * index, `}` of a literal opened in the middle of the line. A `{` at the end of the line is a body, the caller's concern.
     */
    private fun closers(leaves: List<PsiElement>, last: PsiElement): String {
        val missing = ArrayList<IElementType>()
        for (leaf in leaves.asReversed()) {
            val closer = CLOSERS[leaf.node.elementType] ?: continue
            val parent = leaf.parent ?: continue
            if (parent is PsiErrorElement) continue
            if (closer == GoTypes.RBRACE && (leaf === last || parent !is GoLiteralValue)) continue
            if (parent.node.findChildByType(closer) == null) missing += closer
        }
        return missing.joinToString("") { if (it == GoTypes.RPAREN) ")" else if (it == GoTypes.RBRACK) "]" else "}" }
    }

    /** The statement or declaration that wants a body and begins on the line: after a leading `}` (`} else`), the largest one at its start. */
    private fun header(leaves: List<PsiElement>): PsiElement? {
        val first = leaves.firstOrNull { it.node.elementType != GoTypes.RBRACE } ?: return null
        val start = first.textRange.startOffset
        var e: PsiElement = first
        // a declaration holds its doc comment: where it begins is where its code begins
        while (e.parent != null && e.parent !is PsiFile && codeStart(e.parent) == start) e = e.parent
        return when (e) {
            is GoElseStatement -> e.statement.takeIf { it is GoIfStatement } ?: e
            is GoIfStatement, is GoForStatement, is GoExprSwitchStatement, is GoTypeSwitchStatement, is GoSelectStatement, is GoFunctionOrMethodDeclaration -> e
            is GoTypeDeclaration -> e.takeIf { PsiTreeUtil.findChildOfAnyType(it, GoStructType::class.java, GoInterfaceType::class.java) != null }
            else -> null
        }
    }

    private fun codeStart(element: PsiElement): Int {
        var child = element.firstChild
        while (child != null && (child is PsiWhiteSpace || child is PsiComment)) child = child.nextSibling
        return (child ?: element).textRange.startOffset
    }

    /** The `{` that opens the body of [header], null when it has none. */
    private fun ownBrace(header: PsiElement): PsiElement? = when (header) {
        is GoExprSwitchStatement, is GoTypeSwitchStatement, is GoSelectStatement -> header.node.findChildByType(GoTypes.LBRACE)?.psi
        is GoTypeDeclaration -> PsiTreeUtil.findChildOfAnyType(header, GoStructType::class.java, GoInterfaceType::class.java)?.node?.findChildByType(GoTypes.LBRACE)?.psi
        is GoFunctionOrMethodDeclaration -> header.block?.node?.findChildByType(GoTypes.LBRACE)?.psi
        else -> PsiTreeUtil.getChildOfType(header, GoBlock::class.java)?.node?.findChildByType(GoTypes.LBRACE)?.psi
    }

    /**
     * Whether the line is an element of a literal or an argument of a call that spans lines: then Go wants the `,` before the line
     * break. Not after `{`, `(` or `,` themselves.
     */
    private fun needsComma(last: PsiElement, lineStart: Int, lineEnd: Int): Boolean {
        if (last.node.elementType in setOf(GoTypes.COMMA, GoTypes.LBRACE, GoTypes.LPAREN, GoTypes.LBRACK)) return false
        var e: PsiElement? = last.parent
        while (e != null && e !is PsiFile && e !is GoBlock) {
            if (e is GoLiteralValue || e is GoArgumentList) {
                val open = e.firstChild
                val close = e.lastChild?.takeIf { it.node.elementType == GoTypes.RBRACE || it.node.elementType == GoTypes.RPAREN }
                if (open.textRange.startOffset < lineStart && (close == null || close.textRange.startOffset >= lineEnd)) return true
            }
            e = e.parent
        }
        return false
    }

    private fun onLine(element: PsiElement, lineStart: Int, lineEnd: Int): Boolean = element.textRange.startOffset in lineStart until lineEnd

    private fun indentOf(text: CharSequence, offset: Int): String {
        val start = text.lastIndexOf('\n', offset - 1) + 1
        return text.subSequence(start, offset).toString().takeWhile { it == ' ' || it == '\t' }
    }

    /** `//` alone above a declaration: `// Name `, the start Go wants of its doc comment. */
    private fun docComment(text: CharSequence, afterSlashes: Int): Edit? {
        val name = GoDocComments.nameToComment(text, afterSlashes) ?: return null
        val lineEnd = text.indexOf('\n', afterSlashes).let { if (it < 0) text.length else it }
        val inserted = " $name "
        return Edit(afterSlashes, lineEnd, inserted, inserted.length)
    }
}

/**
 * `//` typed on an empty line right above a declaration becomes `// Name `: the doc comment Go wants to start with the name. On by
 * default, off in Settings | Tools | Go, Editor. By the text: the character is typed before the PSI sees it.
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
        return name.takeIf { it != "_" && it !in GoNames.KEYWORDS }
    }
}
