package io.github.golangsupport.ide.rename

import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.util.containers.MultiMap
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse

/**
 * Conflicts of renaming a name resolved by scope (variables, constants, parameters, receivers, functions, types, type parameters), as
 * gopls checks them: a reference of the element whose new name would resolve to a nearer declaration (`lb` → `a` inside
 * `for _, a := range alerts`: `lb.Reset(a.Labels)` became `a.Reset(a.Labels)` and the build broke, docs/compare/prometheus-refactorings),
 * a reference of the new name the renamed element would capture, and a name already declared in the element's scope. The platform shows
 * them in its conflicts dialog (Continue / Cancel), for the dialog and the in-place rename alike. Methods and fields are selectors: the
 * method processor and the checker cover them.
 */
class GoRenameConflictsProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean =
        (element is GoVarDefinition || element is GoConstDefinition || element is GoParamDefinition || element is GoReceiver ||
            element is GoFunctionDeclaration || element is GoTypeSpec || element is GoTypeParamDefinition) &&
            GoIdeFeatureGate.enabled(GoIdeFeature.RENAME, element.project)

    override fun findExistingNameConflicts(element: PsiElement, newName: String, conflicts: MultiMap<PsiElement, String>) {
        val target = element as? GoNamedElement ?: return
        val oldName = target.name ?: return
        if (newName == oldName || newName == "_") return
        val refs = ReferencesSearch.search(target, target.useScope).findAll().mapNotNull { it.element }.filter(::unqualified)
        for (ref in refs) shadowed(target, oldName, newName, ref)?.let { conflicts.putValue(ref, it) }
        for (ref in candidates(target, newName)) captured(target, oldName, newName, ref)?.let { conflicts.putValue(ref, it) }
    }

    /** [ref] of the element would resolve to a nearer declaration of [newName]. */
    private fun shadowed(target: GoNamedElement, oldName: String, newName: String, ref: PsiElement): String? {
        val (found, scope) = GoScopes.resolveNameWithScope(ref, newName) ?: return null
        val other = found.first().element
        if (other == target || other is GoNamedElement && GoUniverse.isBuiltinDeclaration(other)) return null
        val own = GoScopes.resolveNameWithScope(ref, oldName)?.takeIf { (list, _) -> list.any { it.element == target } }?.second ?: return null
        val nearer = scope != own && PsiTreeUtil.isAncestor(own, scope, true)
        val same = scope == own
        if (!nearer && !same) return null
        val what = describe(other)
        return if (same) "'$newName' is already declared in this scope ($what): renaming '$oldName' would make a duplicate declaration"
        else "Renaming '$oldName' to '$newName' would make this reference refer to $what instead"
    }

    /** [ref] of [newName] would resolve to the renamed element instead of what it refers to now. */
    private fun captured(target: GoNamedElement, oldName: String, newName: String, ref: PsiElement): String? {
        val (found, scope) = GoScopes.resolveNameWithScope(ref, newName) ?: return null
        val now = found.first().element
        if (now == target) return null
        // the renamed element is visible here (not shadowed by another declaration of its old name)
        val own = GoScopes.resolveNameWithScope(ref, oldName)?.takeIf { (list, _) -> list.any { it.element == target } }?.second ?: return null
        val builtin = now is GoNamedElement && GoUniverse.isBuiltinDeclaration(now)
        val captures = (own != scope && PsiTreeUtil.isAncestor(scope, own, true)) || (own == scope && builtin)
        if (!captures) return null
        return "Renaming '$oldName' to '$newName' would make this reference to ${describe(now)} refer to the renamed '$oldName'"
    }

    /** The unqualified references of [newName] where the renamed element could be visible: its package, or its function. */
    private fun candidates(target: GoNamedElement, newName: String): List<PsiElement> {
        val file = target.containingFile as? GoFile ?: return emptyList()
        val files = if (packageLevel(target)) GoPackageModel.getInstance(target.project).scopeOf(file).files else listOf(file)
        val region: List<PsiElement> = if (packageLevel(target)) files else listOfNotNull(enclosingFunction(target) ?: file)
        val out = ArrayList<PsiElement>()
        for (root in region) PsiTreeUtil.processElements(root) { e ->
            if (unqualified(e) && nameOf(e) == newName) out += e
            true
        }
        return out
    }

    private fun packageLevel(e: PsiElement): Boolean = (e is GoFunctionDeclaration || e is GoTypeSpec || e is GoVarDefinition || e is GoConstDefinition) &&
        PsiTreeUtil.getParentOfType(e, io.github.golangsupport.lang.psi.GoBlock::class.java, io.github.golangsupport.lang.psi.GoFunctionLit::class.java) == null

    private fun enclosingFunction(e: PsiElement): PsiElement? =
        PsiTreeUtil.getParentOfType(e, GoFunctionDeclaration::class.java, io.github.golangsupport.lang.psi.GoMethodDeclaration::class.java, io.github.golangsupport.lang.psi.GoFunctionLit::class.java)

    private fun unqualified(e: PsiElement): Boolean = when (e) {
        is GoReferenceExpression -> e.expression == null
        is GoTypeReferenceExpression -> e.period == null
        else -> false
    }

    private fun nameOf(e: PsiElement): String? = when (e) {
        is GoReferenceExpression -> e.identifier?.text
        is GoTypeReferenceExpression -> e.identifier?.text
        else -> null
    }

    private fun describe(e: PsiElement): String {
        val line = e.containingFile?.viewProvider?.document?.getLineNumber(e.textOffset)?.plus(1)
        val name = (e as? GoNamedElement)?.name ?: (e as? io.github.golangsupport.lang.psi.GoImportSpec)?.let { GoScopes.importName(it) } ?: e.text
        val kind = when {
            e is GoNamedElement && GoUniverse.isBuiltinDeclaration(e) -> "the builtin"
            e is io.github.golangsupport.lang.psi.GoImportSpec -> "the import"
            e is GoFunctionDeclaration -> "the function"
            e is GoTypeSpec -> "the type"
            e is GoConstDefinition -> "the constant"
            e is GoParamDefinition -> "the parameter"
            e is GoNamedElement -> "the variable"
            else -> "the declaration"
        }
        return "$kind '$name'" + if (line != null && !(e is GoNamedElement && GoUniverse.isBuiltinDeclaration(e))) " (${e.containingFile.name}:$line)" else ""
    }
}
