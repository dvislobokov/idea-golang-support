package io.github.golangsupport.semantic.resolve

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import io.github.golangsupport.lang.psi.impl.GoTypeReferenceExpressionMixin
import io.github.golangsupport.project.api.GoPackage
import io.github.golangsupport.semantic.cache.GoBodyCache
import io.github.golangsupport.semantic.infer.GoExpressionTyper
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.literalType
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier
import io.github.golangsupport.semantic.psi.GoPsiUtil.referenceName
import io.github.golangsupport.semantic.scope.GoPackageModel
import io.github.golangsupport.semantic.scope.GoScopes
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoKey
import io.github.golangsupport.lang.psi.GoElement
import io.github.golangsupport.lang.psi.GoTypeReferenceExpression
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoLabelRef
import io.github.golangsupport.lang.psi.GoLabelDefinition
import io.github.golangsupport.semantic.types.*

/**
 * Name resolution for every reference site. Results are PSI declarations (or the package
 * directory for imports); each resolve is cached through [GoBodyCache] (the body store of the
 * enclosing function, or a `CachedValue` on package-level elements).
 *
 * Order for unqualified names (spec): innermost block (declaration before use) ... function
 * (receiver, params, results, type params) ... file (imports) ... package (all files, stubs)
 * ... dot imports ... universe.
 */
@Service(Service.Level.PROJECT)
class GoResolver(private val project: Project) {

    private val packages get() = GoPackageModel.getInstance(project)

    sealed class Result {
        abstract val element: PsiElement?
        data class Declaration(override val element: GoNamedElement) : Result()
        data class Import(override val element: GoImportSpec) : Result()
        /** `pkg.Name`: the package-level declaration in the imported package. */
        data class Member(override val element: GoNamedElement, val pkg: GoPackage) : Result()
        /** The directory of an imported package (for import paths). */
        data class Package(override val element: PsiElement, val pkg: GoPackage) : Result()
        /** A field, method or promoted member selected on a value. */
        data class Selection(override val element: GoNamedElement?, val selection: GoLookup.Selection) : Result()
        /** `C.xxx`: resolvable only through cgo; the target is the import spec of `"C"`. */
        data class Cgo(override val element: GoImportSpec) : Result()
        data class Label(override val element: GoLabelDefinition) : Result()
        /** A receiver type parameter name: [element] is the identifier in the receiver, [definition] the type's parameter it binds. */
        data class ReceiverTypeParam(override val element: PsiElement, val definition: GoTypeParamDefinition) : Result()
    }

    // --- entry points ---

    fun resolveReferenceExpression(ref: GoReferenceExpression): List<Result> = cached(ref, REFERENCE_KEY) { computeReference(ref) }

    fun resolveTypeReference(ref: GoTypeReferenceExpression): PsiElement? = cached(ref, TYPE_REFERENCE_KEY) { computeTypeReference(ref) }

    fun resolveLabel(ref: GoLabelRef): GoLabelDefinition? {
        val name = ref.identifier?.text ?: return null
        return GoScopes.resolveLabel(ref, name)
    }

    fun resolveImport(spec: GoImportSpec): Result.Package? {
        val file = spec.containingFile as? GoFile ?: return null
        val pkg = packages.resolveImport(spec.path, file) ?: return null
        val dir = com.intellij.psi.PsiManager.getInstance(project).findDirectory(pkg.directory) ?: return null
        return Result.Package(dir, pkg)
    }

    /** Struct literal keys: `T{Name: v}`. */
    fun resolveFieldKey(key: GoKey): List<Result> = cached(key, FIELD_KEY_KEY) { computeFieldKey(key) }

    /** Cached through [GoBodyCache]; a null result (prevented recursion, unresolved type reference) is recomputed unguarded. */
    private fun <T> cached(element: PsiElement, key: Key<CachedValue<T?>>, compute: () -> T): T =
        GoBodyCache.cached(element, key) { RecursionManager.doPreventingRecursion(element, true, compute) } ?: compute()

    // --- unqualified and qualified names ---

    private fun computeReference(ref: GoReferenceExpression): List<Result> {
        val name = ref.referenceName ?: return emptyList()
        val qualifier = ref.qualifier
        if (qualifier == null) return resolveUnqualified(ref, name)
        // `pkg.Name` when the qualifier is an identifier naming an import.
        if (qualifier is GoReferenceExpression && qualifier.qualifier == null) {
            val qName = qualifier.referenceName ?: ""
            val targets = GoScopes.resolveName(qualifier, qName)
            val import = targets.firstOrNull() as? GoScopes.Target.Import
            if (import != null) return resolveMember(import.element, name)
        }
        return resolveSelector(ref, qualifier, name)
    }

    fun resolveUnqualified(place: PsiElement, name: String): List<Result> =
        GoScopes.resolveName(place, name).map {
            when (it) {
                is GoScopes.Target.Declaration -> Result.Declaration(it.element)
                is GoScopes.Target.Import -> Result.Import(it.element)
                is GoScopes.Target.ReceiverTypeParam -> Result.ReceiverTypeParam(it.receiverName, it.element)
            }
        }

