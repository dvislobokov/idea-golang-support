package io.github.golangsupport.ide.inspections.style

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.ide.inspections.GoAnalysisScope
import io.github.golangsupport.ide.inspections.GoDocCommentInspection
import io.github.golangsupport.ide.inspections.GoEditFix
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.lang.psi.GoFile

/**
 * GoLand's "Comment has no leading space" (`GoCommentLeadingSpace`): a line comment whose text starts right after `//`. Directives are
 * not comments for this check: `//go:generate`, `//nolint:x`, `//lint:ignore` (any `//word:` form), `//line`, `//export`, `//extern`,
 * `//nolint`, `//noinspection`, region markers, cgo `//#` lines and `//+build` (that one is the build tag inspection's). `////` and a
 * bare `//` stay quiet; generated files are skipped. Fix: add the space.
 */
class GoCommentLeadingSpaceInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is PsiComment || !needsSpace(element.text) || GoAnalysisScope.isGenerated(file)) return
        holder.registerProblem(element, "Line comment should have a space after '//'", FIX)
    }

    companion object {
        private val DIRECTIVE = Regex("^//([a-z0-9]+:[a-z0-9]|line[ :]|export |extern |nolint|noinspection|region|endregion|#|\\+build)")

        fun needsSpace(text: String): Boolean {
            if (!text.startsWith("//") || text.length < 3) return false
            val c = text[2]
            return !c.isWhitespace() && c != '/' && !DIRECTIVE.containsMatchIn(text)
        }

        private val FIX = GoEditFix("Add a space after '//'") { listOf(GoEditPlan.Edit(it.textRange.startOffset + 2, it.textRange.startOffset + 2, " ")) }
    }
}

/**
 * GoLand's "Comment of exported element starts with the incorrect name" (`GoCommentStart`): the form half of golint `exported`, split
 * out of [GoDocCommentInspection] (which keeps "should have comment"): a doc comment of an exported function, method, type, const or var
 * that does not start with the name (`A` / `An` / `The` and a `Deprecated:` paragraph accepted), and a comment that is nothing but the
 * name (`// NewOrder`: "Comment should be meaningful or it should be removed", seen live in GoLand). Skipped: `_test.go` and generated files.
 */
class GoCommentStartInspection : GoDocCommentInspection() {
    override val checksMissing: Boolean get() = false
    override val checksForm: Boolean get() = true
    override fun skipFile(file: GoFile): Boolean = file.isTestFile || GoAnalysisScope.isGenerated(file)
}
