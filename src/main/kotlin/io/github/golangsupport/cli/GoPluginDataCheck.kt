package io.github.golangsupport.cli

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import io.github.golangsupport.GoBundle
import io.github.golangsupport.debugger.GoBundledDelve
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.settings.GoToolsConfigurable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Once a session, on the first project: can programs run from the plugin data directory ([GoPluginData])? When not (a `noexec` home,
 * an execution policy that allows only `/home/work/<user>`), a notification says so and offers a directory where they can, or the
 * settings page. The system temporary directory is probed too: [GoPluginData.goEnvironment] moves `go run` builds out of it when needed.
 */
class GoPluginDataCheck : ProjectActivity {
    override suspend fun execute(project: Project) {
        // a project without Go files does not need the plugin's programs; the next Go project checks
        if (SystemInfo.isWindows || !io.github.golangsupport.lang.GoProjectPresence.hasGoFiles(project) || !CHECKED.compareAndSet(false, true)) return
        GoExecutionProbe.check(GoPluginData.systemTemp())
        val root = GoPluginData.root()
        if (GoExecutionProbe.check(root) != GoExecutionProbe.Result.NOT_EXECUTABLE) return
        val reason = GoExecutionProbe.reason(root).orEmpty()
        GoPluginLog.warn("notify", "Programs cannot run in the plugin data directory $root: $reason")
        val suggested = GoPluginRelocation.suggestion()
        val notification = NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
            .createNotification(GoBundle.message("pluginData.notExecutable.title"), GoBundle.message("pluginData.notExecutable.text", root.toString(), reason), NotificationType.WARNING)
        if (suggested != null) notification.addAction(NotificationAction.createSimpleExpiring(GoBundle.message("pluginData.use", suggested.toString())) { GoPluginRelocation.relocate(project, suggested.toString()) })
        notification.addAction(NotificationAction.createSimple(GoBundle.message("pluginData.choose")) { ShowSettingsUtil.getInstance().showSettingsDialog(project, GoToolsConfigurable::class.java) })
        notification.notify(project)
    }

    private companion object {
        val CHECKED = AtomicBoolean()
    }
}

object GoPluginRelocation {
    /** `/home/work/<user>/.go-plugin` where that home exists and programs can run in it; null elsewhere. */
    fun suggestion(): Path? {
        val user = System.getProperty("user.name") ?: return null
        val home = Path.of("/home/work", user)
        if (!Files.isDirectory(home)) return null
        val candidate = home.resolve(".go-plugin")
        return candidate.takeIf { GoExecutionProbe.check(it) == GoExecutionProbe.Result.OK }
    }

    /**
     * Makes [directory] the plugin data directory (empty: back to the default) in the background: probes it, moves what the old one
     * holds, then builds delve again where it can run. A directory where programs cannot run is refused with a notification.
     */
    fun relocate(project: Project?, directory: String) {
        object : Task.Backgroundable(project, GoBundle.message("pluginData.moving"), false) {
            override fun run(indicator: ProgressIndicator) {
                val from = GoPluginData.root()
                val target = directory.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it) } ?: GoPluginData.defaultRoot()
                if (GoExecutionProbe.check(target) == GoExecutionProbe.Result.NOT_EXECUTABLE) {
                    GoCli.notifyError(project, GoBundle.message("pluginData.notExecutable.title"), GoBundle.message("pluginData.refused", target.toString(), GoExecutionProbe.reason(target).orEmpty()))
                    return
                }
                GoSettings.getInstance().pluginDataDirectory = directory.trim()
                val failed = GoPluginData.move(from, target)
                GoPluginLog.info("tools", "Plugin data moved from $from to $target" + if (failed.isEmpty()) "" else "; not moved: ${failed.joinToString()}")
                GoBundledDelve.forgetFailure()
                project?.let { GoBundledDelve.ensureBuilt(it) }
                GoCli.notifyInfo(project, GoBundle.message("pluginData.moved", target.toString()))
            }
        }.queue()
    }
}
