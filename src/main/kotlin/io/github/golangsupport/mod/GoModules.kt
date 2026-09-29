package io.github.golangsupport.mod

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil

/** A directory with a go.mod. */
class GoModule(val root: VirtualFile, val modFile: VirtualFile, val content: GoModFile) {
    val path: String get() = content.modulePath ?: root.name

    /** `example.com/app/internal/store` for a directory of the module; null outside of it. */
    fun importPath(directory: VirtualFile): String? {
        val relative = VfsUtilCore.getRelativePath(directory, root) ?: return null
        return if (relative.isEmpty()) path else "$path/$relative"
    }
}

/** The Go modules of the project: which one a file belongs to, where `go` commands are to be run. */
@Service(Service.Level.PROJECT)
class GoModulesService(private val project: Project) {
    /** The module [file] belongs to: the nearest go.mod above it. */
    fun moduleOf(file: VirtualFile?): GoModule? {
        var directory = if (file == null || file.isDirectory) file else file.parent
        while (directory != null) {
            directory.findChild(GoModFileType.GO_MOD)?.takeIf { !it.isDirectory }?.let { return module(it) }
            directory = directory.parent
        }
        return null
    }

    /** The go.mod files the index has given last: what EDT is answered with. */
    @Volatile private var known: List<VirtualFile>? = null

    /**
     * Every module of the project. Needs the index: while it is built, only the module of the project directory is known (or what was
     * found before). On EDT the index is not asked, a slow operation there is a SEVERE in the log (seen live, from an action and from
     * `runWhenSmart`): the modules found last are given, and looked for again in the background.
     */
    fun modules(): List<GoModule> {
        val baseModule = project.guessProjectDir()?.findChild(GoModFileType.GO_MOD)?.let(::module)
        val remembered = known?.filter { it.isValid }?.map(::module)
        if (DumbService.isDumb(project)) return remembered ?: listOfNotNull(baseModule)
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread && !application.isUnitTestMode) {
            ReadAction.nonBlocking<List<VirtualFile>> { find() }.inSmartMode(project).expireWhen { project.isDisposed }.coalesceBy(this)
                .submit(AppExecutorUtil.getAppExecutorService())
            return remembered ?: listOfNotNull(baseModule)
        }
        return find().map(::module)
    }

    private fun find(): List<VirtualFile> =
        FilenameIndex.getVirtualFilesByName(GoModFileType.GO_MOD, GlobalSearchScope.projectScope(project))
            .filter { !it.isDirectory && VENDOR !in it.path }.sortedBy { it.path }.also { known = it }

    /** The go.work of the project directory, when there is one. */
    fun workspace(): GoModFile? = project.guessProjectDir()?.findChild(GoModFileType.GO_WORK)?.let(::parse)

    /** Where `go build ./...` and the like are run: every module, or the project directory when there is none (GOPATH-style code, a lone file). */
    fun commandDirectories(): List<VirtualFile> = modules().map { it.root }.ifEmpty { listOfNotNull(project.guessProjectDir()) }

    private fun module(modFile: VirtualFile): GoModule = GoModule(modFile.parent, modFile, parse(modFile))

    /** The text in the editor when the file is open and changed, parsed once per change. */
    private fun parse(file: VirtualFile): GoModFile {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        file.getUserData(PARSED)?.takeIf { it.first == stamp }?.let { return it.second }
        val text = document?.immutableCharSequence ?: runCatching { VfsUtilCore.loadText(file) }.getOrDefault("")
        return GoModFile.parse(text).also { file.putUserData(PARSED, stamp to it) }
    }

    companion object {
        private val PARSED: Key<Pair<Long, GoModFile>> = Key.create("io.github.golangsupport.mod.parsed")
        private const val VENDOR = "/vendor/"

        fun getInstance(project: Project): GoModulesService = project.service()
    }
}
