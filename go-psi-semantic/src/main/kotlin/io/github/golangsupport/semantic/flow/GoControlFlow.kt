package io.github.golangsupport.semantic.flow

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoAndExpr
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoDeferStatement
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoOrExpr
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.semantic.cache.GoBodyCache
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.psi.GoPsiUtil.block

/**
 * The control-flow graph of one function body: a function or method declaration, or a function literal (a literal has its own
 * graph; variables it captures from the enclosing function are *escaping* there and untracked here).
 *
 * Nodes ([GoFlowNode]) are simple statements, the atomic conditions of `if` / `for` / tagless `switch` (short-circuit `&&`, `||`
 * and `!` are split, so `x == nil || x.f` is two condition nodes with [GoFlowEdge.Kind.TRUE] / [GoFlowEdge.Kind.FALSE] edges),
 * `range` heads, `case` tests, `select` communications, `return`s and calls that do not return. Every `return` (and the end of
 * the body) goes through [deferred] — the point where deferred calls run — to [exit]; `panic(…)` and `runtime.Goexit` / `t.Fatal`
 * run the deferred calls too ([GoFlowNode.Kind.PANIC]); `os.Exit` and `log.Fatal*` go straight to [exit]
 * ([GoFlowNode.Kind.TERMINATE]).
 *
 * Each node lists its variable [GoFlowNode.accesses] in evaluation order (reads of the right side before the writes of an
 * assignment). Variables are the parameters, results, receiver and locals of this function, identified by their declaring
 * element after `:=` redeclarations are folded into the first declaration. A variable whose address is taken (`&x`, a method
 * call that may take `&x`, slicing an array), that a nested function literal captures, or whose references do not resolve
 * cleanly is *escaping* ([isEscaping]): analyses make no claims about it.
 *
 * The graph is built lazily per body and cached in the body store ([GoBodyCache]). [of] returns null when the body has a syntax
 * error or a construct the builder does not model (a `goto` into a block, a `break` without a target, a huge body): checks built
 * on the graph then stay silent for that function.
 */
