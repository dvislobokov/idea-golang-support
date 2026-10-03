package io.github.golangsupport.sdk

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoLanguageServerControl
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.settings.GoSettingsConfigurable
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.table.DefaultTableModel

/** What the dialog shows, gathered off EDT: every row is a name and a value. */
class GoEnvironmentReport(val rows: List<Pair<String, String>>) {
    companion object {
        /** The variables worth a look first, in this order; the rest of `go env` follows by name. */
        val IMPORTANT = listOf("GOVERSION", "GOROOT", "GOPATH", "GOBIN", "GOMODCACHE", "GOTOOLCHAIN", "GOOS", "GOARCH", "GOFLAGS", "GOPROXY", "GOPRIVATE", "GONOSUMDB", "CGO_ENABLED", "GOWORK", "GOMOD")

        fun build(executable: String?, environment: GoEnvironment, modules: List<Pair<String, String?>>, tools: List<Pair<String, String?>>): GoEnvironmentReport = GoEnvironmentReport(buildList {
            add("go" to (executable ?: "not found"))
            for ((path, version) in modules) add("module $path" to ("go " + (version ?: "?")))
            for ((tool, path) in tools) add(tool to (path ?: "not installed"))
            val values = environment.values
            IMPORTANT.filter { it in values }.forEach { add(it to values.getValue(it)) }
            values.keys.filter { it !in IMPORTANT }.sorted().forEach { add(it to values.getValue(it)) }
        })
    }
}

/** Menu Go | Go on This Machine: the toolchain the plugin uses, the tools it has found and what `go env` says. */
class GoEnvironmentAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            GoEnvironment.reset()
            val modules = ReadAction.compute<List<Pair<String, String?>>, RuntimeException> {
                if (project.isDisposed) emptyList() else GoModulesService.getInstance(project).modules().map { it.path to it.content.goVersion }
            }
            val report = GoEnvironmentReport.build(GoCli.findExecutable(), GoEnvironment.get(), modules, GoTool.entries.map { it.command to it.find()?.path })
            ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) GoEnvironmentDialog(project, report).show() }, ModalityState.any())
        }
    }
}

private class GoEnvironmentDialog(project: Project, private val report: GoEnvironmentReport) : DialogWrapper(project) {
    init {
        title = "Go on This Machine"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val model = object : DefaultTableModel(report.rows.map { arrayOf<Any>(it.first, it.second) }.toTypedArray(), arrayOf("Name", "Value")) {
            override fun isCellEditable(row: Int, column: Int): Boolean = false
        }
        val table = JBTable(model).apply {
            columnModel.getColumn(0).preferredWidth = JBUI.scale(180)
            columnModel.getColumn(1).preferredWidth = JBUI.scale(560)
        }
        return ScrollPaneFactory.createScrollPane(table).apply { preferredSize = Dimension(JBUI.scale(760), JBUI.scale(480)) }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)
}

/**
 * A project with Go code and no `go` to be found: said once when the project opens, with the way out. With `go` in place, the tools
 * the plugin drives that are missing are named in one notification with one Install All, instead of one balloon per tool as each is needed.
 */
class GoToolchainCheckActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val hasGo = ReadAction.compute<Boolean, RuntimeException> { !project.isDisposed && GoModulesService.getInstance(project).modules().isNotEmpty() }
        if (GoCli.findExecutable() != null) {
            // while at it: warm up `go env`, so that the first thing that needs GOMODCACHE on EDT has it (and the tools are looked for in GOBIN)
            GoEnvironment.get()
            if (hasGo) offerMissingTools(project)
            return
        }
        if (!hasGo) return
        NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
            .createNotification("Go is not found", "The project has a go.mod, but there is no <code>go</code> on PATH. Build, run, tests and the language server need it.", NotificationType.WARNING)
            .addAction(NotificationAction.createSimple("Download Go") { BrowserUtil.browse("https://go.dev/dl/") })
            .addAction(NotificationAction.createSimple("Configure...") { ShowSettingsUtil.getInstance().showSettingsDialog(project, GoSettingsConfigurable::class.java) })
            .notify(project)
    }

    private fun offerMissingTools(project: Project) {
        if (PropertiesComponent.getInstance().getBoolean(DISMISSED_KEY)) return
        // govulncheck is offered in the Go Dependencies window, golangci-lint only once it is turned on: see GoTool.offeredAtStart
        val missing = GoTool.offeredAtStart(GoSettings.getInstance().golangciLint).filter { it.find() == null }
        if (missing.isEmpty()) return
        val names = missing.joinToString(", ") { "<code>${it.command}</code>" }
        NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
            .createNotification("Go tools are missing", "$names: ${missing.joinToString("; ") { it.purpose.substringBefore(':').lowercase() }}. Installed with <code>go install</code> into GOBIN.", NotificationType.INFORMATION)
            // the language server among them: started for the open files once it is there, not at the next opening of a file
            .addAction(NotificationAction.createSimpleExpiring("Install All") { GoTool.installAll(project, missing) { GoLanguageServerControl.restartAll(project) } })
            .addAction(NotificationAction.createSimple("Configure...") { ShowSettingsUtil.getInstance().showSettingsDialog(project, GoSettingsConfigurable::class.java) })
            .addAction(NotificationAction.createSimpleExpiring("Don't Ask Again") { PropertiesComponent.getInstance().setValue(DISMISSED_KEY, true) })
            .notify(project)
    }

    private companion object {
        const val DISMISSED_KEY = "io.github.golangsupport.tools.dismissed"
    }
}
