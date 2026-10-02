package io.github.golangsupport.semantic.check

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.semantic.api.GoDiagnostic
import io.github.golangsupport.semantic.cache.GoBodyCache
import io.github.golangsupport.semantic.cache.GoTrackers
import org.jetbrains.annotations.ApiStatus

/**
 * `GoSemanticService.check(file)` assembled from cached parts, so that a keystroke inside one
 * function re-checks only that function:
 *
 * - **Package level** ([GoChecker.checkPackageLevel]): everything outside function bodies, plus
 *   the list of outermost bodies. A `CachedValue` on the file depending on the file's out-of-block
 *   dependencies ([GoTrackers.fileOutOfBlockDependencies]), not on edits inside bodies.
 * - **Init cycles** ([GoChecker.checkInitCycleUnit]): they follow function bodies, so the value
 *   also depends on the tracker of every body the walk read.
 * - **One result per outermost body** ([GoChecker.checkBody], same unit as [GoBodyCache]: a
 *   top-level function or method body with its function literals, or a function literal in
 *   package-level code), kept in the body's [GoBodyCache] store: it survives edits in other bodies.
 * - **Unused imports** are computed on each call from the union of the imports used by the
 *   package level and by every body.
 *
 * Cached ranges are relative (to the top-level element containing them, or to the body block),
 * because an edit in one body moves everything after it.
 *
 * The containment filter (a diagnostic that strictly contains another one is dropped) runs inside
 * each body pass over the body's own diagnostics; here, package-level diagnostics are filtered
 * against all diagnostics and body diagnostics against the package-level ones. This equals the
 * filter over the whole file because a body pass reports only inside its body or on its own
 * signature (parameter redeclarations), and the package-level pass never inside a body. The
 * result equals [GoChecker.checkMonolithic] (tests compare both).
 */
@ApiStatus.Internal
object GoIncrementalChecker {

    private val PACKAGE_LEVEL_KEY = Key.create<CachedValue<PackageLevel>>("gopsi.check.packageLevel")
    private val INIT_CYCLES_KEY = Key.create<CachedValue<List<Anchored>>>("gopsi.check.initCycles")
    private val BODY_KEY = Key.create<CachedValue<BodyResult>>("gopsi.check.body")

    /** `-Dgopsi.check.monolithic=true`: `check(file)` runs the single-walk checker (comparison, fallback). */
    private val monolithic: Boolean get() = java.lang.Boolean.getBoolean("gopsi.check.monolithic")

    /** A diagnostic with its range relative to [anchor]'s start offset. */
    private class Anchored(val anchor: PsiElement, val start: Int, val end: Int, val diagnostic: GoDiagnostic) {
        fun absolute(): GoDiagnostic {
            val base = anchor.textRange.startOffset
            return diagnostic.copy(range = TextRange(base + start, base + end))
        }
    }

    private class PackageLevel(
        val fileLevel: List<Anchored>,
        val rest: List<Anchored>,
        val usedImports: Set<GoImportSpec>,
        val bodies: List<GoBlock>,
    ) {
        fun isValid(): Boolean = bodies.all { it.isValid } && fileLevel.all { it.anchor.isValid } && rest.all { it.anchor.isValid }
    }

    /**
     * The check result of one outermost body: its diagnostics after the containment filter among
     * them, ranges relative to the body start (negative for its signature), and the imports it uses.
     */
    class BodyResult internal constructor(
        private val starts: IntArray,
        private val ends: IntArray,
        private val diagnostics: List<GoDiagnostic>,
        val usedImports: Set<GoImportSpec>,
    ) {
        /** The diagnostics at absolute offsets for the body starting at [base]. */
        fun diagnosticsAt(base: Int): List<GoDiagnostic> =
            diagnostics.mapIndexed { i, d -> d.copy(range = TextRange(base + starts[i], base + ends[i])) }

        val size: Int get() = diagnostics.size
    }

