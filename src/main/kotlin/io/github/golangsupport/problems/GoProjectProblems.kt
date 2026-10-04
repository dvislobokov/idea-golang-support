package io.github.golangsupport.problems

import io.github.golangsupport.lang.GoProjectPresence
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.ide.PowerSaveMode
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Alarm
import io.github.golangsupport.ci.GoBatchInspections
import io.github.golangsupport.ci.GoInspectFiles
import io.github.golangsupport.ci.GoInspectRun
import io.github.golangsupport.ci.GoSarif
import io.github.golangsupport.ci.GoSarifLevel
import io.github.golangsupport.cli.GoPluginLog
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.cli.GoEnvDiskCache
import io.github.golangsupport.cli.GoPluginData
import io.github.golangsupport.help.GoPages
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import io.github.golangsupport.project.api.GoBuildContext
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.settings.GoSettings
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap

/**
 * Every Go and go.mod problem of the project in the Project Errors tab of the Problems tool window ([ProblemsCollector], the collector of
 * that tab), kept up to date in the background:
 * - a full pass once the project is open and indexed (and on Go | Reanalyse Project Problems), over [GoProblemsScope.files];
 * - then a file again ~1.5 s after its PSI or its disk content changed (typing, refactorings, git checkout), and when its declarations
 *   changed (the out-of-block stamp of [GoTrackers]) the other files of its package and the files that import it ([GoProblemsScope.dependents]);
 *   a changed go.mod or a new directory queues the files under it, a deleted file its package's dependents.
 *
 * One worker thread at a time and at minimal priority; each file in its own non-blocking read action, which a write action cancels and
 * restarts, so typing never waits on the analysis. The inspections are [GoInspectRun]'s (the profile's Go and go.mod tools, same filters),
 * with the gate of the native diagnostics opened on the worker's thread only ([GoBatchInspections.onThisThread]): with gopls in the editor
 * the editor keeps showing gopls alone. Plus the parser's syntax errors of Go files.
 *
 * Settings | Go | Linters: [GoSettings.projectAnalysis], [GoSettings.projectAnalysisWarnings]; nothing runs in Power Save Mode.
 */
@Service(Service.Level.PROJECT)
class GoProjectProblems(private val project: Project) : Disposable {
    private val provider = GoProblemsProvider(project)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    private val lock = Any()
    private var fullPending = false
    /** go.mod changed, a directory appeared: every analysed file under it. */
    private val directories = LinkedHashSet<VirtualFile>()
    /** A file of the package went away: the rest of the package and its importers. */
    private val packages = LinkedHashSet<VirtualFile>()
    /** A package directory went away: the files that import it. */
    private val importPaths = LinkedHashSet<String>()
    /** Changed files: first, and their dependents after them when their declarations changed. */
    private val urgent = LinkedHashSet<VirtualFile>()
    /** Files of a full pass and dependents. */
    private val queue = LinkedHashSet<VirtualFile>()
    private var worker: ProgressIndicator? = null
    private var starting = false
    @Volatile private var fullDone = false

    /** Every level, so that "Include warnings" filters without analysing again. */
    private val findings = ConcurrentHashMap<VirtualFile, List<GoFinding>>()
    private val published = HashMap<VirtualFile, List<GoProjectProblem>>()
    private val outOfBlock = ConcurrentHashMap<VirtualFile, Long>()

    @Volatile private var analysedLast: List<VirtualFile> = emptyList()

