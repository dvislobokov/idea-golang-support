package io.github.golangsupport.ide.navigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.impl.GoUseScopes
import io.github.golangsupport.lang.stubs.index.GoMethodFingerprintIndex
import io.github.golangsupport.lang.stubs.index.GoMethodSpecFingerprintIndex
import io.github.golangsupport.lang.stubs.index.GoTypesIndex
import io.github.golangsupport.lang.stubs.index.goMethodFingerprint
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoLookup
import io.github.golangsupport.semantic.types.GoMethod
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType

/**
 * Interface/implementation relations for Go to Implementation, Go to Super and the gutter markers.
 *
 * Candidates always come from the stub indices first: concrete methods by `name/arity`
 * ([GoMethodFingerprintIndex]) and interface methods by `name/arity`
 * ([GoMethodSpecFingerprintIndex]); receiver and interface type specs are found by name in the
 * candidate's own package directory ([GoTypesIndex]). Only those candidates are checked with
 * [GoSemanticService.implements] (`T` or `*T` implements the interface). Interfaces that have no
 * methods, have type terms (constraints) or are generic are skipped: every type would match, or the
 * answer needs instantiation.
 */
object GoImplementations {

    /** The interface type named by [spec], when it is a usable (non-empty, non-generic, method-only) interface. */
    fun interfaceOf(spec: GoTypeSpec): GoInterfaceType? {
        if (spec.typeParameters != null) return null
        val type = GoSemanticService.getInstance(spec.project).declarationType(spec)
        val iface = type.underlying() as? GoInterfaceType ?: return null
        if (iface.hasTypeTerms || iface.allMethods.isEmpty()) return null
        return iface
    }

    /** Whether [spec] declares an interface type (any interface, including empty ones and constraints). */
    fun isInterface(spec: GoTypeSpec): Boolean = spec.type is io.github.golangsupport.lang.psi.GoInterfaceType

    /** Concrete (non-interface) named types whose value or pointer implements [iface]. */
    fun implementingTypes(iface: GoTypeSpec, scope: GlobalSearchScope, limit: Int = Int.MAX_VALUE): List<GoTypeSpec> {
        val ifaceType = interfaceOf(iface) ?: return emptyList()
        val project = iface.project
        val probe = ifaceType.allMethods.minByOrNull { candidateCount(project, fingerprint(it), scope) } ?: return emptyList()
        val result = LinkedHashSet<GoTypeSpec>()
        val checked = HashSet<GoTypeSpec>()
        for (method in methodsByFingerprint(project, fingerprint(probe), scope)) {
            ProgressManager.checkCanceled()
            val typeSpec = receiverTypeSpec(method) ?: continue
            if (!checked.add(typeSpec)) continue
            if (typeImplements(typeSpec, ifaceType)) {
                result += typeSpec
                if (result.size >= limit) break
            }
        }
        return result.toList()
    }

    /** Methods implementing the interface method [spec]: the same-named method of every implementing type. */
    fun implementingMethods(spec: GoMethodSpec, scope: GlobalSearchScope, limit: Int = Int.MAX_VALUE): List<GoMethodDeclaration> {
        val name = spec.name ?: return emptyList()
        val iface = interfaceSpecOf(spec) ?: return emptyList()
        val semantic = GoSemanticService.getInstance(spec.project)
        val result = LinkedHashSet<GoMethodDeclaration>()
        for (typeSpec in implementingTypes(iface, scope)) {
            val named = semantic.declarationType(typeSpec) as? GoNamedType ?: continue
            val selection = semantic.lookupFieldOrMethod(GoPointerType(named), name, typeSpec.containingFile as? GoFile)
            val method = (selection as? GoLookup.Selection.Method)?.method?.declaration as? GoMethodDeclaration ?: continue
            result += method
            if (result.size >= limit) break
        }
        return result.toList()
    }

    /** Interface method specs that [method] implements (its receiver type implements their interface). */
    fun superMethods(method: GoMethodDeclaration, scope: GlobalSearchScope, limit: Int = Int.MAX_VALUE): List<GoMethodSpec> {
        val name = method.name ?: return emptyList()
        if (name == "_") return emptyList()
        val typeSpec = receiverTypeSpec(method) ?: return emptyList()
        val semantic = GoSemanticService.getInstance(method.project)
        val signature = semantic.declarationType(method)
        val arity = (signature as? io.github.golangsupport.semantic.types.GoSignatureType)?.params?.size ?: return emptyList()
        val result = ArrayList<GoMethodSpec>()
        val checked = HashMap<GoTypeSpec, Boolean>()
        for (spec in specsByFingerprint(method.project, goMethodFingerprint(name, arity), scope)) {
            ProgressManager.checkCanceled()
            val iface = interfaceSpecOf(spec) ?: continue
            val ok = checked.getOrPut(iface) { interfaceOf(iface)?.let { typeImplements(typeSpec, it) } == true }
            if (ok) {
                result += spec
                if (result.size >= limit) break
            }
        }
        return result
    }