class GoControlFlow internal constructor(
    /** The function: a [GoFunctionOrMethodDeclaration] or a [GoFunctionLit]. */
    val owner: PsiElement,
    /** The body block of [owner]. */
    val body: GoBlock,
    /** All nodes; [GoFlowNode.index] is the position in this list. */
    val nodes: List<GoFlowNode>,
    /** The entry node: defines the parameters, results and receiver. */
    val entry: GoFlowNode,
    /** Deferred calls run here: reached from every `return`, the end of the body and every panic. */
    val deferred: GoFlowNode,
    /** The single exit node. */
    val exit: GoFlowNode,
    /** The variables of this function (escaping ones included), in declaration order. */
    val variables: List<GoNamedElement>,
    /** The named results of [owner], in order (empty for unnamed results). */
    val namedResults: List<GoNamedElement>,
    /** The `defer` statements of this body (not of nested literals), in source order. */
    val deferStatements: List<GoDeferStatement>,
    private val escaping: Set<GoNamedElement>,
    private val nodeByElement: Map<PsiElement, GoFlowNode>,
    private val accessByElement: Map<PsiElement, List<GoFlowAccess>>,
) {
    private val variableIndex: Map<GoNamedElement, Int> = variables.withIndex().associate { it.value to it.index }

    /** Accesses of every variable, by variable. */
    private val accessesByVariable: Map<GoNamedElement, List<GoFlowAccess>> = nodes.flatMap { it.accesses }.groupBy { it.variable }

    /** All accesses of all nodes, in node order; [GoFlowAccess.index] is the position in this list. */
    val accesses: List<GoFlowAccess> = nodes.flatMap { it.accesses }

    /** Nodes reachable from [entry]. */
    private val reachable: BooleanArray = BooleanArray(nodes.size).also { seen ->
        val stack = ArrayDeque<GoFlowNode>()
        stack.addLast(entry)
        seen[entry.index] = true
        while (stack.isNotEmpty()) {
            for (e in stack.removeLast().successors) if (!seen[e.to.index]) { seen[e.to.index] = true; stack.addLast(e.to) }
        }
    }

    /** Position of [variable] in [variables], or -1 when it is not a variable of this function. */
    fun indexOf(variable: GoNamedElement): Int = variableIndex[variable] ?: -1

    /** Whether [variable] belongs to this function and is not escaping: the variables analyses reason about. */
    fun isTracked(variable: GoNamedElement): Boolean = variable in variableIndex && variable !in escaping

    /** Whether [variable] is a variable of this function whose value may change or be read outside the graph (see the class comment). */
    fun isEscaping(variable: GoNamedElement): Boolean = variable in escaping

    /** Whether [node] can be reached from [entry]. */
    fun isReachable(node: GoFlowNode): Boolean = reachable[node.index]

    /** The accesses of [variable], in node order. */
    fun accessesOf(variable: GoNamedElement): List<GoFlowAccess> = accessesByVariable[variable] ?: emptyList()

    /**
     * The accesses at [element]: a reference expression (one read or write; two for `x += 1`, `x++`), or a variable / parameter
     * definition (its definition).
     */
    fun accessesAt(element: PsiElement): List<GoFlowAccess> = accessByElement[element] ?: emptyList()

    /** The variable of this function [reference] reads or writes, or null (not a variable, or one of an enclosing function). */
    fun variableOf(reference: GoReferenceExpression): GoNamedElement? = accessesAt(reference).firstOrNull()?.variable

    /** The node evaluating [element]: the innermost node whose element contains it, or null (outside the body, or in a nested literal). */
    fun nodeOf(element: PsiElement): GoFlowNode? {
        var e: PsiElement? = element
        while (e != null && e !== owner) {
            nodeByElement[e]?.let { return it }
            if (e is GoFunctionLit) return null
            e = e.parent
        }
        return null
    }

    /**
     * The first accesses of [access]'s variable on the paths that leave [access] (later accesses of the same node first): the reads
     * that may observe a value it wrote, or the writes that may replace it. Paths reaching the exit without one add nothing; use
     * [GoLiveness.fatesAfter] for that. A depth-first walk of the graph, linear in its size.
     */
    fun nextAccesses(access: GoFlowAccess): List<GoFlowAccess> {
        val v = access.variable
        access.node.accesses.let { list -> list.drop(list.indexOf(access) + 1).firstOrNull { it.variable == v }?.let { return listOf(it) } }
        val out = LinkedHashSet<GoFlowAccess>()
        val seen = BooleanArray(nodes.size)
        val stack = ArrayDeque<GoFlowNode>()
        access.node.successors.forEach { stack.addLast(it.to) }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (seen[node.index]) continue
            seen[node.index] = true
            val first = node.accesses.firstOrNull { it.variable == v }
            if (first != null) { out += first; continue }
            node.successors.forEach { stack.addLast(it.to) }
        }
        return out.toList()
    }

    /** The node whose [GoFlowNode.element] is exactly [element], or null. */
    fun nodeFor(element: PsiElement): GoFlowNode? = nodeByElement[element]

    /**
     * Whether [element] is evaluated only conditionally within its node: inside the right operand of a `&&` / `||` that the graph
     * did not split (an assignment `ok := p != nil && p.f`). Facts at the node's entry do not hold there.
     */
    fun isConditionallyEvaluated(element: PsiElement): Boolean {
        val node = nodeOf(element) ?: return false
        val top = node.element ?: return false
        var child: PsiElement = element
        var e: PsiElement? = element.parent
        while (e != null && child !== top) {
            if ((e is GoAndExpr || e is GoOrExpr) && (e as GoBinaryExpr).right === child) return true
            child = e
            e = e.parent
        }
        return false
    }

    override fun toString(): String = GoFlowDump.dump(this)

    companion object {
        private val FLOW_KEY = Key.create<CachedValue<GoControlFlow?>>("gopsi.flow.graph")

        /** The graph of a function or method declaration, or null (no body, syntax errors, unsupported constructs). */
        fun of(function: GoFunctionOrMethodDeclaration): GoControlFlow? = function.block?.let { forBody(function, it) }

        /** The graph of a function literal, or null (see [of]). */
        fun of(literal: GoFunctionLit): GoControlFlow? = literal.block?.let { forBody(literal, it) }

        /** The graph of the innermost function (declaration or literal) containing [element], or null. */
        fun enclosing(element: PsiElement): GoControlFlow? = when (val owner = GoPsiUtil.functionOwner(element)) {
            is GoFunctionOrMethodDeclaration -> of(owner)
            is GoFunctionLit -> of(owner)
            else -> null
        }

        private fun forBody(owner: PsiElement, body: GoBlock): GoControlFlow? =
            GoBodyCache.cached(body, FLOW_KEY) { GoControlFlowBuilder(owner, body).build() }
    }
}