    /** The diagnostics of [file], equal to `GoChecker(project, file).checkMonolithic()`. */
    fun check(file: GoFile): List<GoDiagnostic> {
        if (monolithic) return GoChecker(file.project, file).checkMonolithic()
        val pkg = packageLevel(file)
        val packageDiagnostics = ArrayList<GoDiagnostic>()
        pkg.fileLevel.mapTo(packageDiagnostics) { it.absolute() }
        initCycles(file).mapTo(packageDiagnostics) { it.absolute() }
        pkg.rest.mapTo(packageDiagnostics) { it.absolute() }
        val used = HashSet(pkg.usedImports)
        val bodyDiagnostics = ArrayList<GoDiagnostic>()
        for (body in pkg.bodies) {
            val r = bodyResult(body)
            used += r.usedImports
            if (r.size > 0) bodyDiagnostics += r.diagnosticsAt(body.textRange.startOffset)
        }
        packageDiagnostics += GoChecker.unusedImports(file, used)

        // Containment filter across parts (inside one body it already ran).
        val kept = ArrayList<GoDiagnostic>(packageDiagnostics.size + bodyDiagnostics.size)
        for (d in packageDiagnostics) {
            if (GoChecker.keptAlways(d) || (packageDiagnostics.none { GoChecker.strictlyContains(d, it) } && bodyDiagnostics.none { GoChecker.strictlyContains(d, it) })) kept += d
        }
        for (d in bodyDiagnostics) {
            if (GoChecker.keptAlways(d) || packageDiagnostics.none { GoChecker.strictlyContains(d, it) }) kept += d
        }
        return kept.sortedWith(GoChecker.ORDER)
    }

    /** The cached result of the outermost body [body] (tests: identity shows whether it was recomputed). */
    fun bodyResult(body: GoBlock): BodyResult = GoBodyCache.cached(body, BODY_KEY) {
        val file = body.containingFile as GoFile
        val pass = GoChecker(file.project, file).checkBody(body)
        val base = body.textRange.startOffset
        BodyResult(
            IntArray(pass.kept.size) { pass.kept[it].range.startOffset - base },
            IntArray(pass.kept.size) { pass.kept[it].range.endOffset - base },
            pass.kept,
            pass.usedImports,
        )
    }

    /** The outermost bodies of [file] in document order, as the package-level pass found them (tests). */
    fun bodies(file: GoFile): List<GoBlock> = packageLevel(file).bodies

    private fun packageLevel(file: GoFile): PackageLevel {
        val cached = CachedValuesManager.getCachedValue(file, PACKAGE_LEVEL_KEY) {
            CachedValueProvider.Result.create(computePackageLevel(file), *GoTrackers.getInstance(file.project).fileOutOfBlockDependencies(file))
        }
        // Safety net: a change the trackers did not see as out-of-block replaced a body or a declaration.
        return if (cached.isValid()) cached else computePackageLevel(file)
    }

    private fun computePackageLevel(file: GoFile): PackageLevel {
        val pass = GoChecker(file.project, file).checkPackageLevel()
        val anchored = anchor(file, pass.diagnostics)
        return PackageLevel(anchored.subList(0, pass.fileLevelCount), anchored.subList(pass.fileLevelCount, anchored.size), pass.usedImports, pass.bodies)
    }

    private fun initCycles(file: GoFile): List<Anchored> {
        val cached = CachedValuesManager.getCachedValue(file, INIT_CYCLES_KEY) {
            val (diagnostics, bodies) = GoChecker(file.project, file).checkInitCycleUnit()
            val trackers = GoTrackers.getInstance(file.project)
            val deps = ArrayList<Any>()
            deps.addAll(trackers.fileOutOfBlockDependencies(file))
            for (b in bodies) deps.add(trackers.forBody(b))
            CachedValueProvider.Result.create(anchor(file, diagnostics), *deps.toTypedArray())
        }
        return if (cached.all { it.anchor.isValid }) cached else anchor(file, GoChecker(file.project, file).checkInitCycleUnit().first)
    }

    /** Anchors each diagnostic at the top-level element of [file] containing its start (the file itself before the first one). */
    private fun anchor(file: GoFile, diagnostics: List<GoDiagnostic>): List<Anchored> {
        if (diagnostics.isEmpty()) return emptyList()
        val children = generateSequence(file.firstChild) { it.nextSibling }.toList()
        val starts = IntArray(children.size) { children[it].textRange.startOffset }
        return diagnostics.map { d ->
            val i = starts.binarySearch(d.range.startOffset).let { if (it >= 0) it else -it - 2 }
            val anchor: PsiElement = if (i >= 0) children[i] else file
            val base = anchor.textRange.startOffset
            Anchored(anchor, d.range.startOffset - base, d.range.endOffset - base, d)
        }
    }
}
