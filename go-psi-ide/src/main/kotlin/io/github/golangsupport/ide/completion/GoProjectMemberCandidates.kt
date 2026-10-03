package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.lang.stubs.index.GoAllPublicNamesIndex
import io.github.golangsupport.project.api.GoPackageResolver

/**
 * Exported functions, types, vars and consts of the project's packages the file does not import, by their bare name: `Hand`
 * gives `api.Handler`, and inserting it writes the qualifier and adds the import. Read from the stub index of exported names
 * ([GoAllPublicNamesIndex], project scope: the standard library and the module cache are not in it) without loading any AST;
 * the import path of a package is the one of its directory. Left out: the current package, `main` packages, test files,
 * `vendor` / `testdata`, `internal` packages the file may not import, packages whose name is taken in the file.
 * Needs the indices: nothing in dumb mode.
 */
class GoProjectMemberCandidates(private val context: GoCompletionContext) {
    private val semantics = context.semantics

    fun collect(matcher: PrefixMatcher, typesOnly: Boolean, inScope: Set<String>, out: MutableList<GoCandidate>) {
        val project = context.file.project
        if (DumbService.isDumb(project)) return
        val ownDir = context.originalFile.virtualFile?.parent ?: return
        try {
            collect(matcher, typesOnly, inScope, ownDir, out)
        } catch (_: IndexNotReadyException) {
        }
    }

    private fun collect(matcher: PrefixMatcher, typesOnly: Boolean, inScope: Set<String>, ownDir: VirtualFile, out: MutableList<GoCandidate>) {
        val project = context.file.project
        val scope = GlobalSearchScope.projectScope(project)
        val index = StubIndex.getInstance()
        val names = ArrayList<String>()
        index.processAllKeys(GoAllPublicNamesIndex.KEY, { name ->
            ProgressManager.checkCanceled()
            if (name !in inScope && matcher.prefixMatches(name)) names += name
            names.size < MAX_NAMES
        }, scope, null)
        if (names.isEmpty()) return
        val own = semantics.packagePath
        val imported = semantics.imports.mapTo(HashSet()) { it.path }
        val taken = semantics.imports.filter { !it.isBlank && !it.isDot }.mapTo(HashSet(inScope)) { semantics.importName(it) }
        val resolver = GoPackageResolver.getInstance(project)
        val paths = HashMap<VirtualFile, String?>()
        val start = out.size
        for (name in names.sorted()) {
            index.processElements(GoAllPublicNamesIndex.KEY, name, project, scope, GoNamedElement::class.java) { e ->
                ProgressManager.checkCanceled()
                if (e !is GoFunctionDeclaration && e !is GoTypeSpec && e !is GoVarDefinition && e !is GoConstDefinition) return@processElements true
                if (typesOnly && e !is GoTypeSpec) return@processElements true
                val file = e.containingFile as? GoFile ?: return@processElements true
                val vf = file.virtualFile ?: return@processElements true
                val dir = vf.parent ?: return@processElements true
                if (dir == ownDir || file.isTestFile || SKIPPED.containsMatchIn(vf.path)) return@processElements true
                val path = paths.getOrPut(dir) { resolver.importPathOf(dir) } ?: return@processElements true
                if (path == own || path in imported || !importable(path, own)) return@processElements true
                val packageName = file.packageName ?: return@processElements true
                if (packageName == "main" || packageName in taken) return@processElements true
                val base = GoScopeCandidates.declarationCandidate(e, name, GoScopeLevel.UNIMPORTED, context)
                out += GoCandidate(
                    name, base.kind, GoScopeLevel.UNIMPORTED, e, valueType = base.valueType, tailSupplier = base.tailSupplier, tailText = base.tailText,
                    typeText = path, importPath = path, lookupString = "$packageName.$name", lookupStrings = listOf(name),
                )
                out.size - start < MAX_ITEMS
            }
            if (out.size - start >= MAX_ITEMS) return
        }
    }

    companion object {
        const val MAX_NAMES = 200
        const val MAX_ITEMS = 100
        private val SKIPPED = Regex("/(vendor|testdata)/")

        /**
         * Whether a package at [path] may be imported by the package at [from]: a path with an `internal` element only from the
         * tree rooted at the parent of its last `internal` element (the go command's rule).
         */
        fun importable(path: String, from: String?): Boolean {
            val elements = path.split('/')
            val i = elements.lastIndexOf("internal")
            if (i < 0) return true
            if (from == null || i == 0) return false
            val parent = elements.subList(0, i).joinToString("/")
            return from == parent || from.startsWith("$parent/")
        }
    }
}
