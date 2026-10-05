package io.github.golangsupport.ide.inspections

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import io.github.golangsupport.ide.intentions.GoEditPlan
import io.github.golangsupport.lang.psi.GoNamedElement

/**
 * A quick fix whose text edits are computed at apply time from the problem element ([edits] gets `descriptor.psiElement`; null or
 * empty does nothing). [edits] must not capture PSI: it runs on the fresh tree. Edits are applied from the end of the document.
 */
class GoEditFix(private val text: String, private val edits: (PsiElement) -> List<GoEditPlan.Edit>?) : LocalQuickFix {
    override fun getFamilyName(): String = text

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        val file = element.containingFile ?: return
        val plan = edits(element)?.takeIf { it.isNotEmpty() } ?: return
        val document = GoImportEdits.document(file) ?: return
        for (edit in plan.sortedByDescending { it.start }) document.replaceString(edit.start, edit.end, edit.text)
        GoImportEdits.commit(file, document)
    }
}

/** Renames the named element at (or above) the problem element to [newName] through the platform's rename processor (references too). */
class GoRenameToFix(private val newName: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Rename to '$newName'"

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = PsiTreeUtil.getParentOfType(descriptor.psiElement, GoNamedElement::class.java, false) ?: return
        RenameProcessor(project, element, newName, false, false).run()
    }
}

/** Text helpers of the GoLand-parity inspections. */
object GoInspectionText {
    /** The indentation (spaces and tabs) of the line holding [offset]. */
    fun indentAt(text: CharSequence, offset: Int): String {
        var start = offset
        while (start > 0 && text[start - 1] != '\n') start--
        var end = start
        while (end < text.length && (text[end] == ' ' || text[end] == '\t')) end++
        return text.substring(start, end)
    }

    /** The start of the line holding [offset]. */
    fun lineStart(text: CharSequence, offset: Int): Int {
        var start = offset
        while (start > 0 && text[start - 1] != '\n') start--
        return start
    }

    /** The range of the whole lines [start, end) occupy, with the line break after them, when nothing but blanks shares them; else null. */
    fun wholeLines(text: CharSequence, start: Int, end: Int): IntRange? {
        val from = lineStart(text, start)
        if (text.subSequence(from, start).any { it != ' ' && it != '\t' }) return null
        var to = end
        while (to < text.length && (text[to] == ' ' || text[to] == '\t' || text[to] == '\r')) to++
        if (to < text.length && text[to] != '\n') return null
        return from until minOf(to + 1, text.length)
    }

    /** `foo_bar` -> `fooBar`, `Foo_bar_baz` -> `FooBarBaz`; leading and trailing underscores stay. */
    fun camelCase(name: String): String {
        val lead = name.takeWhile { it == '_' }
        val trail = name.drop(lead.length).takeLastWhile { it == '_' }
        val core = name.substring(lead.length, name.length - trail.length)
        val parts = core.split('_').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return name
        val first = parts.first()
        val rest = parts.drop(1).joinToString("") { p -> if (p.all { it.isUpperCase() || it.isDigit() }) p else p.replaceFirstChar { it.uppercaseChar() } }
        return lead + first + rest + trail
    }
}
