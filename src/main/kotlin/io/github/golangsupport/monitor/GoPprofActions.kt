package io.github.golangsupport.monitor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.golangsupport.cli.GoCli
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Looks for a pprof endpoint on the ports of a process, again and again: a server starts listening some time after the program starts,
 * and a program can be restarted. Stops looking once it has found one; [onChange] gets the endpoint or null, on a pooled thread.
 */
class GoPprofWatcher(private val pids: () -> Set<Long>, private val onChange: (GoPprofEndpoint?, attempts: Int) -> Unit) : Disposable {
    private var task: ScheduledFuture<*>? = null
    @Volatile private var found: GoPprofEndpoint? = null
    @Volatile private var attempts = 0

    fun start() {
        task = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ runCatching { tick() } }, 0, INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private fun tick() {
        val current = found
        // one that answered is asked again, cheaply: the program may have exited or stopped serving
        if (current != null) {
            if (GoPprofProbe.get(current.url, 1_000, 1024) != null) return
            found = null
            onChange(null, attempts)
        }
        val endpoint = GoPprofProbe.find(GoListeningPorts.of(pids()))
        attempts++
        found = endpoint
        onChange(endpoint, attempts)
    }

    override fun dispose() {
        task?.cancel(false)
    }

    private companion object {
        const val INTERVAL_SECONDS = 5L
    }
}

/** Takes a profile from a pprof endpoint (a CPU profile records for 30 s: under a progress that can be cancelled) and opens it. */
object GoPprofProfiles {
    fun take(project: Project, endpoint: GoPprofEndpoint, kind: GoPprofKind, programTitle: String, force: Boolean = false) {
        if (kind == GoPprofKind.GOROUTINES) return goroutines(project, endpoint, programTitle)
        if (!force && (kind == GoPprofKind.MUTEX || kind == GoPprofKind.BLOCK)) return checkNotEmpty(project, endpoint, kind, programTitle)
        object : Task.Backgroundable(project, "${kind.title} of $programTitle", true) {
            override fun run(indicator: ProgressIndicator) {
                if (kind.seconds > 0) indicator.text = "Recording for ${kind.seconds} s..."
                val file = File.createTempFile("go-${kind.name.lowercase()}-", if (kind.trace) ".trace" else ".pprof")
                val download = AppExecutorUtil.getAppExecutorService().submit { GoPprofProbe.download(endpoint.profileUrl(kind), file, (kind.seconds + 30) * 1000) }
                try {
                    while (!download.isDone) {
                        indicator.checkCanceled()
                        if (kind.seconds > 0) indicator.fraction = 0.0
                        Thread.sleep(200)
                    }
                    download.get()
                } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                    download.cancel(true)
                    file.delete()
                    throw e
                } catch (e: Exception) {
                    file.delete()
                    GoCli.notifyError(project, "${kind.title} of $programTitle", (e.cause ?: e).message ?: e.javaClass.simpleName)
                    return
                }
                GoProfileServers.getInstance(project).open(file, kind.title, kind.trace, programTitle)
            }
        }.queue()
    }

    /** A mutex or block profile is empty unless the program switches it on: saying so beats an empty page of pprof (seen live). */
    private fun checkNotEmpty(project: Project, endpoint: GoPprofEndpoint, kind: GoPprofKind, programTitle: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = GoPprofProbe.get(endpoint.url + kind.path + "?debug=1", 5_000, 1 shl 20)
            val reason = text?.let { GoPprofProbe.emptyReason(kind, it) }
            if (reason == null) return@executeOnPooledThread take(project, endpoint, kind, programTitle, force = true)
            com.intellij.notification.NotificationGroupManager.getInstance().getNotificationGroup("Go")
                .createNotification("${kind.title} profile of $programTitle is empty", reason, com.intellij.notification.NotificationType.INFORMATION)
                .addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Open anyway") { take(project, endpoint, kind, programTitle, force = true) })
                .notify(project)
        }
    }

    /** The goroutines as text (`?debug=1`): grouped by stack, the biggest groups first; the program is not stopped. */
    private fun goroutines(project: Project, endpoint: GoPprofEndpoint, programTitle: String) {
        object : Task.Backgroundable(project, "Goroutines of $programTitle", true) {
            override fun run(indicator: ProgressIndicator) {
                val text = GoPprofProbe.get(endpoint.url + "goroutine?debug=1", 10_000, 16 * 1024 * 1024)
                if (text == null) return GoCli.notifyError(project, "Goroutines of $programTitle", "${endpoint.url}goroutine?debug=1 has not answered")
                val groups = GoGoroutineDump.parse(text)
                ApplicationManager.getApplication().invokeLater({
                    GoMonitorTabs.open(project, "Goroutines", GoroutineGroupsPanel(project, groups, GoGoroutineDump.total(text), programTitle))
                }, project.disposed)
            }
        }.queue()
    }
}