    fun resolveMember(spec: GoImportSpec, name: String): List<Result> {
        if (spec.path == "C") return listOf(Result.Cgo(spec))
        val file = spec.containingFile as? GoFile ?: return emptyList()
        val pkg = packages.resolveImport(spec.path, file) ?: return emptyList()
        val scope = packages.scopeOf(pkg)
        return scope.lookup(name).filter { it.isPublic() || pkg.importPath == scope.importPath && false }.map { Result.Member(it, pkg) }
    }

    /** `x.f`: field or method on the type of `x` (pointer auto-deref, promotion, interfaces). */
    private fun resolveSelector(ref: GoReferenceExpression, qualifier: GoExpression, name: String): List<Result> {
        val typer = GoExpressionTyper.getInstance(project)
        val qualifierType = typer.typeOf(qualifier)
        if (qualifierType is GoUnknownType) return emptyList()
        val file = ref.containingFile as? GoFile
        val pkgPath = file?.let { packages.packagePathOf(it) }
        val isTypeExpression = typer.isTypeExpression(qualifier)
        // Method expression `T.M` / `(*T).M`: methods only, all of them for pointer.
        val selection = GoLookup.lookupFieldOrMethod(qualifierType, name, pkgPath) ?: return emptyList()
        return listOf(toResult(selection))
    }

    private fun toResult(selection: GoLookup.Selection): Result = when (selection) {
        is GoLookup.Selection.Field -> Result.Selection(selection.member.declaration, selection)
        is GoLookup.Selection.Method -> Result.Selection(selection.method.declaration, selection)
        is GoLookup.Selection.Ambiguous -> Result.Selection(null, selection)
    }

    // --- types ---

    private fun computeTypeReference(ref: GoTypeReferenceExpression): PsiElement? {
        val mixin = ref as? GoTypeReferenceExpressionMixin
        val name = mixin?.referenceName ?: ref.identifier?.text ?: return null
        val qualifier = mixin?.qualifierName
        if (qualifier != null) {
            val qRef = ref.referenceExpression
            val targets = GoScopes.resolveName(qRef ?: ref, qualifier)
            val import = targets.firstOrNull() as? GoScopes.Target.Import ?: return null
            return resolveMember(import.element, name).firstOrNull()?.element
        }
        val targets = GoScopes.resolveName(ref, name)
        for (t in targets) {
            when (t) {
                is GoScopes.Target.Declaration -> if (t.element is GoTypeSpec || t.element is GoTypeParamDefinition) return t.element
                is GoScopes.Target.ReceiverTypeParam -> return t.receiverName
                is GoScopes.Target.Import -> {}
            }
        }
        return targets.firstOrNull()?.element
    }

    // --- struct literal keys ---

    private fun computeFieldKey(key: GoKey): List<Result> {
        val keyRef = key.expression as? GoReferenceExpression ?: return emptyList()
        if (keyRef.qualifier != null) return emptyList()
        val name = keyRef.referenceName ?: return emptyList()
        val literalType = literalTypeOfKey(key) ?: return emptyList()
        val struct = (if (literalType is GoTypeParamType) literalType.coreType else null) ?: literalType.underlying()
        if (struct !is GoStructType) return emptyList()
        val file = key.containingFile as? GoFile
        val pkgPath = file?.let { packages.packagePathOf(it) }
        val sel = GoLookup.lookupFieldOrMethod(literalType, name, pkgPath) as? GoLookup.Selection.Field ?: return emptyList()
        return listOf(Result.Selection(sel.member.declaration, sel))
    }

    /** The struct type whose fields a `Key` names, following nested literal values with elided types. */
    fun literalTypeOfKey(key: GoKey): GoType? {
        val element = key.parent as? GoElement ?: return null
        val value = element.parent as? GoLiteralValue ?: return null
        return GoExpressionTyper.getInstance(project).typeOfLiteralValue(value)
    }

    /** Whether a `Key` in a literal is a field name (struct) rather than an expression (map/array). */
    fun isFieldKey(key: GoKey): Boolean {
        val lt = literalTypeOfKey(key) ?: return false
        val t = (if (lt is GoTypeParamType) lt.coreType else null) ?: lt.underlying()
        return t is GoStructType && key.expression is GoReferenceExpression && (key.expression as GoReferenceExpression).qualifier == null
    }

    companion object {
        private val REFERENCE_KEY = Key.create<CachedValue<List<Result>?>>("gopsi.resolveReference")
        private val TYPE_REFERENCE_KEY = Key.create<CachedValue<PsiElement?>>("gopsi.resolveTypeReference")
        private val FIELD_KEY_KEY = Key.create<CachedValue<List<Result>?>>("gopsi.resolveFieldKey")

        @JvmStatic
        fun getInstance(project: Project): GoResolver = project.service()
    }
}
