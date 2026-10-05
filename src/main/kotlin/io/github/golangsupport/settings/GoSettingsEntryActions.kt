package io.github.golangsupport.settings

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import io.github.golangsupport.mod.GoModulesService

/**
 * "Go Settings…" and "Actions on Save…" of the settings menu of the main toolbar (the gear, `SettingsEntryPointGroup`), where GoLand
 * has them (dumps/toolbar.txt). Only in a project with a go.mod.
 */
abstract class GoSettingsEntryAction(private val configurableId: String) : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && !project.isDefault && GoModulesService.getInstance(project).modules().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // the wrappers of the EP configurables are searchable by the id of their XML line
        ShowSettingsUtil.getInstance().showSettingsDialog(project, { (it as? SearchableConfigurable)?.id == configurableId }, null)
    }

    companion object {
        const val GO_SETTINGS = "io.github.golangsupport.settings"
        const val ACTIONS_ON_SAVE = "actions.on.save"
    }
}

class GoEditSettingsAction : GoSettingsEntryAction(GO_SETTINGS)

class GoEditActionsOnSaveAction : GoSettingsEntryAction(ACTIONS_ON_SAVE)
