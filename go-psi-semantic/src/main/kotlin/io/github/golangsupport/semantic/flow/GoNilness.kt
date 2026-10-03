package io.github.golangsupport.semantic.flow

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValue
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoCompositeLit
import io.github.golangsupport.lang.psi.GoConditionalExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.cache.GoBodyCache
import io.github.golangsupport.semantic.psi.GoPsiUtil.qualifier
import io.github.golangsupport.semantic.psi.GoPsiUtil.referenceName
import io.github.golangsupport.semantic.scope.GoUniverse
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType

/** What is known about whether a variable is nil. */
enum class GoNil {
    /** Nothing (or it differs between paths). */
    UNKNOWN,
    /** Nil on every path: after `x == nil` held, `x = nil`, or `var x *T`. */
    NIL,
    /** Not nil on every path: after `x != nil` held, `x = &T{}`, `new`, `make`, a composite or function literal. */
    NOT_NIL,
}

/**
 * Nil-ness of the tracked variables of nilable type (pointer, map, slice, channel, function, interface) in a [GoControlFlow]: a
 * forward must-analysis. Facts come from assignments (see [GoNil]) and from conditions `x == nil` / `x != nil` (either operand
 * order) on the edges of their condition nodes; paths meeting with different facts give [GoNil.UNKNOWN].
 */
class GoNilness private constructor(val flow: GoControlFlow, private val result: GoDataflowResult<Facts>, private val analysis: Analysis) {

    /** The fact about [variable] just before [node]; UNKNOWN for untracked variables and unreachable nodes. */
    fun before(variable: GoNamedElement, node: GoFlowNode): GoNil {
        val i = analysis.slot(variable)
        if (i < 0) return GoNil.UNKNOWN
        return result.before(node).get(i)
    }

    /**
     * The fact about the variable [reference] reads, where it is evaluated: the node's fact with earlier writes of the same node
     * applied. UNKNOWN when the reference is not a read of a tracked variable or is evaluated only conditionally within its node
     * ([GoControlFlow.isConditionallyEvaluated]).
     */
    fun at(reference: GoReferenceExpression): GoNil {
        val read = flow.accessesAt(reference).firstOrNull { !it.isWrite } ?: return GoNil.UNKNOWN
        val i = analysis.slot(read.variable)
        if (i < 0 || flow.isConditionallyEvaluated(reference)) return GoNil.UNKNOWN
        var facts = result.before(read.node)
        for (a in read.node.accesses) {
            if (a === read) break
            facts = analysis.apply(a, facts)
        }
        return facts.get(i)
    }

    /** Facts per tracked nilable variable; `values == null` is an unreached point (the bottom). */
    internal class Facts(val values: ByteArray?) {
        fun get(i: Int): GoNil = if (values == null) GoNil.UNKNOWN else GoNil.entries[values[i].toInt()]
        override fun equals(other: Any?): Boolean = other is Facts && (values?.contentEquals(other.values) ?: (other.values == null))
        override fun hashCode(): Int = values?.contentHashCode() ?: 0
    }

    internal class Analysis(private val flow: GoControlFlow) : GoDataflowAnalysis<Facts> {
        private val service = GoSemanticService.getInstance(flow.body.project)
        private val slots = HashMap<GoNamedElement, Int>()

        init {
            for (v in flow.variables) if (flow.isTracked(v) && isNilable(service.declarationType(v))) slots[v] = slots.size
        }

        /** What a write stores: a constant fact, or a copy of another slot (`x = y`). */
        private val stores = HashMap<GoFlowAccess, Pair<GoNil, Int>>()

        /** Condition node -> (slot, fact on the TRUE edge). */
        private val tests = HashMap<GoFlowNode, Pair<Int, GoNil>>()

        init {
            for (a in flow.accesses) if (a.isWrite && a.variable in slots) stores[a] = valueOf(a)
            for (n in flow.nodes) if (n.kind == GoFlowNode.Kind.CONDITION) test(n.element as? GoExpression)?.let { tests[n] = it }
        }

        fun slot(v: GoNamedElement): Int = slots[v] ?: -1

        override val direction = GoDataflowDirection.FORWARD

        override fun boundary(flow: GoControlFlow) = Facts(ByteArray(slots.size))

        override fun bottom(flow: GoControlFlow) = Facts(null)

        override fun join(a: Facts, b: Facts): Facts {
            val x = a.values ?: return b
            val y = b.values ?: return a
            if (x.contentEquals(y)) return a
            return Facts(ByteArray(x.size) { k -> if (x[k] == y[k]) x[k] else 0 })
        }

        override fun transfer(node: GoFlowNode, fact: Facts): Facts {
            var f = fact
            for (a in node.accesses) f = apply(a, f)
            return f
        }

