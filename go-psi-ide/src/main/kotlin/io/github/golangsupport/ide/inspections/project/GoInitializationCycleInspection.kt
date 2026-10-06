package io.github.golangsupport.ide.inspections.project

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.inspections.GoAnalysisInspectionBase
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoConstSpec
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.psi.GoVarSpec
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.cache.GoTrackers
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoPackageModel

/**
 * go/types `initOrder` across the files of a package: a package-level variable (or constant) whose initializer reaches itself through
 * package-level variables and functions declared in other files of the package. The checker follows one file only (its cycles are
 * reported there as `init-cycle`); this inspection reports only the cycles that leave the file, once, at the cycle's first variable in
 * package order (file name, offset), with go/types' text `initialization cycle for x` + `x refers to f` lines (functions included) joined with `; ` like the checker
 * diagnostics.
 *
 * Function bodies and initializers of other files are read only when the walk reaches them (that loads their AST), so the walk is off
 * for packages above [MAX_FILES] files and gives up after [MAX_NODES] declarations. Cached per file on the package's trackers and the
 * whole-file trackers of every file it read.
 */
class GoInitializationCycleInspection : GoAnalysisInspectionBase() {

    override fun visit(element: PsiElement, holder: ProblemsHolder, file: GoFile) {
        if (element !is GoVarDefinition && element !is GoConstDefinition) return
        if (DumbService.isDumb(file.project)) return
        val message = cycles(file)[element] ?: return
        holder.registerProblem((element as GoNamedElement).nameIdentifier ?: element, message, ProblemHighlightType.GENERIC_ERROR)
    }

    companion object {
        const val MAX_FILES = 100
        const val MAX_NODES = 2000

        private val KEY = Key.create<CachedValue<Map<PsiElement, String>>>("gopsi.ide.crossFileInitCycles")

        /** The cross-file cycles to report in [file]: first member → message. */
        fun cycles(file: GoFile): Map<PsiElement, String> = CachedValuesManager.getCachedValue(file, KEY) {
            val read = LinkedHashSet<GoFile>()
            val value = Walk(file, read).run()
            val trackers = GoTrackers.getInstance(file.project)
            val deps = ArrayList<Any>()
            deps.addAll(trackers.packageDependencies(file))
            deps += trackers.forFile(file)
            for (f in read) deps += trackers.forFile(f)
            CachedValueProvider.Result.create(value, *deps.toTypedArray())
        }
    }

    private class Walk(val file: GoFile, val read: MutableSet<GoFile>) {
        private val service = GoSemanticService.getInstance(file.project)
        private val files = GoPackageModel.getInstance(file.project).scopeOf(file).files.toSet()
        private val deps = HashMap<GoNamedElement, Set<GoNamedElement>>()
        private var budget = MAX_NODES

        fun run(): Map<PsiElement, String> {
            if (files.size < 2 || files.size > MAX_FILES) return emptyMap()
            val roots: List<GoNamedElement> = file.vars + file.consts
            if (roots.isEmpty()) return emptyMap()
            val result = LinkedHashMap<PsiElement, String>()
            val color = HashMap<GoNamedElement, Int>() // 1 = on path, 2 = done
            val path = ArrayList<GoNamedElement>()
            fun dfs(n: GoNamedElement): Boolean {
                color[n] = 1; path += n
                for (m in depsOf(n) ?: return false) {
                    when (color[m]) {
                        1 -> report(path.subList(path.indexOf(m), path.size).toList(), result)
                        null -> if (!dfs(m)) return false
                        else -> {}
                    }
                }
                path.removeAt(path.size - 1); color[n] = 2
                return true
            }
            for (r in roots) if (color[r] == null && !dfs(r)) return emptyMap()
            return result
        }

        private fun report(cycle: List<GoNamedElement>, result: MutableMap<PsiElement, String>) {
            if (cycle.all { it.containingFile == file }) return // the checker's own init-cycle
            val first = cycle.filter { it !is GoFunctionDeclaration }.minWithOrNull(compareBy({ it.containingFile.name }, { it.textOffset })) ?: return
            if (first.containingFile != file || first in result) return
            val i = cycle.indexOf(first)
            val chain = cycle.drop(i) + cycle.take(i) + first
            result[first] = "initialization cycle for ${first.name}" + chain.zipWithNext().joinToString("") { (a, b) -> "; ${a.name} refers to ${b.name}" }
        }

        /** Package-level variables, constants and functions [n]'s initializer or body refers to; null when the budget is spent. */
        private fun depsOf(n: GoNamedElement): Set<GoNamedElement>? {
            deps[n]?.let { return it }
            if (--budget < 0) return null
            val roots: List<PsiElement> = when (n) {
                is GoFunctionDeclaration -> listOfNotNull(n.block)
                is GoVarDefinition -> initializer(PsiTreeUtil.getParentOfType(n, GoVarSpec::class.java)?.let { it.varDefinitionList to it.expressionList }, n)
                is GoConstDefinition -> initializer(PsiTreeUtil.getParentOfType(n, GoConstSpec::class.java)?.let { it.constDefinitionList to it.expressionList }, n)
                else -> emptyList()
            }
            (n.containingFile as? GoFile)?.let { if (it != file) read += it }
            val out = LinkedHashSet<GoNamedElement>()
            for (root in roots) for (ref in listOfNotNull(root as? GoReferenceExpression) + PsiTreeUtil.findChildrenOfType(root, GoReferenceExpression::class.java)) {
                if (ref.expression != null || ref.parent is GoKey) continue
                val target = service.resolve(ref).firstOrNull() as? GoNamedElement ?: continue
                if (target.containingFile !in files) continue
                when (target) {
                    is GoFunctionDeclaration -> out += target
                    is GoVarDefinition, is GoConstDefinition -> if (!GoPsiUtil.isInsideFunctionBody(target)) out += target
                }
            }
            deps[n] = out
            return out
        }

        private fun initializer(spec: Pair<List<GoNamedElement>, List<PsiElement>>?, n: GoNamedElement): List<PsiElement> {
            val (names, values) = spec ?: return emptyList()
            return if (values.size == names.size) listOfNotNull(values.getOrNull(names.indexOf(n))) else values
        }
    }
}
