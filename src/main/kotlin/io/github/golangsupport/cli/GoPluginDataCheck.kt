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
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Once a session, on the first project: can programs run from the plugin data directory ([GoPluginData])? When not (a `noexec` home,
 * an execution policy that allows only `/home/work/<user>`), the data moves by itself to the fallback directory where they can
 * ([GoPluginRelocation.suggestion]; seen live 2026-10-06: the IDE cache under `~/.cache` was noexec and the bundled delve never got built),
 * with a notification; without such a directory the notification offers the settings page. The system temporary directory is probed too:
 * [GoPluginData.goEnvironment] moves `go run` builds out of it when needed.
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
        if (suggested != null && !GoPluginData.isCustom) {
            // the default directory is unusable and a known good one exists: move there without asking (the setting records the move)
            GoPluginLog.info("tools", "Plugin data moves to the fallback directory $suggested")
            GoPluginRelocation.relocate(project, suggested.toString())
            return
        }
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
    /** The fallback directory inside the work home: `/home/work/<user>@<domain>/.cache/go-support`. */
    const val FALLBACK = ".cache/go-support"

    /**
     * [FALLBACK] under the first work home that exists ([workHomes]), belongs to this user ([ownedByMe]) and where programs can run; null
     * elsewhere. The name is guessed from the account, so a directory of that name made by someone else must not become the place the
     * plugin builds and runs delve from.
     */
    fun suggestion(): Path? = workHomes(System.getProperty("user.name"), System.getProperty("user.home")).firstOrNull { Files.isDirectory(it) && ownedByMe(it) }
        ?.resolve(FALLBACK)?.takeIf { GoExecutionProbe.check(it) == GoExecutionProbe.Result.OK && ownedByMe(it) }

    /** Owned by the user of this process and not writable by others; false when that cannot be told. */
    fun ownedByMe(directory: Path): Boolean = runCatching {
        val me = ProcessHandle.current().info().user().orElse(null) ?: return false
        if (Files.getOwner(directory).name != me) return false
        val permissions = Files.getPosixFilePermissions(directory)
        PosixFilePermission.OTHERS_WRITE !in permissions && PosixFilePermission.GROUP_WRITE !in permissions
    }.getOrDefault(false)

    /**
     * The work homes a machine may have, by the account: `/home/work/<user.name>` and, for domain accounts whose home is
     * `/home/<domain>@<user>` while the work home is `/home/work/<user>@<domain>` (seen live), the swapped name too. Pure, for tests.
     */
    fun workHomes(userName: String?, userHome: String?): List<Path> {
        val names = LinkedHashSet<String>()
        userName?.takeIf { it.isNotBlank() }?.let { names += it; names += swapped(it) }
        userHome?.let { Path.of(it).fileName?.toString() }?.takeIf { it.isNotBlank() }?.let { names += it; names += swapped(it) }
        return names.filter { !it.contains('/') }.map { Path.of("/home/work", it) }
    }

    /** `domain@user` ↔ `user@domain`; `DOMAIN\user` → `user@domain`; a name without either stays. */
    private fun swapped(name: String): String = when {
        name.count { it == '@' } == 1 -> name.substringAfter('@') + "@" + name.substringBefore('@')
        name.count { it == '\\' } == 1 -> name.substringAfter('\\') + "@" + name.substringBefore('\\')
        else -> name
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
