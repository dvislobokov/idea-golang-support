package io.github.golangsupport.lsp

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.GoFile
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Range

/** Which of the code actions of gopls are actions on the code, and how they are named in a list. */
object GoplsActionKinds {
    /** These open a page of the web server of gopls (documentation, assembly, free symbols): not what Alt+Enter is pressed for. */
    private val BROWSING = listOf("source.doc", "source.assembly", "source.freesymbols", "source.toggleCompilerOptDetails", "source.splitPackage", "gopls.doc")

    fun isEditing(kind: String?): Boolean = kind == null || BROWSING.none { kind == it || kind.startsWith("$it.") }
}

/**
 * Alt+Enter | "Refactorings and Actions of gopls...": everything the server offers for the caret or the selection, in a popup:
 * Extract variable / function / method, Inline call, Fill struct, Invert if, Add test, Extract declarations to a new file...
 *
 * The LSP client of the platform shows the quick fixes of the server's own diagnostics, but at a place without a diagnostic its
 * Alt+Enter stays empty, while gopls, asked directly for the same place, has a handful of actions (checked with tools/gopls/probe.py).
 * So the question is asked here, without a filter on the kind; applying an action is left to the platform (`LspIntentionAction`
 * resolves the edit, applies it and runs the command).
 */
class GoplsActionsIntention : IntentionAction {
    override fun getText(): String = "Refactorings and actions of gopls..."
    override fun getFamilyName(): String = "gopls"
    override fun startInWriteAction(): Boolean = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = editor != null && file is GoFile && Gopls.client(project) != null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val virtualFile = file.virtualFile ?: return
        val client = Gopls.client(project) ?: return
        val document = editor.document
        val selection = editor.selectionModel
        val range = Range(Gopls.position(document, if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset),
            Gopls.position(document, if (selection.hasSelection()) selection.selectionEnd else editor.caretModel.offset))
        // EDT: the server is asked under a progress that can be cancelled
        val actions = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<CodeAction>, RuntimeException>(
            { actions(client, CodeActionParams(client.getDocumentIdentifier(virtualFile), range, CodeActionContext(emptyList()))) }, "Asking gopls for Actions", true, project,
        )
        if (actions.isEmpty()) {
            HintManager.getInstance().showInformationHint(editor, "gopls has no actions for this place" + if (selection.hasSelection()) "" else "; select an expression or statements to extract them")
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(actions)
            .setTitle("gopls")
            .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { it.title })
            .setNamerForFiltering { it.title }
            .setItemChosenCallback { LspIntentionAction(client, it).invoke(project, editor, file) }
            .createPopup().showInBestPositionFor(editor)
    }

    private fun actions(client: LspClient, params: CodeActionParams): List<CodeAction> =
        Gopls.codeActions(client, params, TIMEOUT_MS).filter { GoplsActionKinds.isEditing(it.kind) }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
