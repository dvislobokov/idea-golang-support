package io.github.golangsupport.sdk

import io.github.golangsupport.lang.GoProjectPresence
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.mod.GoModulesService
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import javax.swing.JComponent

/**
 * `.go`, `go.mod` and `go.work` are claimed with the declarative `extensions` / `fileNames` of plugin.xml, which are only *default*
 * associations: a user association in config/options/filetypes.xml always wins over them. A colleague ended up having to set the
 * association by hand (File | Settings | Editor | File Types) because one was already there — a leftover of the JetBrains Go plugin we
 * are incompatible with, or a .go file opened as plain text before the plugin was installed. There is no declarative way to override a
 * user mapping (the platform guards the user's choice on purpose), so when the project is Go but the mapping points elsewhere, we say
 * so in a modal dialog (not a corner balloon — the colleague missed that it was broken) and offer to claim the extensions back.
 *
 * The dialog comes at startup of a Go project, when the project turns out to be Go later (the index was not there at startup) and when
 * a Go file is opened with another file type (seen live 2026-10-07: a colleague got no dialog at all); [GoFileTypeNotificationProvider]
 * puts a banner with the same fix above every such file, which stays even after "Don't ask again".
 */
object GoFileTypeCheck {
    private val LOG = logger<GoFileTypeCheck>()
    private const val DISMISSED = "io.github.golangsupport.fileTypeCheck.dismissed"

    /** An expected extension (`*.go`) or exact file name (`go.mod`) that should open as a Go file type, and what it opens as now. */
    class Claim(val what: String, val extension: Boolean, val expected: FileType, val actual: () -> FileType) {
        val wrong: Boolean get() = actual() != expected
    }

    private fun claims(): List<Claim> {
        val ftm = FileTypeManager.getInstance()
        return listOf(
            Claim("*.go", extension = true, GoFileType) { ftm.getFileTypeByExtension(GoFileType.defaultExtension) },
            Claim(GoModFileType.GO_MOD, extension = false, GoModFileType) { ftm.getFileTypeByFileName(GoModFileType.GO_MOD) },
            Claim(GoModFileType.GO_WORK, extension = false, GoModFileType) { ftm.getFileTypeByFileName(GoModFileType.GO_WORK) },
        )
    }

    /** The associations that point away from Go right now. Cheap: the file type manager alone, no index. */
    fun wrongClaims(): List<Claim> = claims().filter { it.wrong }

    /** Whether [file] is one of ours by name but opens as something else. */
    fun isMistyped(file: VirtualFile): Boolean = !file.isDirectory && when {
        file.extension == GoFileType.defaultExtension -> file.fileType != GoFileType
        file.name == GoModFileType.GO_MOD || file.name == GoModFileType.GO_WORK -> file.fileType != GoModFileType
        else -> false
    }

