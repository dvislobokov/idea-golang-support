package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator

/**
 * vet `buildtag` over the `//go:build` and `// +build` comments of a file, from comment text only:
 * - a `//go:build` expression with a syntax error (error);
 * - a `//go:build` line outside the header (after the package clause, or not followed by a blank line before it), or a second one;
 * - the tags of a `// +build` line (the line itself is the Go fix inspection `GoFixPlusBuild`'s: it reports and converts it, so this one does not);
 * - a tag one edit away from a known GOOS/GOARCH (`linx`; weak warning; fix "Replace with 'linux'"). Custom tags stay quiet;
 * - (GoLand parity G7, `GoBuildTag`) a `// +build` line outside the header ("misplaced +build comment") and a comment above the package
 *   clause that mentions `+build` but is not a `+build` line, e.g. `// see +build` ("possible malformed +build comment"), as vet does.
 */
class GoBuildConstraintInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is PsiComment) return
        val text = element.text
        when {
            GoBuildConstraintEvaluator.isGoBuild(text) -> checkGoBuild(element, text, holder, file)
            GoBuildConstraintEvaluator.isPlusBuild(text) ->
                if (inHeader(element, file)) checkPlusBuild(element, text, holder)
                else holder.registerProblem(element, "misplaced +build comment", ProblemHighlightType.WEAK_WARNING)
            text.startsWith("//") && text.contains("+build") && beforePackage(element, file) ->
                holder.registerProblem(element, "possible malformed +build comment", ProblemHighlightType.WEAK_WARNING)
        }
    }

    private fun checkGoBuild(comment: PsiComment, text: String, holder: ProblemsHolder, file: GoFile) {
        if (!inHeader(comment, file)) return holder.registerProblem(comment, "misplaced //go:build comment", ProblemHighlightType.GENERIC_ERROR)
        if (goBuildComments(file).first() !== comment) return holder.registerProblem(comment, "multiple //go:build comments", ProblemHighlightType.GENERIC_ERROR)
        val error = GoBuildConstraints.syntaxError(text)
        if (error != null) return holder.registerProblem(comment, "invalid //go:build expression: ${error.message}", ProblemHighlightType.GENERIC_ERROR)
        checkTags(comment, text, GoBuildConstraints.GO_BUILD.length, holder)
    }

    private fun checkPlusBuild(comment: PsiComment, text: String, holder: ProblemsHolder) =
        checkTags(comment, text, text.indexOf("+build") + "+build".length, holder)

    private fun checkTags(comment: PsiComment, text: String, from: Int, holder: ProblemsHolder) {
        for ((tag, offset) in GoBuildConstraints.tags(text, from)) {
            val suggestion = GoBuildConstraints.suggest(tag) ?: continue
            holder.registerProblem(comment, "unknown GOOS/GOARCH '$tag'", ProblemHighlightType.WEAK_WARNING, TextRange(offset, offset + tag.length), GoReplaceTagFix(suggestion))
        }
    }

    private fun comments(file: GoFile): Collection<PsiComment> = PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java)
    private fun goBuildComments(file: GoFile) = comments(file).filter { GoBuildConstraintEvaluator.isGoBuild(it.text) && inHeader(it, file) }

    /** Whether [comment] is where go reads constraints: before the package clause, with a blank line between them. */
    private fun inHeader(comment: PsiComment, file: GoFile): Boolean {
        if (comment.parent !== file) return false
        val packageStart = file.packageClause?.textRange?.startOffset ?: return false
        val end = comment.textRange.endOffset
        if (end > packageStart) return false
        return BLANK_LINE.containsMatchIn(file.text.substring(end, packageStart))
    }

    companion object {
        private val BLANK_LINE = Regex("\n[ \t\r]*\n")

        /** Whether [comment] ends before the package clause (vet reports malformed `+build` lines only there). */
        private fun beforePackage(comment: PsiComment, file: GoFile): Boolean =
            comment.parent === file && comment.textRange.endOffset <= (file.packageClause?.textRange?.startOffset ?: -1)
    }
}

/** Replaces one tag of a constraint comment by [replacement]. */
class GoReplaceTagFix(private val replacement: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace with '$replacement'"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val comment = descriptor.psiElement ?: return
        val range = descriptor.textRangeInElement ?: return
        val file = comment.containingFile
        val document = GoImportEdits.document(file) ?: return
        val start = comment.textRange.startOffset
        document.replaceString(start + range.startOffset, start + range.endOffset, replacement)
        GoImportEdits.commit(file, document)
    }
}

/** Adds `//go:build [expression]` above the first `// +build` line. */
