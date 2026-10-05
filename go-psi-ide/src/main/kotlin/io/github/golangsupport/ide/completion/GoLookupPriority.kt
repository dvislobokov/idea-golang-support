package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement

/**
 * The platform orders by [PrioritizedLookupElement] priority before any weigher of ours, and other contributors of the host (the catalogue of
 * importable names) give their items 2.0 / 1.0 for a name that begins with what is typed. Without the same priority every item of this
 * module sorted after them whatever its fit (seen live: `fu` where a handler is expected listed `ast.Fun`, `expvar.Func`, ... and the
 * `func(...) {}` literal last). The scheme is the host's plus the fit of the expected type, so a fitting item wins over a bare name.
 */
object GoLookupPriority {
    const val STARTS_WITH = 2.0
    const val STARTS_WITH_IGNORING_CASE = 1.0
    const val IDENTICAL_TYPE = 1.0
    const val ASSIGNABLE_TYPE = 0.5

    fun of(prefix: String, element: LookupElement): Double {
        val info = GoCompletionWeigher.infoOf(element)
        val name = element.lookupString
        val start = when {
            prefix.isEmpty() -> 0.0
            name.startsWith(prefix) || element.allLookupStrings.any { it.startsWith(prefix) } -> STARTS_WITH
            name.startsWith(prefix, ignoreCase = true) || element.allLookupStrings.any { it.startsWith(prefix, ignoreCase = true) } -> STARTS_WITH_IGNORING_CASE
            else -> 0.0
        }
        val fit = when (info?.expectedMatch ?: 0) { 2 -> IDENTICAL_TYPE; 1 -> ASSIGNABLE_TYPE; else -> 0.0 }
        return start + fit
    }

    fun wrap(element: LookupElement, prefix: String): LookupElement = of(prefix, element).let { if (it == 0.0) element else PrioritizedLookupElement.withPriority(element, it) }
}
