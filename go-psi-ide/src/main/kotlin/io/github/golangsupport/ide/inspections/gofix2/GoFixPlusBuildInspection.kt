package io.github.golangsupport.ide.inspections.gofix2

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoBuildConstraints
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator

/**
 * modernize `plusbuild` / go fix `buildtag` (go1.17): a `// +build` line of the file header.
 * - With a `//go:build` line: "+build line is no longer needed"; fix removes every `// +build` line.
 * - Without one: the `// +build` lines (ANDed) become one `//go:build` line with the equivalent expression.
 */
class GoFixPlusBuildInspection : GoFix2InspectionBase() {
    override val minVersion = "1.17"

    override fun detect(element: PsiElement, file: GoFile): GoFixFinding? {
        if (element !is PsiComment || !GoBuildConstraintEvaluator.isPlusBuild(element.text) || !inHeader(element, file)) return null
        val comments = file.children.filterIsInstance<PsiComment>().filter { inHeader(it, file) }
        val plusBuild = comments.filter { GoBuildConstraintEvaluator.isPlusBuild(it.text) }
        val text = file.text
        val deletions = plusBuild.map { GoFixEdit.delete(lineRange(text, it.textRange)) }
        if (comments.any { GoBuildConstraintEvaluator.isGoBuild(it.text) }) {
            return GoFixFinding(element, "+build line is no longer needed", listOf("Remove obsolete +build lines" to { _ -> deletions }))
        }
        val expression = GoBuildConstraints.plusBuildToGoBuild(plusBuild.map { it.text }) ?: return null
        return GoFixFinding(element, "+build line is obsolete; use //go:build", listOf("Replace +build lines with //go:build" to { _ ->
            listOf(GoFixEdit.replace(plusBuild.first(), "//go:build $expression")) + deletions.drop(1)
        }))
    }

    /** The comment's line with its line break. */
    private fun lineRange(text: CharSequence, range: TextRange): TextRange {
        var start = range.startOffset
        while (start > 0 && text[start - 1] != '\n') start--
        var end = range.endOffset
        while (end < text.length && text[end] != '\n') end++
        return TextRange(start, minOf(end + 1, text.length))
    }

    /** Where go reads constraints: before the package clause, with a blank line between them (as vet `buildtag`). */
    private fun inHeader(comment: PsiComment, file: GoFile): Boolean {
        if (comment.parent !== file) return false
        val packageStart = file.packageClause?.textRange?.startOffset ?: return false
        val end = comment.textRange.endOffset
        return end <= packageStart && BLANK_LINE.containsMatchIn(file.text.substring(end, packageStart))
    }

    companion object {
        private val BLANK_LINE = Regex("\n[ \t\r]*\n")
    }
}
