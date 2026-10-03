package io.github.golangsupport.build

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import io.github.golangsupport.catalogue.GoCatalogueService
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.settings.GoLanguageServerControl
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.GoProjectModelTracker
import io.github.golangsupport.sdk.GoIgsToolchainProvider
import io.github.golangsupport.semantic.cache.GoTrackers

/**
 * Go | Reanalyze Project: everything the plugin knows about the project is thrown away and read again, without a restart of the IDE.
 * The indexes of the Go files and go.mod files (declarations, exports, structure), the catalogue of packages, the language server,
 * the problems of the last build, the highlighting of the open files. The indexes of everything else stay as they are: for those there
 * is File | Invalidate Caches.
 */
class GoReanalyzeAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        reanalyze(e.project ?: return)
    }

    companion object {
        fun reanalyze(project: Project) {
            object : Task.Backgroundable(project, "Reanalyzing Go project", true) {
                var files = 0

                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "Finding Go files..."
                    val scope = GlobalSearchScope.projectScope(project)
                    val goFiles: List<VirtualFile> = DumbService.getInstance(project).runReadActionInSmartMode<List<VirtualFile>> {
                        FileTypeIndex.getFiles(GoFileType, scope).toList() + FileTypeIndex.getFiles(GoModFileType, scope)
                    }
                    files = goFiles.size
                    indicator.text = "Reindexing $files files..."
                    // the platform indexes the files again on the next request to an index; the ones with a stamp still have it
                    for (file in goFiles) {
                        indicator.checkCanceled()
                        FileBasedIndex.getInstance().requestReindex(file)
                    }
                    GoBuildProblems.getInstance(project).clear(null)
                    // the caches of the native PSI: types, resolve, the project model
                    GoTrackers.getInstance(project).invalidateAll()
                    (GoToolchainProvider.getInstance() as? GoIgsToolchainProvider)?.invalidate()
                    GoProjectModelTracker.getInstance(project).bump("Go | Reanalyze")
                    if (GoSettings.getInstance().completionCatalogue) GoCatalogueService.getInstance(project).refresh(rescan = true)
                }

                override fun onSuccess() {
                    if (project.isDisposed) return
                    GoLanguageServerControl.restartAll(project)
                    DaemonCodeAnalyzer.getInstance(project).restart()
                    val catalogue = if (GoSettings.getInstance().completionCatalogue) ", the packages of the catalogue are read again" else ""
                    GoCli.notifyInfo(project, "Go project is being reanalyzed", "$files Go and go.mod files are indexed again, the language server is restarted$catalogue.")
                }
            }.queue()
        }
    }
}
