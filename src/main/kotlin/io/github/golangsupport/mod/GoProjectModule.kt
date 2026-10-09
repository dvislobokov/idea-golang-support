package io.github.golangsupport.mod

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.project.isDirectoryBased
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.lang.GoProjectPresence
import io.github.golangsupport.settings.GoSettings

/**
 * A project without a module gets one, with the project directory as its content root. Seen live 2026-10-08 (IDEA 2026.1.4, a directory
 * opened as a project by GIGA IDE: `.idea` with no module): the files of such a project are outside every content root, so the IDE does
 * not index them, and the daemon of 2026.1 treats a file it has not indexed as dumb per file — annotators and inspections that are not
 * DumbAware are skipped (`DumbService.isUsableInCurrentContext(annotator, file)` is false), only the parser's errors remain. The editor
 * shows no colours, no errors and no Go intentions, while the checker itself answers when asked directly. The IDE creates the module
 * only when it opens a directory with no `.idea`; a `.idea` made by another IDE is taken as a configured project and left alone.
 */
object GoProjectModule {
    /** Whether a module is to be created: the project is directory based, has Go files, and has no module at all. */
    fun needsModule(moduleCount: Int, hasGoFiles: Boolean, directoryBased: Boolean, enabled: Boolean): Boolean =
        enabled && directoryBased && hasGoFiles && moduleCount == 0

    /** The module file the IDE itself would write for the project: `.idea/<name>.iml`. */
    fun moduleFilePath(projectDirectory: String, projectName: String): String = "$projectDirectory/.idea/$projectName.iml"

    /** Creates the module with [directory] as the content root. EDT, write action. */
    fun create(project: Project, directory: VirtualFile) {
        val manager = ModuleManager.getInstance(project)
        if (manager.modules.isNotEmpty()) return
        val path = moduleFilePath(directory.path, project.name)
        val module = manager.newModule(path, ModuleTypeManager.getInstance().defaultModuleType.id)
        ModuleRootModificationUtil.updateModel(module) { it.addContentEntry(directory) }
        GoPluginLog.info("go", "${project.name} had no module: created ${module.name} ($path) with the content root ${directory.path}, " +
            "so that the IDE indexes and highlights its files")
    }
}

/** After the Go presence is known: a Go project without a module gets one ([GoProjectModule]). */
class GoProjectModuleActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val directory = project.guessProjectDir() ?: return
        val modules = ModuleManager.getInstance(project).modules.size
        val go = GoProjectPresence.hasGoFiles(project)
        val needed = GoProjectModule.needsModule(modules, go, project.isDirectoryBased, GoSettings.getInstance().createModule)
        // the journal says why a project with no module did or did not get one (seen live: the first run created nothing and nothing explained it)
        GoPluginLog.info("go", "module check of ${project.name}: modules=$modules, Go=$go, directory based=${project.isDirectoryBased}, " +
            "setting=${GoSettings.getInstance().createModule} -> ${if (needed) "creating a module" else "nothing to do"}")
        if (!needed) return
        edtWriteAction { if (!project.isDisposed) GoProjectModule.create(project, directory) }
    }
}
