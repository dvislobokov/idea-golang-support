package io.github.golangsupport.mod

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import io.github.golangsupport.build.GoModulesAction
import io.github.golangsupport.catalogue.GoCatalogueService
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.project.impl.GoProjectModelTracker
import io.github.golangsupport.settings.GoSettings

/** Which directories a Go module may be attached from or detached: pure, over paths. */
object GoModuleRoots {
    /** Why [directory] cannot be attached to the project; null when it can. */
    fun attachProblem(directory: String, hasGoMod: Boolean, contentRoots: List<String>): String? = when {
        !hasGoMod -> "There is no go.mod in $directory. Only Go modules can be attached (GOPATH mode is not supported)."
        contentRoots.any { FileUtil.isAncestor(it, directory, false) } -> "$directory is already a part of the project."
        else -> null
    }

    /** A content root other than the project directory (and not inside it) is what Attach Go Module has added: it can be detached. */
    fun isDetachable(directory: String, isContentRoot: Boolean, projectDirectory: String?): Boolean =
        isContentRoot && (projectDirectory == null || !FileUtil.isAncestor(projectDirectory, directory, false))
}

/** After the roots or the dependencies of a module changed: the project model, the catalogue of packages and the highlighting follow. */
private fun reread(project: Project, reason: String) {
    GoProjectModelTracker.getInstance(project).bump(reason)
    if (GoSettings.getInstance().completionCatalogue) GoCatalogueService.getInstance(project).refreshLater()
    DaemonCodeAnalyzer.getInstance(project).restart()
}

/**
 * Sync Go Module (GoLand's name): `go mod download` of the module of the selection (every module from the main menu), then the
 * project model, the catalogue and the highlighting read the module graph again.
 */
class GoSyncModuleAction : GoModulesAction("Sync Go Module") {
    override val changesFiles: Boolean get() = true
    override fun arguments(): List<String> = listOf("mod", "download")
    override fun succeeded(project: Project) = reread(project, "Sync Go Module")
}

/**
 * Attach Go Module...: a directory with go.mod outside the project becomes a content root of it, so that a second module (a library
 * being changed next to the application, say) is indexed, resolved and built with the project. GoLand's "Add Directory to Current
 * Project" of GOPATH mode, for modules.
 */
class GoAttachModuleAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && ModuleManager.getInstance(project).modules.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Attach Go Module").withDescription("A directory with go.mod")
        val directory = FileChooser.chooseFile(descriptor, project, null) ?: return
        attach(project, directory)
    }

    companion object {
        fun attach(project: Project, directory: VirtualFile) {
            val module = ModuleManager.getInstance(project).modules.firstOrNull() ?: return
            val roots = ProjectRootManager.getInstance(project).contentRoots.map { it.path }
            GoModuleRoots.attachProblem(directory.path, directory.findChild(GoModFileType.GO_MOD) != null, roots)?.let {
                return Messages.showWarningDialog(project, it, "Attach Go Module")
            }
            ModuleRootModificationUtil.updateModel(module) { it.addContentEntry(directory) }
            GoPluginLog.info("go", "attached Go module ${directory.path} to ${module.name}")
            reread(project, "Attach Go Module ${directory.path}")
            GoCli.notifyInfo(project, "Go module attached", "${directory.path} is a part of the project now. Detach Go Module in its context menu takes it out.")
        }
    }
}

/** Detach Go Module: the content root Attach Go Module has added leaves the project (the files stay on disk). */
class GoDetachModuleAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun directory(e: AnActionEvent): VirtualFile? {
        val project = e.project ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isDirectory } ?: return null
        val isRoot = ProjectRootManager.getInstance(project).fileIndex.getContentRootForFile(file) == file
        return file.takeIf { GoModuleRoots.isDetachable(it.path, isRoot, project.guessProjectDir()?.path) }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = directory(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = directory(e) ?: return
        val module = ModuleUtilCore.findModuleForFile(directory, project) ?: return
        if (Messages.showOkCancelDialog(project, "Remove ${directory.path} from the project? The files stay on disk.", "Detach Go Module", "Detach", Messages.getCancelButton(), null) != Messages.OK) return
        ModuleRootModificationUtil.updateModel(module) { model -> model.contentEntries.firstOrNull { it.file == directory }?.let(model::removeContentEntry) }
        GoPluginLog.info("go", "detached Go module ${directory.path} from ${module.name}")
        reread(project, "Detach Go Module ${directory.path}")
    }
}
