package io.github.golangsupport.ide.refactoring

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.stubs.index.GoMethodFingerprintIndex
import io.github.golangsupport.lang.stubs.index.goMethodFingerprint
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoNamedType

/**
 * The declarations that must change together with an interface method or a method implementing one: the interface method specs and
 * the methods that implement them, closed over both directions (a spec gives its implementations, a method the specs it satisfies, so
 * a second interface the wrapper also satisfies comes along) until nothing new appears. A spec reached through an embedded interface
 * (`interface{ Reader; Writer }`) is the one where it is declared, found once.
 *
 * [members] are editable project declarations; [generated] are project declarations in generated files (left to `go generate`);
 * [outside] are declarations outside the project (library interfaces the hierarchy implements, implementations in dependencies).
 */
class GoSignatureHierarchy private constructor(val members: List<PsiElement>, val generated: List<PsiElement>, val outside: List<PsiElement>) {

    val all: List<PsiElement> get() = members + generated

    companion object {
        private val GENERATED = Regex("""(?m)^// Code generated .* DO NOT EDIT\.$""")

        /** Whether Change Signature of [target] can follow a hierarchy: an interface method, or a method implementing a project interface method. */
        fun applies(target: PsiElement): Boolean = when (target) {
            is GoMethodSpec -> GoImplementations.interfaceSpecOf(target) != null
            is GoMethodDeclaration -> GoImplementations.superMethods(target, GlobalSearchScope.projectScope(target.project), 1).isNotEmpty()
            else -> false
        }

        /** [target] alone: Change Signature with the hierarchy option off, or of a plain function. */
        fun single(target: PsiElement): GoSignatureHierarchy = GoSignatureHierarchy(listOf(target), emptyList(), emptyList())

        /** The hierarchy of [target]; a plain function gives itself alone. */
        fun of(target: PsiElement): GoSignatureHierarchy {
            if (target !is GoMethodSpec && target !is GoMethodDeclaration) return single(target)
            val scope = GlobalSearchScope.allScope(target.project)
            val members = ArrayList<PsiElement>()
            val generated = ArrayList<PsiElement>()
            val outside = ArrayList<PsiElement>()
            val seen = HashSet<PsiElement>()
            val queue = ArrayDeque<PsiElement>()
            fun visit(e: PsiElement) {
                if (!seen.add(e)) return
                when {
                    !GoImplementations.isInProject(e) -> outside += e // not followed: its own hierarchy is not ours to change
                    isGenerated(e.containingFile) -> generated += e.also(queue::add)
                    else -> members += e.also(queue::add)
                }
            }
            visit(target)
            while (queue.isNotEmpty()) {
                ProgressManager.checkCanceled()
                when (val current = queue.removeFirst()) {
                    is GoMethodSpec -> GoImplementations.implementingMethods(current, scope).forEach(::visit)
                    is GoMethodDeclaration -> GoImplementations.superMethods(current, scope).forEach(::visit)
                }
            }
            return GoSignatureHierarchy(members, generated, outside)
        }

        /** `// Code generated ... DO NOT EDIT.` before the package clause (the convention of `go generate` tools). */
        fun isGenerated(file: PsiFile?): Boolean {
            val go = file as? GoFile ?: return false
            val end = go.packageClause?.textRange?.endOffset ?: return false
            return GENERATED.containsMatchIn(go.viewProvider.contents.subSequence(0, end))
        }

        /**
         * Project types outside the hierarchy that have a method named [name] (with [arities] parameters) and are used as one of
         * [interfaces] (`var s Store = &legacy{}`, an argument, a result): with another signature they do not implement it, before or
         * after the change. [keeps] tells whether the method has the new signature (then the change fixes the use). At most [limit].
         */
        fun misfits(
            interfaces: Collection<GoTypeSpec>, name: String, arities: Iterable<Int>, exclude: Collection<PsiElement>, keeps: (GoMethodDeclaration) -> Boolean,
            limit: Int = 10,
        ): List<Pair<PsiElement, String>> {
            val project = interfaces.firstOrNull()?.project ?: return emptyList()
            val scope = GlobalSearchScope.projectScope(project)
            val semantic = GoSemanticService.getInstance(project)
            val result = ArrayList<Pair<PsiElement, String>>()
            val checked = HashSet<GoTypeSpec>()
            for (arity in arities) for (method in com.intellij.psi.stubs.StubIndex.getElements(GoMethodFingerprintIndex.KEY, goMethodFingerprint(name, arity), project, scope, GoMethodDeclaration::class.java)) {
                ProgressManager.checkCanceled()
                if (method in exclude || keeps(method)) continue
                val type = GoImplementations.receiverTypeSpec(method) ?: continue
                if (!checked.add(type)) continue
                for (ref in ReferencesSearch.search(type, scope).findAll()) {
                    val expr = valueOf(ref.element) ?: continue
                    val expected = (runCatching { semantic.expectedTypeAt(expr) }.getOrNull() as? GoNamedType)?.declaration ?: continue
                    if (expected !in interfaces) continue
                    result += expr to "Type ${type.name} has a method $name of another signature but is used as ${expected.name} here; it does not implement it"
                    if (result.size >= limit) return result
                    break
                }
            }
            return result
        }

        /** The value expression a type reference builds (`T{}`, `&T{}`, `T(x)`), or null. */
        private fun valueOf(ref: PsiElement): GoExpression? {
            var e: PsiElement = PsiTreeUtil.getParentOfType(ref, GoExpression::class.java) ?: return null
            while (e.parent is GoUnaryExpr || e.parent is GoParenthesesExpr) e = e.parent
            return e as? GoExpression
        }
    }
}
