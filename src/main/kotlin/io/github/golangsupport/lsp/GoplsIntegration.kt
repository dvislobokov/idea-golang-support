package io.github.golangsupport.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.settings.GoLanguageServerControl
import io.github.golangsupport.settings.GoSettings
import org.eclipse.lsp4j.ConfigurationItem
import java.util.concurrent.atomic.AtomicBoolean

/** Starts gopls for the project when a Go file (or a go.mod / go.work, which gopls checks too) is opened. */
class GoplsIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
        if (!isGoplsFile(file) || !GoSettings.getInstance().languageServerEnabled) return
        if (GoTool.GOPLS.find() == null) {
            // once per session: the notification has an Install button, and every opened file would stack another one
            if (OFFERED.compareAndSet(false, true)) GoTool.GOPLS.offerInstallation(project, "Go language server") { restart(project) }
            return
        }
        clientStarter.ensureClientStarted(GoplsDescriptor(project))
    }

    companion object {
        private val OFFERED = AtomicBoolean()

        fun isGoplsFile(file: VirtualFile): Boolean = file.fileType == GoFileType || file.fileType == GoModFileType

        fun restart(project: Project) = LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(GoplsIntegrationProvider::class.java)
    }
}

class GoplsDescriptor(project: Project) : ProjectWideLspClientDescriptor(project, "gopls") {
    override fun isSupportedFile(file: VirtualFile): Boolean = GoplsIntegrationProvider.isGoplsFile(file)

    override fun createCommandLine(): GeneralCommandLine {
        val gopls = GoTool.GOPLS.find() ?: throw com.intellij.execution.ExecutionException("gopls is not found")
        return GoCli.toolCommandLine(gopls.path, project.guessProjectDir()?.path, "serve")
    }

    override fun getLanguageId(file: VirtualFile): String = when {
        file.name == GoModFileType.GO_WORK -> "go.work"
        file.fileType == GoModFileType -> "go.mod"
        else -> "go"
    }

    override fun createInitializationOptions(): Any = GoplsOptions.build(GoSettings.getInstance())

    /** gopls asks for the section `gopls` after `workspace/didChangeConfiguration`. */
    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any? = if (item.section == "gopls") GoplsOptions.build(GoSettings.getInstance()) else null
}

/** The settings of gopls (https://go.dev/gopls/settings) the page Settings | Tools | Go has switches for. */
object GoplsOptions {
    fun build(settings: GoSettings): Map<String, Any> = buildMap {
        put("staticcheck", settings.goplsStaticcheck)
        put("gofumpt", settings.goplsGofumpt)
        settings.buildTagArguments().takeIf { it.isNotEmpty() }?.let { put("buildFlags", it) }
        put("usePlaceholders", true)
        put("semanticTokens", true)
        if (settings.goplsInlayHints) put("hints", HINTS.associateWith { true })
    }

    private val HINTS = listOf("parameterNames", "assignVariableTypes", "rangeVariableTypes", "constantValues", "compositeLiteralFields", "functionTypeParameters")
}

class GoplsControl : GoLanguageServerControl {
    override fun restart(project: Project) = GoplsIntegrationProvider.restart(project)
}

class RestartGoplsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(GoplsIntegrationProvider::restart)
    }
}
