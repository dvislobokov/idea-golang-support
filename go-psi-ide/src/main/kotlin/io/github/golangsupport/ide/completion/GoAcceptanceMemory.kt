package io.github.golangsupport.ide.completion

import com.intellij.codeInsight.completion.CompletionLocation
import com.intellij.codeInsight.completion.CompletionWeigher
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import io.github.golangsupport.lang.psi.GoArgumentList
import kotlin.math.ln

/**
 * What the user accepted from Go completion lists in this project ("learns from me"): a counter per context kind and lookup string,
 * persisted in the project's workspace cache (`$CACHE_FILE$`: not under `.idea`, not roamed, not in VCS). The counts are halved once
 * a month ([DECAY_MILLIS]) so that a habit of last year does not outweigh this week's, and capped ([MAX_COUNT], [MAX_ENTRIES]).
 *
 * The counts feed ranking in two ways ([io.github.golangsupport.ide.completion.api.GoCompletionRanker.acceptanceWeight]): a ranker
 * with a model gets `weight × ln(1 + count)` added to its score; with the rankers without a model [GoAcceptanceWeigher] orders by
 * the count inside the deterministic buckets (expected-type match, scope level). Both only while
 * [GoCompletionAssistSettings.acceptanceEnabled]; off, nothing is recorded or read. The engine side reads [snapshot] (ML_ACCEPTANCE.md).
 */
@Service(Service.Level.PROJECT)
@State(name = "GoCompletionAcceptance", storages = [Storage(StoragePathMacros.CACHE_FILE, roamingType = RoamingType.DISABLED)])
class GoAcceptanceMemory : PersistentStateComponent<GoAcceptanceMemory.State> {
    class State {
        /** `kind|lookupString` → acceptances. */
        var counts: MutableMap<String, Int> = HashMap()
        /** When the counts were last halved (epoch millis); 0 until the first record. */
        var lastDecay: Long = 0
    }

    private var state = State()

    /** The clock of the decay (tests move it). */
    @Volatile
    var now: () -> Long = System::currentTimeMillis

    @Synchronized
    override fun getState(): State = State().also { it.counts = HashMap(state.counts); it.lastDecay = state.lastDecay }

    @Synchronized
    override fun loadState(state: State) {
        this.state = State().also { it.counts = HashMap(state.counts); it.lastDecay = state.lastDecay }
    }

    /** Acceptances of [lookupString] in the lists of [kind] (see [kindOf]); 0 when never, or when the memory is off. */
    @Synchronized
    fun count(kind: String, lookupString: String): Int = state.counts[key(kind, lookupString)] ?: 0

    /** One more acceptance of [lookupString] in a list of [kind]. */
    @Synchronized
    fun record(kind: String, lookupString: String) {
        decayIfDue()
        val key = key(kind, lookupString)
        state.counts[key] = minOf(MAX_COUNT, (state.counts[key] ?: 0) + 1)
        if (state.counts.size > MAX_ENTRIES) {
            // the rarest go first; a tie by the key keeps the choice deterministic
            val victims = state.counts.entries.sortedWith(compareBy({ it.value }, { it.key })).take(state.counts.size - MAX_ENTRIES).map { it.key }
            victims.forEach { state.counts.remove(it) }
        }
    }

    /** Halves every count when a month has passed since the last halving (counts that reach 0 are dropped). */
    @Synchronized
    fun decayIfDue() {
        val now = now()
        if (state.lastDecay == 0L) {
            state.lastDecay = now
            return
        }
        if (now - state.lastDecay < DECAY_MILLIS) return
        // several months without a record: halve once per month passed
        var periods = ((now - state.lastDecay) / DECAY_MILLIS).toInt().coerceAtMost(MAX_DECAY_PERIODS)
        while (periods-- > 0) {
            val halved = HashMap<String, Int>()
            for ((k, v) in state.counts) if (v / 2 > 0) halved[k] = v / 2
            state.counts = halved
        }
        state.lastDecay = now
    }

