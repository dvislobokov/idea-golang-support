package io.github.golangsupport.format

import com.intellij.openapi.actionSystem.AnActionEvent
import io.github.golangsupport.build.GoFileToolAction
import io.github.golangsupport.build.refreshOf
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoTool

/**
 * Tools | Go Tools | Goimports File: `goimports -w file.go` whatever formatter Reformat Code uses, in the directory of the file (goimports
 * finds its module there). The documents are saved first by the background run, the file is re-read afterwards. Without goimports the
 * notification offers to install it.
 */
class GoImportsFileAction : GoFileToolAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = goFile(e) ?: return
        val title = "Goimports File"
        val goimports = GoTool.GOIMPORTS.find() ?: return GoTool.GOIMPORTS.offerInstallation(project, title)
        val commands = GoCli.commandLinesOrNotify(project, title) { listOf(GoCli.toolCommandLine(goimports.path, file.parent?.path, *arguments(file.name).toTypedArray())) } ?: return
        GoCli.runInBackground(project, title, commands, refresh = refreshOf(file))
    }

    companion object {
        fun arguments(fileName: String): List<String> = listOf("-w", fileName)
    }
}
