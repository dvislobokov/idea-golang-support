package io.github.golangsupport.semantic.flow

import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.semantic.cache.GoBodyCache

/**
 * Reaching definitions of the tracked variables of a [GoControlFlow]: which writes ([GoFlowAccess.isWrite], including the
 * definitions of parameters at entry and `var x T` zero values) may have produced the value a variable holds at a point.
 * Escaping variables are not tracked (no definitions reach them).
 */
class GoReachingDefinitions private constructor(val flow: GoControlFlow, private val result: GoDataflowResult<GoBits>) {

    /** The writes of [variable] that reach the point just before [node]. */
    fun reaching(variable: GoNamedElement, node: GoFlowNode): List<GoFlowAccess> {
        if (!flow.isTracked(variable)) return emptyList()
        return result.before(node).indices().map { flow.accesses[it] }.filter { it.variable == variable }
    }

    /** The writes whose value [read] may observe (earlier writes of the same node included). */
    fun definitionsOf(read: GoFlowAccess): List<GoFlowAccess> {
        val accesses = read.node.accesses
        for (k in accesses.indexOf(read) - 1 downTo 0) {
            val a = accesses[k]
            if (a.variable == read.variable && a.isWrite) return listOf(a)
        }
        return reaching(read.variable, read.node)
    }

    private class Analysis(private val flow: GoControlFlow) : GoDataflowAnalysis<GoBits> {
        override val direction = GoDataflowDirection.FORWARD

        /** Write access indices by variable. */
        private val writes: Map<GoNamedElement, List<Int>> = flow.accesses.filter { it.isWrite && flow.isTracked(it.variable) }.groupBy({ it.variable }, { it.index })

        override fun boundary(flow: GoControlFlow): GoBits = GoBits.EMPTY

        override fun bottom(flow: GoControlFlow): GoBits = GoBits.EMPTY

        override fun join(a: GoBits, b: GoBits): GoBits = a.or(b)

        override fun transfer(node: GoFlowNode, fact: GoBits): GoBits {
            if (node.accesses.none { it.isWrite }) return fact
            val b = GoBits.Builder(fact)
            for (a in node.accesses) {
                if (!a.isWrite) continue
                val all = writes[a.variable] ?: continue
                for (w in all) b.clear(w)
                b.set(a.index)
            }
            return b.build()
        }
    }

    companion object {
        private val KEY = Key.create<CachedValue<GoReachingDefinitions?>>("gopsi.flow.reaching")

        /** The reaching definitions of [flow], cached with the graph; null when the solver gives up. */
        fun of(flow: GoControlFlow): GoReachingDefinitions? = GoBodyCache.cached(flow.body, KEY) {
            GoDataflowSolver.solve(flow, Analysis(flow))?.let { GoReachingDefinitions(flow, it) }
        }
    }
}
