package io.github.golangsupport.lang

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import io.github.golangsupport.catalogue.GoCatalogueService
import io.github.golangsupport.debugger.GoBundledDelve
import io.github.golangsupport.mod.GoModFileType
import io.github.golangsupport.problems.GoProjectProblems
import io.github.golangsupport.run.GoRunConfigurationGenerator
import io.github.golangsupport.sdk.GoFileTypeCheck
import io.github.golangsupport.sdk.GoToolchainCheckActivity
import io.github.golangsupport.settings.GoSettings
import java.util.concurrent.Callable

/**
 * Whether the project has anything of Go: a `.go` file, a go.mod or a go.work. The menu Go, the tool windows, the status bar widget and the
 * startup work of a Go project hang on it, so a Java or Python project opened in IDEA shows and pays nothing of Go. Answered from a cached
 * value (actions ask it in `update`); computed in the background and again, debounced, when Go files or directories come and go.
 * Until it is computed, the project directory is looked at: a Go project keeps its menu from the first frame, without a flicker.
 */
@Service(Service.Level.PROJECT)
class GoProjectPresence(private val project: Project) : Disposable {
    fun interface Listener {
        /** Any thread. */
        fun presenceChanged(hasGoFiles: Boolean)
    }

    @Volatile private var computed: Boolean? = null
    @Volatile private var guessed: Boolean? = null
    /** What listeners were told last (the guess before the first computation): a change is published once. */
    @Volatile private var published: Boolean? = null
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { concerns(it, computed) }) schedule(DELAY_MS)
            }
        })
        // a project directory is opened before its content roots are set (seen live in GoPluginAdvisor); the index answers only in smart mode
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) = schedule(DELAY_MS)
        })
        connection.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun exitDumbMode() = schedule(0)
        })
        schedule(0)
    }

    /** Cheap: the cached answer, or the look at the project directory before there is one. */
    val hasGoFiles: Boolean get() = computed ?: guess()

    /** Computes the answer now, under a read action, and publishes a change: for tests and callers already in the background. */
    fun recompute(): Boolean = ReadAction.compute<Boolean, RuntimeException> { compute() }.also(::update)

    private fun schedule(delay: Int) {
        if (project.isDisposed || alarm.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({
            ReadAction.nonBlocking(Callable { compute() }).expireWith(this).coalesceBy(this)
                .submit(AppExecutorUtil.getAppExecutorService())
                .onSuccess(::update)
        }, delay)
    }

    private fun update(value: Boolean) {
        if (project.isDisposed) return
        val before = published ?: guess()
        computed = value
        published = value
        if (before != value) project.messageBus.syncPublisher(TOPIC).presenceChanged(value)
    }

    private fun guess(): Boolean = guessed ?: ReadAction.compute<Boolean, RuntimeException> { !project.isDisposed && guessFromDirectory(project.guessProjectDir()) }
        .also { guessed = it }

    /** Under a read action. In dumb mode the index is not there: the content roots are walked, or the answer known so far is kept. */
    private fun compute(): Boolean {
        if (project.isDisposed) return false
        if (!DumbService.isDumb(project)) return indexed()
        return computed ?: walk() ?: guess()
    }

    private fun indexed(): Boolean {
        val scope = GlobalSearchScope.projectScope(project)
        if (FileTypeIndex.containsFileOfType(GoFileType, scope) || FileTypeIndex.containsFileOfType(GoModFileType, scope)) return true
        if (MODULE_FILES.any { FilenameIndex.getVirtualFilesByName(it, scope).isNotEmpty() }) return true
        // `.go` taken by another file type (a leftover association, see GoFileTypeCheck): the file type index does not know them as Go
        val association = FileTypeManager.getInstance().getFileTypeByExtension(GoFileType.defaultExtension)
        return association != GoFileType && FilenameIndex.getAllFilesByExt(project, GoFileType.defaultExtension, scope).isNotEmpty()
    }

    /** Null when the walk hit its bound: too big to tell without the index. */
    private fun walk(): Boolean? {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val roots = ProjectRootManager.getInstance(project).contentRoots.toList().ifEmpty { listOfNotNull(project.guessProjectDir()) }
        var visited = 0
        var found = false
        var bounded = false
        for (root in roots) {
            VfsUtilCore.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
                override fun visitFileEx(file: VirtualFile): Result {
                    if (found || bounded) return SKIP_CHILDREN
                    if (++visited > WALK_LIMIT) { bounded = true; return SKIP_CHILDREN }
                    if (file.isDirectory) return if (file != root && (skippedDirectory(file.name) || fileIndex.isExcluded(file))) SKIP_CHILDREN else CONTINUE
                    if (isGoName(file.name)) found = true
                    return CONTINUE
                }
            })
            if (found) return true
        }
        return if (bounded) null else false
    }

    override fun dispose() = Unit

    /** The project-wide part: tool windows, widgets and the startup work of a Go project follow the answer. */
    class Ui(private val project: Project) : Listener {
        override fun presenceChanged(hasGoFiles: Boolean) {
            Windows.refreshUi(project, hasGoFiles)
            if (hasGoFiles) resume(project)
        }
    }

    object Windows {
        /**
         * Tool windows and widgets after a change of the presence or of the settings: the gopls window only while the language server
         * is on (seen live: its stripe button stayed with gopls off by default).
         */
        fun refreshUi(project: Project, hasGoFiles: Boolean = hasGoFiles(project)) {
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) return@invokeLater
                val manager = ToolWindowManager.getInstance(project)
                TOOL_WINDOWS.forEach { id ->
                    val available = toolWindowAvailable(id, hasGoFiles)
                    manager.getToolWindow(id)?.let { if (it.isAvailable != available) it.isAvailable = available }
                }
                val widgets = project.service<StatusBarWidgetsManager>()
                StatusBarWidgetFactory.EP_NAME.extensionList.filter { it.id in WIDGETS }.forEach(widgets::updateWidget)
            }, ModalityState.any(), project.disposed)
        }

        fun toolWindowAvailable(id: String, hasGoFiles: Boolean): Boolean =
            hasGoFiles && (id != "gopls" || io.github.golangsupport.settings.GoSettings.getInstance().languageServerEnabled)
    }

    /**
     * Registered first among the plugin's activities: the service subscribes to file changes and starts its computation, and the look at
     * the project directory is taken here, in the background, rather than by the first menu update.
     */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) {
            getInstance(project).hasGoFiles
        }
    }

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        private const val DELAY_MS = 500
        private const val WALK_LIMIT = 50_000
        private val MODULE_FILES = listOf(GoModFileType.GO_MOD, GoModFileType.GO_WORK)
        private val SKIPPED_DIRECTORIES = setOf("node_modules", "target", "obj", "dist", "__pycache__")

        /** The tool windows of the plugin that a project without Go files does not have (ids: `gopls` belongs to the lsp content module). */
        val TOOL_WINDOWS = listOf("Go Dependencies", "Go Monitor", "Go Tests", "Go Optimization", "gopls")
        val WIDGETS = setOf("Go.Platform.Status", "Go.Gopls.Status")

        fun getInstance(project: Project): GoProjectPresence = project.service()

        fun hasGoFiles(project: Project): Boolean = !project.isDisposed && getInstance(project).hasGoFiles

        fun isGoName(name: String?): Boolean = name != null && (name.endsWith(".${GoFileType.defaultExtension}") || name in MODULE_FILES)

        fun skippedDirectory(name: String): Boolean = name.startsWith('.') || name in SKIPPED_DIRECTORIES

        /** go.mod, go.work or a `.go` file in [directory] or one level below it (not in hidden or build directories). */
        fun guessFromDirectory(directory: VirtualFile?): Boolean {
            if (directory == null || !directory.isValid) return false
            val children = directory.children.orEmpty()
            if (children.any { !it.isDirectory && isGoName(it.name) }) return true
            return children.asSequence().filter { it.isDirectory && !skippedDirectory(it.name) }.take(GUESS_DIRECTORIES)
                .any { sub -> sub.children.orEmpty().any { !it.isDirectory && isGoName(it.name) } }
        }

        private const val GUESS_DIRECTORIES = 64

        /**
         * Whether [event] can change the answer [known]: a Go file or a directory (copied in with its files, or deleted with them) created,
         * copied, moved, renamed or deleted. Adding cannot take Go away, deleting cannot bring it.
         */
        fun concerns(event: VFileEvent, known: Boolean?): Boolean {
            val adds = known != true
            val removes = known != false
            return when (event) {
                is VFileCreateEvent -> adds && (event.isDirectory || isGoName(event.childName))
                is VFileCopyEvent -> adds && (event.file.isDirectory || isGoName(event.newChildName))
                is VFileMoveEvent -> event.file.isDirectory || isGoName(event.file.name)
                is VFileDeleteEvent -> removes && (event.file.isDirectory || isGoName(event.file.name))
                is VFilePropertyChangeEvent -> event.propertyName == VirtualFile.PROP_NAME &&
                    (event.file.isDirectory || isGoName(event.oldValue as? String) || isGoName(event.newValue as? String))
                else -> false
            }
        }

        /**
         * Go files have appeared: the startup work skipped for a project without them runs now. Not the welcome page and not the plugin
         * advice: a tab or a balloon on the creation of a first file would be a surprise; they come at the next opening of the project.
         */
        fun resume(project: Project) {
            val application = ApplicationManager.getApplication()
            // tests add .go files all the time: no `go env`, no build of delve there
            if (application.isUnitTestMode || project.isDisposed) return
            application.executeOnPooledThread { if (!project.isDisposed) GoToolchainCheckActivity.check(project) }
            GoBundledDelve.ensureBuiltWhenSmart(project)
            GoFileTypeCheck.verify(project)
            GoCatalogueService.getInstance(project).refresh(false)
            GoProjectInterfaces.getInstance(project).warmUp()
            GoRunConfigurationGenerator.getInstance(project).schedule()
            if (GoSettings.getInstance().projectAnalysis) DumbService.getInstance(project).runWhenSmart { GoProjectProblems.getInstance(project).requestFull() }
        }
    }
}

/** `Go.MainMenu` and `Go.ProjectViewPopup`: hidden, together with what the content modules add to them, in a project without Go files. */
class GoProjectActionGroup : DefaultActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && GoProjectPresence.hasGoFiles(project)
    }
}