/** A node of [GoControlFlow]. */
class GoFlowNode internal constructor(
    /** Position in [GoControlFlow.nodes]. */
    val index: Int,
    val kind: Kind,
    /**
     * The PSI the node evaluates: a statement, a condition expression, a `range` clause, a `case` clause, a comm case, the
     * `switch` tag or type-switch guard; the [GoControlFlow.body] for the implicit return at its end; null for
     * [Kind.ENTRY], [Kind.DEFERRED], [Kind.EXIT] and loop heads.
     */
    val element: PsiElement?,
) {
    /** What a node stands for. */
    enum class Kind {
        /** Function entry: defines parameters, results and the receiver. */
        ENTRY,
        /** Where deferred calls run. */
        DEFERRED,
        /** Function exit. */
        EXIT,
        /** A simple statement (assignment, declaration, expression, send, inc/dec, `go`, `defer`) or a `switch` tag / guard. */
        STATEMENT,
        /** An atomic condition: [GoFlowEdge.Kind.TRUE] and [GoFlowEdge.Kind.FALSE] successors. */
        CONDITION,
        /** A `range` head: assigns the iteration variables; successors are the body and the loop exit. */
        RANGE,
        /** A `case` test of a tagged or type switch ([GoFlowEdge.Kind.TRUE]: matched), or a type-switch clause binding its variable. */
        CASE,
        /** A `select` communication (comm case) chosen by the select. */
        COMM,
        /** A join point without effects: loop heads, labels, `select`. */
        JOIN,
        /** An explicit `return` (element: the statement) or the implicit one at the end of the body (element: the body). */
        RETURN,
        /** `panic(…)`, `runtime.Goexit()`, `t.Fatal…` / `FailNow` / `Skip…`: leaves the function running deferred calls. */
        PANIC,
        /** `os.Exit`, `log.Fatal…`: ends the process; deferred calls do not run. */
        TERMINATE,
    }

    internal val succ = ArrayList<GoFlowEdge>(2)
    internal val pred = ArrayList<GoFlowEdge>(2)

    /** Outgoing edges. */
    val successors: List<GoFlowEdge> get() = succ

    /** Incoming edges. */
    val predecessors: List<GoFlowEdge> get() = pred

    /** Variable accesses of this node in evaluation order. */
    var accesses: List<GoFlowAccess> = emptyList()
        internal set

    override fun toString(): String = "$index:$kind" + (element?.let { " '" + it.text.lineSequence().first().take(40) + "'" } ?: "")
}

/** An edge of [GoControlFlow]. */
class GoFlowEdge internal constructor(val from: GoFlowNode, val to: GoFlowNode, val kind: Kind) {
    enum class Kind {
        NORMAL,
        /** The condition (or `case` test) of [from] holds. */
        TRUE,
        /** The condition (or `case` test) of [from] does not hold. */
        FALSE,
    }

    override fun toString(): String = "${from.index}->${to.index}" + if (kind == Kind.NORMAL) "" else ":$kind"
}

/** One read or write of a variable at a node. */
class GoFlowAccess internal constructor(
    /** The variable (its first declaration: `:=` redeclarations are folded). */
    val variable: GoNamedElement,
    val kind: Kind,
    /** The reference expression, or the definition (`x := …`, `var x`, parameters, range and type-switch variables). */
    val element: PsiElement,
    /** The node of the access. */
    val node: GoFlowNode,
    /**
     * For writes: the expression whose value is stored — the matching right-hand side, or the single multi-value right-hand side
     * (`a, err := f()`, then [resultIndex] is the position). Null for zero-value declarations, parameters, range / receive /
     * type-switch variables and compound assignments (`x += 1`, `x++`).
     */
    val value: GoExpression?,
    /** Position in a multi-value right-hand side, or -1 when [value] is a single value. */
    val resultIndex: Int,
    /** `x += …`, `x++`: the write follows a read of the same variable. */
    val isCompound: Boolean,
) {
    /** Position in [GoControlFlow.accesses]. */
    var index: Int = -1
        internal set

    enum class Kind {
        READ,
        /** `=`, compound assignments, receive / range with `=`. */
        WRITE,
        /** `:=`, `var`, parameters at entry, range / receive with `:=`, type-switch bindings. */
        DEFINE,
    }

    /** A write or definition. */
    val isWrite: Boolean get() = kind != Kind.READ

    /** `var x T` without a value: the variable starts at its zero value. */
    val isZeroValue: Boolean get() = kind == Kind.DEFINE && value == null && node.kind == GoFlowNode.Kind.STATEMENT

    override fun toString(): String = "$kind ${variable.name}@${element.textOffset}"
}
