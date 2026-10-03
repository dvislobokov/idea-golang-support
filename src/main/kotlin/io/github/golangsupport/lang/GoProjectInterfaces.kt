package io.github.golangsupport.lang

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.golangsupport.lang.psi.GoConstraintTerm
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.stubs.GoConstraintTermStub
import io.github.golangsupport.lang.stubs.GoTypeReferenceExpressionStub
import io.github.golangsupport.lang.stubs.index.GoTypesIndex
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.semantic.cache.GoTrackers

/**
 * The interfaces of the Go files of the project, for Implement Interface: every type spec of [GoTypesIndex] in the project scope whose
 * type is an interface, read from the stubs (no AST of any file is loaded). The list is kept until a declaration of the project changes
 * ([GoTrackers.projectWideDependencies]), so the popup opens at once the second time; in dumb mode the last list is given. Warmed up in
 * the background after the project opens.
 */
@Service(Service.Level.PROJECT)
class GoProjectInterfaces(private val project: Project) {
    /** An interface as declared: what does not depend on where it is asked from. */
    class Entry(val name: String, val directory: VirtualFile, val file: VirtualFile, val importPath: String?, val packageName: String, val methods: Set<String>, val embeds: Boolean)

    @Volatile private var last: List<Entry> = emptyList()

    private val cache: CachedValue<List<Entry>> = CachedValuesManager.getManager(project).createCachedValue {
        CachedValueProvider.Result.create(collect(), *GoTrackers.getInstance(project).projectWideDependencies())
    }

    /** The interfaces there are now. A non-blocking read action that gives way to write actions; [indicator] cancels it. */
    fun entries(indicator: ProgressIndicator? = null): List<Entry> {
        if (DumbService.isDumb(project)) return last
        indicator?.isIndeterminate = true
        if (ApplicationManager.getApplication().isReadAccessAllowed) return current()
        val action = ReadAction.nonBlocking<List<Entry>> { current() }.expireWhen { project.isDisposed }
        return (if (indicator != null) action.wrapProgress(indicator) else action).executeSynchronously()
    }

    private fun current(): List<Entry> = if (DumbService.isDumb(project)) last else cache.value.also { last = it }

    private fun collect(): List<Entry> {
        val scope = GlobalSearchScope.projectScope(project)
        val index = StubIndex.getInstance()
        val names = ArrayList<String>()
        // the type names first, then the specs: an interface is a type spec, which index of interfaces there is not
        index.processAllKeys(GoTypesIndex.KEY, { names += it; true }, scope, null)
        val modules = GoModulesService.getInstance(project)
        val result = ArrayList<Entry>()
        for (name in names) {
            ProgressManager.checkCanceled()
            index.processElements(GoTypesIndex.KEY, name, project, scope, GoTypeSpec::class.java) { spec -> entryOf(spec, modules)?.let(result::add); true }
        }
        return result
    }

    private fun entryOf(spec: GoTypeSpec, modules: GoModulesService): Entry? {
        val type = spec.type as? GoInterfaceType ?: return null
        val file = spec.containingFile as? GoFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val directory = virtualFile.parent ?: return null
        val methods = type.methodSpecList.mapNotNullTo(LinkedHashSet()) { it.name }
        var embeds = false
        for (element in type.constraintElemList) {
            // a union or an approximation (`~int | ~string`) is a type set: there is nothing to implement in it
            val term = element.constraintTermList.singleOrNull()?.takeUnless(::hasTilde) ?: continue
            when (embeddedName(term)) {
                null, "any", "comparable" -> Unit
                "error" -> methods += "Error"
                else -> embeds = true
            }
        }
        if (methods.isEmpty() && !embeds) return null
        return Entry(spec.name ?: return null, directory, virtualFile, modules.moduleOf(directory)?.importPath(directory), file.packageName ?: directory.name, methods, embeds)
    }

    private fun hasTilde(term: GoConstraintTerm): Boolean = ((term as? StubBasedPsiElementBase<*>)?.greenStub as? GoConstraintTermStub)?.hasTilde ?: (term.tilde != null)

    /** `Reader`, `io.Reader`: from the stub of the type reference, or from its text when the AST is there. */
    private fun embeddedName(term: GoConstraintTerm): String? {
        val reference = term.type.typeReferenceExpression ?: return null
        return ((reference as? StubBasedPsiElementBase<*>)?.greenStub as? GoTypeReferenceExpressionStub)?.qualifiedText ?: reference.text
    }

    /** Reads the interfaces in the background, with write actions going first: the first popup finds them read. */
    fun warmUp() {
        ReadAction.nonBlocking<Unit> { entries() }.inSmartMode(project).expireWhen { project.isDisposed }.coalesceBy(this)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    companion object {
        fun getInstance(project: Project): GoProjectInterfaces = project.service()
    }
}

class GoProjectInterfacesStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (GoProjectPresence.hasGoFiles(project)) GoProjectInterfaces.getInstance(project).warmUp()
    }
}
