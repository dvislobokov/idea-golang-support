package io.github.golangsupport.semantic.cache

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.util.indexing.FileBasedIndex
import io.github.golangsupport.lang.GoFileType
import io.github.golangsupport.lang.index.GoFileImportsIndex
import com.intellij.util.containers.ContainerUtil
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.impl.GoProjectModelTracker
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoPackageModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Go-specific modification trackers (never `PsiModificationTracker.MODIFICATION_COUNT`).
 *
 * Out-of-block changes (declarations, signatures, types, imports: anything outside a function
 * body) are tracked per package directory:
 *
 * - A **project package** (a directory in project content, not under `vendor`) has its own stamp,
 *   bumped by out-of-block changes in its files. Its values depend on [forPackage], whose count is
 *   the newest stamp among the package and the project packages it imports transitively. An edit
 *   in an unrelated project package therefore keeps its caches.
 * - **Library packages** (GOROOT, module cache, vendor, anything outside project content) share
 *   one tracker, [library], bumped by an out-of-block change in any library file (they can be
 *   edited when opened). Library values never depend on project edits; every value depends on
 *   [library], because project code sees library declarations.
 *
 * Every value also depends on [projectModel] (go.mod/go.work/vendor, toolchain) and on project
 * roots (which decide what is project and what is library).
 *
 * Values inside function bodies live in one store per outermost body ([GoBodyCache]) and
 * additionally depend on that body's tracker ([forBody]), bumped by changes inside the body (a
 * function literal belongs to its enclosing top-level function), and on the file's body fallback
 * stamp, bumped when a change cannot be attributed to one body. An edit in one function therefore
 * keeps the caches of the other functions of the file. [forFile] is bumped by every change of the
 * file (whole-file values such as the diagnostics list, see [bodyDependencies]).
 *
 * Correctness of the package tracker: stamps come from one global sequence, so a change in any
 * package of the import closure yields a count larger than every count returned before it (the
 * closure can only shrink through an import edit in a package that stays reachable, whose new
 * stamp is then the maximum). Library packages importing project packages (module cycles) are
 * not tracked: such a library value can stay stale until the next library or model change.
 */
@Service(Service.Level.PROJECT)
class GoTrackers(private val project: Project) : Disposable {
    /** Bumped by an out-of-block change in a library file. */
    val library = SimpleModificationTracker()

    /** Bumped by any change in any Go file (benchmarks only). */
    val anyGoChange = SimpleModificationTracker()

    /** Global stamp sequence of project packages. */
    private val stamps = AtomicLong()

    /** Bumped by every out-of-block change in a project package (any package). */
    val projectOutOfBlock: ModificationTracker = ModificationTracker { stamps.get() }

    private val packages = ConcurrentHashMap<VirtualFile, PackageTracker>()

    /**
     * Stamp of the last Go file added, removed or renamed in project content. Such a change can
     * make an import resolve to a different package (a directory becomes or stops being a package),
     * so it invalidates every cached import edge and every project package count.
     */
    @Volatile private var fileSetStamp = 0L
    private val files = ContainerUtil.createConcurrentWeakMap<VirtualFile, FileState>()

    private class FileState {
        /** Bumped by every change of the file ([forFile]). */
        val any = SimpleModificationTracker()

        /** Bumped by a change that cannot be attributed to one body: drops every body store of the file. */
        val bodies = SimpleModificationTracker()

        /** Bumped by an out-of-block change of the file ([forFileOutOfBlock]). */
        val outOfBlock = SimpleModificationTracker()

        /** A precise (non-generic) PSI event arrived since the last generic `childrenChanged`. */
        @Volatile var precise = false
    }

    val projectModel: ModificationTracker = GoProjectModelTracker.getInstance(project)

    /** Bumped on project roots changes (content and libraries decide what is project code). */
    private val roots = SimpleModificationTracker()

