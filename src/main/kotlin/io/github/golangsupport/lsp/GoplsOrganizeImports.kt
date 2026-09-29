package io.github.golangsupport.lsp

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.lang.GoImports
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Range

/**
 * Alt+Enter | Optimize Imports on the imports of a file, as in GoLand: the unused ones go, the missing ones come, the rest is sorted.
 *
 * gopls has it as an action of the file (`source.organizeImports`), not as a fix of the error `"sort" imported and not used` (checked
 * with tools/gopls/probe.py: the error has no fix of its own), and the platform shows under Alt+Enter the fixes of errors only; so on
 * an unused import there was nothing to choose (reported by the user).
 */
class GoplsOrganizeImportsIntention : IntentionAction, HighPriorityAction {
    override fun getText(): String = "Optimize imports"
    override fun getFamilyName(): String = "Optimize imports"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file is GoFile && Gopls.client(project) != null && GoImports.isInImports(editor.document.immutableCharSequence, editor.caretModel.offset)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val virtualFile = file.virtualFile ?: return
        val client = Gopls.client(project) ?: return
        val position = Gopls.position(editor.document, editor.caretModel.offset)
        val params = CodeActionParams(client.getDocumentIdentifier(virtualFile), Range(position, position), CodeActionContext(emptyList()).apply { only = listOf(GoplsEdits.ORGANIZE) })
        val action = ProgressManager.getInstance().runProcessWithProgressSynchronously<CodeAction?, RuntimeException>(
            { Gopls.codeActions(client, params, TIMEOUT_MS).firstOrNull { it.kind?.startsWith(GoplsEdits.ORGANIZE) == true } }, "Asking gopls for the Imports", true, project,
        )
        // gopls gives no action when there is nothing to change
        if (action == null) HintManager.getInstance().showInformationHint(editor, "The imports are in order")
        else GoplsEdits.apply(project, editor, file, client, action)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
