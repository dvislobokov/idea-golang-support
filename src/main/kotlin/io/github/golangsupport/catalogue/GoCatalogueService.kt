package io.github.golangsupport.catalogue

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.mod.GoModule
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.settings.GoSettings
import io.github.golangsupport.view.GoDependencyNode
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The catalogue of what the project may import: the standard library and the modules its go.mod files require directly. A module of
 * a version never changes in the module cache, and the standard library changes with the version of Go, so each is read once, kept
 * as a file for every project of the machine ([GoCatalogueFiles]) and in memory for every project of the IDE.
 *
 * Not the indexes of the platform: library roots would have every index built for every file of every dependency, where one list of
 * exported names per package is all that is asked for.
 *
 * The packages of the project itself do change, and for them the index of the platform is the cache ([GoExportsIndex]): it is the
 * platform that knows which file has changed. They are put together again when the index says it is not what it was.
 */
@Service(Service.Level.PROJECT)
class GoCatalogueService(private val project: Project) : Disposable {
    /** A directory of sources and the name its contents are kept by. */
    private class Source(val key: String, val directory: File, val modulePath: String, val standard: Boolean)

    /** Everything there is, as it was put together last. */
    @Volatile var index: GoSymbolIndex = GoSymbolIndex.EMPTY
        private set

    @Volatile private var dependencies: List<GoModuleSymbols> = emptyList()
    @Volatile private var ownPackages: List<GoPackageSymbols> = emptyList()
    @Volatile private var ownStamp = -1L

    /**
     * The catalogue for a completion list: what there is now, at once; the packages of the project are read again behind it when the
     * index has changed, for the next list. Needs read access.
     */
    fun current(): GoSymbolIndex {
        if (!DumbService.isDumb(project) && GoExportsIndex.stamp(project) != ownStamp) refreshProject()
        return index
    }

    private fun refreshProject() {
        ReadAction.nonBlocking<Pair<Long, List<GoPackageSymbols>>> { GoExportsIndex.stamp(project) to GoExportsIndex.projectPackages(project) }
            .inSmartMode(project).expireWhen { project.isDisposed }.coalesceBy(this, "project")
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess { (stamp, packages) ->
                ownStamp = stamp
                ownPackages = packages
                assemble()
            }
    }

    @Synchronized
    private fun assemble() {
        index = GoSymbolIndex(dependencies + GoModuleSymbols("project", false, ownPackages, project = true))
    }

    private val running = AtomicBoolean()
    private val again = AtomicBoolean()
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        // go.mod saved, go get, go mod tidy: other modules, or other versions of them
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith("/" + GoModFileType.GO_MOD) }) refreshLater()
            }
        })
    }

    fun refreshLater() {
        alarm.cancelAllRequests()
        alarm.addRequest({ refresh(false) }, DELAY_MS)
    }

    /** [rescan]: the files of the cache are not trusted, everything is read from the sources again. */
    fun refresh(rescan: Boolean) {
        if (project.isDisposed || !GoSettings.getInstance().completionCatalogue) return
        refreshProject()
        if (!running.compareAndSet(false, true)) return again.set(true)
        ReadAction.nonBlocking<List<GoModule>> { GoModulesService.getInstance(project).modules() }
            .inSmartMode(project).expireWhen { project.isDisposed }
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess { modules -> if (modules.isEmpty()) finished() else build(modules, rescan) }
            .onError { finished() }
    }

    private fun build(modules: List<GoModule>, rescan: Boolean) {
        object : Task.Backgroundable(project, "Reading Go packages", true) {
            override fun run(indicator: ProgressIndicator) {
                val started = System.currentTimeMillis()
                val sources = sources(modules)
                indicator.isIndeterminate = false
                var scanned = 0
                val loaded = sources.mapIndexedNotNull { i, source ->
                    indicator.checkCanceled()
                    indicator.fraction = i.toDouble() / sources.size
                    indicator.text2 = source.key
                    load(source, rescan) { scanned++ }
                }
                dependencies = loaded
                assemble()
                LOG.info("Go catalogue: ${index.size} symbols of ${index.packages} packages (${ownPackages.size} of the project) in ${loaded.size} of ${sources.size} modules, " +
                    "$scanned scanned, ${System.currentTimeMillis() - started} ms")
            }

            override fun onFinished() = finished()
        }.queue()
    }

    private fun finished() {
        running.set(false)
        if (again.compareAndSet(true, false)) refreshLater()
    }

    private fun sources(modules: List<GoModule>): List<Source> {
        val environment = GoEnvironment.get()
        val result = LinkedHashMap<String, Source>()
        val root = environment.goRoot ?: GoCli.findExecutable()?.let { File(it).parentFile?.parent }
        val standard = root?.let { File(it, "src") }?.takeIf { it.isDirectory }
        // the same version of Go in another directory is another build of it, as far as the cache can tell
        if (standard != null) result["std"] = Source("std@${environment.goVersion ?: "unknown"}@${Integer.toHexString(standard.path.hashCode())}", standard, "", true)
        for (module in modules) {
            for (require in module.content.directRequires) {
                val replace = module.content.replacementOf(require)
                // a directory next to the project changes as the project does: not something to keep a file of
                if (replace != null && replace.isLocal) continue
                val directory = GoDependencyNode.sourceDirectory(module, require, environment.goModCache) ?: continue
                val path = replace?.newPath ?: require.path
                val version = replace?.newVersion ?: require.version
                // what the code imports it by is the path that is required, whatever it is replaced with
                result.putIfAbsent("$path@$version", Source("${require.path}=$path@$version", directory, require.path, false))
            }
        }
        return result.values.toList()
    }

    private fun load(source: Source, rescan: Boolean, onScan: () -> Unit): GoModuleSymbols? {
        if (!rescan) LOADED[source.key]?.let { return it }
        val file = File(DIRECTORY, GoCatalogueFiles.fileName(source.key))
        val cached = if (rescan) null else GoCatalogueFiles.read(file, source.key)
        val module = cached ?: try {
            onScan()
            GoModuleSymbols(source.key, source.standard, GoCatalogueScanner.scanModule(source.directory, source.modulePath, source.standard) { ProgressManager.checkCanceled() })
                .also { runCatching { GoCatalogueFiles.write(file, it) }.onFailure { e -> LOG.warn("Cannot write $file: $e") } }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Cannot read the packages of ${source.directory}: $e")
            return null
        }
        LOADED[source.key] = module
        return module
    }

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<GoCatalogueService>()
        private const val DELAY_MS = 2_000

        /** The modules read in this IDE, whatever the project: a second project with the same dependencies reads nothing. */
        private val LOADED = ConcurrentHashMap<String, GoModuleSymbols>()

        private val DIRECTORY: File get() = File(PathManager.getSystemPath(), "go-support/catalogue/v${GoCatalogueFiles.VERSION}")

        fun getInstance(project: Project): GoCatalogueService = project.service()
    }
}

class GoCatalogueStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) = GoCatalogueService.getInstance(project).refresh(false)
}

/** Go | Read Packages Again: for a module cache that was changed by hand, or a cache file that went wrong. */
class GoRebuildCatalogueAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && GoSettings.getInstance().completionCatalogue
    }
    override fun actionPerformed(e: AnActionEvent) {
        GoCatalogueService.getInstance(e.project ?: return).refresh(true)
    }
}
