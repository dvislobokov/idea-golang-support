package io.github.golangsupport.lang

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.golangsupport.ide.completion.GoStructTagCompletion
import io.github.golangsupport.lang.psi.GoFieldDeclaration
import io.github.golangsupport.lang.psi.GoStructType
import org.jetbrains.annotations.TestOnly

/**
 * GoLand's first item at a key of a struct tag: **Add tag key to all fields…** asks for a key (`json`, `yaml`, …) and writes it, with the
 * name of each field in the style the struct uses for that key already, into the tags of every field that lacks it (the field being
 * edited included). The tags are written by [GoGenerateStructTagsAction.tagEdits], as Generate | Add Tags does.
 */
object GoStructTagKeyToAllFields {
    const val ITEM = "Add tag key to all fields…"

    /** The keys offered: those whose value is named after the field, in the order of the completion of keys. */
    val KEYS: List<String> = GoStructTagCompletion.KEYS.filter { it in GoStructTagCompletion.NAME_KEYS }

    /** Picks the key instead of the popup (tests): gets [KEYS], returns the chosen one or null for Cancel. */
    @TestOnly @Volatile var chooserForTests: ((List<String>) -> String?)? = null

    fun item(): LookupElement = PrioritizedLookupElement.withPriority(
        LookupElementBuilder.create(ITEM).withIcon(AllIcons.Actions.IntentionBulb).withInsertHandler(INSERT), 1000.0,
    )

    /** Whether [offset] of [file] is inside a closed raw-string tag of a struct field. */
    fun applicable(file: PsiFile, offset: Int): Boolean {
        val leaf = file.findElementAt(offset - 1) ?: return false
        val (tag, _) = GoStructTagCompletion.tagAt(leaf, offset) ?: return false
        val text = tag.text
        return text.length >= 2 && text.endsWith('`') && tag.parent is GoFieldDeclaration
    }

    private val INSERT = InsertHandler<LookupElement> { ctx, _ ->
        val document = ctx.document
        val offset = ctx.startOffset
        document.deleteString(offset, ctx.tailOffset)
        PsiDocumentManager.getInstance(ctx.project).commitDocument(document)
        val file = ctx.file
        chooserForTests?.let { choose ->
            choose(KEYS)?.let { add(file, ctx.editor, offset, it) }
            return@InsertHandler
        }
        val marker = document.createRangeMarker(offset, offset)
        val project = ctx.project
        val editor = ctx.editor
        // a popup and a command of its own: not from inside the write action of the insertion
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || editor.isDisposed) return@invokeLater
            JBPopupFactory.getInstance().createPopupChooserBuilder(KEYS)
                .setTitle("Add Tag Key to All Fields")
                .setItemChosenCallback { key ->
                    if (!marker.isValid) return@setItemChosenCallback
                    WriteCommandAction.runWriteCommandAction(project, "Add Tag Key to All Fields", null, {
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                        add(file, editor, marker.startOffset, key)
                    }, file)
                }
                .createPopup().showInBestPositionFor(editor)
        }
    }

    /** Writes [key] into the tags of the struct whose tag holds [offset]; the caret stays at the end of that tag. */
    fun add(file: PsiFile, editor: Editor, offset: Int, key: String) {
        val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return
        val tag = GoStructPsi.tagAround(leaf) ?: return
        val declaration = tag.parent as? GoFieldDeclaration ?: return
        val struct = declaration.parent as? GoStructType ?: return
        val style = GoStructTagCompletion.styleFor(struct, key)
        val edits = GoGenerateStructTagsAction.tagEdits(GoStructPsi.fields(struct).filter { !it.embedded }, listOf(key), style::apply, omitEmpty = false)
        val document = editor.document
        val range = tag.textRange
        val shift = edits.filter { it.first.endOffset <= range.startOffset && it.first != range }.sumOf { it.second.length - it.first.length }
        val newTag = edits.firstOrNull { it.first == range }?.second ?: tag.text
        for ((r, text) in edits.sortedByDescending { it.first.startOffset }) document.replaceString(r.startOffset, r.endOffset, text)
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        editor.caretModel.moveToOffset(range.startOffset + shift + newTag.length - 1)
    }
}
