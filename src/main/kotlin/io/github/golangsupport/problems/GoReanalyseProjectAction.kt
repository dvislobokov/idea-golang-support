package io.github.golangsupport.problems

import com.intellij.analysis.problemsView.toolWindow.ProblemsViewToolWindowUtils
import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiDocumentManager
import io.github.golangsupport.ci.GoInspectActionBase
import io.github.golangsupport.settings.GoSettings

/**
 * Go | Reanalyse Project Problems: a full pass of [GoProjectProblems] now, and the Project Errors tab in front. Disabled while the background
 * analysis is off in the settings or Power Save Mode is on (nothing would keep the results up to date), and in a project without Go.
 */
class GoReanalyseProjectAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && GoSettings.getInstance().projectAnalysis && !PowerSaveMode.isEnabled() && GoInspectActionBase.hasGo(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        GoProjectProblems.getInstance(project).requestFull()
        ProblemsViewToolWindowUtils.selectProjectErrorsTab(project)
    }
}
