package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionLocation
import com.intellij.codeInsight.completion.CompletionWeigher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator

/**
 * Deterministic ranking of Go candidates (registered before the platform's `prefix` weigher, so
 * it decides before prefix/camel-hump quality and statistics):
 *
 * 1. the score of a registered `GoCompletionRanker`, when one answered: it is trained on the
 *    expected-type match and the scope level among its features, so it decides alone, and scored
 *    candidates go above unscored ones;
 * 2. expected-type match (identical > assignable > none), see [GoLookupElementFactory.expectedMatch];
 * 3. scope distance: locals > parameters > keywords/snippets > package > imported > universe >
 *    unimported (members: direct > promoted).
 *
 * Elements of other contributors get a neutral weight. Alphabetical order is the last tie-break
 * ([GoAlphabeticalWeigher]).
 */
class GoCompletionWeigher : CompletionWeigher() {
    override fun weigh(element: LookupElement, location: CompletionLocation): Comparable<*> {
        val info = infoOf(element) ?: return NEUTRAL
        val score = info.rankerScore
        return if (score != null) Weight(RANKED, score, -info.level) else Weight(info.expectedMatch, 0.0, -info.level)
    }

    /** Larger is better (the platform orders completion weigher results descending). */
    data class Weight(val expected: Int, val ranker: Double, val closeness: Int) : Comparable<Weight> {
        override fun compareTo(other: Weight): Int = compareValuesBy(this, other, Weight::expected, Weight::ranker, Weight::closeness)
    }

    companion object {
        private val NEUTRAL = Weight(0, 0.0, -GoScopeLevel.PACKAGE)
        /** The `expected` bucket of ranker-scored candidates: above every expected-type match (the ranker is trained on that match). */
        private const val RANKED = 3

        fun infoOf(element: LookupElement): GoLookupInfo? {
            var e: LookupElement = element
            while (true) {
                e.getUserData(GoLookupInfo.KEY)?.let { return it }
                e = (e as? LookupElementDecorator<*>)?.delegate ?: return null
            }
        }
    }
}

/**
 * Alphabetical tie-break for Go candidates (case-insensitive, then case-sensitive); the packages of one name (`rand` of crypto/rand and
 * math/rand) by their import path, the standard library first — the order of the list when the import statistics have nothing to say.
 */
class GoAlphabeticalWeigher : CompletionWeigher() {
    override fun weigh(element: LookupElement, location: CompletionLocation): Comparable<*> {
        val info = GoCompletionWeigher.infoOf(element) ?: return Reversed("", "")
        return Reversed(element.lookupString, info.importPath?.let { (if ('.' in it.substringBefore('/')) "~" else "") + it } ?: "")
    }

    /** Descending weights sort first, so the string order is inverted. */
    data class Reversed(val text: String, val path: String) : Comparable<Reversed> {
        override fun compareTo(other: Reversed): Int {
            val c = other.text.compareTo(text, ignoreCase = true)
            if (c != 0) return c
            val cs = other.text.compareTo(text)
            return if (cs != 0) cs else other.path.compareTo(path)
        }
    }
}

/**
 * Inside the bucket of unimported candidates of [GoCompletionWeigher] (same expected-type match, same scope level, no ranker score or the
 * same one): the paths the corpus import statistics know for the name, best first ([GoLookupInfo.importStatsScore], see
 * [io.github.golangsupport.ml.GoImportStats]), then the ones it does not know in the plugin's own order. Neutral for everything else, so a
 * `GoCompletionRanker` that scored the list still decides the whole order and this only breaks its ties.
 */
class GoImportStatsWeigher : CompletionWeigher() {
    override fun weigh(element: LookupElement, location: CompletionLocation): Comparable<*> {
        val score = GoCompletionWeigher.infoOf(element)?.importStatsScore ?: return NEUTRAL
        return Weight(1, score)
    }

    /** Larger is better: a known path above an unknown one, then by the score. */
    data class Weight(val known: Int, val score: Float) : Comparable<Weight> {
        override fun compareTo(other: Weight): Int = compareValuesBy(this, other, Weight::known, Weight::score)
    }

    companion object {
        private val NEUTRAL = Weight(0, 0f)
    }
}
