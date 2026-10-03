package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.lint.GoLintPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.ide.rules.GoRuleNeed
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.semantic.flow.GoFlowAccess
import io.github.golangsupport.semantic.flow.GoReachingDefinitions
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoMethod
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoTypeRenderer
import io.github.golangsupport.semantic.types.GoUnknownType

/** Shared helpers of the batch-B3 staticcheck rules (values through local variables, go/types-style type strings, 32-bit layout). */
internal object GoB3Psi {

    val TYPES: Set<GoRuleNeed> = setOf(GoRuleNeed.TYPES)
    val TYPES_FLOW: Set<GoRuleNeed> = setOf(GoRuleNeed.TYPES, GoRuleNeed.FLOW)

    /** Where a value comes from: an expression, and the position in it when it is a multi-value call (`a, err := f()`), else -1. */
    class Origin(val expression: GoExpression, val resultIndex: Int)

    /**
     * The expression [e] stands for, the way staticcheck's SSA sees it: parentheses dropped, and a read of a local variable with exactly
     * one reaching definition replaced by the value that definition stored (a few steps deep). A variable captured by a closure or
     * written in several places stays as it is.
     */
    fun origin(e: GoExpression, ctx: GoRuleContext): Origin {
        var current: GoExpression = GoLintPsi.unparen(e) ?: e
        var index = -1
        repeat(4) {
            if (index >= 0) return Origin(current, index)
            val ref = current as? GoReferenceExpression ?: return Origin(current, index)
            if (ref.expression != null) return Origin(current, index)
            val definition = singleDefinition(ref, ctx) ?: return Origin(current, index)
            if (definition.isCompound) return Origin(current, index)
            val value = definition.value ?: return Origin(current, index)
            current = GoLintPsi.unparen(value) ?: value
            index = definition.resultIndex
        }
        return Origin(current, index)
    }

    /** The only write whose value the local-variable read [ref] can observe; null for anything else. */
    fun singleDefinition(ref: GoReferenceExpression, ctx: GoRuleContext): GoFlowAccess? {
        val flow = ctx.flowOf(ref) ?: return null
        val read = flow.accessesAt(ref).firstOrNull { !it.isWrite } ?: return null
        val reaching = GoReachingDefinitions.of(flow) ?: return null
        return reaching.definitionsOf(read).singleOrNull()
    }

    /** `go/types` `Type.String()`: named types qualified by their full import path (predeclared ones bare), or bare in [relativeTo]. */
    fun typeString(type: GoType, relativeTo: String? = null): String = GoTypeRenderer.render(type) { named ->
        val path = named.pkgPath
        when {
            (named.declaration.containingFile as? GoFile)?.packageName == "builtin" -> null
            path == null || path == relativeTo -> null
            else -> path
        }
    }

    /** The default type of an untyped constant type (`int` for an untyped integer), else [type]. */
    fun defaultType(type: GoType): GoType = if (type is GoBasicType && type.isUntyped) GoTypePredicates.defaultType(type) else type

    /** A type the checks can reason about: not unknown, not a type parameter, not invalid / untyped nil. */
    fun isKnown(type: GoType): Boolean {
        if (type === GoUnknownType || type is GoTypeParamType) return false
        if (type is GoBasicType && (type.kind == GoBasicKind.INVALID || type.kind == GoBasicKind.UNTYPED_NIL)) return false
        return type.underlying() !== GoUnknownType
    }

    /** `x` of a conversion `T(x)` ([call] resolves to a type); null for a call. */
    fun conversionOperand(call: GoCallExpr, ctx: GoRuleContext): GoExpression? {
        val ref = GoLintPsi.calleeReference(call) ?: return null
        val targets = ctx.resolve(ref)
        if (targets.isEmpty() || targets.any { it !is GoTypeSpec }) return null
        return GoStaticcheckPsi.arguments(call)?.singleOrNull()
    }

    /** The method [name] in the method set of [type]. */
    fun method(type: GoType, name: String, ctx: GoRuleContext): GoMethod? = ctx.semantic.methodsOf(type).firstOrNull { it.name == name }

