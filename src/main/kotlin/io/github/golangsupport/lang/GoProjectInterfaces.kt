package io.github.golangsupport.lang

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.golangsupport.mod.GoModulesService
import java.util.concurrent.ConcurrentHashMap

/**
 * The interfaces of the Go files of the project, for Implement Interface: read once and kept by file, so that the popup opens at
 * once the second time (the first run on a big project took long even on fast machines, seen live). A file is read again only
 * when its text has changed; the files to look at come from the index (the ones that declare an interface) when it is ready, and
 * from a walk over the content of the project while it is being built. Warmed up in the background after the project opens.
 */
@Service(Service.Level.PROJECT)
class GoProjectInterfaces(private val project: Project) {
    /** An interface as declared: what does not depend on where it is asked from. */
    class Entry(val name: String, val directory: VirtualFile, val file: VirtualFile, val importPath: String?, val packageName: String, val methods: Set<String>, val embeds: Boolean)

    private class Scanned(val stamp: Long, val entries: List<Entry>)

    private val byFile = ConcurrentHashMap<VirtualFile, Scanned>()

    /** The interfaces there are now; [indicator] is told the file at hand. Read actions per file: nothing long is held. */
    fun entries(indicator: ProgressIndicator? = null): List<Entry> {
        val files = ReadAction.compute<Collection<VirtualFile>, RuntimeException> { files() }
        byFile.keys.retainAll(files.toSet())
        val documents = FileDocumentManager.getInstance()
        val modules = GoModulesService.getInstance(project)
        val result = ArrayList<Entry>()
        indicator?.isIndeterminate = false
        for ((i, file) in files.withIndex()) {
            (indicator ?: ProgressManager.getInstance().progressIndicator)?.checkCanceled()
            indicator?.fraction = i.toDouble() / files.size
            indicator?.text2 = file.path
            val scanned = ReadAction.compute<Scanned?, RuntimeException> {
                if (!file.isValid) return@compute null
                val document = documents.getCachedDocument(file)
                val stamp = document?.modificationStamp ?: file.modificationStamp
                byFile[file]?.takeIf { it.stamp == stamp } ?: Scanned(stamp, scan(file, document?.immutableCharSequence ?: LoadTextUtil.loadText(file), modules)).also { byFile[file] = it }
            } ?: continue
            result += scanned.entries
        }
        return result
    }

    private fun files(): Collection<VirtualFile> {
        if (!DumbService.isDumb(project)) return GoDeclarationIndex.filesWithInterfaces(project, GlobalSearchScope.projectScope(project))
        val all = ArrayList<VirtualFile>()
        ProjectFileIndex.getInstance(project).iterateContent { file -> if (!file.isDirectory && file.extension == GoFileType.defaultExtension) all.add(file); true }
        return all
    }

    private fun scan(file: VirtualFile, text: CharSequence, modules: GoModulesService): List<Entry> {
        val directory = file.parent ?: return emptyList()
        val structure = GoDeclarations.scan(text)
        val importPath = modules.moduleOf(directory)?.importPath(directory)
        return structure.declarations.mapNotNull { declaration ->
            if (declaration.kind != GoDeclarationKind.INTERFACE || declaration.body == null) return@mapNotNull null
            val body = GoInterfaces.parseBody(text.subSequence(declaration.body.startOffset, declaration.body.endOffset))
            if (body.methods.isEmpty() && body.embedded.isEmpty()) return@mapNotNull null
            Entry(declaration.name, directory, file, importPath, structure.packageName ?: directory.name, body.methods.mapTo(LinkedHashSet()) { it.first }, body.embedded.isNotEmpty())
        }
    }

    /** Reads what is not read yet, in the background and with write actions going first: the first popup finds the files done. */
    fun warmUp() {
        ReadAction.nonBlocking<Unit> { entries() }.inSmartMode(project).expireWhen { project.isDisposed }.coalesceBy(this)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    companion object {
        fun getInstance(project: Project): GoProjectInterfaces = project.service()
    }
}

class GoProjectInterfacesStartup : ProjectActivity {
    override suspend fun execute(project: Project) = GoProjectInterfaces.getInstance(project).warmUp()
}
