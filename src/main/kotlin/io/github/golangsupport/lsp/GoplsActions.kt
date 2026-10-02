package io.github.golangsupport.lsp

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.LowPriorityAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import io.github.golangsupport.catalogue.GoCatalogueService
import io.github.golangsupport.lang.GoImports
import io.github.golangsupport.lang.GoSemanticColors
import org.eclipse.lsp4j.Position
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoFile
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Range

/** Which of the code actions of gopls are actions on the code, and how they are named in a list. */
object GoplsActionKinds {
    /** These open a page of the web server of gopls (documentation, assembly, free symbols): not what Alt+Enter is pressed for. */
    private val BROWSING = listOf("source.doc", "source.assembly", "source.freesymbols", "source.toggleCompilerOptDetails", "source.splitPackage", "gopls.doc")

    fun isEditing(kind: String?): Boolean = kind == null || BROWSING.none { kind == it || kind.startsWith("$it.") }

    /** These have items of their own, which do more: the imports are put in order on the imports only, a struct is filled with its imports. */
    private val OWN = listOf("source.organizeImports", "refactor.rewrite.fillStruct")

    /** What is shown as an item of the list of Alt+Enter ([GoplsIntention]). */
    fun isIntention(kind: String?): Boolean = isEditing(kind) && (kind == null || OWN.none { kind == it || kind.startsWith("$it.") })

    /** An intention of the plugin that is offered at the place: its class and its name in the list. */
    class Offered(val className: String, val text: String)

    /** The actions of gopls that do what an intention of the plugin does under another name. */
    private val SAME_AS = mapOf("source.addTest" to "GoGenerateTestIntention", "refactor.rewrite.addTags" to "GoAddStructTagsIntention")

    /**
     * Whether the list has the action of gopls already, as an intention of the plugin: by the kind of the action, or by its name,
     * `Add struct tags` being `Add struct tags...` (reported by the user: both were in the list). The one of the plugin stays: it is
     * the one with the choices. Where it is not offered, the one of gopls is.
     */
    fun isOffered(kind: String?, title: String?, offered: Collection<Offered>, nativeCodeActions: Boolean = false): Boolean {
        if (nativeCodeActions && isNativeCodeAction(kind, title)) return true
        val same = SAME_AS.entries.firstOrNull { (k, _) -> kind == k || kind?.startsWith("$k.") == true }?.value
        if (same != null && offered.any { it.className.substringAfterLast('.') == same }) return true
        val name = name(title)
        return name.isNotEmpty() && offered.any { name(it.text) == name }
    }

    /** The kinds of gopls whose actions the native intentions of go-psi-ide do (`ide.intentions`, MIGRATION.md step 9). */
    private val NATIVE_KINDS = listOf("refactor.rewrite.fillStruct", "refactor.rewrite.fillSwitch")

    /**
     * Whether a code action of gopls is one of those the native intentions replace when the switch Code actions says Built-in:
     * fillstruct (`Fill Options`, `Fill anonymous struct`), fillswitch (`Add cases for Color`), the fillreturns quick fix
     * (`Fill in return values`); titles read in gopls v0.23 (`internal/analysis/fill*`). gopls has no action that handles an error.
     */
    fun isNativeCodeAction(kind: String?, title: String?): Boolean {
        if (kind != null && NATIVE_KINDS.any { kind == it || kind.startsWith("$it.") }) return true
        val text = title.orEmpty().trim()
        return text.startsWith("Fill ") || text.startsWith("Add cases for ")
    }

    /** A name without what differs by habit: the case of the letters, the dots of a dialog to come, the quotes around a name (`Create function 'f'`). */
    private fun name(text: String?): String = text.orEmpty().trim().trimEnd('.', '…', ' ').replace("'", "").lowercase()
}

/**
 * A code action of gopls, applied. The class of the platform that applies one ([LspIntentionAction]) asks the server for the edit and
 * finds the documents when it is asked whether it is available, in the background; its `invoke` without that question before it does
 * nothing at all, silently (seen live: Fill All Fields did nothing). So the question is asked first, under a progress.
 */
