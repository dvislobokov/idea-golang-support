package io.github.golangsupport.lsp

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.settings.GoSettings
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Range

/**
 * The actions of gopls for the caret or the selection, each as an item of its own under Alt+Enter: Extract variable, Inline call,
 * Invert if, Add test, Convert to raw string...
 *
 * The platform has the same for any language server, and with gopls it shows nothing: it asks with the trigger `Automatic`, and gopls
 * answers such a question with no actions at all, while asked as `Invoked` for the same place it has a handful (checked with a probe
 * of both). So the question is asked here, the way gopls answers it, and what the platform asks is switched off in [GoplsDescriptor].
 *
 * Asked once for a place of a text: the items are asked one after another whether they are available, and the first of them asks.
 */
@Service(Service.Level.PROJECT)
class GoplsIntentionService(private val project: Project) {
    private class Asked(val path: String, val stamp: Long, val start: Int, val end: Int, val actions: List<CodeAction>)

    @Volatile private var asked: Asked? = null

    fun actions(editor: Editor, file: PsiFile): List<CodeAction> {
        val virtualFile = file.virtualFile ?: return emptyList()
        val document = editor.document
        val caret = editor.caretModel.primaryCaret
        val start = caret.selectionStart
        val end = caret.selectionEnd
        asked?.takeIf { it.path == virtualFile.path && it.stamp == document.modificationStamp && it.start == start && it.end == end }?.let { return it.actions }
        // a server is not waited for on EDT: what was asked in the background is there, or nothing is
        if (ApplicationManager.getApplication().isDispatchThread) return emptyList()
        val client = Gopls.client(project) ?: return emptyList()
        val range = Range(Gopls.position(document, start), Gopls.position(document, end))
        val context = CodeActionContext(emptyList()).apply { triggerKind = CodeActionTriggerKind.Invoked }
        val offered = offered(editor, file)
        val actions = Gopls.codeActions(client, CodeActionParams(client.getDocumentIdentifier(virtualFile), range, context), TIMEOUT_MS)
            .filter { GoplsActionKinds.isIntention(it.kind) && it.disabled == null && !GoplsActionKinds.isOffered(it.kind, it.title, offered) }
            .distinctBy { it.title }.take(SLOTS)
        asked = Asked(virtualFile.path, document.modificationStamp, start, end, actions)
        return actions
    }

    /** The intentions of the plugin itself that are in the list for this place: what gopls has of the same is not shown twice. */
    private fun offered(editor: Editor, file: PsiFile): List<GoplsActionKinds.Offered> =
        IntentionManager.getInstance().availableIntentions.asSequence().map { IntentionActionDelegate.unwrap(it) }
            .filter { it.javaClass.name.startsWith(PLUGIN) && it !is GoplsIntention && it !is GoplsActionsIntention }
            .filter { runCatching { it.isAvailable(project, editor, file) }.getOrDefault(false) }
            .map { GoplsActionKinds.Offered(it.javaClass.name, it.text) }.toList()

    companion object {
        /** As many as there are classes below: an item of the list is a class registered in the descriptor of the module. */
        const val SLOTS = 10

        // asked while the caret moves: better no items than a read action that waits
        private const val TIMEOUT_MS = 1_000
        private const val PLUGIN = "io.github.golangsupport."

        fun getInstance(project: Project): GoplsIntentionService = project.service()
    }
}

/** The action of gopls number [index] for the place of the caret, under its own name. */
abstract class GoplsIntention(private val index: Int) : IntentionAction, PriorityAction {
    /** What the item was shown for: an intention is one object for every editor, and its text is asked for without one. */
    @Volatile private var action: CodeAction? = null

    override fun getFamilyName(): String = "gopls"
    override fun getText(): String = action?.title ?: familyName
    override fun startInWriteAction(): Boolean = false

    /** A refactoring among the intentions; what is about the file as a whole (Add test) below them. */
    override fun getPriority(): PriorityAction.Priority = if (action?.kind?.startsWith("refactor") == true) PriorityAction.Priority.NORMAL else PriorityAction.Priority.LOW

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is GoFile || !GoSettings.getInstance().goplsActionsInMenu) return false
        action = GoplsIntentionService.getInstance(project).actions(editor, file).getOrNull(index)
        return action != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val chosen = action ?: return
        val client = Gopls.client(project) ?: return
        GoplsEdits.apply(project, editor, file, client, chosen)
    }
}

class GoplsIntention0 : GoplsIntention(0)
class GoplsIntention1 : GoplsIntention(1)
class GoplsIntention2 : GoplsIntention(2)
class GoplsIntention3 : GoplsIntention(3)
class GoplsIntention4 : GoplsIntention(4)
class GoplsIntention5 : GoplsIntention(5)
class GoplsIntention6 : GoplsIntention(6)
class GoplsIntention7 : GoplsIntention(7)
class GoplsIntention8 : GoplsIntention(8)
class GoplsIntention9 : GoplsIntention(9)