    /** Interfaces implemented by the concrete type [typeSpec] (by its value or pointer method set). */
    fun implementedInterfaces(typeSpec: GoTypeSpec, scope: GlobalSearchScope, limit: Int = Int.MAX_VALUE): List<GoTypeSpec> {
        if (isInterface(typeSpec)) return emptyList()
        val semantic = GoSemanticService.getInstance(typeSpec.project)
        val named = semantic.declarationType(typeSpec) as? GoNamedType ?: return emptyList()
        val methods = semantic.methodsOf(GoPointerType(named))
        if (methods.isEmpty()) return emptyList()
        val names = methods.mapTo(HashSet()) { it.name }
        val result = LinkedHashSet<GoTypeSpec>()
        val checked = HashSet<GoTypeSpec>()
        for (m in methods) {
            for (spec in specsByFingerprint(typeSpec.project, fingerprint(m), scope)) {
                ProgressManager.checkCanceled()
                val iface = interfaceSpecOf(spec) ?: continue
                if (!checked.add(iface)) continue
                val ifaceType = interfaceOf(iface) ?: continue
                if (ifaceType.allMethods.any { it.name !in names }) continue
                if (typeImplements(typeSpec, ifaceType)) {
                    result += iface
                    if (result.size >= limit) return result.toList()
                }
            }
        }
        return result.toList()
    }

    /** `T` or `*T` implements [iface]; interfaces and aliases of non-named types never count. */
    fun typeImplements(typeSpec: GoTypeSpec, iface: GoInterfaceType): Boolean {
        if (isInterface(typeSpec)) return false
        val semantic = GoSemanticService.getInstance(typeSpec.project)
        val named = semantic.declarationType(typeSpec) as? GoNamedType ?: return false
        if (named.underlying() is GoInterfaceType) return false
        return semantic.implements(named, iface) || semantic.implements(GoPointerType(named), iface)
    }

    /** The package-level type spec of a method's receiver, looked up in the method's own package directory. */
    fun receiverTypeSpec(method: GoMethodDeclaration): GoTypeSpec? {
        val typeName = method.receiverTypeName ?: return null
        val file = method.containingFile as? GoFile ?: return null
        return packageTypeSpec(file, typeName)
    }

    /**
     * The type spec whose interface type directly declares [spec] (`type I interface{ M() }`); null
     * for interface literals elsewhere. Walks stub parents, so stubbed files keep their AST unloaded.
     */
    fun interfaceSpecOf(spec: GoMethodSpec): GoTypeSpec? {
        val iface = PsiTreeUtil.getStubOrPsiParent(spec) as? io.github.golangsupport.lang.psi.GoInterfaceType ?: return null
        return PsiTreeUtil.getStubOrPsiParent(iface) as? GoTypeSpec
    }

    private fun packageTypeSpec(file: GoFile, typeName: String): GoTypeSpec? {
        val directory = file.originalFile.virtualFile?.parent
        val packageName = file.packageName
        if (directory == null) {
            return file.types.firstOrNull { it.name == typeName }
        }
        val dirScope = GlobalSearchScopesCore.directoryScope(file.project, directory, false)
        return StubIndex.getElements(GoTypesIndex.KEY, typeName, file.project, dirScope, GoTypeSpec::class.java)
            .firstOrNull { (it.containingFile as? GoFile)?.packageName == packageName && !GoUseScopes.isInsideFunctionBody(it) }
    }

    private fun fingerprint(m: GoMethod): String = goMethodFingerprint(m.name, m.signature.params.size)

    private fun candidateCount(project: Project, key: String, scope: GlobalSearchScope): Int =
        StubIndex.getInstance().getMaxContainingFileCount(GoMethodFingerprintIndex.KEY, key, project, scope)

    private fun methodsByFingerprint(project: Project, key: String, scope: GlobalSearchScope): Collection<GoMethodDeclaration> =
        StubIndex.getElements(GoMethodFingerprintIndex.KEY, key, project, scope, GoMethodDeclaration::class.java)

    private fun specsByFingerprint(project: Project, key: String, scope: GlobalSearchScope): Collection<GoMethodSpec> =
        StubIndex.getElements(GoMethodSpecFingerprintIndex.KEY, key, project, scope, GoMethodSpec::class.java)

    /** Whether [element] is in project content (gutter markers search wider for library elements only on demand). */
    fun isInProject(element: PsiElement): Boolean {
        val vf = element.containingFile?.originalFile?.virtualFile ?: return false
        return com.intellij.openapi.roots.ProjectFileIndex.getInstance(element.project).isInContent(vf)
    }
}
