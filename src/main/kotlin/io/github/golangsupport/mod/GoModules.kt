package io.github.golangsupport.mod

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

    /** Every module of the project. Needs the index: while it is built, only the module of the project directory is known. */
    fun modules(): List<GoModule> {
        val baseModule = project.guessProjectDir()?.findChild(GoModFileType.GO_MOD)?.let(::module)
        if (DumbService.isDumb(project)) return listOfNotNull(baseModule)
        val files = FilenameIndex.getVirtualFilesByName(GoModFileType.GO_MOD, GlobalSearchScope.projectScope(project))
        return files.filter { !it.isDirectory && VENDOR !in it.path }.sortedBy { it.path }.map(::module)
    }

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
