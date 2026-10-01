package io.github.golangsupport.sdk

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.Messages
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.mod.GoModulesService
import java.util.concurrent.Callable

/**
 * `.go`, `go.mod` and `go.work` are claimed with the declarative `extensions` / `fileNames` of plugin.xml, which are only *default*
 * associations: a user association in config/options/filetypes.xml always wins over them. A colleague ended up having to set the
 * association by hand (File | Settings | Editor | File Types) because one was already there — a leftover of the JetBrains Go plugin we
 * are incompatible with, or a .go file opened as plain text before the plugin was installed. There is no declarative way to override a
 * user mapping (the platform guards the user's choice on purpose), so when the project is Go but the mapping points elsewhere, we say
 * so in a modal dialog (not a corner balloon — the colleague missed that it was broken) and offer to claim the extensions back.
 */
object GoFileTypeCheck {
    private val LOG = logger<GoFileTypeCheck>()
    private const val DISMISSED = "io.github.golangsupport.fileTypeCheck.dismissed"

    /** An expected extension (`*.go`) or exact file name (`go.mod`) that should open as a Go file type, and what it opens as now. */
    private class Claim(val what: String, val extension: Boolean, val expected: FileType, val actual: () -> FileType) {
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

    fun verify(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode || dismissed() || project.isDisposed) return
        DumbService.getInstance(project).runWhenSmart {
            // off EDT: the Go check reads the filename index
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                val wrong = ReadAction.nonBlocking(Callable {
                    if (project.isDisposed || !looksLikeGo(project)) emptyList() else claims().filter { it.wrong }
                }).executeSynchronously()
                if (wrong.isEmpty()) return@executeOnPooledThread
                LOG.info("Go file type association off: ${wrong.joinToString { "${it.what} opens as ${it.actual().name}" }}")
                ApplicationManager.getApplication().invokeLater({ prompt(project, wrong) }, ModalityState.any(), project.disposed)
            }
        }
    }

    /** Told without trusting the .go association itself (that is what may be broken): a go.mod the module service parses, or a file that ends in .go. */
    private fun looksLikeGo(project: Project): Boolean =
        GoModulesService.getInstance(project).modules().isNotEmpty() ||
            FilenameIndex.getAllFilesByExt(project, GoFileType.defaultExtension, GlobalSearchScope.projectScope(project)).isNotEmpty() ||
            project.guessProjectDir()?.children.orEmpty().any { !it.isDirectory && it.extension == GoFileType.defaultExtension }

    private fun prompt(project: Project, wrong: List<Claim>) {
        if (project.isDisposed || dismissed()) return
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
        if (answer != Messages.YES) return
        ApplicationManager.getApplication().runWriteAction {
            val ftm = FileTypeManager.getInstance()
            wrong.forEach { if (it.extension) ftm.associateExtension(it.expected, it.what.removePrefix("*.")) else ftm.associatePattern(it.expected, it.what) }
        }
        LOG.info("Claimed ${wrong.joinToString { it.what }} for their Go file types")
    }

    private fun dismissed(): Boolean = PropertiesComponent.getInstance().getBoolean(DISMISSED, false)
}

/** On startup of a Go project: the modal dialog if the file type associations were hijacked. */
class GoFileTypeCheckActivity : ProjectActivity {
    override suspend fun execute(project: Project) = GoFileTypeCheck.verify(project)
}
