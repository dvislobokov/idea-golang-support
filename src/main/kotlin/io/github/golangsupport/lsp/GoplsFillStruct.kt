package io.github.golangsupport.lsp

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.HighPriorityAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.lang.GoStructLiterals
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Range

/**
 * Alt+Enter | Fill All Fields, as in GoLand: every field of the struct with its zero value, written by gopls
 * (`refactor.rewrite.fillStruct`). gopls has the action for a literal only (checked with tools/gopls/probe.py); on the name of a type
 * that stands where a value is expected the braces are written first, which is what makes a literal of it.
 */
class GoplsFillStructIntention : IntentionAction, HighPriorityAction {
    override fun getText(): String = "Fill all fields"
    override fun getFamilyName(): String = "Fill all fields"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is GoFile || Gopls.client(project) == null) return false
        val text = editor.document.immutableCharSequence
        val offset = editor.caretModel.offset
        return GoStructLiterals.isInLiteral(text, offset) || bareType(project, editor) != null
    }

    /**
     * The end of a type at the caret that is written without braces. That the name is a type is what the compiler says about it
     * through gopls: `Options (type) is not an expression`; the text alone does not tell a type from a variable.
     */
    private fun bareType(project: Project, editor: Editor): Int? {
        val (start, end) = GoStructLiterals.bareName(editor.document.immutableCharSequence, editor.caretModel.offset) ?: return null
        var found = false
        DaemonCodeAnalyzerEx.processHighlights(editor.document, project, HighlightSeverity.INFORMATION, start, end) { info ->
            found = info.description?.contains(NOT_AN_EXPRESSION) == true
            !found
        }
        return end.takeIf { found }
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val virtualFile = file.virtualFile ?: return
        val client = Gopls.client(project) ?: return
        val document = editor.document
        var offset = editor.caretModel.offset
        if (!GoStructLiterals.isInLiteral(document.immutableCharSequence, offset)) {
            val end = bareType(project, editor) ?: return
            WriteCommandAction.runWriteCommandAction(project, text, null, {
                document.insertString(end, "{}")
                PsiDocumentManager.getInstance(project).commitDocument(document)
            })
            offset = end + 1
            editor.caretModel.moveToOffset(offset)
        }
        val position = Gopls.position(document, offset)
        val params = CodeActionParams(client.getDocumentIdentifier(virtualFile), Range(position, position), CodeActionContext(emptyList()).apply { only = listOf(KIND) })
        val action = ProgressManager.getInstance().runProcessWithProgressSynchronously<CodeAction?, RuntimeException>({ ask(client, params) }, "Asking gopls for the Fields", true, project)
        if (action == null) HintManager.getInstance().showInformationHint(editor, "gopls has no fields to fill here")
        else GoplsEdits.apply(project, editor, file, client, action)
    }

    /** Asked again after a moment when there is no answer: the braces that were just written may not have reached the server. */
    private fun ask(client: LspClient, params: CodeActionParams): CodeAction? {
        repeat(ATTEMPTS) { attempt ->
            Gopls.codeActions(client, params, TIMEOUT_MS).firstOrNull { it.kind == KIND }?.let { return it }
            if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_MS)
        }
        return null
    }

    private companion object {
        const val KIND = "refactor.rewrite.fillStruct"
        const val NOT_AN_EXPRESSION = "is not an expression"
        const val TIMEOUT_MS = 10_000
        const val ATTEMPTS = 3
        const val RETRY_MS = 300L
    }
}
