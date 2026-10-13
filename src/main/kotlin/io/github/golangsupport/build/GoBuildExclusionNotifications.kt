package io.github.golangsupport.build

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.PsiElementRenameHandler
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.settings.GoBuildTagsConfigurable
import java.util.function.Function
import javax.swing.JComponent

/**
 * The banner of a Go file the build leaves out, with GoLand's text and links (docs/goland-analysis/dumps/excluded-files.txt):
 * "'mmap_arm64.go' is ignored by the build tool because of the architecture mismatch" — Rename file (excluded by its name) or Navigate
 * to build constraints, and Edit settings (the target platform and build tags of the analysis). The file is checked for syntax only.
 */
class GoBuildExclusionNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (file.extension != "go") return null
        val psi = PsiManager.getInstance(project).findFile(file) as? GoFile ?: return null
        val exclusion = GoPackageModel.getInstance(project).buildExclusion(psi) ?: return null
        return Function { _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
                text = exclusion.message(file.name)
                if (exclusion.byName) createActionLabel("Rename file") {
                    PsiManager.getInstance(project).findFile(file)?.let { PsiElementRenameHandler.invoke(it, project, it, null) }
                } else createActionLabel("Navigate to build constraints") { OpenFileDescriptor(project, file, constraintOffset(file)).navigate(true) }
                createActionLabel("Edit settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, GoBuildTagsConfigurable::class.java)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
            }
        }
    }

    /** The `//go:build` line (or the first `// +build` one) of [file], else its start. */
    private fun constraintOffset(file: VirtualFile): Int {
        val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file)?.charsSequence ?: return 0
        val m = Regex("""(?m)^//go:build |^// \+build """).find(text) ?: return 0
        return m.range.first
    }
}
