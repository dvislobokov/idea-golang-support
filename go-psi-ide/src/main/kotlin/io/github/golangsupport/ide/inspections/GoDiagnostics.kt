package io.github.golangsupport.ide.inspections

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.api.GoDiagnostic
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.cache.GoTrackers

/**
 * Diagnostic classes ([GoDiagnostic.code]) owned by each inspection. A class not listed here is
 * reported by the catch-all [GoCheckerInspection], so new checker classes are never lost.
 */
object GoDiagnosticClasses {
    val UNRESOLVED = setOf("undefined", "undefined-member", "unexported", "unknown-field")
    val UNUSED_IMPORT = setOf("unused-import")
    val UNUSED_VARIABLE = setOf("unused-variable")
    val UNUSED_LABEL = setOf("unused-label")
    val TYPE_MISMATCH = setOf("assignability", "representability", "conversion", "untyped-nil", "mismatched-types")
    val ARITY = setOf("call-arity", "spread", "return-arity", "assignment-mismatch", "multiple-value", "no-value", "builtin-arity")
    val DUPLICATE = setOf("redeclared", "no-new-variables", "duplicate-case", "duplicate-default")
    val GENERICS = setOf("cannot-infer", "inference", "constraint", "type-args", "generic-no-instantiation")
    val MISSING_RETURN = setOf("missing-return")

    /** Every class owned by a specific inspection. */
    val CLAIMED: Set<String> = UNRESOLVED + UNUSED_IMPORT + UNUSED_VARIABLE + UNUSED_LABEL + TYPE_MISMATCH + ARITY + DUPLICATE + GENERICS + MISSING_RETURN

    /**
     * Classes with known false positives in the GOROOT corpus gate
     * (`testData/metrics/goroot-src-check.json`) that are hidden unless an inspection option
     * enables them. Empty since 0.0.9: the gate reports 0 false positives in every class.
     */
    val SHAKY: Set<String> = emptySet()
}

/**
 * `GoSemanticService.check(file)` once per file and modification: every Go inspection reads the
 * same cached list. The value depends on the Go trackers only (the file's own tracker, the
 * out-of-block tracker and the project model), never on `PsiModificationTracker`. The holder
 * computes lazily under a lock, so inspections running in parallel do not repeat the check.
 */
object GoDiagnosticsCache {
    private val KEY = Key.create<CachedValue<Holder>>("gopsi.inspections.diagnostics")

    private class Holder(private val file: GoFile) {
        val diagnostics: List<GoDiagnostic> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            GoSemanticService.getInstance(file.project).check(file)
        }
    }

    fun diagnostics(file: GoFile): List<GoDiagnostic> =
        CachedValuesManager.getManager(file.project).getCachedValue(file, KEY, {
            CachedValueProvider.Result.create(Holder(file), *GoTrackers.getInstance(file.project).bodyDependencies(file))
        }, false).diagnostics

    /**
     * The problem description of [d]: the checker's message; go/types continuation lines
     * (`\n\thave (...)\n\twant (...)`) are joined with `; ` because problem descriptions are single-line.
     */
    fun description(d: GoDiagnostic): String {
        val lines = d.message.split('\n')
        if (lines.size == 1) return d.message
        return lines.joinToString("; ") { it.trim() }
    }

    /**
     * The element to report [range] on: the outermost element whose range equals it, or the
     * smallest element containing it (with a range inside it). Empty ranges grow to the next leaf.
     */
    fun anchor(file: PsiFile, range: TextRange): Pair<PsiElement, TextRange>? {
        if (range.endOffset > file.textLength) return null
        val leaf = file.findElementAt(range.startOffset) ?: file.findElementAt(maxOf(0, range.startOffset - 1)) ?: return null
        if (range.isEmpty) return leaf to TextRange(0, leaf.textLength)
        var e: PsiElement = leaf
        while (e.parent != null && e.parent !is PsiFile && e.parent.textRange.startOffset == range.startOffset && e.parent.textRange.endOffset <= range.endOffset) {
            e = e.parent
        }
        if (e.textRange == range) return e to TextRange(0, e.textLength)
        var container: PsiElement = leaf
        while (!container.textRange.contains(range) && container.parent != null) container = container.parent
        return container to range.shiftLeft(container.textRange.startOffset)
    }
}
