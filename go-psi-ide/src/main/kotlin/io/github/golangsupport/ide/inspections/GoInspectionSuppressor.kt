package io.github.golangsupport.ide.inspections

import com.intellij.codeInsight.daemon.impl.actions.AbstractBatchSuppressByNoInspectionCommentFix
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.codeInspection.SuppressionUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoCommClause
import io.github.golangsupport.lang.psi.GoExprCaseClause
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoTypeCaseClause
import io.github.golangsupport.lang.psi.GoTypes

/**
 * `//noinspection <ShortName>[, <ShortName>]` (or `ALL`) on the line(s) directly above a
 * statement, a top-level declaration or an import spec suppresses the inspection inside it; the
 * same comment before the package clause (detached by a blank line, so it is not package
 * documentation) suppresses it for the whole file.
 */
class GoInspectionSuppressor : InspectionSuppressor {

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        val file = element.containingFile as? GoFile ?: return false
        var e: PsiElement? = element
        while (e != null && e !is PsiFile) {
            if (GoSuppressionContainers.isContainer(e) && GoSuppressionContainers.leadingComments(e).any { mentions(it, toolId) }) return true
            e = e.parent
        }
        return GoSuppressionContainers.fileComments(file).any { mentions(it, toolId) }
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> = arrayOf(
        GoSuppressByCommentFix(toolId, GoSuppressByCommentFix.Kind.STATEMENT),
        GoSuppressByCommentFix(toolId, GoSuppressByCommentFix.Kind.DECLARATION),
        GoSuppressByCommentFix(toolId, GoSuppressByCommentFix.Kind.FILE),
    )

    private fun mentions(comment: PsiComment, toolId: String): Boolean {
        val matcher = SuppressionUtil.SUPPRESS_IN_LINE_COMMENT_PATTERN.matcher(comment.text)
        return matcher.matches() && SuppressionUtil.isInspectionToolIdMentioned(matcher.group(1), toolId)
    }
}

/** Where suppression comments attach in Go code. */
internal object GoSuppressionContainers {

    /** A statement in a block or case clause, a top-level declaration, or an import spec of a group. */
    fun isContainer(e: PsiElement): Boolean = isStatement(e) || isDeclaration(e) || (e is GoImportSpec && (e.parent as? GoImportDeclaration)?.lparen != null)

    fun isStatement(e: PsiElement): Boolean = e !is PsiComment && e !is PsiWhiteSpace && e.node.elementType != GoTypes.SEMICOLON_SYNTHETIC &&
        e.node.elementType != GoTypes.LBRACE && e.node.elementType != GoTypes.RBRACE && e.textLength > 0 &&
        when (e.parent) {
            is GoBlock, is GoExprCaseClause, is GoTypeCaseClause, is GoCommClause -> e.node.elementType !in CLAUSE_TOKENS
            else -> false
        }

    fun isDeclaration(e: PsiElement): Boolean = e.parent is GoFile && e !is PsiComment && e !is PsiWhiteSpace && e !is GoPackageClause &&
        e.node.elementType != GoTypes.SEMICOLON_SYNTHETIC && e.node.elementType != GoTypes.SEMICOLON && e.textLength > 0

    private val CLAUSE_TOKENS = setOf(GoTypes.CASE, GoTypes.DEFAULT, GoTypes.COLON, GoTypes.SEMICOLON)

    /**
     * Comments that start their own line directly above [e] (no blank line in between), plus the
     * comments bound inside [e] before its first token (doc comments of declarations).
     */
    fun leadingComments(e: PsiElement): List<PsiComment> {
        val out = ArrayList<PsiComment>()
        var child = e.firstChild
        while (child != null && (child is PsiComment || child is PsiWhiteSpace)) {
            if (child is PsiComment) out += child
            child = child.nextSibling
        }
        val text = e.containingFile.viewProvider.contents
        var newlines = 0
        var p = e.prevSibling
        while (p != null) {
            when {
                p is PsiWhiteSpace || p.node.elementType == GoTypes.SEMICOLON_SYNTHETIC -> {
                    newlines += p.text.count { it == '\n' }
                    if (newlines > 1) break
                }
                p is PsiComment -> {
                    if (!startsLine(text, p.textRange.startOffset)) break
                    out += p
                    newlines = 0
                }
                else -> break
            }
            p = p.prevSibling
        }
        return out
    }

