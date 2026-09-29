package io.github.golangsupport.run

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Menu Go | Analyze Stack Trace...: the "Analyze Stack Trace or Thread Dump" dialog of the platform (action `Unscramble`, its class and dialog
 * are not public), whose console applies the console filters of the project, among them [GoConsoleFilter]: a panic pasted from a log gets its
 * `file.go:12` frames clickable. All that is Go-specific is having the action where a Go developer looks for it; the platform keeps it under Code.
 */
class GoAnalyzeStackTraceAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && ActionManager.getInstance().getAction(PLATFORM_ACTION) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val action = ActionManager.getInstance().getAction(PLATFORM_ACTION) ?: return
        action.actionPerformed(e)
    }

    private companion object {
        const val PLATFORM_ACTION = "Unscramble"
    }
}
