package io.github.golangsupport.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.inspections.printf.GoPrintfCalls
import io.github.golangsupport.ide.inspections.printf.GoPrintfChecker
import io.github.golangsupport.ide.inspections.printf.GoPrintfProblem
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.semantic.api.GoSemanticService

/**
 * vet's `printf` check over the PSI: calls of `fmt`/`log`/`testing` print functions and of the package's own wrappers
 * ([GoPrintfCalls]) checked by [GoPrintfChecker] (argument count, `[n]` indexes, `*`, verb against argument type, flags, `%w`;
 * Printf directives and redundant newlines in Print-like calls). Problems point at the directive inside the format string.
 * Fixes: replace the verb by the one the argument's type takes, remove extra arguments or add `%v` placeholders for them;
 * `%v` of an error in `Errorf` offers `%w` without a warning. Reports nothing while the host serves diagnostics from another
 * source ([GoIdeFeature.DIAGNOSTICS] off); not dumb-aware (resolve and types go through stub indices).
 */
class GoPrintfInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val file = holder.file as? GoFile ?: return PsiElementVisitor.EMPTY_VISITOR
        if (!GoIdeFeatureGate.enabled(GoIdeFeature.DIAGNOSTICS, file.project)) return PsiElementVisitor.EMPTY_VISITOR
        val checker = GoPrintfChecker(GoSemanticService.getInstance(file.project))
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element !is GoCallExpr || element.argumentList == null) return
                val call = GoPrintfCalls.of(element) ?: return
                for (p in checker.check(call)) register(holder, p, isOnTheFly)
            }
        }
    }

    private fun register(holder: ProblemsHolder, p: GoPrintfProblem, isOnTheFly: Boolean) {
        if (p.suggestion && !isOnTheFly) return
        val fixes = fixesOf(p)
        val type = if (p.suggestion) ProblemHighlightType.INFORMATION else ProblemHighlightType.GENERIC_ERROR_OR_WARNING
        val end = p.endAnchor
        if (end != null && end !== p.anchor) {
            holder.registerProblem(holder.manager.createProblemDescriptor(p.anchor, end, p.message, type, isOnTheFly, *fixes))
        } else {
            holder.registerProblem(holder.manager.createProblemDescriptor(p.anchor, p.range ?: TextRange(0, p.anchor.textLength), p.message, type, isOnTheFly, *fixes))
        }
    }

    private fun fixesOf(p: GoPrintfProblem): Array<LocalQuickFix> = when (val fix = p.fix) {
        is GoPrintfProblem.Fix.ReplaceVerb -> arrayOf(GoReplacePrintfVerbFix(pointer(fix.literal), fix.offset, fix.from, fix.to))
        is GoPrintfProblem.Fix.ExtraArguments -> if (fix.count <= 0) emptyArray() else listOfNotNull(
            GoRemoveExtraArgumentsFix(fix.count),
            if (fix.literal != null && fix.insertAt != null) GoAddPrintfPlaceholdersFix(pointer(fix.literal), fix.insertAt, fix.count) else null,
        ).toTypedArray()
        null -> emptyArray()
    }

    private fun <T : PsiElement> pointer(e: T): SmartPsiElementPointer<T> = SmartPointerManager.createPointer(e)
}

/** `Replace %d with %s`: the verb char of a directive, flags and width kept. */
class GoReplacePrintfVerbFix(
    private val literal: SmartPsiElementPointer<GoStringLiteral>,
    private val offset: Int,
    private val from: Char,
    private val to: Char,
) : LocalQuickFix {
    override fun getFamilyName(): String = "Replace the printf verb"

    override fun getName(): String = "Replace %$from with %$to"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = literal.element ?: return
        val document = GoImportEdits.document(element.containingFile) ?: return
        val at = element.textRange.startOffset + offset
        if (document.charsSequence.getOrNull(at) != from) return
        document.replaceString(at, at + 1, to.toString())
        GoImportEdits.commit(element.containingFile, document)
    }
}

/** Removes the arguments no directive reads (with the commas before them). */
class GoRemoveExtraArgumentsFix(private val count: Int) : LocalQuickFix {
    override fun getFamilyName(): String = "Remove extra arguments"

    override fun getName(): String = if (count == 1) "Remove extra argument" else "Remove $count extra arguments"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val first = descriptor.startElement ?: return
        val last = descriptor.endElement ?: first
        val list = first.parent as? GoArgumentList ?: return
        val document = GoImportEdits.document(first.containingFile) ?: return
        // From the end of the argument before the first extra one: its comma goes too.
        var prev = first.prevSibling
        while (prev != null && prev !is io.github.golangsupport.lang.psi.GoExpression) prev = prev.prevSibling
        val start = prev?.textRange?.endOffset ?: list.lparen?.textRange?.endOffset ?: return
        document.deleteString(start, last.textRange.endOffset)
        GoImportEdits.commit(first.containingFile, document)
    }
}

/** Appends a ` %v` placeholder per extra argument to the format string (before a trailing `\n`). */
class GoAddPrintfPlaceholdersFix(
    private val literal: SmartPsiElementPointer<GoStringLiteral>,
    private val offset: Int,
    private val count: Int,
) : LocalQuickFix {
    override fun getFamilyName(): String = "Add missing argument placeholders"

    override fun getName(): String = if (count == 1) "Add missing argument placeholder" else "Add $count missing argument placeholders"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = literal.element ?: return
        val document = GoImportEdits.document(element.containingFile) ?: return
        val at = element.textRange.startOffset + offset
        val text = element.text
        val before = text.getOrNull(offset - 1)
        val separator = if (before == null || before == '"' || before == '`' || before == ' ') "" else " "
        document.insertString(at, separator + List(count) { "%v" }.joinToString(" "))
        GoImportEdits.commit(element.containingFile, document)
    }
}
