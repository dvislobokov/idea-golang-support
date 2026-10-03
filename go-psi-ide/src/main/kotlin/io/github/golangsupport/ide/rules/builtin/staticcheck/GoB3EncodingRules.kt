package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoUnknownType
import java.util.Collections
import java.util.IdentityHashMap

/** staticcheck SA1003: a value passed to `binary.Write` whose type has no fixed size (`int`, a slice of `int`, a struct with a pointer). */
class GoBinaryWriteRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1003"
    override val title: String get() = "Unsupported argument to binary.Write"
    override val description: String get() =
        "<code>binary.Write</code> encodes fixed-size values only: <code>int</code>, <code>uint</code>, <code>bool</code> before Go 1.8, pointers, " +
            "maps and strings are rejected at run time. Use <code>int32</code> / <code>int64</code> and arrays or slices of them."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        if (callee != "encoding/binary.Write") return
        val data = arguments.getOrNull(2) ?: return
        val type = GoB3Psi.defaultType(ctx.typeOf(data))
        if (!GoB3Psi.isKnown(type) || canMarshal(type)) return
        ctx.report(data, "value of type ${GoB3Psi.typeString(type)} cannot be used with binary.Write")
    }

    private fun canMarshal(type: GoType): Boolean {
        var t = type.underlying()
        if (t is GoPointerType) t = t.elem.underlying()
        t = when (t) {
            is GoSliceType -> t.elem
            is GoArrayType -> t.elem
            is GoMapType -> t.value
            is GoChanType -> t.elem
            else -> t
        }
        return valid(t, 0)
    }

    private fun valid(type: GoType, depth: Int): Boolean {
        if (depth > 16 || type is GoTypeParamType) return true
        return when (val t = type.underlying()) {
            GoUnknownType -> true
            is GoBasicType -> t.kind in FIXED
            is GoStructType -> t.fields.all { valid(it.type, depth + 1) }
            is GoArrayType -> valid(t.elem, depth + 1)
            is GoInterfaceType -> true
            else -> false
        }
    }

    private companion object {
        val NAMES = setOf("Write")
        val FIXED = setOf(GoBasicKind.UINT8, GoBasicKind.UINT16, GoBasicKind.UINT32, GoBasicKind.UINT64, GoBasicKind.INT8, GoBasicKind.INT16,
            GoBasicKind.INT32, GoBasicKind.INT64, GoBasicKind.FLOAT32, GoBasicKind.FLOAT64, GoBasicKind.COMPLEX64, GoBasicKind.COMPLEX128,
            GoBasicKind.INVALID, GoBasicKind.BOOL)
    }
}

/** staticcheck SA1026: marshaling a value of a type `encoding/json` / `encoding/xml` cannot encode (channels, functions, complex numbers, …). */
class GoMarshalUnsupportedRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA1026"
    override val title: String get() = "Cannot marshal channels or functions"
    override val description: String get() =
        "<code>json.Marshal</code>, <code>xml.Marshal</code> and <code>Encoder.Encode</code> fail at run time on channels, functions, complex " +
            "numbers and maps with unsupported keys, anywhere in the value. Exclude the field (<code>json:\"-\"</code>) or give the type a marshaler."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        val xml = when (callee) {
            "encoding/json.Marshal", "encoding/json.MarshalIndent", "encoding/json.Encoder.Encode" -> false
            "encoding/xml.Marshal", "encoding/xml.MarshalIndent", "encoding/xml.Encoder.Encode" -> true
            else -> return
        }
        val argument = arguments.firstOrNull() ?: return
        val type = GoB3Psi.defaultType(ctx.typeOf(argument))
        if (!GoB3Psi.isKnown(type)) return
        val types = GoB3MarshalTypes(ctx)
        val problem = (if (xml) types.xml(type) else types.json(type)) ?: return
        val shown = GoB3Psi.typeString(problem.type, GoAnalysisPsi.packagePath(ctx.file))
        val what = when (problem.kind) {
            GoB3MarshalTypes.Kind.UNSUPPORTED -> "unsupported"
            GoB3MarshalTypes.Kind.CYCLIC -> "cyclic"
            GoB3MarshalTypes.Kind.SILENT -> return
        }
        val via = if (problem.path == "x") "" else ", via ${problem.path}"
        ctx.report(argument, "trying to marshal $what type $shown$via")
    }

    private companion object {
        val NAMES = setOf("Marshal", "MarshalIndent", "Encode")
    }
}

