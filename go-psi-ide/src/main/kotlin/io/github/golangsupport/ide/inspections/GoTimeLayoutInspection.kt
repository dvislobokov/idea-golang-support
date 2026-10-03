package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.inspections.printf.GoStringValue
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStringLiteral

/**
 * Mistakes in the layout of `Time.Format` / `time.Parse` written as a string literal (constants are not checked: `time.RFC3339` and
 * the like are right):
 * - a layout in the notation of other languages (`yyyy-MM-dd HH:mm:ss`): Go takes the reference time `2006-01-02 15:04:05`;
 *   fix "Convert to Go layout";
 * - a layout with no element at all (`"date"`): it formats to itself;
 * - the ISO date with the day before the month, `2006-02-01` (fix "Swap to '2006-01-02'").
 */
class GoTimeLayoutInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoCallExpr) return
        val literal = GoTimeLayoutCalls.layoutArgument(element) as? GoStringLiteral ?: return
        val layout = GoStringValue.decode(literal.text)?.value ?: return
        if (layout.isEmpty()) return
        val go = GoTimeLayout.foreignToGo(layout)
        if (go != null) {
            val shown = if (layout.length > 40) layout.take(39) + "…" else layout
            holder.registerProblem(
                literal, "Layout uses '$shown' notation; Go layouts use the reference time 2006-01-02 15:04:05",
                ProblemHighlightType.WEAK_WARNING, GoReplaceLayoutFix("Convert to Go layout", go),
            )
        } else if (GoTimeLayout.elementCount(layout) == 0) {
            holder.registerProblem(literal, "Layout contains no time elements", ProblemHighlightType.WEAK_WARNING)
        } else if (GoTimeLayout.isSwappedIsoDate(layout)) {
            holder.registerProblem(
                literal, "Day and month swapped? '2006-02-01' formats day 02 as month",
                ProblemHighlightType.WEAK_WARNING, GoReplaceLayoutFix("Swap to '2006-01-02'", "2006-01-02" + layout.substring(10)),
            )
        }
    }
}

/** Replaces the layout literal by [layout], keeping its kind (raw or interpreted string). */
class GoReplaceLayoutFix(private val text: String, private val layout: String) : LocalQuickFix {
    override fun getFamilyName(): String = text

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val literal = descriptor.psiElement as? GoStringLiteral ?: return
        val document = GoImportEdits.document(literal.containingFile) ?: return
        val replacement = if (literal.rawString != null && '`' !in layout) "`$layout`" else GoStructTags.quote(layout)
        document.replaceString(literal.textRange.startOffset, literal.textRange.endOffset, replacement)
        GoImportEdits.commit(literal.containingFile, document)
    }
}