    /** Comments before the package clause (and those bound inside it as package documentation). */
    fun fileComments(file: GoFile): List<PsiComment> {
        val out = ArrayList<PsiComment>()
        var child = file.firstChild
        while (child != null && (child is PsiComment || child is PsiWhiteSpace)) {
            if (child is PsiComment) out += child
            child = child.nextSibling
        }
        if (child is GoPackageClause) {
            var c = child.firstChild
            while (c != null && (c is PsiComment || c is PsiWhiteSpace)) {
                if (c is PsiComment) out += c
                c = c.nextSibling
            }
        }
        return out
    }

    private fun startsLine(text: CharSequence, offset: Int): Boolean {
        var i = offset - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        return i < 0 || text[i] == '\n'
    }
}

/**
 * Adds `//noinspection <id>` above the enclosing statement, top-level declaration (below its doc
 * comment), or at the top of the file followed by a blank line; an existing suppression comment
 * there gets the id appended. Text-based, so the new comment is always on its own line.
 */
class GoSuppressByCommentFix(toolId: String, private val kind: Kind) : AbstractBatchSuppressByNoInspectionCommentFix(toolId, false) {
    enum class Kind(val text: String) { STATEMENT("Suppress for statement"), DECLARATION("Suppress for declaration"), FILE("Suppress for file") }

    init {
        setText(kind.text)
    }

    override fun getContainer(context: PsiElement?): PsiElement? {
        if (context == null || context.containingFile !is GoFile) return null
        if (kind == Kind.FILE) return context.containingFile
        var e: PsiElement? = context
        while (e != null && e !is PsiFile) {
            if (kind == Kind.STATEMENT && GoSuppressionContainers.isStatement(e)) return e
            if (kind == Kind.DECLARATION && GoSuppressionContainers.isDeclaration(e)) return e
            e = e.parent
        }
        return null
    }

    override fun getCommentsFor(container: PsiElement): List<PsiElement>? = when (container) {
        is GoFile -> GoSuppressionContainers.fileComments(container)
        else -> GoSuppressionContainers.leadingComments(container)
    }.filter { SuppressionUtil.SUPPRESS_IN_LINE_COMMENT_PATTERN.matcher(it.text).matches() }.ifEmpty { null }

    override fun createSuppression(project: Project, element: PsiElement, container: PsiElement) {
        val file = container.containingFile
        val manager = PsiDocumentManager.getInstance(project)
        val document = manager.getDocument(file) ?: return
        manager.doPostponedOperationsAndUnblockDocument(document)
        val comment = "//${SuppressionUtil.SUPPRESS_INSPECTIONS_TAG_NAME} $myID"
        if (container is PsiFile) {
            document.insertString(0, "$comment\n\n")
        } else {
            // Below the doc comment bound inside a declaration, so the documentation stays first.
            var first = container.firstChild
            while (first != null && (first is PsiComment || first is PsiWhiteSpace)) first = first.nextSibling
            val offset = (first ?: container).textRange.startOffset
            val text = document.charsSequence
            val lineStart = document.getLineStartOffset(document.getLineNumber(offset))
            val prefix = text.subSequence(lineStart, offset)
            if (prefix.isBlank()) {
                document.insertString(lineStart, "$prefix$comment\n")
            } else {
                // The container shares its line with other code (`{ x := 1 }`): start a new line.
                document.insertString(offset, "\n$comment\n")
            }
        }
        manager.commitDocument(document)
    }
}
