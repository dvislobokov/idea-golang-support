package io.github.golangsupport.lsp

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.mod.GoModulesService
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Range

/**
 * The menu Go | gopls: what the server can do beyond what the editor asks it for. Each action needs a running gopls; most need the
 * file in the editor (the module of it, the place of the caret). What gopls answers with is a page in the browser, diagnostics, edits
 * to go.mod, or a text in the log window.
 */
abstract class GoplsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val available = project != null && Gopls.client(project) != null && isAvailable(e)
        if (e.isFromContextMenu) e.presentation.isEnabledAndVisible = available else e.presentation.isEnabled = available
    }

    protected open fun isAvailable(e: AnActionEvent): Boolean = true

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        perform(project, Gopls.client(project) ?: return, e)
    }

    protected abstract fun perform(project: Project, client: LspClient, e: AnActionEvent)

    protected fun file(e: AnActionEvent): VirtualFile? = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.fileType == GoFileType || it.fileType == GoModFileType }

    /** The go.mod of the file in the editor, or of the only module of the project. */
    protected fun modFile(project: Project, e: AnActionEvent): VirtualFile? {
        val service = GoModulesService.getInstance(project)
        return (service.moduleOf(file(e)) ?: service.modules().singleOrNull())?.modFile
    }
}

/** The whole `require` list at once: the lens of gopls asks per module. */
class GoplsCheckUpgradesAction : GoplsAction() {
    override fun perform(project: Project, client: LspClient, e: AnActionEvent) {
        val mod = modFile(project, e) ?: return GoCli.notifyInfo(project, "gopls", "Open a file of the module whose dependencies to check")
        val modules = GoModulesService.getInstance(project).moduleOf(mod)?.content?.requires.orEmpty().map { it.path }
        if (modules.isEmpty()) return GoCli.notifyInfo(project, "gopls", "${mod.name} requires nothing")
        GoplsCommands.send(client, GoplsCommands.command("gopls.check_upgrades", "Check for Upgrades", mapOf("URI" to client.getDocumentIdentifier(mod).uri, "Modules" to modules)))
    }
}

class GoplsUpgradeAllAction : GoplsAction() {
    override fun perform(project: Project, client: LspClient, e: AnActionEvent) {
        val mod = modFile(project, e) ?: return GoCli.notifyInfo(project, "gopls", "Open a file of the module whose dependencies to upgrade")
        val arguments = mapOf("URI" to client.getDocumentIdentifier(mod).uri, "GoCmdArgs" to listOf("-d", "-u", "-t", "./..."), "AddRequire" to false)
        GoplsCommands.send(client, GoplsCommands.command("gopls.upgrade_dependency", "Upgrade All Dependencies", arguments))
    }
}

class GoplsVulncheckAction : GoplsAction() {
    override fun perform(project: Project, client: LspClient, e: AnActionEvent) {
        val mod = modFile(project, e) ?: return GoCli.notifyInfo(project, "gopls", "Open a file of the module to check")
        GoplsCommands.send(client, GoplsCommands.command("gopls.vulncheck", "Run govulncheck", mapOf("URI" to client.getDocumentIdentifier(mod).uri, "Pattern" to "./...")))
    }
}

class GoplsResetModDiagnosticsAction : GoplsAction() {
    override fun perform(project: Project, client: LspClient, e: AnActionEvent) {
        val mod = modFile(project, e) ?: return
        GoplsCommands.send(client, GoplsCommands.command("gopls.reset_go_mod_diagnostics", "Reset go.mod Diagnostics", mapOf("URI" to client.getDocumentIdentifier(mod).uri, "DiagnosticSource" to "")))
    }
}

/** Statistics of the workspace and of the memory of the server: to the log window. */
class GoplsStatisticsAction : GoplsAction() {
    override fun perform(project: Project, client: LspClient, e: AnActionEvent) {
        GoplsCommands.send(client, GoplsCommands.command("gopls.workspace_stats", "Workspace Statistics", emptyMap<String, Any>()))
        GoplsCommands.send(client, GoplsCommands.command("gopls.mem_stats", "Memory Statistics", emptyMap<String, Any>()))
    }
}

/** The packages gopls knows and the file does not import yet; the chosen one is imported by the server. */
class GoplsAddImportAction : GoplsAction() {
    override fun isAvailable(e: AnActionEvent): Boolean = file(e)?.fileType == GoFileType

    override fun perform(project: Project, client: LspClient, e: AnActionEvent) {
        val file = file(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val uri = client.getDocumentIdentifier(file).uri
        GoplsCommands.send(client, GoplsCommands.command("gopls.list_known_packages", "List Known Packages", mapOf("URI" to uri))) { result ->
            val packages = GoplsCommandArguments.json(result)?.getAsJsonArray("Packages")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
            ApplicationManager.getApplication().invokeLater {
                if (packages.isEmpty()) return@invokeLater GoCli.notifyInfo(project, "gopls", "No packages to import")
                val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(packages)
                    .setTitle("Add Import")
                    .setNamerForFiltering { it }
                    .setItemChosenCallback { GoplsCommands.send(client, GoplsCommands.command("gopls.add_import", "Add Import", mapOf("ImportPath" to it, "URI" to uri))) }
                    .createPopup()
                if (editor != null) popup.showInBestPositionFor(editor) else popup.showCenteredInCurrentWindow(project)
            }
        }
    }
}

/**
 * The pages of the web server of gopls about the code at the caret: documentation, assembly of the function, free symbols of the
 * selection; and the toggle of the compiler's optimization decisions for the package. Each is a code action of one kind, whose command
 * the server answers with `window/showDocument` (a browser) or with diagnostics.
 */
abstract class GoplsCodeActionAction(private val kind: String, private val hint: String) : GoplsAction() {
    override fun isAvailable(e: AnActionEvent): Boolean = file(e)?.fileType == GoFileType && e.getData(CommonDataKeys.EDITOR) != null

    override fun perform(project: Project, client: LspClient, e: AnActionEvent) {
        val file = file(e) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val range = ReadAction.compute<Range, RuntimeException> {
            val selection = editor.selectionModel
            val start = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
            val end = if (selection.hasSelection()) selection.selectionEnd else editor.caretModel.offset
            Range(Gopls.position(editor.document, start), Gopls.position(editor.document, end))
        }
        val params = CodeActionParams(client.getDocumentIdentifier(file), range, CodeActionContext(emptyList()).apply { only = listOf(kind) })
        val actions = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<CodeAction>, RuntimeException>(
            { Gopls.codeActions(client, params, 10_000).filter { it.kind == kind || it.kind?.startsWith("$kind.") == true } }, "Asking gopls", true, project,
        )
        val command = actions.firstOrNull()?.command ?: return HintManager.getInstance().showInformationHint(editor, hint)
        GoplsCommands.send(client, command)
    }
}

class GoplsBrowseDocumentationAction : GoplsCodeActionAction("source.doc", "gopls has no documentation page for this place: put the caret on a name or a package")
class GoplsBrowseAssemblyAction : GoplsCodeActionAction("source.assembly", "gopls shows assembly for a function: put the caret inside one")
class GoplsBrowseFreeSymbolsAction : GoplsCodeActionAction("source.freesymbols", "Select the code whose free symbols to show")
class GoplsToggleOptimizationDetailsAction : GoplsCodeActionAction("source.toggleCompilerOptDetails", "gopls has no optimization details for this file")
