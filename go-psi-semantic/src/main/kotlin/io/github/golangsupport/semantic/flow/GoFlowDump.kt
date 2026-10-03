package io.github.golangsupport.semantic.flow

/**
 * A text form of a [GoControlFlow] for tests and debugging: one line per node,
 * `index KIND 'first line of the element' {accesses} -> successors`, where an access is `r:x` (read), `w:x` (write) or
 * `d:x` (definition), and a successor carries `T` / `F` for condition edges. Unreachable nodes are marked with `!`.
 */
object GoFlowDump {
    fun dump(flow: GoControlFlow): String = buildString {
        for (node in flow.nodes) {
            if (!flow.isReachable(node)) append('!')
            append(node.index).append(' ').append(node.kind)
            node.element?.let { e -> append(" '").append(if (e === flow.body) "}" else e.text.lineSequence().first().trim().take(48)).append('\'') }
            if (node.accesses.isNotEmpty()) {
                append(" {")
                append(node.accesses.joinToString(" ") { a -> (when (a.kind) { GoFlowAccess.Kind.READ -> "r:"; GoFlowAccess.Kind.WRITE -> "w:"; GoFlowAccess.Kind.DEFINE -> "d:" }) + a.variable.name + if (flow.isEscaping(a.variable)) "*" else "" })
                append('}')
            }
            if (node.successors.isNotEmpty()) {
                append(" -> ")
                append(node.successors.joinToString(",") { e -> e.to.index.toString() + when (e.kind) { GoFlowEdge.Kind.TRUE -> "T"; GoFlowEdge.Kind.FALSE -> "F"; else -> "" } })
            }
            append('\n')
        }
    }
}
