package io.github.golangsupport.semantic.cache

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.ParameterizedCachedValue
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.semantic.psi.GoPsiUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * Semantic cache entry point for values computed at one PSI element (expression types, constants,
 * callee signatures, resolve results, scope data).
 *
 * - Inside a function body the value goes into the **body store** of the outermost body
 *   ([GoPsiUtil.outermostBody]): one `CachedValue` on that body block holding a lazily filled map
 *   per [key]. The store depends on the body's own tracker ([GoTrackers.forBody]), the file's
 *   body fallback stamp and the package dependencies, so an edit in one function keeps the
 *   values of every other function of the file. Function literals share the store of their
 *   enclosing top-level function.
 * - Elsewhere (package-level code) the value is a `CachedValue` on the element itself under [key],
 *   depending on [GoTrackers.packageDependencies].
 *
 * The store is attached to the body block (AST), not to the stub-based declaration, so it never
 * keeps an unloaded file's AST reachable. Values are filled get-then-put (inference recurses into
 * the same body, so no `computeIfAbsent`); a value is stored only when no recursion was prevented
 * while computing it (`RecursionManager.markStack`, as `CachedValuesManager` does), and the first
 * stored value wins so concurrent readers see one instance. Null results are stored as a sentinel.
 */
object GoBodyCache {

    private val STORE_KEY = Key.create<CachedValue<Store>>("gopsi.bodyStore")
    private val LAST_STORE_KEY = Key.create<Store>("gopsi.lastBodyStore")
    private val NULL = Any()

    /** One lazily filled map per cache key. */
    class Store internal constructor(private val model: ModificationTracker, private val physical: Boolean) {
        /** [GoPsiUtil.treeStamp] and project-model count at the last full validation (fast path of [storeOf]). */
        @Volatile private var checkedTree = -1L
        @Volatile private var checkedModel = -1L

        internal fun validatedNow(tree: Long, model: Long) {
            checkedTree = tree
            checkedModel = model
        }

        internal fun stillValid(tree: Long): Boolean = physical && checkedTree == tree && checkedModel == model.modificationCount

        internal val modelCount: Long get() = model.modificationCount

        private val maps = ConcurrentHashMap<Key<*>, ConcurrentHashMap<PsiElement, Any>>(8)

        internal fun get(key: Key<*>, element: PsiElement): Any? = maps[key]?.get(element)

        /** Stores [value] unless a value is already present; returns the stored one. */
        internal fun put(key: Key<*>, element: PsiElement, value: Any): Any {
            val map = maps[key] ?: ConcurrentHashMap<PsiElement, Any>().let { maps.putIfAbsent(key, it) ?: it }
            return map.putIfAbsent(element, value) ?: value
        }

        /** Number of cached values (tests and the memory benchmark). */
        val size: Int get() = maps.values.sumOf { it.size }
    }

    /**
     * The value of [compute] for [element], cached under [key] (see the class comment). [compute]
     * keeps its own recursion guard; [key] must be used for one kind of value only.
     */
    fun <T> cached(element: PsiElement, key: Key<CachedValue<T>>, compute: () -> T): T {
        val body = GoPsiUtil.outermostBodyNode(element)
            ?: return CachedValuesManager.getCachedValue(element, key) {
                CachedValueProvider.Result.create(compute(), *GoTrackers.getInstance(element.project).packageDependencies(element))
            }
        val store = storeOf(body)
        store.get(key, element)?.let { return unwrap(it) }
        val stamp = RecursionManager.markStack()
        val value = compute()
        if (!stamp.mayCacheNow()) return value
        return unwrap(store.put(key, element, value ?: NULL))
    }

    /**
     * The body store of [body] (an outermost body block), created on demand. Fast path: every
     * dependency of the store changes only with a Go PSI change or a roots change (both bump
     * [GoPsiUtil.treeStamp]) or a project-model change, so a store validated at the current stamp
     * and model count is returned without checking its `CachedValue` (non-physical files always
     * take the full check).
     */
    fun storeOf(body: GoBlock): Store = storeOf(body.node)

    private fun storeOf(bodyNode: ASTNode): Store {
        val tree = GoPsiUtil.treeStamp
        bodyNode.getUserData(LAST_STORE_KEY)?.let { if (it.stillValid(tree)) return it }
        val body = bodyNode.psi as GoBlock
        val store = CachedValuesManager.getCachedValue(body, STORE_KEY) {
            val trackers = GoTrackers.getInstance(body.project)
            CachedValueProvider.Result.create(Store(trackers.projectModel, body.containingFile?.isPhysical == true), *trackers.bodyStoreDependencies(body))
        }
        store.validatedNow(tree, store.modelCount)
        bodyNode.putUserData(LAST_STORE_KEY, store)
        return store
    }

    /** Whether a value for [element] under [key] is cached and up to date (never computes it). */
    fun isCached(element: PsiElement, key: Key<*>): Boolean {
        val body = GoPsiUtil.outermostBodyNode(element) ?: return hasUpToDateValue(element, key)
        return storeOf(body).get(key, element) != null
    }

    private fun hasUpToDateValue(holder: PsiElement, key: Key<*>): Boolean =
        // The static CachedValuesManager.getCachedValue stores a parameterized cached value under the key.
        when (val v: Any? = holder.getUserData(key)) {
            is CachedValue<*> -> v.hasUpToDateValue()
            is ParameterizedCachedValue<*, *> -> v.hasUpToDateValue()
            else -> false
        }

    /** The current store of [body] if one exists and is up to date (tests). */
    fun existingStore(body: GoBlock): Store? = if (hasUpToDateValue(body, STORE_KEY)) storeOf(body) else null

    @Suppress("UNCHECKED_CAST")
    private fun <T> unwrap(v: Any): T = (if (v === NULL) null else v) as T
}