    /** The startup path: once the project is smart and known to be Go, the dialog if anything is off. */
    fun verify(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode || dismissed() || project.isDisposed) return
        DumbService.getInstance(project).runWhenSmart {
            // off EDT: the Go check reads the filename index
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                val wrong = ReadAction.nonBlocking(Callable {
                    if (project.isDisposed || !looksLikeGo(project)) emptyList() else wrongClaims()
                }).executeSynchronously()
                if (wrong.isEmpty()) return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater({ prompt(project, wrong) }, ModalityState.any(), project.disposed)
            }
        }
    }

    /** A Go file opened with another type: the dialog right away, no index needed — the file in the editor is the proof. */
    fun verifyOpened(project: Project, file: VirtualFile) {
        if (ApplicationManager.getApplication().isUnitTestMode || dismissed() || project.isDisposed || !isMistyped(file)) return
        val wrong = wrongClaims()
        if (wrong.isEmpty()) return
        ApplicationManager.getApplication().invokeLater({ prompt(project, wrong) }, ModalityState.any(), project.disposed)
    }

    /** Told without trusting the .go association itself (that is what may be broken): a go.mod the module service parses, or a file that ends in .go. */
    private fun looksLikeGo(project: Project): Boolean =
        GoModulesService.getInstance(project).modules().isNotEmpty() ||
            FilenameIndex.getAllFilesByExt(project, GoFileType.defaultExtension, GlobalSearchScope.projectScope(project)).isNotEmpty() ||
            project.guessProjectDir()?.children.orEmpty().any { !it.isDirectory && it.extension == GoFileType.defaultExtension }

    /** One dialog at a time (the startup path and an opened file can both ask); the answer "Not Now" holds for the session. */
    private val asking = AtomicBoolean()
    @Volatile private var declined = false

    private fun prompt(project: Project, wrong: List<Claim>) {
        if (project.isDisposed || dismissed() || declined || wrongClaims().isEmpty() || !asking.compareAndSet(false, true)) return
        try {
            GoPluginLog.info("go", "Go file type association off: ${wrong.joinToString { "${it.what} opens as ${it.actual().name}" }}; asking")
            val mapped = wrong.joinToString("\n") { "    • ${it.what} opens as \"${it.actual().name}\"" }
            val message = "Go files are not recognized as Go in this IDE:\n\n$mapped\n\n" +
                "This is usually a leftover of another Go plugin, or a file opened before this plugin was installed. " +
                "Until it is fixed there is no Go highlighting, navigation or completion.\n\nAssociate them with Go now?"
            val answer = Messages.showYesNoDialog(
                project, message, "Go File Types Not Associated",
                "Associate with Go", "Not Now", Messages.getWarningIcon(),
                object : DoNotAskOption.Adapter() {
                    override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                        if (isSelected) PropertiesComponent.getInstance().setValue(DISMISSED, true)
                    }
                },
            )
            if (answer == Messages.YES) fix(project) else declined = true
        } finally {
            asking.set(false)
        }
    }

    /** Claims every wrong association back for its Go file type; the open editors re-read their file types with the banners. */
    fun fix(project: Project?) {
        val wrong = wrongClaims()
        if (wrong.isEmpty()) return
        ApplicationManager.getApplication().runWriteAction {
            val ftm = FileTypeManager.getInstance()
            wrong.forEach { if (it.extension) ftm.associateExtension(it.expected, it.what.removePrefix("*.")) else ftm.associatePattern(it.expected, it.what) }
        }
        GoPluginLog.info("go", "Claimed ${wrong.joinToString { it.what }} for their Go file types")
        LOG.info("Claimed ${wrong.joinToString { it.what }} for their Go file types")
        if (project != null && !project.isDisposed) EditorNotifications.getInstance(project).updateAllNotifications()
    }

    private fun dismissed(): Boolean = PropertiesComponent.getInstance().getBoolean(DISMISSED, false)
}

/** On startup of a Go project, or when the project turns out to be Go later: the modal dialog if the file type associations were hijacked. */
class GoFileTypeCheckActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (GoProjectPresence.getInstance(project).hasGoFiles) GoFileTypeCheck.verify(project)
    }

    /** The presence is computed after startup (the index, the content roots): a project found to be Go then gets the check too. */
    class OnPresence(private val project: Project) : GoProjectPresence.Listener {
        override fun presenceChanged(hasGoFiles: Boolean) {
            if (hasGoFiles) GoFileTypeCheck.verify(project)
        }
    }

    /** A .go / go.mod / go.work opened as another file type: the dialog at once, whatever the index says about the project. */
    class OnOpen(private val project: Project) : FileEditorManagerListener {
        override fun fileOpened(source: FileEditorManager, file: VirtualFile) = GoFileTypeCheck.verifyOpened(project, file)
    }
}

/** The banner above a Go file that opens as another file type: the fix in place, and the File Types page for a look. */
class GoFileTypeNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!GoFileTypeCheck.isMistyped(file)) return null
        val actual = file.fileType.name
        return Function { _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
                text = "This file opens as \"$actual\", not as Go: no Go highlighting, navigation or completion here"
                createActionLabel("Associate with Go") { GoFileTypeCheck.fix(project) }
                createActionLabel("File Types Settings…") { ShowSettingsUtil.getInstance().showSettingsDialog(project, "preferences.fileTypes") }
            }
        }
    }
}
