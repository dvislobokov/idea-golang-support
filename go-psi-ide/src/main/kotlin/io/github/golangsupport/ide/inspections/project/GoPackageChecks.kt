package io.github.golangsupport.ide.inspections.project

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.api.GoBuildConstraintEvaluator
import io.github.golangsupport.project.api.GoBuildContext
import io.github.golangsupport.project.api.GoPackage
import io.github.golangsupport.project.api.GoPackageResolver
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.psi.GoPsiUtil
import java.io.IOException

/**
 * Package-level build checks of the go command (`go build` / `go/build`), on the project model: the package of a directory
 * partitioned for the toolchain's build context, package clauses and `func main` read from stubs. Project packages only
 * ([GoProjectPackages.isProjectPackage]); values cached on the directory until the package changes.
 */
internal object GoPackageChecks {

    private val MULTIPLE = Key.create<CachedValue<Map<VirtualFile, String>>>("gopsi.ide.multiplePackages")
    private val HAS_MAIN = Key.create<CachedValue<Boolean>>("gopsi.ide.packageHasMain")

    /** The context the project model partitions packages for (the toolchain's, else the host's), as `DefaultGoPackageResolver` uses. */
    fun buildContext(project: Project): GoBuildContext =
        GoToolchainProvider.getInstance().toolchainFor(project)?.buildContext ?: GoBuildContext(DefaultGoToolchainProvider.hostOs(), DefaultGoToolchainProvider.hostArch())

    /** The project package [file] is a buildable file of (excluded by build constraints → null); null outside the project model. */
    fun projectPackageOf(file: GoFile): Pair<VirtualFile, GoPackage>? {
        val vf = GoPsiUtil.originalVirtualFile(file)
        if (vf.fileSystem.protocol != "file") return null
        val dir = vf.parent ?: return null
        if (!GoProjectPackages.isProjectPackage(file.project, dir)) return null
        val pkg = GoPackageResolver.getInstance(file.project).packageOf(dir) ?: return null
        return dir to pkg
    }

    /** Whether some non-test file of [pkg] declares `func main` (stubs; build constraints applied by the model). */
    fun hasMain(project: Project, dir: VirtualFile, pkg: GoPackage): Boolean {
        val psiDir = PsiManager.getInstance(project).findDirectory(dir) ?: return true
        return CachedValuesManager.getCachedValue(psiDir, HAS_MAIN) {
            val psi = PsiManager.getInstance(project)
            val found = pkg.goFiles.any { vf -> (psi.findFile(vf) as? GoFile)?.functions?.any { it.name == "main" } ?: true }
            CachedValueProvider.Result.create(found, *GoTrackers.getInstance(project).ownPackageDependencies(psiDir))
        }
    }

    /**
     * `go/build`'s `MultiplePackageError` per file of [dir]: the files that match the build context (test files too; `documentation`
     * skipped), sorted by name; the first one names the package (an external test package `x_test` of a test file counts as `x`),
     * and every later file with another name gets `found packages a (first.go) and b (this.go) in <dir>`.
     */
    fun multiplePackages(project: Project, dir: VirtualFile, pkg: GoPackage): Map<VirtualFile, String> {
        val psiDir = PsiManager.getInstance(project).findDirectory(dir) ?: return emptyMap()
        return CachedValuesManager.getCachedValue(psiDir, MULTIPLE) {
            CachedValueProvider.Result.create(computeMultiple(project, dir, pkg), *GoTrackers.getInstance(project).ownPackageDependencies(psiDir))
        }
    }

    private fun computeMultiple(project: Project, dir: VirtualFile, pkg: GoPackage): Map<VirtualFile, String> {
        if (pkg.ignoredFiles.isEmpty()) return emptyMap() // the model puts every file with another package clause there
        val context = buildContext(project)
        val psi = PsiManager.getInstance(project)
        val buildable = HashSet<VirtualFile>().apply { addAll(pkg.goFiles); addAll(pkg.testFiles); addAll(pkg.xTestFiles) }
        val files = (buildable + pkg.ignoredFiles.filter { vf -> textOf(vf)?.let { GoBuildConstraintEvaluator.matchFile(vf.name, it, context) } == true })
            .sortedBy { it.name }
        var name: String? = null
        var first: String? = null
        val result = LinkedHashMap<VirtualFile, String>()
        for (vf in files) {
            var p = (psi.findFile(vf) as? GoFile)?.packageName ?: continue
            if (p == "documentation") continue
            if (vf.name.endsWith("_test.go") && p.endsWith("_test") && p != name) p = p.removeSuffix("_test")
            if (name == null) { name = p; first = vf.name }
            else if (p != name) result[vf] = "found packages $name ($first) and $p (${vf.name}) in ${dir.presentableUrl}"
        }
        return result
    }

    private fun textOf(vf: VirtualFile): CharSequence? {
        FileDocumentManager.getInstance().getCachedDocument(vf)?.let { return it.immutableCharSequence }
        return try { VfsUtilCore.loadText(vf) } catch (_: IOException) { null }
    }
}