        fun apply(a: GoFlowAccess, fact: Facts): Facts {
            val values = fact.values ?: return fact
            val (constant, from) = stores[a] ?: return fact
            val slot = slots[a.variable] ?: return fact
            val nil = if (from >= 0) GoNil.entries[values[from].toInt()] else constant
            if (values[slot].toInt() == nil.ordinal) return fact
            val copy = values.copyOf()
            copy[slot] = nil.ordinal.toByte()
            return Facts(copy)
        }

        override fun transferEdge(edge: GoFlowEdge, fact: Facts): Facts {
            if (edge.kind == GoFlowEdge.Kind.NORMAL) return fact
            val values = fact.values ?: return fact
            val (slot, onTrue) = tests[edge.from] ?: return fact
            val nil = if (edge.kind == GoFlowEdge.Kind.TRUE) onTrue else if (onTrue == GoNil.NIL) GoNil.NOT_NIL else GoNil.NIL
            val copy = values.copyOf()
            copy[slot] = nil.ordinal.toByte()
            return Facts(copy)
        }

        private fun valueOf(a: GoFlowAccess): Pair<GoNil, Int> {
            if (a.isZeroValue) return GoNil.NIL to -1
            val value = a.value ?: return GoNil.UNKNOWN to -1
            if (a.resultIndex >= 0) return GoNil.UNKNOWN to -1
            val e = GoControlFlowBuilder.unparen(value)
            if (e is GoReferenceExpression && e.qualifier == null) {
                flow.accessesAt(e).firstOrNull { !it.isWrite }?.let { r -> slots[r.variable]?.let { return GoNil.UNKNOWN to it } }
            }
            return nilnessOf(e, service) to -1
        }

        private fun test(cond: GoExpression?): Pair<Int, GoNil>? {
            val c = GoControlFlowBuilder.unparen(cond) as? GoConditionalExpr ?: return null
            val equal = when {
                c.eql != null -> true
                c.neq != null -> false
                else -> return null
            }
            val left = GoControlFlowBuilder.unparen(c.left)
            val right = GoControlFlowBuilder.unparen(c.right)
            val operand = when {
                isNilLiteral(right, service) -> left
                isNilLiteral(left, service) -> right
                else -> return null
            } as? GoReferenceExpression ?: return null
            if (operand.qualifier != null) return null
            val v = flow.accessesAt(operand).firstOrNull { !it.isWrite }?.variable ?: return null
            val slot = slots[v] ?: return null
            return slot to if (equal) GoNil.NIL else GoNil.NOT_NIL
        }
    }

    companion object {
        private val KEY = Key.create<CachedValue<GoNilness?>>("gopsi.flow.nilness")

        /** The nil-ness facts of [flow], cached with the graph; null when the solver gives up. */
        fun of(flow: GoControlFlow): GoNilness? = GoBodyCache.cached(flow.body, KEY) {
            val analysis = Analysis(flow)
            GoDataflowSolver.solve(flow, analysis)?.let { GoNilness(flow, it, analysis) }
        }

        /** Whether values of [type] can be nil. Type parameters and unknown types are not. */
        fun isNilable(type: GoType): Boolean {
            if (type is GoTypeParamType) return false
            return when (val u = type.underlying()) {
                is GoPointerType, is GoMapType, is GoSliceType, is GoChanType, is GoSignatureType, is GoInterfaceType -> true
                is GoBasicType -> u.kind == GoBasicKind.UNSAFE_POINTER
                else -> false
            }
        }

        /** Whether [e] is the predeclared `nil` (not a shadowing declaration). */
        fun isNilLiteral(e: PsiElement?, service: GoSemanticService): Boolean {
            val x = GoControlFlowBuilder.unparen(e) as? GoReferenceExpression ?: return false
            if (x.qualifier != null || x.referenceName != "nil") return false
            return service.typeOf(x) == GoBasicType.UNTYPED_NIL
        }

        /** The nil-ness of the value of [e] by its form alone: `nil`, `&x`, composite and function literals, `new`, `make`, literals. */
        fun nilnessOf(e: GoExpression?, service: GoSemanticService): GoNil {
            val x = GoControlFlowBuilder.unparen(e) ?: return GoNil.UNKNOWN
            return when {
                isNilLiteral(x, service) -> GoNil.NIL
                x is GoUnaryExpr && x.and != null -> GoNil.NOT_NIL
                x is GoCompositeLit || x is GoFunctionLit || x is GoStringLiteral || x is GoLiteral -> GoNil.NOT_NIL
                x is GoCallExpr && isBuiltinAllocation(x, service) -> GoNil.NOT_NIL
                else -> GoNil.UNKNOWN
            }
        }

        private fun isBuiltinAllocation(call: GoCallExpr, service: GoSemanticService): Boolean {
            val callee = GoControlFlowBuilder.unparen(call.expression) as? GoReferenceExpression ?: return false
            val name = callee.referenceName
            if (callee.qualifier != null || name != "new" && name != "make") return false
            val targets = service.resolve(callee)
            return targets.isEmpty() || targets.all { it is GoFunctionDeclaration && GoUniverse.isBuiltinDeclaration(it) }
        }
    }
}