    init {
        PsiManager.getInstance(project).addPsiTreeChangeListener(Listener(), this)
        project.messageBus.connect(this).subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                roots.incModificationCount()
                GoPsiUtil.treeChanged()
            }
        })
    }

    /** Bumped by every change of [file] (any position). */
    fun forFile(file: PsiFile): ModificationTracker = stateOf(file).any

    /**
     * Bumped by every out-of-block change of [file] (outside function bodies), and conservatively by
     * a change that cannot be attributed to one body. Package-level values of one file (its top-level
     * names, see [fileDeclarationsDependencies]) depend on it.
     */
    fun forFileOutOfBlock(file: PsiFile): ModificationTracker = stateOf(file).outOfBlock

    /**
     * Dependencies for a value derived from [file]'s own package-level declarations only (no other
     * file, no import). A non-physical file gets no PSI events, so it also depends on the file itself
     * (its modification stamp).
     */
    fun fileDeclarationsDependencies(file: PsiFile): Array<Any> =
        if (file.isPhysical) arrayOf(forFileOutOfBlock(file), roots) else arrayOf(forFileOutOfBlock(file), roots, file)

    private fun stateOf(file: PsiFile): FileState = files.computeIfAbsent(file.viewProvider.virtualFile) { FileState() }

    /**
     * The tracker of an outermost function body ([GoPsiUtil.outermostBody]), bumped by every change
     * inside it. Kept on the block. Bodies are lazy and reparseable (docs/GRAMMAR.md section N): a
     * re-parse of an edited body is merged into the existing block, which keeps its identity, and
     * the events arrive inside it. A re-parse that replaces the block (a changed body that was never
     * expanded) yields a fresh tracker, and the replacement is an out-of-block change of the declaration.
     */
    fun forBody(body: GoBlock): ModificationTracker =
        body.getUserData(BODY_TRACKER) ?: (body as UserDataHolderEx).putUserDataIfAbsent(BODY_TRACKER, SimpleModificationTracker())

    /** The body tracker of the outermost body containing [element], or null outside bodies. */
    fun forFunctionBody(element: PsiElement): ModificationTracker? = GoPsiUtil.outermostBody(element)?.let(::forBody)

    /**
     * The out-of-block tracker of the package in [dir]: [library] for library directories,
     * otherwise the closure tracker of the project package.
     */
    fun forPackage(dir: VirtualFile): ModificationTracker = projectPackage(dir) ?: library

    /**
     * Dependencies for a value computed at [element]: [bodyStoreDependencies] of its outermost body
     * inside function bodies, else [packageDependencies]. Prefer [GoBodyCache.cached], which keeps
     * body values in the body store instead of a `CachedValue` per element.
     */
    fun dependencies(element: PsiElement): Array<Any> {
        val body = GoPsiUtil.outermostBody(element) ?: return packageDependencies(element)
        return bodyStoreDependencies(body)
    }

    /** Dependencies for values inside the outermost function body [body]: its tracker, the file's body fallback stamp, the package. */
    fun bodyStoreDependencies(body: GoBlock): Array<Any> {
        val file = body.containingFile
        return arrayOf(forBody(body), stateOf(file).bodies, *packageDependencies(file))
    }

    /**
     * Dependencies for a value of [file] that reads only its package-level code (no bodies): the
     * package, and the file's body fallback stamp (a change that could not be attributed to one
     * body may as well have been outside bodies). Edits inside bodies keep it.
     */
    fun fileOutOfBlockDependencies(file: PsiFile): Array<Any> = arrayOf(stateOf(file).bodies, *packageDependencies(file))

    /** Dependencies for a value of the whole [file] that reads its bodies (any change of the file). */
    fun bodyDependencies(file: PsiFile): Array<Any> = arrayOf(forFile(file), *packageDependencies(file))

    /** Dependencies for a package-level value of the package containing [element] (a file, a directory or any element in a file). */
    fun packageDependencies(element: PsiElement): Array<Any> {
        val dir = packageDirOf(element)
            // No directory (an in-memory file): conservatively any project change.
            ?: return arrayOf(projectOutOfBlock, library, projectModel, roots)
        val pkg = projectPackage(dir) ?: return arrayOf(library, projectModel, roots)
        return arrayOf(pkg, library, projectModel, roots)
    }

    /**
     * Dependencies for project-wide relations (interface implementations): any out-of-block change in
     * project code (file additions and removals bump it too), the library tracker, the model and roots.
     */
    fun projectWideDependencies(): Array<Any> = arrayOf(projectOutOfBlock, library, projectModel, roots)

    /**
     * Dependencies for a value derived only from the package's own declarations (names by file,
     * methods by receiver name), never from what they mean: the package's own stamp and the file-set
     * stamp, not the import closure, so an edit in an imported package keeps it. Values that carry
     * types must use [packageDependencies]. Library packages depend on [library].
     */
    fun ownPackageDependencies(element: PsiElement): Array<Any> {
        val dir = packageDirOf(element)
            ?: return arrayOf(projectOutOfBlock, library, projectModel, roots)
        val pkg = projectPackage(dir) ?: return arrayOf(library, projectModel, roots)
        return arrayOf(pkg.ownTracker, projectModel, roots)
    }

    /** Drops every Go cache (structural changes; "cold" benchmarks). */
    fun invalidateAll() {
        library.incModificationCount()
        anyGoChange.incModificationCount()
        GoPsiUtil.treeChanged()
        for (pkg in packages.values) pkg.own = stamps.incrementAndGet()
        stamps.incrementAndGet()
        for (s in files.values) {
            s.any.incModificationCount()
            s.bodies.incModificationCount()
            s.outOfBlock.incModificationCount()
        }
    }

    private fun packageDirOf(element: PsiElement): VirtualFile? {
        if (element is PsiDirectory) return element.virtualFile
        val file = element.containingFile ?: return null
        val vf = if (file is GoFile) GoPsiUtil.originalVirtualFile(file) else (file.originalFile.virtualFile ?: file.viewProvider.virtualFile)
        return vf.parent
    }

    /** The tracker of the project package in [dir], or null when [dir] is library code. */
    private fun projectPackage(dir: VirtualFile): PackageTracker? {
        packages[dir]?.let { return it }
        if (!isProjectDirectory(dir)) return null
        return packages.computeIfAbsent(dir) { PackageTracker(it) }
    }

    private fun isProjectDirectory(dir: VirtualFile): Boolean {
        val index = ProjectFileIndex.getInstance(project)
        if (!index.isInContent(dir)) return false
        val root = index.getContentRootForFile(dir)
        var d: VirtualFile? = dir
        while (d != null && d != root) {
            if (d.name == "vendor") return false
            d = d.parent
        }
        return true
    }

    private class Snapshot(val seq: Long, val model: Long, val roots: Long, val value: Long)
    private class Edges(val own: Long, val fileSet: Long, val model: Long, val roots: Long, val targets: List<PackageTracker>)

    /** One project package directory: its own stamp and the closure count over its project imports. */
    private inner class PackageTracker(val dir: VirtualFile) : ModificationTracker {
        @Volatile var own = 0L

        /** The package's own stamp, not below the file-set stamp ([ownPackageDependencies]); monotonic. */
        val ownTracker = ModificationTracker { maxOf(own, fileSetStamp) }

        @Volatile private var snapshot: Snapshot? = null
        @Volatile private var edges: Edges? = null

        override fun getModificationCount(): Long {
            val seq = stamps.get()
            val model = projectModel.modificationCount
            val rootsCount = roots.modificationCount
            snapshot?.let { if (it.seq == seq && it.model == model && it.roots == rootsCount) return it.value }
            val value = try {
                runReadAction { closureMax(model, rootsCount) }
            } catch (e: Exception) {
                if (e is ControlFlowException) throw e
                // E.g. dumb mode: the newest stamp is a valid (conservative) count.
                seq
            }
            snapshot = Snapshot(seq, model, rootsCount, value)
            return value
        }

        private fun closureMax(model: Long, rootsCount: Long): Long {
            val seen = HashSet<PackageTracker>()
            val queue = ArrayDeque<PackageTracker>()
            queue += this
            // Not below the file-set stamp: a closure that shrank through a removed package must not
            // return to a count an earlier, now stale, value was cached with.
            var max = fileSetStamp
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                if (!seen.add(p)) continue
                max = maxOf(max, p.own)
                queue += p.directImports(model, rootsCount)
            }
            return max
        }

        /** The project packages imported by any Go file of this directory, cached until this package changes. */
        fun directImports(model: Long, rootsCount: Long): List<PackageTracker> {
            val ownNow = own
            val fileSet = fileSetStamp
            edges?.let { if (it.own == ownNow && it.fileSet == fileSet && it.model == model && it.roots == rootsCount) return it.targets }
            val packageModel = GoPackageModel.getInstance(project)
            val targets = LinkedHashSet<PackageTracker>()
            for ((vf, paths) in importPathsOf(dir)) {
                for (path in paths) {
                    val target = packageModel.resolveImport(path, vf)?.directory ?: continue
                    if (target != dir) projectPackage(target)?.let(targets::add)
                }
            }
            val list = targets.toList()
            edges = Edges(ownNow, fileSet, model, rootsCount, list)
            return list
        }

        fun bump() {
            own = stamps.incrementAndGet()
        }
    }

    /**
     * The import paths of every Go file in [dir]. Files whose text is not on disk yet (unsaved
     * document) or whose AST is loaded anyway read the PSI; the others read [GoFileImportsIndex]
     * (lexer-based, no stub or AST is loaded). Falls back to the PSI walk when the index is not
     * available (dumb mode).
     */
    internal fun importPathsOf(dir: VirtualFile): List<Pair<VirtualFile, Collection<String>>> {
        if (!dir.isValid) return emptyList()
        return try {
            importPathsOf(dir, useIndex = true)
        } catch (e: IndexNotReadyException) {
            importPathsOf(dir, useIndex = false)
        }
    }

    /** [importPathsOf] through the index ([useIndex]) or through the PSI of every file (tests compare both). */
    internal fun importPathsOf(dir: VirtualFile, useIndex: Boolean): List<Pair<VirtualFile, Collection<String>>> {
        if (!dir.isValid) return emptyList()
        val psiManager = PsiManager.getInstance(project)
        val documents = FileDocumentManager.getInstance()
        val result = ArrayList<Pair<VirtualFile, Collection<String>>>()
        for (vf in dir.children) {
            if (vf.isDirectory || !FileTypeRegistry.getInstance().isFileOfType(vf, GoFileType)) continue
            val psi = if (!useIndex || documents.isFileModified(vf) || hasLoadedTree(vf)) psiManager.findFile(vf) as? GoFile else null
            val paths = if (psi != null) psi.imports.map { it.path }
            else if (useIndex) FileBasedIndex.getInstance().getFileData(GoFileImportsIndex.NAME, vf, project).keys
            else continue
            result += vf to paths
        }
        return result
    }

    /** The file's PSI has a loaded AST (reading its imports costs nothing more). */
    private fun hasLoadedTree(vf: VirtualFile): Boolean {
        val cached = (PsiManager.getInstance(project) as? PsiManagerEx)?.fileManager?.getCachedPsiFile(vf) ?: return false
        return (cached as? PsiFileImpl)?.treeElement != null
    }

    // --- events ---

    /** A precise change at [element] (the event's parent) in [file]: its body, or out of block. */
    private fun bump(file: PsiFile?, element: PsiElement?) {
        if (file !is GoFile) return
        anyGoChange.incModificationCount()
        GoPsiUtil.treeChanged()
        val state = stateOf(file)
        state.any.incModificationCount()
        state.precise = true
        val body = if (element == null || !element.isValid) null else GoPsiUtil.outermostBody(element)
        if (body != null) body.getUserData(BODY_TRACKER)?.incModificationCount()
        else {
            state.outOfBlock.incModificationCount()
            bumpPackageOf(file.viewProvider.virtualFile.parent)
        }
    }

    /**
     * The generic `childrenChanged` the platform sends for the whole file after a commit. The
     * precise events of the same change (sent before it) carry the real parents; when none came,
     * the change cannot be attributed to one body and every body store of the file is dropped.
     */
    private fun bumpGeneric(file: PsiFile?) {
        if (file !is GoFile) return
        anyGoChange.incModificationCount()
        GoPsiUtil.treeChanged()
        val state = stateOf(file)
        state.any.incModificationCount()
        if (!state.precise) {
            state.bodies.incModificationCount()
            state.outOfBlock.incModificationCount()
        }
        state.precise = false
    }

    private fun bumpPackageOf(dir: VirtualFile?) {
        when (val pkg = dir?.let(::projectPackage)) {
            null -> if (dir == null) stamps.incrementAndGet() else library.incModificationCount()
            else -> pkg.bump()
        }
    }

    /**
     * A file or directory added, removed, moved or renamed (no containing file). A Go file changes
     * the package of its directory; a directory can contain whole packages, so it drops everything
     * unless it lies outside project content and libraries (e.g. an excluded build directory).
     */
    private fun structural(parent: PsiElement?, vararg children: PsiElement?) {
        for (child in children) {
            when (child) {
                is GoFile -> {
                    anyGoChange.incModificationCount()
                    GoPsiUtil.treeChanged()
                    val dir = (parent as? PsiDirectory)?.virtualFile ?: child.viewProvider.virtualFile.parent
                    if (dir != null && projectPackage(dir) != null) fileSetStamp = stamps.incrementAndGet()
                    bumpPackageOf(dir)
                }
                is PsiDirectory -> {
                    val anchor = (parent as? PsiDirectory)?.virtualFile ?: child.virtualFile
                    val index = ProjectFileIndex.getInstance(project)
                    if (index.isInContent(anchor) || index.isInLibrary(anchor)) invalidateAll()
                }
            }
        }
    }

    override fun dispose() {}

    private inner class Listener : PsiTreeChangeAdapter() {
        override fun childAdded(event: PsiTreeChangeEvent) =
            if (event.file != null) bump(event.file, event.parent) else structural(event.parent, event.child)
        override fun childRemoved(event: PsiTreeChangeEvent) =
            if (event.file != null) bump(event.file, event.parent) else structural(event.parent, event.child)
        override fun childReplaced(event: PsiTreeChangeEvent) =
            if (event.file != null) bump(event.file, event.parent) else structural(event.parent, event.oldChild, event.newChild)
        override fun childMoved(event: PsiTreeChangeEvent) =
            if (event.file != null) bump(event.file, event.parent) else {
                structural(event.oldParent, event.child)
                structural(event.newParent, event.child)
            }
        override fun childrenChanged(event: PsiTreeChangeEvent) {
            // After every commit the platform also sends a generic `childrenChanged` for the file as a
            // summary; the precise events (added/removed/replaced at the real parent) are sent too.
            // Classifying the generic one would make every keystroke out-of-block, so it only bumps
            // the file-local trackers (and the bodies when no precise event came, see bumpGeneric).
            if ((event as? com.intellij.psi.impl.PsiTreeChangeEventImpl)?.isGenericChange == true) bumpGeneric(event.file)
            else bump(event.file, event.parent)
        }
        override fun propertyChanged(event: PsiTreeChangeEvent) {
            when (val element = event.element) {
                // File rename (e.g. `_test.go`, a GOOS suffix) changes the package's file set.
                is GoFile -> structural(element.parent, element)
                is PsiDirectory -> structural(element.parent, element)
                else -> if (event.file != null) bump(event.file, null)
            }
        }
    }

    companion object {
        private val BODY_TRACKER = Key.create<SimpleModificationTracker>("gopsi.bodyTracker")

        @JvmStatic
        fun getInstance(project: Project): GoTrackers = project.service()
    }
}
