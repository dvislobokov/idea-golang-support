package io.github.golangsupport.ide.formatter

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import io.github.golangsupport.ide.inspections.GoAnalysisScope
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTokenSets

/** Line comments that want a space after `//`: shared by the inspection `GoCommentLeadingSpace` and [GoCommentSpacePostFormatProcessor]. */
object GoLineComments {
    /** Directives are not comments: `//go:generate`, `//nolint:x`, any `//word:` form, `//line`, `//export`, `//extern`, regions, cgo `//#`, `//+build`. */
    private val DIRECTIVE = Regex("^//([a-z0-9]+:[a-z0-9]|line[ :]|export |extern |nolint|noinspection|region|endregion|#|\\+build)")

    /** `//text`: true; `// text`, a bare `//`, `////` and directives: false. */
    fun needsLeadingSpace(text: CharSequence): Boolean {
        if (!text.startsWith("//") || text.length < 3) return false
        val c = text[2]
        return !c.isWhitespace() && c != '/' && !DIRECTIVE.containsMatchIn(text)
    }
}

/**
 * Code Style | Go | Other "Add a leading space to comments" ([GoCodeStyleSettings.ADD_LEADING_SPACE_TO_COMMENTS], off by default): as in
 * GoLand, Reformat Code turns `//text` into `// text`, with the exclusions of the inspection ([GoLineComments]); generated files are left
 * alone. Only the Built-in formatter runs it (an external gofmt claims the file before the platform's formatter).
 */
class GoCommentSpacePostFormatProcessor : PostFormatProcessor {
    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement {
        if (source is GoFile && source.isValid) processText(source, source.textRange, settings)
        return source
    }

    override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
        if (source !is GoFile || !settings.getCustomSettings(GoCodeStyleSettings::class.java).ADD_LEADING_SPACE_TO_COMMENTS) return rangeToReformat
        val documents = PsiDocumentManager.getInstance(source.project)
        val document = documents.getDocument(source) ?: return rangeToReformat
        documents.doPostponedOperationsAndUnblockDocument(document)
        if (GoAnalysisScope.isGenerated(document.immutableCharSequence)) return rangeToReformat
        val offsets = insertions(source.node, rangeToReformat)
        if (offsets.isEmpty()) return rangeToReformat
        for (offset in offsets.sortedDescending()) document.insertString(offset, " ")
        documents.commitDocument(document)
        return TextRange(rangeToReformat.startOffset, (rangeToReformat.endOffset + offsets.size).coerceIn(rangeToReformat.startOffset, document.textLength))
    }

    companion object {
        /** Offsets right after `//` of every line comment inside [range] that needs the space. */
        fun insertions(root: ASTNode, range: TextRange): List<Int> {
            val result = ArrayList<Int>()
            fun visit(node: ASTNode) {
                if (!node.textRange.intersects(range)) return
                if (GoTokenSets.COMMENTS.contains(node.elementType)) {
                    if (range.contains(node.textRange) && GoLineComments.needsLeadingSpace(node.chars)) result += node.startOffset + 2
                    return
                }
                var child = node.firstChildNode
                while (child != null) {
                    visit(child)
                    child = child.treeNext
                }
            }
            visit(root)
            return result
        }
    }
}
