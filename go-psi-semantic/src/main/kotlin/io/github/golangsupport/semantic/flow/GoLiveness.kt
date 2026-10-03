package io.github.golangsupport.semantic.flow

import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.semantic.cache.GoBodyCache

/** What can happen next to the value a variable holds at some point, on at least one path. */
enum class GoValueFate {
    /** The value is read. */
    READ,
    /** The variable is assigned again before any read. */
    OVERWRITTEN,
    /** The function returns (or the body ends) before any read or write. Paths ending in a panic or `os.Exit` count for nothing. */
    RETURNED,
}

/**
 * Liveness of the tracked variables of a [GoControlFlow], extended with *value fates*: for every point and variable, which of
 * [GoValueFate] can come first on some path from there. A value is live when [GoValueFate.READ] is among its fates.
 *
 * Named results are read at the exit (the caller sees them) and by a bare `return`; `return x` writes them. Escaping variables
 * are never tracked: their fates are reported as `{READ}`, so checks built on this stay silent about them.
 */
class GoLiveness private constructor(val flow: GoControlFlow, private val result: GoDataflowResult<GoBits>) {

    /** The fates of the value [access] stores (a write or definition), or of the value [access] reads. */
    fun fatesAfter(access: GoFlowAccess): Set<GoValueFate> {
        val i = flow.indexOf(access.variable)
        if (i < 0 || !flow.isTracked(access.variable)) return READ_ONLY
        val accesses = access.node.accesses
        for (k in accesses.indexOf(access) + 1 until accesses.size) {
            val next = accesses[k]
            if (next.variable == access.variable) return if (next.isWrite) OVERWRITTEN_ONLY else READ_ONLY
        }
        return decode(afterNode(access.node), i)
    }

    /** Whether the value [access] stores is read on some path. */
    fun isReadAfter(access: GoFlowAccess): Boolean = GoValueFate.READ in fatesAfter(access)

    /** The fates of [variable]'s value just before [node]. */
    fun fatesBefore(variable: GoNamedElement, node: GoFlowNode): Set<GoValueFate> {
        val i = flow.indexOf(variable)
        if (i < 0 || !flow.isTracked(variable)) return READ_ONLY
        return decode(result.before(node), i)
    }

    /** Whether [variable] is live (read on some path before being written) just before [node]. */
    fun isLiveBefore(variable: GoNamedElement, node: GoFlowNode): Boolean = GoValueFate.READ in fatesBefore(variable, node)

    private fun afterNode(node: GoFlowNode): GoBits = if (stops(node)) GoBits.EMPTY else result.after(node)

    private fun decode(bits: GoBits, i: Int): Set<GoValueFate> {
        val out = java.util.EnumSet.noneOf(GoValueFate::class.java)
        for (f in GoValueFate.entries) if (bits[i * FATES + f.ordinal]) out += f
        return out
    }

    private class Analysis(private val flow: GoControlFlow) : GoDataflowAnalysis<GoBits> {
        override val direction = GoDataflowDirection.BACKWARD

        override fun boundary(flow: GoControlFlow): GoBits {
            val b = GoBits.Builder()
            flow.variables.forEachIndexed { i, v ->
                if (!flow.isTracked(v)) return@forEachIndexed
                b.set(i * FATES + (if (v in flow.namedResults) GoValueFate.READ else GoValueFate.RETURNED).ordinal)
            }
            return b.build()
        }

        override fun bottom(flow: GoControlFlow): GoBits = GoBits.EMPTY

        override fun join(a: GoBits, b: GoBits): GoBits = a.or(b)

        override fun transfer(node: GoFlowNode, fact: GoBits): GoBits {
            if (node.accesses.isEmpty() && !stops(node)) return fact
            val b = GoBits.Builder(if (stops(node)) GoBits.EMPTY else fact)
            for (k in node.accesses.indices.reversed()) {
                val a = node.accesses[k]
                if (!flow.isTracked(a.variable)) continue
                val i = flow.indexOf(a.variable)
                for (f in 0 until FATES) b.clear(i * FATES + f)
                b.set(i * FATES + (if (a.isWrite) GoValueFate.OVERWRITTEN else GoValueFate.READ).ordinal)
            }
            return b.build()
        }
    }

    companion object {
        private const val FATES = 3
        private val READ_ONLY: Set<GoValueFate> = java.util.Collections.unmodifiableSet(java.util.EnumSet.of(GoValueFate.READ))
        private val OVERWRITTEN_ONLY: Set<GoValueFate> = java.util.Collections.unmodifiableSet(java.util.EnumSet.of(GoValueFate.OVERWRITTEN))
        private val KEY = Key.create<CachedValue<GoLiveness?>>("gopsi.flow.liveness")

        /** Paths through a panic or a terminating call never reach a return. */
        private fun stops(node: GoFlowNode) = node.kind == GoFlowNode.Kind.PANIC || node.kind == GoFlowNode.Kind.TERMINATE

        /** The liveness of [flow], cached with the graph; null when the solver gives up. */
        fun of(flow: GoControlFlow): GoLiveness? = GoBodyCache.cached(flow.body, KEY) {
            GoDataflowSolver.solve(flow, Analysis(flow))?.let { GoLiveness(flow, it) }
        }
    }
}

/** An immutable bit set with structural equality: the fact type of the bit-vector analyses. */
class GoBits private constructor(private val words: LongArray) {

    operator fun get(i: Int): Boolean = i / 64 < words.size && words[i / 64] and (1L shl (i % 64)) != 0L

    fun or(other: GoBits): GoBits {
        if (other.words.isEmpty() || other === this) return this
        if (words.isEmpty()) return other
        val out = LongArray(maxOf(words.size, other.words.size))
        for (k in out.indices) out[k] = words.getOrElse(k) { 0L } or other.words.getOrElse(k) { 0L }
        return GoBits(trim(out))
    }

    /** Indices of the set bits. */
    fun indices(): List<Int> {
        val out = ArrayList<Int>()
        for (k in words.indices) {
            var w = words[k]
            while (w != 0L) {
                val b = java.lang.Long.numberOfTrailingZeros(w)
                out += k * 64 + b
                w = w and (w - 1)
            }
        }
        return out
    }

    override fun equals(other: Any?): Boolean = other is GoBits && words.contentEquals(other.words)
    override fun hashCode(): Int = words.contentHashCode()
    override fun toString(): String = indices().toString()

    /** A mutable copy used inside one transfer function. */
    class Builder(from: GoBits = EMPTY) {
        private var words = from.words.copyOf()

        fun set(i: Int) {
            if (i / 64 >= words.size) words = words.copyOf(i / 64 + 1)
            words[i / 64] = words[i / 64] or (1L shl (i % 64))
        }

        fun clear(i: Int) {
            if (i / 64 < words.size) words[i / 64] = words[i / 64] and (1L shl (i % 64)).inv()
        }

        fun build(): GoBits = GoBits(trim(words))
    }

    companion object {
        val EMPTY = GoBits(LongArray(0))

        private fun trim(words: LongArray): LongArray {
            var n = words.size
            while (n > 0 && words[n - 1] == 0L) n--
            return if (n == words.size) words else words.copyOf(n)
        }
    }
}
