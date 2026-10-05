package io.github.golangsupport.build

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.settings.GoSettings
import java.io.File

/** Tools | Go Tools | Go Fmt Project: `go fmt ./...` (gofmt -l -w over the packages) in every module, the files re-read afterwards. */
class GoFmtProjectAction : GoModulesAction("Go Fmt Project") {
    override val changesFiles: Boolean get() = true
    override fun arguments(): List<String> = listOf("fmt", "./...")
}

/** An action on the Go file of the editor or of the selection; hidden in context menus for anything else, disabled in the main menu. */
abstract class GoFileToolAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    protected fun goFile(e: AnActionEvent): VirtualFile? = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { !it.isDirectory && it.extension == "go" && it.isInLocalFileSystem }

    override fun update(e: AnActionEvent) {
        val enabled = e.project != null && goFile(e) != null
        if (e.isFromContextMenu) e.presentation.isEnabledAndVisible = enabled else e.presentation.isEnabled = enabled
    }
}

/** Tools | Go Tools | Go Vet File: `go vet .` in the directory of the file, that is its package (with its tests), in the Build window. */
class GoVetFileAction : GoFileToolAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = goFile(e)?.parent ?: return
        val title = "Go Vet File"
        val commands = GoCli.commandLinesOrNotify(project, title) { listOf(GoCli.commandLine(directory.path, *vetFileArguments(GoSettings.getInstance().buildTagArguments()).toTypedArray())) } ?: return
        GoCli.runInBackground(project, title, commands)
    }

    companion object {
        /** A file of a package is vetted with the package: cmd/vet over a lone file misses the declarations of its siblings. */
        fun vetFileArguments(buildTags: List<String>): List<String> = listOf("vet") + buildTags + "."
    }
}

/** Where the commands of a file action write back: the file itself, re-read from disk after the tool has rewritten it. */
internal fun refreshOf(file: VirtualFile): List<File> = listOf(File(file.path))