/** staticcheck SA9005: marshaling a struct with fields, none of them exported and no custom marshaler: the output is empty. */
class GoNoopMarshalRule : GoStaticcheckCallRule() {
    override val id: String get() = "SA9005"
    override val title: String get() = "Marshaling a struct without exported fields"
    override val description: String get() =
        "<code>encoding/json</code> and <code>encoding/xml</code> only see exported fields: a struct whose fields are all unexported and that has " +
            "no <code>MarshalJSON</code> / <code>MarshalXML</code> / <code>MarshalText</code> (or the Unmarshal counterparts) marshals to nothing."
    override val calleeNames: Set<String> get() = NAMES

    override fun checkCallee(call: GoCallExpr, callee: String, arguments: List<GoExpression>, ctx: GoRuleContext) {
        val (index, methods) = TARGETS[callee] ?: return
        val argument = arguments.getOrNull(index) ?: return
        val type = ctx.typeOf(argument)
        if (!GoB3Psi.isKnown(type)) return
        val deref = if (type is GoPointerType) type.elem else type
        val struct = deref.underlying() as? GoStructType ?: return
        if (struct.fields.isEmpty()) return
        val seen = Collections.newSetFromMap(IdentityHashMap<GoStructType, Boolean>())
        if (hasExported(struct, seen, 0)) return
        if (methods.any { GoB3Psi.method(type, it, ctx) != null }) return
        ctx.report(argument, "struct type '${GoB3Psi.typeString(deref)}' doesn't have any exported fields, nor custom marshaling")
    }

    /** `typeutil.FlattenFields`: embedded structs (or pointers to them) contribute their own fields; anything unknown counts as exported. */
    private fun hasExported(struct: GoStructType, seen: MutableSet<GoStructType>, depth: Int): Boolean {
        if (depth > 16) return true
        if (!seen.add(struct)) return false
        for (f in struct.fields) {
            if (f.type === GoUnknownType) return true
            if (f.embedded) {
                val t = f.type.let { if (it is GoPointerType) it.elem else it }
                val inner = t.underlying()
                if (inner === GoUnknownType) return true
                if (inner is GoStructType) {
                    if (hasExported(inner, seen, depth + 1)) return true
                    continue
                }
            }
            if (f.isExported) return true
        }
        return false
    }

    private companion object {
        val JSON_M = listOf("MarshalJSON", "MarshalText")
        val XML_M = listOf("MarshalXML", "MarshalText")
        val JSON_U = listOf("UnmarshalJSON", "UnmarshalText")
        val XML_U = listOf("UnmarshalXML", "UnmarshalText")
        val TARGETS: Map<String, Pair<Int, List<String>>> = mapOf(
            "encoding/json.Marshal" to (0 to JSON_M), "encoding/json.MarshalIndent" to (0 to JSON_M), "encoding/json.Encoder.Encode" to (0 to JSON_M),
            "encoding/xml.Marshal" to (0 to XML_M), "encoding/xml.MarshalIndent" to (0 to XML_M), "encoding/xml.Encoder.Encode" to (0 to XML_M),
            "encoding/json.Unmarshal" to (1 to JSON_U), "encoding/xml.Unmarshal" to (1 to XML_U),
            "encoding/json.Decoder.Decode" to (0 to JSON_U), "encoding/xml.Decoder.Decode" to (0 to XML_U),
        )
        val NAMES = setOf("Marshal", "MarshalIndent", "Encode", "Unmarshal", "Decode")
    }
}
