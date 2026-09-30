package io.github.golangsupport.sdk

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginEnabler
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.mod.GoModulesService
import javax.swing.JComponent

/**
 * Offers to switch off the bundled plugins a Go developer has no use for (Java, Maven, Python and its frameworks, Spring, the GIGA
 * "Elements"/"Endpoints" tools, GitHub, GitLab — the last two are unreachable here anyway) to keep the IDE light. Shown on startup when
 * the project is a Go one, and right away when the plugin is enabled without a restart; reachable later from Go | Optimize IDE for
 * Go.... Only plugins that are actually installed and still enabled are listed, and "Don't ask again" stops the balloon for good.
 */
object GoPluginAdvisor {
    private val LOG = logger<GoPluginAdvisor>()

    /** Curated by the ids of GIGA IDE 2026.1; absent ids are simply skipped, so this is safe on plain IntelliJ IDEA and other forks. */
    private val TARGET_IDS = listOf(
        "com.intellij.java", "com.intellij.java.ide", "com.intellij.java-i18n",  // Java
        "org.jetbrains.idea.maven",                                              // Maven
        "PythonCore", "com.gigaide.django", "com.gigaide.fastApi", "com.gigaide.flask",  // Python and its frameworks
        "com.gigaide.spring", "com.gigaide.spring.boot", "com.gigaide.spring.boot.run",
        "com.gigaide.spring.boot.wizard", "com.gigaide.spring.cloud", "com.gigaide.spring.data",  // Spring
        "com.gigaide.elements", "com.gigaide.endpoint",                          // GIGA tool integrations
        "org.jetbrains.plugins.github", "org.jetbrains.plugins.gitlab",          // VCS hosting, not reachable here
    )

    private const val DISMISSED = "io.github.golangsupport.pluginAdvisor.dismissed"

    /** Installed and still-enabled plugins from the list. */
    private fun candidates(): List<IdeaPluginDescriptor> =
        TARGET_IDS.mapNotNull { PluginManagerCore.getPlugin(PluginId.getId(it)) }.filter { !PluginManagerCore.isDisabled(it.pluginId) }

    private fun dismissed(): Boolean = PropertiesComponent.getInstance().getBoolean(DISMISSED, false)

    /**
     * The balloon, once per startup of a Go project, unless it was dismissed for good or nothing on the list is enabled. After the
     * indexing: the Go check asks the index when go.mod is not at the root of the project, and while the index is built that throws
     * (seen on a fresh machine: no balloon, nothing in the log).
     */
    fun suggest(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode || dismissed() || project.isDisposed) return
        DumbService.getInstance(project).runWhenSmart {
            // off EDT: the Go check reads the index
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                val go = ReadAction.nonBlocking<Boolean> { !project.isDisposed && worksWithGo(project) }.executeSynchronously()
                if (!go) return@executeOnPooledThread LOG.info("Optimize IDE for Go: ${project.name} has no go.mod and no Go files, nothing suggested")
                val found = candidates()
                if (found.isEmpty()) return@executeOnPooledThread LOG.info("Optimize IDE for Go: none of the plugins of the list is enabled in this IDE, nothing suggested")
                ApplicationManager.getApplication().invokeLater({ notify(project, found) }, project.disposed)
            }
        }
    }

    private fun notify(project: Project, found: List<IdeaPluginDescriptor>) {
        if (project.isDisposed || dismissed()) return
        NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
            .createNotification(
                "Speed up the IDE for Go",
                "${found.size} plugins you likely don't need are enabled (Java, Maven, Python, Spring, GitHub, GitLab and the like). Disabling them makes the IDE lighter.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple("Optimize…") { showDialog(project) })
            .addAction(NotificationAction.createSimple("Don't ask again") { PropertiesComponent.getInstance().setValue(DISMISSED, true) })
            .notify(project)
    }

    /** The checkbox list; everything is checked by default. OK disables the picked plugins and offers a restart. */
    fun showDialog(project: Project) {
        val found = candidates()
        if (found.isEmpty()) {
            Messages.showInfoMessage(project, "None of the plugins a Go developer usually does without (Java, Maven, Python, Spring, GitHub, GitLab...) is enabled: nothing to switch off.", "Optimize IDE for Go")
            return
        }
        val dialog = Dialog(project, found)
        if (!dialog.showAndGet()) return
        val chosen = dialog.selected()
        if (chosen.isEmpty()) return
        PluginEnabler.getInstance().disable(chosen)
        val restart = Messages.showYesNoDialog(
            project, "Disabled ${chosen.size} plugin(s). Restart now to apply?", "Plugins Disabled", "Restart Now", "Later", Messages.getQuestionIcon(),
        )
        if (restart == Messages.YES) ApplicationManagerEx.getApplicationEx().restart(true)
    }

    /** A go.mod anywhere, or at least one .go file: the module check works in dumb mode and short-circuits before the index. Under a read action. */
    /**
     * Both index questions are about the content roots of the project, which a freshly opened directory does not always have yet
     * (seen live: a project of a go.mod and a main.go called "has no go.mod and no Go files"): the directory itself is looked at then.
     */
    private fun worksWithGo(project: Project): Boolean =
        GoModulesService.getInstance(project).modules().isNotEmpty() ||
            FileTypeIndex.containsFileOfType(GoFileType, GlobalSearchScope.projectScope(project)) ||
            project.guessProjectDir()?.children.orEmpty().any { it.name == GoModFileType.GO_MOD || (!it.isDirectory && it.extension == GoFileType.defaultExtension) }

    private class Dialog(project: Project, private val plugins: List<IdeaPluginDescriptor>) : DialogWrapper(project) {
        private val checks = plugins.map { JBCheckBox(it.name, true) }

        init {
            title = "Optimize IDE for Go"
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row { label("These plugins are enabled, and a Go developer usually has no use for them: every one costs memory and startup time. Unchecked plugins stay on.") }
            plugins.indices.forEach { i -> row { cell(checks[i]) } }
        }

        fun selected(): List<IdeaPluginDescriptor> = plugins.filterIndexed { i, _ -> checks[i].isSelected }
    }
}

/** On startup: the balloon for a Go project. Covers a fresh install too, since installing the plugin asks for a restart first. */
class GoPluginAdvisorActivity : ProjectActivity {
    override suspend fun execute(project: Project) = GoPluginAdvisor.suggest(project)
}

/** When the plugin is enabled without a restart: suggest right away for the open Go projects. */
class GoPluginAdvisorListener : DynamicPluginListener {
    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString != "io.github.golangsupport") return
        ApplicationManager.getApplication().invokeLater {
            ProjectManager.getInstance().openProjects.forEach { GoPluginAdvisor.suggest(it) }
        }
    }
}

/** Go | Optimize IDE for Go...: opens the list at any time, also after the balloon was dismissed. */
class GoDisablePluginsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
    override fun actionPerformed(e: AnActionEvent) { GoPluginAdvisor.showDialog(e.project ?: return) }
}
