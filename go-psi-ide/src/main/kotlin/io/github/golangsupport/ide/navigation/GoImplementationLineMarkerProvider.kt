package io.github.golangsupport.ide.navigation

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.psi.PsiElement
import com.intellij.openapi.util.Key
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.semantic.cache.GoTrackers
import java.util.concurrent.atomic.AtomicInteger
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoTypes
import javax.swing.Icon

/**
 * Gutter markers for interface relations, computed in the slow pass:
 *
 * - "implemented by" on interface type specs and their method specs (implementations searched in
 *   project content);
 * - "implements" on concrete type specs and on methods (interfaces searched in project and
 *   libraries, so `String() string` points to `fmt.Stringer`).
 *
 * The existence check stops at the first hit (candidates from the fingerprint indices, then
 * `implements`); the full target list is computed only when the popup opens. The boolean result is
 * cached per owner element and marker kind ([GoTrackers.projectWideDependencies]); an
 * `IndexNotReadyException` or cancellation is never cached.
 */
class GoImplementationLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = "Go interface implementations"

    override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        for (leaf in elements) {
            if (leaf.node.elementType !== GoTypes.IDENTIFIER) continue
            val owner = leaf.parent as? GoNamedElement ?: continue
            if (owner.nameIdentifier !== leaf) continue
            ProgressManager.checkCanceled()
            markerFor(leaf, owner)?.let(result::add)
        }
    }

    private fun markerFor(leaf: PsiElement, owner: GoNamedElement): LineMarkerInfo<*>? {
        val project = owner.project
        val implementationsScope = GlobalSearchScope.projectScope(project)
        val interfacesScope = GlobalSearchScope.allScope(project)
        return when (owner) {
            is GoTypeSpec -> if (GoImplementations.isInterface(owner)) {
                if (!exists(owner, IMPLEMENTED_BY) { GoImplementations.implementingTypes(owner, implementationsScope, 1).isNotEmpty() }) return null
                marker(leaf, AllIcons.Gutter.ImplementedMethod, "Is implemented by", "Implementations of ${owner.name}") {
                    GoImplementations.implementingTypes(owner, implementationsScope)
                }
            } else {
                if (!exists(owner, IMPLEMENTS) { GoImplementations.implementedInterfaces(owner, interfacesScope, 1).isNotEmpty() }) return null
                marker(leaf, AllIcons.Gutter.ImplementingMethod, "Implements", "Interfaces implemented by ${owner.name}") {
                    GoImplementations.implementedInterfaces(owner, interfacesScope)
                }
            }
            is GoMethodSpec -> {
                if (!exists(owner, IMPLEMENTED_BY) { GoImplementations.implementingMethods(owner, implementationsScope, 1).isNotEmpty() }) return null
                marker(leaf, AllIcons.Gutter.ImplementedMethod, "Is implemented by", "Implementations of ${owner.name}") {
                    GoImplementations.implementingMethods(owner, implementationsScope)
                }
            }
            is GoMethodDeclaration -> {
                if (!exists(owner, IMPLEMENTS) { GoImplementations.superMethods(owner, interfacesScope, 1).isNotEmpty() }) return null
                marker(leaf, AllIcons.Gutter.ImplementingMethod, "Implements method in", "Interface methods implemented by ${owner.name}") {
                    GoImplementations.superMethods(owner, interfacesScope)
                }
            }
            else -> null
        }
    }

    private fun marker(leaf: PsiElement, icon: Icon, tooltip: String, title: String, targets: () -> List<PsiElement>): LineMarkerInfo<*> =
        NavigationGutterIconBuilder.create(icon)
            .setTargets(NotNullLazyValue.lazy { targets() })
            .setTooltipText(tooltip)
            .setPopupTitle(title)
            .createLineMarkerInfo(leaf)

    /** Cached "has at least one target" of [kind] for [owner]; the popup targets stay lazy and are never cached. */
    private fun exists(owner: GoNamedElement, kind: Key<CachedValue<Boolean>>, compute: () -> Boolean): Boolean =
        CachedValuesManager.getCachedValue(owner, kind) {
            existenceComputations.incrementAndGet()
            CachedValueProvider.Result.create(compute(), *GoTrackers.getInstance(owner.project).projectWideDependencies())
        }

    companion object {
        private val IMPLEMENTED_BY = Key.create<CachedValue<Boolean>>("gopsi.marker.implementedBy")
        private val IMPLEMENTS = Key.create<CachedValue<Boolean>>("gopsi.marker.implements")

        /** Number of existence computations (cache misses); a test hook. */
        internal val existenceComputations = AtomicInteger()
    }
}
