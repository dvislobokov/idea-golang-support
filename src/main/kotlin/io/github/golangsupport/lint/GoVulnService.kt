package io.github.golangsupport.lint

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginData
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.cli.GoTool
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.mod.GoModule
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoSettings
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The results of govulncheck per module, behind `GoVulnerablePackageImport` and `GoVulnerableCodeUsages`. One run covers a module (`./...`)
 * and takes from seconds to minutes, so it is not tied to the highlighting of a file as golangci-lint is: it runs in the background at
 * project open and a moment after go.mod / go.sum change, while [GoSettings.vulnerabilityCheck] is on, and its output is kept in the
 * plugin data directory with the key of go.mod + go.sum ([GoVulnCache]); a result older than an hour is refreshed when asked for again.
 * Go | Check Vulnerabilities runs it at once, also with the setting off. Never on EDT.
 */
@Service(Service.Level.PROJECT)
class GoVulnService(private val project: Project) : Disposable {
    private class Entry(val key: String, val at: Long, val report: GoVulnReport?)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val diskChecked = ConcurrentHashMap.newKeySet<String>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val scheduled = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val keys = ConcurrentHashMap<String, Pair<Long, String>>()
    // the running govulncheck processes: a run takes up to TIMEOUT_MS and must not outlive the project
    private val processes = ConcurrentHashMap.newKeySet<ProcessHandler>()
    @Volatile private var disposed = false

    /** The report for the current go.mod + go.sum of [module], or null; asks for a run in the background when the setting is on and there is none or it is old. */
    fun report(module: GoModule): GoVulnReport? {
        val root = module.root.path
        val key = keyOf(module.root) ?: return null
        val entry = entries[root] ?: loadFromDisk(root)
        if (GoSettings.getInstance().vulnerabilityCheck && root !in running && !scheduled.containsKey(root) && (entry == null || !GoVulnCache.fresh(entry.key, entry.at, key, System.currentTimeMillis(), MAX_AGE_MS))) schedule(module.root, 0)
        return entry?.takeIf { it.key == key }?.report
    }

    /** A run for the module at [root] after [delayMs], unless one comes before it; the setting is checked when it starts. */
    fun schedule(root: VirtualFile, delayMs: Long) {
        val path = root.path
        val previous = scheduled.put(path, AppExecutorUtil.getAppScheduledExecutorService().schedule({ scheduled.remove(path); runIfStale(root) }, delayMs, TimeUnit.MILLISECONDS))
        previous?.cancel(false)
    }

    /** Every module of the project, whose result is missing or stale: at project open. */
    fun checkAll() {
        if (!GoSettings.getInstance().vulnerabilityCheck || project.isDisposed) return
        val modules = DumbService.getInstance(project).runReadActionInSmartMode<List<GoModule>> { GoModulesService.getInstance(project).modules() }
        for (module in modules) schedule(module.root, 0)
    }

    private fun runIfStale(root: VirtualFile) {
        if (project.isDisposed || !GoSettings.getInstance().vulnerabilityCheck || !root.isValid) return
        val key = keyOf(root) ?: return
        val entry = entries[root.path] ?: loadFromDisk(root.path)
        val now = System.currentTimeMillis()
        // a failed run is not repeated for the same files within the hour either; a run just made is not repeated within a minute
        if (entry != null && (GoVulnCache.fresh(entry.key, entry.at, key, now, MAX_AGE_MS) || now - entry.at < MIN_GAP_MS)) return
        run(root.path, key)
    }

    /** Runs govulncheck over the module at [root] on the calling (background) thread and keeps the result; null when it could not run. */
    fun run(root: String, key: String? = null): GoVulnReport? {
        if (!running.add(root)) return null
        try {
            val tool = GoTool.GOVULNCHECK.find()
            if (tool == null) {
                GoPluginLog.info(CATEGORY, "govulncheck is not installed: no vulnerability check for $root")
                store(root, key ?: keyOf(root), null, "")
                return null
            }
            val started = System.currentTimeMillis()
            GoPluginLog.info(CATEGORY, "govulncheck ./... in $root")
            if (disposed) return null
            var handler: ProcessHandler? = null
            val output = try {
                runCatching {
                    GoCli.execute(GoCli.toolCommandLine(tool.path, root, *GoVulnOutput.arguments(GoSettings.getInstance().tagList()).toTypedArray()), TIMEOUT_MS) { h ->
                        handler = h
                        track(h)
                    }
                }.onFailure { GoPluginLog.warn(CATEGORY, "govulncheck has failed to start: ${GoPluginLog.describe(it)}") }.getOrNull()
            } finally {
                handler?.let { processes.remove(it) }
            }
            if (disposed || project.isDisposed) return null
            if (output == null) {
                store(root, key ?: keyOf(root), null, "")
                return null
            }
            val report = GoVulnOutput.parse(output.stdout)
            if (report == null) GoPluginLog.warn(CATEGORY, "govulncheck has failed in $root: exit code ${output.exitCode}, " + output.stderr.lines().lastOrNull { it.isNotBlank() }.orEmpty())
            else GoPluginLog.info(CATEGORY, "govulncheck in $root: ${report.summary().text()} (${System.currentTimeMillis() - started} ms)")
            store(root, key ?: keyOf(root), report, output.stdout)
            return report
        } finally {
            running.remove(root)
        }
    }

