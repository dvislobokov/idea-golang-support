package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.ide.util.ChooseElementsDialog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.GoIdeIcons
import io.github.golangsupport.ide.intentions.GoFillStruct
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.psi.GoPsiUtil.elements
import io.github.golangsupport.semantic.types.GoField
import org.jetbrains.annotations.TestOnly
import javax.swing.Icon

/**
 * The first two items of a struct literal's completion, as in GoLand: **Fill all fields…** writes every field not written yet, one per
 * line with its zero value and the values aligned as gofmt writes them; **Fill selected fields…** asks which ones first (all ticked).
 * Only in a keyed or empty literal (`T{}`, `&T{}`, `[]T{{}}`) with fields left; the text is [GoFillStruct]'s, the Fill intention's.
 */
object GoFillStructCompletion {
    const val FILL_ALL = "Fill all fields…"
    const val FILL_SELECTED = "Fill selected fields…"

    /** Above everything else in the list, whatever the prefix and the expected type. */
    private const val PRIORITY = 1_000_000.0

    /** Picks the fields instead of the dialog (tests): gets the names offered, returns the chosen ones or null for Cancel. */
    @TestOnly @Volatile var chooserForTests: ((List<String>) -> List<String>?)? = null

    /** Adds the two items when the caret element [current] of [literal] (in the completion copy) is where a key may go. */
    fun collect(context: GoCompletionContext, literal: GoLiteralValue, current: GoElement?, result: CompletionResultSet) {
        if (context.keyOnly || context.parameters.completionType != com.intellij.codeInsight.completion.CompletionType.BASIC) return
        GoFillStruct.target(context.file, literal, ignore = current) ?: return
        result.addElement(item(FILL_ALL, selected = false))
        result.addElement(item(FILL_SELECTED, selected = true))
    }

    private fun item(text: String, selected: Boolean): LookupElement = PrioritizedLookupElement.withPriority(
        LookupElementBuilder.create(text).withIcon(BULB).withInsertHandler(handler(selected)), PRIORITY - (if (selected) 1 else 0),
    )

    private val BULB: Icon = AllIcons.Actions.IntentionBulb

    private fun handler(selected: Boolean) = InsertHandler<LookupElement> { ctx, _ ->
        val document = ctx.document
        val offset = ctx.startOffset
        document.deleteString(offset, ctx.tailOffset)
        PsiDocumentManager.getInstance(ctx.project).commitDocument(document)
        val file = ctx.file as? GoFile ?: return@InsertHandler
        if (!selected) return@InsertHandler fill(file, ctx.editor, offset, null)
        val fields = targetAt(file, offset)?.fields ?: return@InsertHandler
        chooserForTests?.let { choose ->
            choose(fields.map { it.name })?.let { fill(file, ctx.editor, offset, it) }
            return@InsertHandler
        }
        // a dialog cannot be shown inside the write action of the insertion
        val marker = document.createRangeMarker(offset, offset)
        val project = ctx.project
        val editor = ctx.editor
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || editor.isDisposed || !marker.isValid) return@invokeLater
            val chosen = ask(project, fields) ?: return@invokeLater
            if (chosen.isEmpty()) return@invokeLater
            WriteCommandAction.runWriteCommandAction(project, "Fill Selected Fields", null, {
                PsiDocumentManager.getInstance(project).commitDocument(document)
                fill(file, editor, marker.startOffset, chosen)
            }, file)
        }
    }

    private fun targetAt(file: GoFile, offset: Int): GoFillStruct.Target? = literalAt(file, offset)?.let { GoFillStruct.target(file, it) }

    private fun literalAt(file: GoFile, offset: Int): GoLiteralValue? {
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
        return PsiTreeUtil.getParentOfType(leaf, GoLiteralValue::class.java, false)
    }

    /** Writes the fields ([chosen] ones, or all) into the literal at [offset] and puts the caret after the first value written. */
    private fun fill(file: GoFile, editor: Editor, offset: Int, chosen: List<String>?) {
        val target = targetAt(file, offset) ?: return
        val plan = GoFillStruct.plan(file, target, chosen, align = true) ?: return
        val first = target.fields.firstOrNull { chosen == null || it.name in chosen }?.name ?: return
        val document = editor.document
        val manager = PsiDocumentManager.getInstance(file.project)
        val lbrace = target.value.lbrace.textRange.startOffset
        val anchor = document.createRangeMarker(lbrace, lbrace + 1)
        for (edit in plan.edits.sortedByDescending { it.start }) document.replaceString(edit.start, edit.end, edit.text)
        manager.commitDocument(document)
        for (path in plan.imports) GoImportInserter.addImport(file, document, path)
        manager.commitDocument(document)
        val value = file.findElementAt(anchor.startOffset)?.parent as? GoLiteralValue ?: return
        val element = value.elements.firstOrNull { (it.key?.expression as? GoReferenceExpression)?.identifier?.text == first } ?: return
        editor.caretModel.moveToOffset(element.value?.textRange?.endOffset ?: return)
    }

    private fun ask(project: Project, fields: List<GoField>): List<String>? {
        val dialog = object : ChooseElementsDialog<GoField>(project, fields, "Select Fields", "Fields to fill:", false) {
            override fun getItemText(item: GoField): String = item.name + "  " + GoLookupElementFactory.typeText(item.type)
            override fun getItemIcon(item: GoField): Icon = GoIdeIcons.FIELD
            override fun canElementsBeMarked(): Boolean = true
            override fun isElementMarkedByDefault(element: GoField): Boolean = true
        }
        return if (dialog.showAndGet()) dialog.markedElements.map { it.name } else null
    }
}
