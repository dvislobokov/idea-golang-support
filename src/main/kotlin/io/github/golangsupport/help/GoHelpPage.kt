package io.github.golangsupport.help

import io.github.golangsupport.lang.GoProjectPresence
import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import io.github.golangsupport.cli.GoCli
import java.nio.file.Files

/**
 * The pages of the plugin, shown in an editor tab: the one about it (docs/demo.html, packed as welcome/index.html by the build) and the
 * reference of its keys, actions and settings (docs/guide.html, packed as welcome/guide.html). One source each: the same files are
 * opened from the repository in a browser.
 *
 * The page about the plugin is shown once after the plugin is installed and again after an update, the way the IDE shows its own
 * What's New; both are in the Go menu.
 */
object GoPages {
    private val LOG = logger<GoPages>()

    class Page(val title: String, val resource: String)

    val WELCOME = Page("Go Project Support", "/welcome/index.html")
    val GUIDE = Page("Go Help", "/welcome/guide.html")

    const val SHOWN_VERSION_KEY = "golang.welcome.shown.version"
    private const val PLUGIN_ID = "io.github.golangsupport"
    private const val OPENING = "<html lang=\"ru\">"

    /** `<kbd data-action="GotoDeclaration">Ctrl+B</kbd>`: a key the page names by the action it is of. */
    private val KEY = Regex("""(<kbd data-action="([\w.$]+)">)([^<]*)(</kbd>)""")

    fun pluginVersion(): String? = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version

    /** Once per version: a new installation has no record, an update has the record of the version before. */
    fun isNewFor(shownVersion: String?, currentVersion: String?): Boolean = currentVersion != null && shownVersion != currentVersion

    /**
     * The page as the IDE shows it: in the theme of the IDE, without the links that lead into the repository, and with the keys of the
     * keymap of this IDE instead of the ones the page was written with ([shortcut] gives the text of a key by the id of its action,
     * null or nothing where the keymap has none: the key of the page stays then). With [anchor] the page scrolls to the element of that id
     * once loaded: the editor tab is given the HTML itself, not a URL, so there is no `#fragment` to open it at.
     */
    fun forIde(page: String, dark: Boolean, anchor: String? = null, shortcut: (String) -> String? = { null }): String =
        page.replaceFirst(OPENING, "<html lang=\"ru\" data-host=\"ide\" data-theme=\"${if (dark) "dark" else "light"}\">")
            .replace(KEY) { match -> match.groupValues[1] + (shortcut(match.groupValues[2])?.takeIf { it.isNotEmpty() }?.let(::escape) ?: match.groupValues[3]) + match.groupValues[4] }
            .let { html -> if (anchor == null) html else html.replaceFirst("</body>", scrollTo(anchor) + "</body>") }

    private fun scrollTo(anchor: String): String =
        "<script>addEventListener('load', function () { var e = document.getElementById('$anchor'); if (e) e.scrollIntoView(); });</script>"

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun html(page: Page, dark: Boolean, anchor: String? = null): String? {
        val text = GoPages::class.java.getResourceAsStream(page.resource)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        return forIde(text, dark, anchor) { id -> ActionManager.getInstance().getAction(id)?.let { KeymapUtil.getFirstKeyboardShortcutText(it) } }
    }

    fun open(project: Project, page: Page, anchor: String? = null) {
        val html = html(page, !JBColor.isBright(), anchor)
        if (html == null) {
            LOG.warn("No ${page.resource} in the plugin")
            return
        }
        if (JBCefApp.isSupported()) {
            HTMLEditorProvider.openEditor(project, page.title, html)
        } else {
            // no embedded browser in this IDE (a remote session, a runtime without JCEF): the system one
            val file = Files.createTempFile("go-project-support", ".html")
            Files.writeString(file, html)
            file.toFile().deleteOnExit()
            BrowserUtil.browse(file)
        }
    }
}

class GoWelcomePageActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (application.isUnitTestMode || application.isHeadlessEnvironment) return
        // shown in a Go project only: a Java project opened in IDEA gets no page about Go
        if (!GoProjectPresence.hasGoFiles(project)) return
        val properties = PropertiesComponent.getInstance()
        val version = GoPages.pluginVersion()
        if (!GoPages.isNewFor(properties.getValue(GoPages.SHOWN_VERSION_KEY), version)) return
        // recorded before it is shown: two projects opening together show it once
        properties.setValue(GoPages.SHOWN_VERSION_KEY, version)
        application.invokeLater({
            if (project.isDisposed) return@invokeLater
            runCatching { GoPages.open(project, GoPages.WELCOME) }.onFailure { failure ->
                logger<GoWelcomePageActivity>().warn("The welcome page could not be opened", failure)
                NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
                    .createNotification("Go Project Support is installed", "See what it can do: Go | Welcome to Go Project Support.", NotificationType.INFORMATION)
                    .addAction(NotificationAction.createSimpleExpiring("Open") { GoPages.open(project, GoPages.WELCOME) })
                    .notify(project)
            }
        }, ModalityState.nonModal())
    }
}

/** Go | Welcome to Go Project Support: what the plugin is for, with its features shown at work. */
class ShowGoWelcomePageAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        GoPages.open(e.project ?: return, GoPages.WELCOME)
    }
}

/**
 * Go | Help Page: the keys, the actions, the settings and the features of the plugin, one page to look things up in. The keys are
 * the ones of the keymap of the IDE, so the page says what the keyboard does here and not what it does elsewhere.
 */
class ShowGoHelpPageAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        GoPages.open(e.project ?: return, GoPages.GUIDE)
    }
}

/** The guide at its Go fix section: the What's New lens of a file with Go fix findings runs it (go-psi-ide-gofix.xml); in no menu. */
class ShowGoFixHelpAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        GoPages.open(e.project ?: return, GoPages.GUIDE, GO_FIX_ANCHOR)
    }

    companion object {
        const val GO_FIX_ANCHOR = "go-fix"
    }
}