object GoplsEdits {
    fun apply(project: Project, editor: Editor, file: PsiFile, client: LspClient, action: CodeAction) {
        val prepared = ProgressManager.getInstance().runProcessWithProgressSynchronously<LspIntentionAction?, RuntimeException>({
            LspIntentionAction(client, action).takeIf { intention -> ReadAction.compute<Boolean, RuntimeException> { intention.isAvailable(project, editor, file) } }
        }, "Asking gopls for the Edit", true, project)
        if (prepared == null) return HintManager.getInstance().showInformationHint(editor, "gopls has not given an edit for \"${action.title}\"")
        val before = editor.document.immutableCharSequence
        prepared.invoke(project, editor, file)
        // what has put the imports in order has nothing to import
        if (action.kind?.startsWith(ORGANIZE) != true) importWritten(project, editor, client, file, before)
    }

    /**
     * The imports of what the edit has written. gopls fills a struct with `URL: &url.URL{}` and leaves the import to the one who
     * asked (checked with its answer: no edit of the imports in it; reported by the user). The package is found by its name and by
     * the names taken of it: in the catalogue at once, and what is not there (a module required indirectly) is asked of gopls, whose
     * Organize Imports knows it; of its answer only what it adds is taken, nothing is removed.
     */
    private fun importWritten(project: Project, editor: Editor, client: LspClient, file: PsiFile, before: CharSequence) {
        val document = editor.document
        val written = GoImports.written(before, document.immutableCharSequence) ?: return
        val missing = GoImports.missing(document.immutableCharSequence, written)
        if (missing.isEmpty()) return
        val index = GoCatalogueService.getInstance(project).index
        val found = missing.mapNotNull { (name, symbols) -> index.packageOf(name, symbols) }
        import(project, editor, found)
        val unknown = missing.keys.filter { name -> found.none { GoSemanticColors.packageName(it) == name } && index.packageOf(name, missing.getValue(name)) == null }
        val virtualFile = file.virtualFile
        if (unknown.isEmpty() || virtualFile == null) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val paths = organized(client, virtualFile).filter { GoSemanticColors.packageName(it) in unknown }
            if (paths.isNotEmpty()) ApplicationManager.getApplication().invokeLater({ import(project, editor, paths) }, project.disposed)
        }
    }

    private fun import(project: Project, editor: Editor, paths: List<String>) {
        if (paths.isEmpty() || editor.isDisposed) return
        WriteCommandAction.runWriteCommandAction(project, "Add Imports", null, {
            for (path in paths) GoImports.add(editor.document.immutableCharSequence, path)?.let { editor.document.insertString(it.offset, it.text) }
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        })
    }

    /** The import paths in what Organize Imports of gopls would write; asked again after a moment, the edit may not have reached the server. */
    private fun organized(client: LspClient, file: VirtualFile): List<String> {
        val start = Position(0, 0)
        val params = CodeActionParams(client.getDocumentIdentifier(file), Range(start, start), CodeActionContext(emptyList()).apply { only = listOf(ORGANIZE) })
        repeat(ATTEMPTS) { attempt ->
            val edits = Gopls.codeActions(client, params, TIMEOUT_MS).filter { it.kind?.startsWith(ORGANIZE) == true }.mapNotNull { it.edit }
            val texts = edits.flatMap { it.documentChanges.orEmpty() }.mapNotNull { it.takeIf { change -> change.isLeft }?.left }.flatMap { it.edits.orEmpty() }.mapNotNull { it.newText } +
                edits.flatMap { it.changes?.values.orEmpty() }.flatten().mapNotNull { it.newText }
            val paths = texts.flatMap { text -> QUOTED.findAll(text).map { it.groupValues[1] }.toList() }
            if (paths.isNotEmpty()) return paths
            if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_MS)
        }
        return emptyList()
    }

    const val ORGANIZE = "source.organizeImports"
    private const val TIMEOUT_MS = 3_000
    private const val ATTEMPTS = 3
    private const val RETRY_MS = 400L
    private val QUOTED = Regex("\"([^\"\\s]+)\"")
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
class GoplsActionsIntention : IntentionAction, LowPriorityAction {
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
            .setItemChosenCallback { GoplsEdits.apply(project, editor, file, client, it) }
            .createPopup().showInBestPositionFor(editor)
    }

    private fun actions(client: LspClient, params: CodeActionParams): List<CodeAction> =
        Gopls.codeActions(client, params, TIMEOUT_MS).filter { GoplsActionKinds.isEditing(it.kind) }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