    /** Off in the unit tests: they drain the queue themselves ([drainForTests]) and see what the listeners queued. */
    private val automatic = !ApplicationManager.getApplication().isUnitTestMode

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childAdded(event: PsiTreeChangeEvent) = psiChanged(event)
            override fun childRemoved(event: PsiTreeChangeEvent) = psiChanged(event)
            override fun childReplaced(event: PsiTreeChangeEvent) = psiChanged(event)
            override fun childMoved(event: PsiTreeChangeEvent) = psiChanged(event)
            override fun childrenChanged(event: PsiTreeChangeEvent) = psiChanged(event)
        }, this)
        val bus = project.messageBus.connect(this)
        bus.subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun before(events: List<VFileEvent>) = vfsDeleting(events)
            override fun after(events: List<VFileEvent>) = vfsChanged(events)
        })
        bus.subscribe(PowerSaveMode.TOPIC, PowerSaveMode.Listener { if (PowerSaveMode.isEnabled()) cancelWorker() else if (!fullDone) requestFull() else kick() })
        bus.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun exitDumbMode() = kick()
        })
    }

    private val settings get() = GoSettings.getInstance()

    private fun active(): Boolean = !project.isDisposed && settings.projectAnalysis && !PowerSaveMode.isEnabled()

    /** A full pass: at project open and from Go | Reanalyse Project Problems. */
    fun requestFull() {
        if (!settings.projectAnalysis) return
        synchronized(lock) { fullPending = true }
        kick()
    }

    private val firstPassAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val firstPassAsked = java.util.concurrent.atomic.AtomicBoolean()

    /**
     * The first full pass at project open, once in smart mode: after the daemon has finished the open editors (and [FIRST_PASS_QUIET_MS] of
     * quiet), at most [FIRST_PASS_MAX_WAIT_MS] later. Started at once it took 3.6-7.6 s of CPU in the first seconds, competing with the
     * highlighting of the editor the user looks at (seen in the sandbox logs). The incremental passes are not delayed.
     */
    fun requestFirstFull() {
        if (!settings.projectAnalysis || !firstPassAsked.compareAndSet(false, true) || firstPassAlarm.isDisposed) return
        val smartAt = System.currentTimeMillis()
        val started = java.util.concurrent.atomic.AtomicBoolean()
        val connection = project.messageBus.connect(this)
        fun start(reason: String) {
            if (project.isDisposed || !started.compareAndSet(false, true)) return
            connection.disconnect()
            firstPassAlarm.cancelAllRequests()
            GoPluginLog.info(CATEGORY, "project problems first pass delayed by ${System.currentTimeMillis() - smartAt} ms after smart mode ($reason)")
            requestFull()
        }
        if (FileEditorManager.getInstance(project).openFiles.isEmpty()) {
            firstPassAlarm.addRequest({ start("no open editors") }, FIRST_PASS_QUIET_MS)
            return
        }
        connection.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonFinished(fileEditors: Collection<FileEditor>) {
                if (started.get() || firstPassAlarm.isDisposed) return
                // a restart of the daemon (a change, the language server) finishes again: the quiet period starts over
                firstPassAlarm.cancelAllRequests()
                firstPassAlarm.addRequest({ start("the daemon finished the open editors") }, FIRST_PASS_QUIET_MS)
                firstPassAlarm.addRequest({ start("waited the longest") }, (FIRST_PASS_MAX_WAIT_MS - (System.currentTimeMillis() - smartAt)).coerceAtLeast(0L))
            }
        })
        firstPassAlarm.addRequest({ start("waited the longest") }, FIRST_PASS_MAX_WAIT_MS)
    }

    private val snapshotAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    private fun snapshotFile(): java.nio.file.Path = GoPluginData.root().resolve("problems").resolve("${project.locationHash}.json")

    /**
     * The fingerprint lines of the project as it is now ([GoProblemsSnapshot]); under a read action in smart mode. Disk stats rather than
     * the VFS where there is a disk: a file changed while the IDE was closed is seen before the refresh reaches it.
     */
    private fun fingerprintLines(): List<String> {
        val lines = ArrayList<String>()
        val scope = GlobalSearchScope.projectScope(project)
        val inputs = LinkedHashSet<VirtualFile>(GoProblemsScope.files(project))
        for (name in listOf("go.mod", "go.sum", "go.work", "go.work.sum", "modules.txt")) inputs += FilenameIndex.getVirtualFilesByName(name, scope)
        for (file in inputs) {
            val nio = runCatching { file.fileSystem.getNioPath(file) }.getOrNull()
            val attributes = nio?.let { runCatching { Files.readAttributes(it, BasicFileAttributes::class.java) }.getOrNull() }
            lines += if (attributes != null) GoProblemsSnapshot.fileLine(file.path, attributes.size(), attributes.lastModifiedTime().toMillis())
            else GoProblemsSnapshot.fileLine(file.path, file.length, file.timeStamp)
        }
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        lines += "profile\t${profile.name}"
        for (tool in GoInspectRun.tools(project, profile, null)) lines += "tool\t${tool.shortName}\t${HighlightDisplayKey.find(tool.shortName)?.let { profile.getErrorLevel(it, null) }}"
        // not toString(): its env holds GOGCCFLAGS, which names a random go-build directory on every run (seen live: no snapshot ever matched)
        GoToolchainProvider.getInstance().toolchainFor(project)?.let { t ->
            lines += "toolchain\t${t.goroot}\t${t.version}\t${t.goos}/${t.goarch}\t${t.cgoEnabled}\t${t.buildTags.sorted()}\t${t.gomodcache}\t${t.gopath}"
            for ((k, v) in t.env) if (k !in GoEnvDiskCache.VOLATILE) lines += "env\t$k=$v"
        }
        val environment = GoEnvironment.quick().values
        lines += "go\t${environment["GOVERSION"]}\t${environment["GOROOT"]}"
        lines += "settings\t${settings.analysisGoos}\t${settings.analysisGoarch}\t${settings.buildTags}\t${settings.cgoMode}\t${settings.goExperiments}"
        lines += "plugin\t${GoPages.pluginVersion()}\t${GoProblemsSnapshot.FORMAT}"
        return lines
    }

    /** A few seconds after a drain that left nothing pending: the snapshot of what the tab knows. */
    private fun scheduleSnapshot() {
        if (!automatic || snapshotAlarm.isDisposed) return
        snapshotAlarm.cancelAllRequests()
        snapshotAlarm.addRequest({ saveSnapshot() }, SNAPSHOT_DELAY_MS)
    }

    /** Writes the snapshot; returns whether it did. Not when work is queued or an analysed file has unsaved changes: the findings must be those of the files on disk. */
    fun saveSnapshot(): Boolean {
        if (!active() || !fullDone) return false
        val started = System.currentTimeMillis()
        val inputs = runCatching {
            ReadAction.nonBlocking<List<String>?> {
                val documents = FileDocumentManager.getInstance()
                val unsaved = documents.unsavedDocuments.any { d -> documents.getFile(d)?.let { GoInspectFiles.isCandidate(it.name) } == true }
                if (unsaved || pendingCount() > 0 || synchronized(lock) { worker != null }) null else fingerprintLines()
            }.inSmartMode(project).expireWith(this).executeSynchronously()
        }.getOrNull() ?: return false
        val files = findings.entries.filter { it.key.isValid }.associate { it.key.path to it.value }
        return runCatching { GoProblemsSnapshot.write(snapshotFile(), GoProblemsSnapshot.Snapshot(GoProblemsSnapshot.FORMAT, GoProblemsSnapshot.fingerprint(inputs), files, inputs.sorted())) }
            .onFailure { GoPluginLog.warn(CATEGORY, "project problems snapshot not written: ${GoPluginLog.describe(it)}") }
            .onSuccess { GoPluginLog.info(CATEGORY, "project problems snapshot written: ${files.size} files with problems, ${System.currentTimeMillis() - started} ms") }
            .isSuccess
    }

    /**
     * At project open: the findings of the last session when the project is the same as then ([GoProblemsSnapshot]); returns whether they
     * were shown, otherwise the caller runs the first full pass. Off the EDT.
     */
    fun restoreSnapshot(): Boolean {
        if (!active()) return false
        val started = System.currentTimeMillis()
        val snapshot = GoProblemsSnapshot.read(snapshotFile())
        if (snapshot == null) { GoPluginLog.info(CATEGORY, "project problems snapshot: none, analysing"); return false }
        val inputs = runCatching {
            ReadAction.nonBlocking<List<String>> { fingerprintLines() }.inSmartMode(project).expireWith(this).executeSynchronously()
        }.getOrNull() ?: return false
        val then = (snapshot.inputs as List<String>?).orEmpty()
        var changes: GoProblemsSnapshot.FileChanges? = null
        if (GoProblemsSnapshot.fingerprint(inputs) != snapshot.fingerprint) {
            val difference = GoProblemsSnapshot.difference(then, inputs).joinToString("; ") { it.replace('\t', ' ') }
            changes = GoProblemsSnapshot.fileChanges(then, inputs)
            // a deleted directory: its import path, which finds its importers, is known only while it exists
            if (changes == null || changes.removed.any { findByPath(it.substringBeforeLast('/')) == null }) {
                GoPluginLog.info(CATEGORY, "project problems snapshot: the project changed since ($difference), analysing")
                return false
            }
            GoPluginLog.info(CATEGORY, "project problems snapshot: ${changes.changed.size} files changed, ${changes.removed.size} gone since ($difference), analysing these")
        }
        val removed = changes?.removed.orEmpty().toSet()
        val restored = snapshot.files.filterKeys { it !in removed }.mapNotNull { (path, found) -> findByPath(path)?.let { it to found } }
        for ((file, found) in restored) publish(file, found)
        fullDone = true
        if (changes != null) {
            // as if they had changed during a session: the file first, then its package and its importers (no declaration stamp yet)
            synchronized(lock) { changes.removed.mapNotNullTo(packages) { findByPath(it.substringBeforeLast('/')) } }
            for (file in changes.changed.mapNotNull(::findByPath)) {
                // go.sum, go.work, vendor/modules.txt: how the imports under them resolve
                if (GoInspectFiles.isCandidate(file.name)) changed(file)
                else (if (file.name == "modules.txt") file.parent?.parent else file.parent)?.let { synchronized(lock) { directories += it } }
            }
            schedule()
        }
        GoPluginLog.info(CATEGORY, "project problems restored from the snapshot: ${restored.size} files with problems, ${restored.sumOf { it.second.size }} problems, " +
            "${System.currentTimeMillis() - started} ms")
        return true
    }

    // the light tests keep their files in temp://
    private fun findByPath(path: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(path) ?: if (automatic) null else VirtualFileManager.getInstance().findFileByUrl("temp://$path")

    /** The page of the settings applied: off clears the tab, the warnings switch filters what was found. */
    fun settingsChanged() {
        if (!settings.projectAnalysis) {
            cancelWorker()
            synchronized(lock) { fullPending = false; directories.clear(); packages.clear(); importPaths.clear(); urgent.clear(); queue.clear() }
            fullDone = false
            findings.clear()
            outOfBlock.clear()
            publishAll()
            return
        }
        publishAll()
        if (!fullDone) requestFull()
    }

    private fun psiChanged(event: PsiTreeChangeEvent) {
        val file = event.file ?: return
        if (!file.isPhysical) return
        val vf = file.viewProvider.virtualFile
        if (GoInspectFiles.isCandidate(vf.name)) changed(vf)
    }

    /** A directory gone with its files: its import path is known only before; the importers of it are analysed after. */
    private fun vfsDeleting(events: List<VFileEvent>) {
        if (!settings.projectAnalysis || project.isDisposed) return
        val index = ProjectFileIndex.getInstance(project)
        for (event in events) {
            val dir = (event as? VFileDeleteEvent)?.file?.takeIf { it.isDirectory && relevant(it, index) } ?: continue
            GoProblemsScope.importPath(project, dir)?.let { synchronized(lock) { importPaths += it } }
        }
    }

    private fun vfsChanged(events: List<VFileEvent>) {
        if (!settings.projectAnalysis || project.isDisposed) return
        val index = ProjectFileIndex.getInstance(project)
        var deleted = false
        fun left(parent: VirtualFile?, file: VirtualFile) {
            if (parent != null && (file.isDirectory || GoInspectFiles.isCandidate(file.name)) && relevant(parent, index)) synchronized(lock) { packages += parent }
        }
        for (event in events) {
            when (event) {
                is VFileDeleteEvent -> { deleted = true; left(event.file.parent, event.file) }
                is VFileMoveEvent -> { left(event.oldParent, event.file); created(event.file, index) }
                is VFileCreateEvent -> event.file?.let { created(it, index) }
                is VFileCopyEvent -> event.findCreatedFile()?.let { created(it, index) }
                // a loaded document reports its changes through the PSI already (a save would only repeat them)
                is VFileContentChangeEvent -> if (GoInspectFiles.isCandidate(event.file.name) && FileDocumentManager.getInstance().getCachedDocument(event.file) == null &&
                    relevant(event.file, index)) changed(event.file)
                is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) { left(event.file.parent, event.file); created(event.file, index) }
            }
        }
        if (deleted) forgetInvalid()
        schedule()
    }

    /** Project content outside the directories `go` skips: node_modules, .git, vendor and the like never cost a query. */
    private fun relevant(file: VirtualFile, index: ProjectFileIndex): Boolean {
        if (!index.isInContent(file) || file.isDirectory && GoInspectFiles.skipDirectory(file.name)) return false
        return !GoInspectRun.underSkippedDirectory(file, index.getContentRootForFile(file))
    }

    private fun created(file: VirtualFile, index: ProjectFileIndex) {
        if (!relevant(file, index)) return
        if (file.isDirectory) synchronized(lock) { directories += file }
        else if (GoInspectFiles.isCandidate(file.name)) changed(file)
    }

    private fun changed(file: VirtualFile) {
        if (!settings.projectAnalysis) return
        synchronized(lock) {
            // a go.mod decides how every import under it resolves
            if (GoInspectFiles.isGoModFile(file.name)) file.parent?.let { directories += it }
            queue -= file
            urgent += file
        }
        schedule()
    }

    /** ~1.5 s after the last change: the burst of a typed word or of a checkout becomes one pass. */
    private fun schedule() {
        if (alarm.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest(::kick, DELAY_MS)
    }

    private fun cancelWorker() = synchronized(lock) { worker?.cancel() }

    private fun pendingCount(): Int = synchronized(lock) { urgent.size + queue.size + directories.size + packages.size + importPaths.size + if (fullPending) 1 else 0 }

    /** Starts the worker unless one runs (it takes what was queued meanwhile). A full pass shows its progress; a few files go quietly. */
    private fun kick() {
        if (!automatic || !active()) return
        val visible = synchronized(lock) {
            if (worker != null || starting || pendingCount() == 0) return
            starting = true
            fullPending || queue.size + urgent.size > VISIBLE_FROM
        }
        if (visible) object : Task.Backgroundable(project, TITLE, true) {
            override fun run(indicator: ProgressIndicator) = drain(indicator)
        }.queue()
        else ApplicationManager.getApplication().executeOnPooledThread {
            val indicator = EmptyProgressIndicator()
            ProgressManager.getInstance().runProcess({ drain(indicator) }, indicator)
        }
    }

    private class Profile(val profile: InspectionProfileImpl, val tools: List<LocalInspectionToolWrapper>, val context: GoBuildContext?) {
        val names: Map<String, String> = tools.associate { it.shortName to it.displayName }
    }

    private sealed interface Outcome {
        /** Not ours (gone, moved out, generated, out of the build): its problems go. */
        data object Dropped : Outcome
        /** The document has changes not in the PSI yet: the commit brings a PSI event and the file again. */
        data object Uncommitted : Outcome
        class Found(val findings: List<GoFinding>, val failures: List<String>, val stamp: Long, val dependents: List<VirtualFile>) : Outcome
    }

    private fun drain(indicator: ProgressIndicator) {
        synchronized(lock) { worker = indicator; starting = false }
        val thread = Thread.currentThread()
        val priority = thread.priority
        thread.priority = Thread.MIN_PRIORITY
        val started = System.currentTimeMillis()
        val analysed = ArrayList<VirtualFile>()
        // analysed in this drain after every change seen so far: a dependent or a file of a full pass found again need not be analysed twice
        val done = HashSet<VirtualFile>()
        var failures = 0
        var current: VirtualFile? = null
        var full = false
        try {
            indicator.isIndeterminate = false
            DumbService.getInstance(project).waitForSmartMode()
            val profile = ReadAction.compute<Profile, Throwable> {
                val p = InspectionProjectProfileManager.getInstance(project).currentProfile
                Profile(p, GoInspectRun.tools(project, p, null), GoToolchainProvider.getInstance().toolchainFor(project)?.buildContext)
            }
            while (true) {
                indicator.checkCanceled()
                if (!active()) break
                if (synchronized(lock) { fullPending.also { fullPending = false } }) {
                    full = true
                    val all = read(indicator) { GoProblemsScope.files(project) } ?: continue
                    // files found before that are no longer analysed (a new exclusion, a moved directory)
                    for (gone in findings.keys - all.toSet()) publish(gone, null)
                    synchronized(lock) { all.filterTo(queue) { it !in urgent && it !in done } }
                    continue
                }
                val dir = synchronized(lock) { directories.firstOrNull()?.also { directories -= it } }
                if (dir != null) {
                    val under = read(indicator) { GoProblemsScope.files(project, dir) } ?: continue
                    synchronized(lock) { under.filterTo(queue) { it !in urgent } }
                    continue
                }
                val pkg = synchronized(lock) { packages.firstOrNull()?.also { packages -= it } }
                if (pkg != null) {
                    val dependents = read(indicator) { GoProblemsScope.dependents(project, pkg) } ?: continue
                    synchronized(lock) { dependents.filterTo(queue) { it !in urgent } }
                    continue
                }
                val gone = synchronized(lock) { importPaths.firstOrNull()?.also { importPaths -= it } }
                if (gone != null) {
                    val importers = read(indicator) { GoProblemsScope.importers(project, gone) } ?: continue
                    synchronized(lock) { importers.filterTo(queue) { it !in urgent && it !in done } }
                    continue
                }
                val (file, expand) = synchronized(lock) {
                    urgent.firstOrNull()?.let { urgent -= it; it to true } ?: queue.firstOrNull()?.let { queue -= it; it to false }
                } ?: break
                if (!expand && file in done) continue
                current = file
                indicator.text2 = file.presentableUrl
                indicator.fraction = analysed.size.toDouble() / (analysed.size + pendingCount() + 1)
                when (val outcome = read(indicator) { analyse(file, profile, expand) }) {
                    null, Outcome.Uncommitted -> {}
                    Outcome.Dropped -> publish(file, null)
                    is Outcome.Found -> {
                        publish(file, outcome.findings)
                        outOfBlock[file] = outcome.stamp
                        failures += outcome.failures.size
                        outcome.failures.firstOrNull()?.let { GoPluginLog.warn(CATEGORY, it) }
                        if (outcome.dependents.isNotEmpty()) synchronized(lock) { outcome.dependents.filterTo(queue) { it !in urgent && it !in done } }
                    }
                }
                analysed += file
                done += file
                current = null
            }
            if (full && pendingCount() == 0) fullDone = true
            if (fullDone && analysed.isNotEmpty()) scheduleSnapshot()
            analysedLast = analysed
            if (analysed.isNotEmpty()) GoPluginLog.info(CATEGORY, summary(analysed.size, failures, System.currentTimeMillis() - started))
        } catch (e: ProcessCanceledException) {
            // what was in hand goes back to the queue: a cancelled pass resumes where it stopped
            current?.let { f -> synchronized(lock) { urgent += f } }
            if (full && !fullDone) synchronized(lock) { fullPending = true }
            if (!automatic) throw e
        } finally {
            thread.priority = priority
            synchronized(lock) { worker = null; starting = false }
        }
        if (pendingCount() > 0) schedule()
    }

    /** [block] in a non-blocking read action in smart mode on this thread; a write action restarts it. Inspections want a progress indicator. */
    private fun <T> read(indicator: ProgressIndicator, block: () -> T): T? {
        var result: T? = null
        ProgressManager.getInstance().executeProcessUnderProgress({
            result = ReadAction.nonBlocking<T> { block() }.inSmartMode(project).expireWith(this).wrapProgress(indicator).executeSynchronously()
        }, indicator)
        return result
    }

    private fun analyse(file: VirtualFile, profile: Profile, expand: Boolean): Outcome {
        if (!GoProblemsScope.isAnalysed(project, file)) return Outcome.Dropped
        val psi = PsiManager.getInstance(project).findFile(file) ?: return Outcome.Dropped
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        if (document != null && !PsiDocumentManager.getInstance(project).isCommitted(document)) return Outcome.Uncommitted
        val result = GoBatchInspections.onThisThread(project) {
            GoInspectRun.inspectFile(project, file, file.path, profile.tools, profile.profile, profile.context, explicit = false)
        } ?: return Outcome.Dropped
        val found = result.results.map { r ->
            GoFinding(r.region.startLine - 1, r.region.startColumn - 1, r.level, r.ruleId, profile.names[r.ruleId] ?: r.ruleId, r.message)
        } + syntaxErrors(psi)
        val stamp = if (psi.language.id == "Go") GoTrackers.getInstance(project).forFileOutOfBlock(psi).modificationCount else -1L
        // the declarations of the file changed (or it is new to us): the package and its importers may have new errors or lost old ones
        val dependents = if (expand && outOfBlock[file] != stamp) file.parent?.let { GoProblemsScope.dependents(project, it, except = file) }.orEmpty() else emptyList()
        return Outcome.Found(found.distinct().sortedWith(compareBy({ it.line }, { it.column })), result.failures, stamp, dependents)
    }

    private fun syntaxErrors(psi: PsiFile): List<GoFinding> {
        if (psi.language.id != "Go") return emptyList()
        val text = psi.viewProvider.contents
        return PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java).map { e ->
            val region = GoSarif.region(text, e.textRange.startOffset, e.textRange.endOffset)
            GoFinding(region.startLine - 1, region.startColumn - 1, GoSarifLevel.ERROR, SYNTAX, "Syntax error", e.errorDescription)
        }
    }

    /** [found] replaces what the tab shows for [file] (`null`: nothing); only the rows that changed come and go. */
    private fun publish(file: VirtualFile, found: List<GoFinding>?) {
        if (found == null) findings.remove(file) else findings[file] = found
        if (found == null) outOfBlock.remove(file)
        show(file, found)
    }

    private fun show(file: VirtualFile, found: List<GoFinding>?) = synchronized(published) {
        val warnings = settings.projectAnalysisWarnings
        val shown = found.orEmpty().filter { it.shown(warnings) }.map { GoProjectProblem(provider, file, it) }
        val old = published[file].orEmpty()
        if (shown.isEmpty()) published.remove(file) else published[file] = shown
        if (old == shown || project.isDisposed) return@synchronized
        val collector = ProblemsCollector.getInstance(project)
        val keep = shown.toSet()
        val had = old.toSet()
        old.filter { it !in keep }.forEach(collector::problemDisappeared)
        shown.filter { it !in had }.forEach(collector::problemAppeared)
    }

    /** Everything again from [findings]: after the settings changed. */
    private fun publishAll() {
        val files = synchronized(published) { published.keys.toSet() } + findings.keys
        for (file in files) show(file, findings[file])
    }

    private fun forgetInvalid() {
        val files = synchronized(published) { published.keys.toSet() } + findings.keys
        for (file in files) if (!file.isValid) publish(file, null)
    }

    private fun summary(files: Int, failures: Int, millis: Long): String {
        val all = findings.values.flatten()
        val byLevel = GoSarifLevel.entries.reversed().joinToString(", ") { l -> "${all.count { it.level == l }} ${l.sarif}" }
        return "$files files analysed, ${findings.size} files with problems, ${all.size} problems in the project ($byLevel), $failures inspection failures, $millis ms"
    }

    override fun dispose() {
        cancelWorker()
    }

    /** The files the tab shows problems for and the problems, as the tab sees them; for the tests and the robot. */
    fun shownProblems(): Map<VirtualFile, List<GoProjectProblem>> = synchronized(published) { published.toMap() }

    @TestOnly
    fun drainForTests(): List<VirtualFile> {
        check(!ApplicationManager.getApplication().isDispatchThread) { "the worker is for a background thread" }
        if (!active()) return emptyList()
        synchronized(lock) { starting = true }
        val indicator = EmptyProgressIndicator()
        ProgressManager.getInstance().runProcess({ drain(indicator) }, indicator)
        return analysedLast
    }

    @TestOnly
    fun clearForTests() {
        synchronized(lock) { fullPending = false; directories.clear(); packages.clear(); importPaths.clear(); urgent.clear(); queue.clear() }
        fullDone = false
        findings.clear()
        outOfBlock.clear()
        publishAll()
    }

    companion object {
        const val CATEGORY = "problems"
        const val TITLE = "Analysing Go project"
        /** The short name of the parser's errors among the inspections. */
        const val SYNTAX = "GoSyntax"
        private const val DELAY_MS = 1500
        /** Quiet after the daemon finished the open editors before the first full pass. */
        private const val FIRST_PASS_QUIET_MS = 2_000
        /** The first full pass starts at the latest this long after smart mode, whatever the daemon does. */
        private const val FIRST_PASS_MAX_WAIT_MS = 15_000L
        /** After the last drain, before the snapshot is written. */
        private const val SNAPSHOT_DELAY_MS = 3_000
        /** Fewer changed files than this go without a progress bar in the status bar. */
        private const val VISIBLE_FROM = 50

        fun getInstance(project: Project): GoProjectProblems = project.service()
    }
}

/** The first full pass once the project is open and indexed, and the open editors are highlighted ([GoProjectProblems.requestFirstFull]). */
class GoProjectProblemsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!GoSettings.getInstance().projectAnalysis || !GoProjectPresence.hasGoFiles(project)) return
        // the findings of the last session when nothing changed since; otherwise the first full pass
        DumbService.getInstance(project).runWhenSmart {
            ApplicationManager.getApplication().executeOnPooledThread {
                val problems = GoProjectProblems.getInstance(project)
                if (!project.isDisposed && !problems.restoreSnapshot()) problems.requestFirstFull()
            }
        }
    }
}
