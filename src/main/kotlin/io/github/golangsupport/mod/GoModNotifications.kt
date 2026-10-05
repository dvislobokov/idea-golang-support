package io.github.golangsupport.mod

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import io.github.golangsupport.cli.GoCli
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import javax.swing.JComponent

/** What of go.mod matters to `go mod tidy`: the requirements and the replacements, as a set that is the same when only comments moved. */
object GoModDependencies {
    fun of(text: CharSequence): Set<String> {
        val mod = GoModFile.parse(text)
        return (mod.requires.map { "require ${it.path} ${it.version}" } + mod.replaces.map { "replace ${it.oldPath} ${it.oldVersion.orEmpty()} => ${it.newPath} ${it.newVersion.orEmpty()}" } +
            mod.excludes.map { "exclude ${it.first} ${it.second}" }).toSet()
    }

    /** True when saving [new] over [old] changes what the module depends on. */
    fun changed(old: CharSequence, new: CharSequence): Boolean = of(old) != of(new)
}

/** The go.mod files whose dependencies were edited by hand and not followed by `go mod tidy` yet: the banner of the editor comes from here. */
@Service(Service.Level.PROJECT)
class GoModChanges(private val project: Project) {
    private val pending = ConcurrentHashMap.newKeySet<String>()

    fun isPending(file: VirtualFile): Boolean = file.path in pending

    fun markPending(file: VirtualFile) {
        pending += file.path
        EditorNotifications.getInstance(project).updateNotifications(file)
    }

    fun clear(file: VirtualFile) {
        pending -= file.path
        EditorNotifications.getInstance(project).updateNotifications(file)
    }

    /** `go mod tidy` or `go mod download` of the module of [modFile], in the Build tool window; the banner goes once the command has succeeded. */
    fun run(modFile: VirtualFile, vararg arguments: String) {
        val root = modFile.parent ?: return
        val title = "Go Mod " + arguments.last().replaceFirstChar(Char::uppercase)
        val commands = GoCli.commandLinesOrNotify(project, title) { listOf(GoCli.commandLine(root.path, *arguments)) } ?: return
        GoCli.runInBackground(project, title, commands, refresh = listOf(File(root.path)), onSuccess = { clear(modFile) })
    }

    /** `go mod download` on its own after the save (Download Go module dependencies, Settings | Go | Go Modules); the banner stays: tidy is still its business. */
    fun download(modFile: VirtualFile) {
        val root = modFile.parent ?: return
        val commands = GoCli.commandLinesOrNotify(project, DOWNLOAD_TITLE) { listOf(GoCli.commandLine(root.path, "mod", "download")) } ?: return
        GoCli.runInBackground(project, DOWNLOAD_TITLE, commands, refresh = listOf(File(root.path)))
    }

    companion object {
        private const val DOWNLOAD_TITLE = "Download Go Module Dependencies"

        fun getInstance(project: Project): GoModChanges = project.service()
    }
}

/** Saving go.mod with other requirements than the disk has: the projects the file is in are told, before the disk changes. */
class GoModSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document)?.takeIf { it.name == GoModFileType.GO_MOD && it.isInLocalFileSystem } ?: return
        val onDisk = runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return
        if (!GoModDependencies.changed(onDisk, document.immutableCharSequence)) return
        val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: return
        if (project.isDisposed) return
        GoModChanges.getInstance(project).markPending(file)
        // after the save: the command reads go.mod from the disk; no real go in the tests
        if (GoModDownloads.isEnabled(project) && !ApplicationManager.getApplication().isUnitTestMode) {
            ApplicationManager.getApplication().invokeLater({ GoModChanges.getInstance(project).download(file) }, project.disposed)
        }
    }
}

/** The banner above a go.mod whose requirements were edited: `go mod tidy` is what brings go.sum and the module cache in line. */
class GoModNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file.name != GoModFileType.GO_MOD) return null
        val changes = GoModChanges.getInstance(project)
        if (!changes.isPending(file)) return null
        return Function { _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
                text = "The requirements of go.mod have changed: go mod tidy brings go.sum and the downloaded modules in line"
                createActionLabel("Run go mod tidy") { changes.run(file, "mod", "tidy") }
                createActionLabel("Download") { changes.run(file, "mod", "download") }
                createActionLabel("Dismiss") { changes.clear(file) }
            }
        }
    }
}