    /** Keeps the result of a run (also of Go | Check Vulnerabilities) for [root], on disk too, and highlights the files again. */
    fun store(root: String, key: String?, report: GoVulnReport?, stdout: String) {
        // a run that ends after the project closed keeps nothing
        if (disposed || project.isDisposed) return
        val at = System.currentTimeMillis()
        val k = key ?: return
        val previous = entries.put(root, Entry(k, at, report))
        if (report != null) runCatching {
            val file = GoPluginData.root().resolve(DIRECTORY).resolve(GoVulnCache.fileName(root))
            Files.createDirectories(file.parent)
            Files.writeString(file, GoVulnCache.encode(GoVulnCache.Stored(k, at, stdout)))
        }.onFailure { GoPluginLog.info(CATEGORY, "govulncheck result is not kept on disk: ${GoPluginLog.describe(it)}") }
        if ((report != null || previous?.report != null) && !project.isDisposed) ApplicationManager.getApplication().invokeLater({ DaemonCodeAnalyzer.getInstance(project).restart("govulncheck") }, project.disposed)
    }

    /** The result of an earlier session, looked for once per module. */
    private fun loadFromDisk(root: String): Entry? {
        if (!diskChecked.add(root)) return entries[root]
        val stored = runCatching {
            GoPluginData.root().resolve(DIRECTORY).resolve(GoVulnCache.fileName(root)).takeIf { Files.isRegularFile(it) }?.let { GoVulnCache.decode(Files.readString(it)) }
        }.getOrNull() ?: return null
        val report = GoVulnOutput.parse(stored.stdout) ?: return null
        return Entry(stored.key, stored.at, report).also { entries.putIfAbsent(root, it) }
    }

    fun keyOf(root: String): String? = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(root)?.let(::keyOf)

    /** The key of go.mod + go.sum of the module, recomputed only when one of them changes. */
    fun keyOf(root: VirtualFile): String? {
        val mod = root.findChild(GoModFileType.GO_MOD) ?: return null
        val sum = root.findChild(GO_SUM)
        val stamp = mod.modificationStamp * 31 + (sum?.modificationStamp ?: -1)
        keys[root.path]?.takeIf { it.first == stamp }?.let { return it.second }
        val key = runCatching { GoVulnCache.key(mod.contentsToByteArray(), sum?.contentsToByteArray()) }.getOrNull() ?: return null
        keys[root.path] = stamp to key
        return key
    }

    @TestOnly
    fun putForTests(root: VirtualFile, report: GoVulnReport?) {
        diskChecked += root.path
        if (report == null) entries.remove(root.path) else entries[root.path] = Entry(keyOf(root)!!, System.currentTimeMillis(), report)
    }

    /** Remembers [handler] so that [dispose] stops it; a handler coming after dispose is stopped at once. */
    internal fun track(handler: ProcessHandler) {
        processes += handler
        if (disposed) handler.destroyProcess()
    }

    /** Whether a result is kept for [root], for tests. */
    @TestOnly
    fun hasResult(root: String): Boolean = entries.containsKey(root)

    override fun dispose() {
        disposed = true
        scheduled.values.forEach { it.cancel(false) }
        scheduled.clear()
        processes.forEach { it.destroyProcess() }
        processes.clear()
    }

    /** go.mod / go.sum changed on disk: a run for that module a moment later (`go get` writes both, an editor saves often). */
    class FileListener(private val project: Project) : BulkFileListener {
        override fun after(events: List<VFileEvent>) {
            if (!GoSettings.getInstance().vulnerabilityCheck || project.isDisposed) return
            val roots = events.mapNotNull { e -> e.file?.takeIf { it.name == GoModFileType.GO_MOD || it.name == GO_SUM }?.parent }.distinct()
            if (roots.isEmpty()) return
            val service = getInstance(project)
            for (root in roots) if (!root.path.contains("/vendor/") && root.findChild(GoModFileType.GO_MOD) != null) service.schedule(root, DEBOUNCE_MS)
        }
    }

    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) {
            if (ApplicationManager.getApplication().isUnitTestMode) return
            project.messageBus.connect(getInstance(project)).subscribe(VirtualFileManager.VFS_CHANGES, FileListener(project))
            if (GoSettings.getInstance().vulnerabilityCheck) ApplicationManager.getApplication().executeOnPooledThread { getInstance(project).checkAll() }
        }
    }

    companion object {
        const val CATEGORY = "vulncheck"
        private const val GO_SUM = "go.sum"
        private const val DIRECTORY = "vulncheck"
        private const val MAX_AGE_MS = 60 * 60 * 1000L
        private const val MIN_GAP_MS = 60 * 1000L
        private const val DEBOUNCE_MS = 10_000L
        private const val TIMEOUT_MS = 10 * 60 * 1000

        fun getInstance(project: Project): GoVulnService = project.service()
    }
}
