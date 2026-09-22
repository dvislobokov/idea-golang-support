package io.github.golangsupport.build

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoSettings
import java.io.File

/**
 * A `go` command over the modules of the project, in the Build tool window. With a file or a directory selected in a tree the command
 * covers the module of the selection only; from the main menu, every module.
 */
abstract class GoModulesAction(private val title: String) : AnAction(), DumbAware {
    /** The arguments of `go` for one module. */
    protected abstract fun arguments(): List<String>

    /** The command rewrites go.mod / go.sum or generates files: the module directory is re-read afterwards. */
    protected open val changesFiles: Boolean get() = false

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasModules = project != null && directories(project, e).isNotEmpty()
        // out of place in a context menu of a project without Go, still visible (and explained by being disabled) in the main menu
        if (e.isFromContextMenu) e.presentation.isEnabledAndVisible = hasModules else e.presentation.isEnabled = hasModules
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directories = directories(project, e)
        val commands = GoCli.commandLinesOrNotify(project, title) { directories.map { GoCli.commandLine(it.path, *arguments().toTypedArray()) } } ?: return
        GoCli.runInBackground(project, title, commands, refresh = if (changesFiles) directories.map { File(it.path) } else emptyList())
    }

    private fun directories(project: Project, e: AnActionEvent): List<VirtualFile> {
        val modules = GoModulesService.getInstance(project)
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { e.isFromContextMenu }
        return selected?.let { modules.moduleOf(it) }?.let { listOf(it.root) } ?: modules.commandDirectories().filter { hasGoCode(it) }
    }

    /** A module, or at least a Go file on the top: `go build` in a directory without Go only fails. */
    private fun hasGoCode(directory: VirtualFile): Boolean = directory.findChild("go.mod") != null || directory.children.any { it.extension == "go" }
}

class GoBuildAction : GoModulesAction("Go Build") {
    override fun arguments(): List<String> = listOf("build") + GoSettings.getInstance().buildTagArguments() + "./..."
}

class GoVetAction : GoModulesAction("Go Vet") {
    override fun arguments(): List<String> = listOf("vet") + GoSettings.getInstance().buildTagArguments() + "./..."
}

class GoModTidyAction : GoModulesAction("Go Mod Tidy") {
    override val changesFiles: Boolean get() = true
    override fun arguments(): List<String> = listOf("mod", "tidy")
}

class GoModDownloadAction : GoModulesAction("Go Mod Download") {
    override val changesFiles: Boolean get() = true
    override fun arguments(): List<String> = listOf("mod", "download")
}

class GoModVendorAction : GoModulesAction("Go Mod Vendor") {
    override val changesFiles: Boolean get() = true
    override fun arguments(): List<String> = listOf("mod", "vendor")
}

class GoGenerateAction : GoModulesAction("Go Generate") {
    override val changesFiles: Boolean get() = true
    override fun arguments(): List<String> = listOf("generate", "./...")
}