    /** Forgets everything (the "Reset memory" button). */
    @Synchronized
    fun clear() {
        state = State()
    }

    /** A copy of every counter, `kind|lookupString` → count (for the engine's export and the tests). */
    @Synchronized
    fun snapshot(): Map<String, Int> = HashMap(state.counts)

    companion object {
        const val MAX_COUNT = 1000
        const val MAX_ENTRIES = 5000
        const val DECAY_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
        private const val MAX_DECAY_PERIODS = 12

        fun getInstance(project: Project): GoAcceptanceMemory = project.getService(GoAcceptanceMemory::class.java)

        fun key(kind: String, lookupString: String): String = "$kind|$lookupString"

        /**
         * The context kind of a completion position, the first half of the key: `dot` after `.`, `arg` inside a call's arguments,
         * `stmt` at a statement start, `key` for struct literal keys, `type` in type positions, `top` between declarations, `expr` elsewhere.
         */
        fun kindOf(context: GoCompletionContext): String = when (context.kind) {
            GoCompletionContext.Kind.SELECTOR, GoCompletionContext.Kind.TYPE_SELECTOR -> KIND_DOT
            GoCompletionContext.Kind.STATEMENT -> KIND_STATEMENT
            GoCompletionContext.Kind.STRUCT_KEY -> KIND_KEY
            GoCompletionContext.Kind.TYPE, GoCompletionContext.Kind.RECEIVER_TYPE -> KIND_TYPE
            GoCompletionContext.Kind.TOP_LEVEL -> KIND_TOP
            else -> if (context.reference?.parent is GoArgumentList) KIND_ARGUMENT else KIND_EXPRESSION
        }

        const val KIND_DOT = "dot"
        const val KIND_ARGUMENT = "arg"
        const val KIND_STATEMENT = "stmt"
        const val KIND_KEY = "key"
        const val KIND_TYPE = "type"
        const val KIND_TOP = "top"
        const val KIND_EXPRESSION = "expr"

        /** The additive bonus of a ranker with a model: [weight] × ln(1 + [count]). */
        fun bonus(weight: Double, count: Int): Double = if (count <= 0 || weight <= 0.0) 0.0 else weight * ln(1.0 + count)
    }
}

/**
 * Orders Go candidates by their acceptance count ([GoAcceptanceMemory]) inside the buckets of [GoCompletionWeigher] — registered
 * right before it, so the count decides only among candidates with the same expected-type match and scope level (the tiers the
 * rankers without a model keep in their score), and the frequency / recency bonus of [GoHeuristicRanker] only among equal counts.
 * Candidates whose ranker already added the bonus to its score ([GoLookupInfo.acceptanceInScore]: the ML ranker) and elements of
 * other contributors get a neutral weight, so their order is the next weigher's.
 */
class GoAcceptanceWeigher : CompletionWeigher() {
    override fun weigh(element: LookupElement, location: CompletionLocation): Comparable<*> {
        val info = GoCompletionWeigher.infoOf(element) ?: return NEUTRAL
        if (info.rankerScore == null) return Weight(info.expectedMatch, -info.level, info.acceptedCount)
        if (info.acceptanceInScore) return RANKED_NEUTRAL
        return Weight(RANKED, info.expectedMatch * 1000 - info.level, info.acceptedCount)
    }

    /** Larger is better; the first two parts mirror [GoCompletionWeigher.Weight] so that nothing but the count changes the order. */
    data class Weight(val tier: Int, val order: Int, val accepted: Int) : Comparable<Weight> {
        override fun compareTo(other: Weight): Int = compareValuesBy(this, other, Weight::tier, Weight::order, Weight::accepted)
    }

    companion object {
        private const val RANKED = 3
        private val NEUTRAL = Weight(0, -GoScopeLevel.PACKAGE, 0)
        private val RANKED_NEUTRAL = Weight(RANKED, 0, 0)
    }
}
