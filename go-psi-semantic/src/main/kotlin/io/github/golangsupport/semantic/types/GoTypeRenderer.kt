package io.github.golangsupport.semantic.types

import com.intellij.openapi.util.RecursionManager

/** Renders types the way gopls prints them (`[]int`, `map[string]*T`, `func(a int) error`). */
object GoTypeRenderer {

    @JvmOverloads
    fun render(type: GoType, qualified: Boolean = false): String = StringBuilder().also { write(it, type, qualified) }.toString()

    /** Renders with a custom package qualifier for named types (null = unqualified); not thread-safe across nested renders. */
    fun render(type: GoType, qualifier: (GoNamedType) -> String?): String {
        val prev = this.qualifier
        this.qualifier = qualifier
        try { return render(type, false) } finally { this.qualifier = prev }
    }

    private var qualifier: ((GoNamedType) -> String?)? = null


    private fun write(sb: StringBuilder, type: GoType, qualified: Boolean) {
        when (type) {
            is GoUnknownType -> sb.append("?")
            is GoBasicType -> sb.append(type.name)
            is GoArrayType -> { sb.append('[').append(type.length ?: "...").append(']'); write(sb, type.elem, qualified) }
            is GoSliceType -> { sb.append("[]"); write(sb, type.elem, qualified) }
            is GoPointerType -> { sb.append('*'); write(sb, type.elem, qualified) }
            is GoMapType -> { sb.append("map["); write(sb, type.key, qualified); sb.append(']'); write(sb, type.value, qualified) }
            is GoChanType -> {
                sb.append(when (type.dir) { GoChanDir.BOTH -> "chan "; GoChanDir.SEND -> "chan<- "; GoChanDir.RECV -> "<-chan " })
                val e = type.elem
                if (type.dir == GoChanDir.BOTH && e is GoChanType && e.dir == GoChanDir.RECV) { sb.append('('); write(sb, e, qualified); sb.append(')') } else write(sb, e, qualified)
            }
            is GoTupleType -> { sb.append('('); type.types.forEachIndexed { i, t -> if (i > 0) sb.append(", "); write(sb, t, qualified) }; sb.append(')') }
            is GoStructType -> {
                sb.append("struct{")
                type.fields.forEachIndexed { i, f ->
                    if (i > 0) sb.append("; ")
                    if (!f.embedded) sb.append(f.name).append(' ')
                    write(sb, f.type, qualified)
                    if (f.tag != null) sb.append(' ').append(f.tag)
                }
                sb.append('}')
            }
            is GoSignatureType -> { sb.append("func"); writeSignature(sb, type, qualified) }
            is GoInterfaceType -> {
                if (type.isEmpty) { sb.append("interface{}"); return }
                if (type.comparableMarker && type.methods.isEmpty() && type.embedded.isEmpty()) { sb.append("comparable"); return }
                if (!type.comparableMarker && type.methods.isEmpty() && type.embedded.size == 1 && (type.embedded[0] as? GoInterfaceType)?.let { it.comparableMarker && it.methods.isEmpty() && it.embedded.isEmpty() } == true) { sb.append("comparable"); return }
                if (type.implicit && type.methods.isEmpty() && type.embedded.size == 1) { write(sb, type.embedded[0], qualified); return }
                sb.append("interface{")
                var first = true
                if (type.comparableMarker) { sb.append("comparable"); first = false }
                for (m in type.methods) { if (!first) sb.append("; "); first = false; sb.append(m.name); writeSignature(sb, m.signature, qualified) }
                for (e in type.embedded) { if (!first) sb.append("; "); first = false; write(sb, e, qualified) }
                sb.append('}')
            }
            is GoNamedType -> {
                if (qualified) type.pkgPath?.let { sb.append(it.substringAfterLast('/')).append('.') }
                else qualifier?.invoke(type)?.let { sb.append(it).append('.') }
                sb.append(type.name)
                if (type.typeArgs.isNotEmpty()) { sb.append('['); type.typeArgs.forEachIndexed { i, t -> if (i > 0) sb.append(", "); write(sb, t, qualified) }; sb.append(']') }
            }
            is GoTypeParamType -> sb.append(type.name)
            is GoUnionType -> type.terms.forEachIndexed { i, t -> if (i > 0) sb.append(" | "); if (t.tilde) sb.append('~'); write(sb, t.type, qualified) }
        }
    }

    private fun writeSignature(sb: StringBuilder, sig: GoSignatureType, qualified: Boolean) {
        if (sig.typeParams.isNotEmpty()) {
            sb.append('[')
            sig.typeParams.forEachIndexed { i, p ->
                if (i > 0) sb.append(", ")
                sb.append(p.name).append(' ')
                val bound = RecursionManager.doPreventingRecursion(p, false) { render(p.bound, qualified) } ?: "?"
                sb.append(if (bound == "interface{}") "any" else bound)
            }
            sb.append(']')
        }
        sb.append('(')
        sig.params.forEachIndexed { i, p ->
            if (i > 0) sb.append(", ")
            if (p.name != null) sb.append(p.name).append(' ')
            if (sig.variadic && i == sig.params.lastIndex) { sb.append("..."); write(sb, (p.type as? GoSliceType)?.elem ?: p.type, qualified) } else write(sb, p.type, qualified)
        }
        sb.append(')')
        when (sig.results.size) {
            0 -> {}
            1 -> if (sig.results[0].name == null) { sb.append(' '); write(sb, sig.results[0].type, qualified) } else { sb.append(" ("); sb.append(sig.results[0].name).append(' '); write(sb, sig.results[0].type, qualified); sb.append(')') }
            else -> { sb.append(" ("); sig.results.forEachIndexed { i, r -> if (i > 0) sb.append(", "); if (r.name != null) sb.append(r.name).append(' '); write(sb, r.type, qualified) }; sb.append(')') }
        }
    }
}
