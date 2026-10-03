package io.github.golangsupport.semantic.flow

/** Direction of a [GoDataflowAnalysis]. */
enum class GoDataflowDirection { FORWARD, BACKWARD }

/**
 * A dataflow problem over a [GoControlFlow]: a lattice of facts [F] (immutable values with structural `equals`) and transfer
 * functions. [GoDataflowSolver] finds the least fixed point starting from [bottom] everywhere and [boundary] at the entry
 * (forward) or exit (backward).
 *
 * The transfer functions must be monotone and the lattice of finite height, or the solver gives up (returns null).
 */
interface GoDataflowAnalysis<F : Any> {
    val direction: GoDataflowDirection

    /** The fact entering [GoControlFlow.entry] (forward) or leaving [GoControlFlow.exit] (backward). */
    fun boundary(flow: GoControlFlow): F

    /** The lattice bottom: the fact of a point no path reaches; the identity of [join]. */
    fun bottom(flow: GoControlFlow): F

    /** The least upper bound of two facts where paths meet. */
    fun join(a: F, b: F): F

    /**
     * The effect of [node]: forward, the fact after the node from the fact before it; backward, the fact before the node from
     * the fact after it.
     */
    fun transfer(node: GoFlowNode, fact: F): F

    /**
     * The effect of taking [edge]; applied to the fact leaving [GoFlowEdge.from] (forward) or entering [GoFlowEdge.to]
     * (backward) before joining. The place for facts learned from a condition on its [GoFlowEdge.Kind.TRUE] / `FALSE` edges.
     */
    fun transferEdge(edge: GoFlowEdge, fact: F): F = fact
}

/** The facts of a solved [GoDataflowAnalysis], in program order whatever the direction. */
class GoDataflowResult<F : Any> internal constructor(
    val flow: GoControlFlow,
    private val beforeFacts: List<F>,
    private val afterFacts: List<F>,
) {
    /** The fact at the point just before [node] executes. */
    fun before(node: GoFlowNode): F = beforeFacts[node.index]

    /** The fact at the point just after [node] executed (before any edge effect). */
    fun after(node: GoFlowNode): F = afterFacts[node.index]
}

/** A worklist solver for [GoDataflowAnalysis]. */
object GoDataflowSolver {

    /** Node visits per node after which the solver gives up (a non-monotone analysis). */
    private const val MAX_VISITS_PER_NODE = 200

    /** Solves [analysis] over [flow]; null when it does not converge. */
    fun <F : Any> solve(flow: GoControlFlow, analysis: GoDataflowAnalysis<F>): GoDataflowResult<F>? {
        val n = flow.nodes.size
        val bottom = analysis.bottom(flow)
        val input = MutableList(n) { bottom }
        val output = MutableList(n) { bottom }
        val forward = analysis.direction == GoDataflowDirection.FORWARD
        val start = if (forward) flow.entry else flow.exit
        val boundary = analysis.boundary(flow)
        // Every node is queued once (backward analyses must see nodes the exit does not reach, such as infinite loops).
        val order = if (forward) flow.nodes else flow.nodes.asReversed()
        val queue = ArrayDeque(order)
        val queued = BooleanArray(n) { true }
        var budget = n.toLong() * MAX_VISITS_PER_NODE
        while (queue.isNotEmpty()) {
            if (--budget < 0) return null
            val node = queue.removeFirst()
            queued[node.index] = false
            val incoming = if (forward) node.predecessors else node.successors
            var fact = if (node === start) boundary else bottom
            for (e in incoming) {
                val other = if (forward) e.from else e.to
                fact = analysis.join(fact, analysis.transferEdge(e, output[other.index]))
            }
            input[node.index] = fact
            val out = analysis.transfer(node, fact)
            if (out == output[node.index]) continue
            output[node.index] = out
            for (e in if (forward) node.successors else node.predecessors) {
                val next = if (forward) e.to else e.from
                if (!queued[next.index]) { queued[next.index] = true; queue.addLast(next) }
            }
        }
        return if (forward) GoDataflowResult(flow, input, output) else GoDataflowResult(flow, output, input)
    }
}
