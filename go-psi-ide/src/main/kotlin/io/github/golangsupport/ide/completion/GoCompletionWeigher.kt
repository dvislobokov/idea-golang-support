package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionLocation
import com.intellij.codeInsight.completion.CompletionWeigher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator

/**
 * Deterministic ranking of Go candidates (registered before the platform's `prefix` weigher, so
 * it decides before prefix/camel-hump quality and statistics):
 *
 * 1. expected-type match (identical > assignable > none), see [GoLookupElementFactory.expectedMatch];
 * 2. the score of a registered `GoCompletionRanker`, when one answered;
 * 3. scope distance: locals > parameters > keywords/snippets > package > imported > universe >
 *    unimported (members: direct > promoted).
 *
 * Elements of other contributors get a neutral weight. Alphabetical order is the last tie-break
 * ([GoAlphabeticalWeigher]).
 */
class GoCompletionWeigher : CompletionWeigher() {
    override fun weigh(element: LookupElement, location: CompletionLocation): Comparable<*> {
        val info = infoOf(element) ?: return NEUTRAL
        return Weight(info.expectedMatch, info.rankerScore ?: 0.0, -info.level)
    }

    /** Larger is better (the platform orders completion weigher results descending). */
    data class Weight(val expected: Int, val ranker: Double, val closeness: Int) : Comparable<Weight> {
        override fun compareTo(other: Weight): Int = compareValuesBy(this, other, Weight::expected, Weight::ranker, Weight::closeness)
    }

    companion object {
        private val NEUTRAL = Weight(0, 0.0, -GoScopeLevel.PACKAGE)

        fun infoOf(element: LookupElement): GoLookupInfo? {
            var e: LookupElement = element
            while (true) {
                e.getUserData(GoLookupInfo.KEY)?.let { return it }
                e = (e as? LookupElementDecorator<*>)?.delegate ?: return null
            }
        }
    }
}

/** Alphabetical tie-break for Go candidates (case-insensitive, then case-sensitive). */
class GoAlphabeticalWeigher : CompletionWeigher() {
    override fun weigh(element: LookupElement, location: CompletionLocation): Comparable<*> {
        if (GoCompletionWeigher.infoOf(element) == null) return Reversed("")
        return Reversed(element.lookupString)
    }

    /** Descending weights sort first, so the string order is inverted. */
    data class Reversed(val text: String) : Comparable<Reversed> {
        override fun compareTo(other: Reversed): Int {
            val c = other.text.compareTo(text, ignoreCase = true)
            return if (c != 0) c else other.text.compareTo(text)
        }
    }
}