    /** Whether the toolchain of the project builds for a 32-bit GOARCH (where 64-bit words are only 4-byte aligned). */
    fun is32Bit(ctx: GoRuleContext): Boolean = GoToolchainProvider.getInstance().toolchainFor(ctx.project)?.goarch in ARCH_32

    private val ARCH_32 = setOf("386", "amd64p32", "arm", "armbe", "mips", "mipsle", "mips64p32", "mips64p32le", "ppc", "riscv", "s390", "sparc")

    /** Type sizes of gc on 32-bit targets (go/types `gcSizes` with WordSize = MaxAlign = 4); null when unknown. */
    object Sizes32 {
        private const val WORD = 4L

        fun alignof(t: GoType, depth: Int = 0): Long? {
            if (depth > 32 || t is GoTypeParamType) return null
            return when (val u = t.underlying()) {
                is GoArrayType -> alignof(u.elem, depth + 1)
                is GoStructType -> u.fields.fold(1L) { acc, f -> maxOf(acc, alignof(f.type, depth + 1) ?: return null) }
                is GoBasicType -> if (u.kind.isString) WORD else basicSize(u.kind)?.coerceIn(1, WORD)
                is GoSliceType, is GoInterfaceType, is GoPointerType, is GoMapType, is GoChanType, is GoSignatureType -> WORD
                else -> null
            }
        }

        fun sizeof(t: GoType, depth: Int = 0): Long? {
            if (depth > 32 || t is GoTypeParamType) return null
            return when (val u = t.underlying()) {
                is GoBasicType -> if (u.kind.isString) 2 * WORD else basicSize(u.kind)
                is GoArrayType -> {
                    val n = u.length ?: return null
                    if (n <= 0L) return 0
                    val e = sizeof(u.elem, depth + 1) ?: return null
                    if (e != 0L && e > Long.MAX_VALUE / n) null else e * n
                }
                is GoSliceType -> 3 * WORD
                is GoInterfaceType -> 2 * WORD
                is GoStructType -> {
                    if (u.fields.isEmpty()) return 0
                    val offsets = offsetsof(u, depth) ?: return null
                    var size = sizeof(u.fields.last().type, depth + 1) ?: return null
                    if (offsets.last() > 0 && size == 0L) size = 1
                    align(offsets.last() + size, alignof(u, depth) ?: return null)
                }
                is GoPointerType, is GoMapType, is GoChanType, is GoSignatureType -> WORD
                else -> null
            }
        }

        fun offsetsof(s: GoStructType, depth: Int = 0): List<Long>? {
            var offset = 0L
            return s.fields.map { f ->
                offset = align(offset, alignof(f.type, depth + 1) ?: return null)
                val o = offset
                offset += sizeof(f.type, depth + 1) ?: return null
                o
            }
        }

        private fun basicSize(kind: GoBasicKind): Long? = when (kind) {
            GoBasicKind.BOOL, GoBasicKind.INT8, GoBasicKind.UINT8 -> 1
            GoBasicKind.INT16, GoBasicKind.UINT16 -> 2
            GoBasicKind.INT32, GoBasicKind.UINT32, GoBasicKind.FLOAT32 -> 4
            GoBasicKind.INT64, GoBasicKind.UINT64, GoBasicKind.FLOAT64, GoBasicKind.COMPLEX64 -> 8
            GoBasicKind.COMPLEX128 -> 16
            GoBasicKind.INT, GoBasicKind.UINT, GoBasicKind.UINTPTR, GoBasicKind.UNSAFE_POINTER -> WORD
            else -> null
        }

        private fun align(x: Long, a: Long): Long = (x + a - 1) / a * a
    }
}

/** Replaces [element] (not necessarily the problem element; held by a smart pointer) with [replacement]. */
internal class GoReplaceElementFix(element: com.intellij.psi.PsiElement, private val family: String, private val replacement: String) : com.intellij.codeInspection.LocalQuickFix {
    private val pointer = com.intellij.psi.SmartPointerManager.createPointer(element)

    override fun getFamilyName(): String = family

    override fun applyFix(project: com.intellij.openapi.project.Project, descriptor: com.intellij.codeInspection.ProblemDescriptor) {
        GoReplaceWithTextFix.replace(pointer.element ?: return, replacement)
    }
}
