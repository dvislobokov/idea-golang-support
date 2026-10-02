package io.github.golangsupport.semantic.types

import com.intellij.openapi.util.RecursionManager

/** Field and method lookup (`go/types.LookupFieldOrMethod`) and method sets. */
object GoLookup {

    /** The selected field or method. [indirect] is set when a pointer was dereferenced on the way. */
    sealed class Selection {
        abstract val type: GoType
        abstract val indirect: Boolean
        /** Embedded fields traversed to reach the member (outermost first). */
        abstract val path: List<GoField>

        data class Field(val member: GoField, override val indirect: Boolean, override val path: List<GoField>) : Selection() {
            override val type: GoType get() = member.type
        }

        data class Method(val method: GoMethod, val receiver: GoType, override val indirect: Boolean, override val path: List<GoField>) : Selection() {
            override val type: GoType get() = method.signature
        }

        /** Several members with the same name at the same depth. */
        data class Ambiguous(val candidates: List<Selection>) : Selection() {
            override val type: GoType get() = GoUnknownType
            override val indirect: Boolean get() = false
            override val path: List<GoField> get() = emptyList()
        }
    }

    /**
     * Looks up [name] in [type]: methods of the named type, fields of the struct, methods of the
     * interface, then promoted members through embedded fields breadth-first. A pointer is
     * dereferenced once at the start (`*T` has the methods and fields of `T`). Unexported names are
     * only found when [pkgPath] (the package of the selector) matches the member's package.
     */
    @JvmOverloads
    fun lookupFieldOrMethod(type: GoType, name: String, pkgPath: String? = null): Selection? {
        if (name == "_" || name.isEmpty()) return null
        var start = type
        var indirect = false
        if (start is GoPointerType) {
            start = start.elem
            indirect = true
        }
        // `type P *T` has no methods, but `p.x` still selects the fields of T.
        var fieldsOnly = false
        if (start !is GoTypeParamType && start !is GoPointerType) {
            val u = start.underlying()
            if (u is GoPointerType) { start = u.elem; indirect = true; fieldsOnly = true }
        }
        // A pointer to an interface or pointer has no members.
        if (indirect && (start.underlying() is GoInterfaceType && start !is GoTypeParamType || start.underlying() is GoPointerType)) return null

        data class Entry(val type: GoType, val indirect: Boolean, val path: List<GoField>, val multiples: Boolean)
        var current = listOf(Entry(start, indirect, emptyList(), false))
        val seen = HashSet<GoNamedType>()
        var depth = 0
        while (current.isNotEmpty() && depth < 32) {
            val next = ArrayList<Entry>()
            val found = ArrayList<Selection>()
            var foundInMultiple = false
            for (e in current) {
                val before = found.size
                var t = e.type
                var ind = e.indirect
                if (t is GoNamedType) {
                    if (!seen.add(t)) continue
                    if (!(fieldsOnly && depth == 0)) for (m in t.methods) {
                        if (m.name == name && visible(m.isExported, m.pkgPath, pkgPath)) {
                            found += Selection.Method(m, t, ind, e.path)
                        }
                    }
                }
                if (t is GoTypeParamType) {
                    // Methods of the constraint interface, then the fields of the core type.
                    val bound = t.bound.underlying() as? GoInterfaceType
                    if (bound != null) for (m in bound.allMethods) if (m.name == name && visible(m.isExported, m.pkgPath, pkgPath)) found += Selection.Method(m, t, ind, e.path)
                    if (found.isNotEmpty()) { val d = found.distinctBy { (it as Selection.Method).method.declaration ?: it }; return if (d.size == 1) d[0] else Selection.Ambiguous(d) }
                }
                when (val u = if (t is GoTypeParamType) t.coreType ?: GoUnknownType else t.underlying()) {
                    is GoStructType -> {
                        for (f in u.fields) {
                            if (f.name == name && visible(f.isExported, f.pkgPath, pkgPath)) found += Selection.Field(f, ind, e.path)
                            if (f.embedded) {
                                var ft = f.type
                                var fi = ind
                                if (ft is GoPointerType) { ft = ft.elem; fi = true }
                                next += Entry(ft, fi, e.path + f, false)
                            }
                        }
                    }
                    is GoInterfaceType -> {
                        for (m in u.allMethods) {
                            if (m.name == name && visible(m.isExported, m.pkgPath, pkgPath)) found += Selection.Method(m, t, ind, e.path)
                        }
                    }
                    is GoPointerType -> { /* embedded pointer to pointer: no members */ }
                    else -> {}
                }
                if (e.multiples && found.size > before) foundInMultiple = true
            }
            if (found.isNotEmpty()) {
                // A method declared twice on the same type (via an alias receiver) is a redeclaration, not an ambiguity.
                val distinct = found.distinctBy { s -> when (s) { is Selection.Field -> s.member.declaration ?: s; is Selection.Method -> s.receiver to s.method.name; else -> s } }
                // The same embedded type reached through several paths at this depth: go/types reports the member as ambiguous.
                if (foundInMultiple) return Selection.Ambiguous(distinct + distinct[0])
                return if (distinct.size == 1) distinct[0] else Selection.Ambiguous(distinct)
            }
            // go/types `consolidateMultiples`: identical embedded types at the same depth collapse into one entry marked as multiple.
            val consolidated = ArrayList<Entry>(next.size)
            for (n in next) {
                val i = consolidated.indexOfFirst { it.type === n.type || it.type is GoNamedType && n.type is GoNamedType && GoTypePredicates.identical(it.type, n.type) }
                if (i >= 0) consolidated[i] = consolidated[i].copy(multiples = true) else consolidated += n
            }
            next.clear(); next.addAll(consolidated)
            current = next
            depth++
        }
        return null
    }

    /** A member found with a different spelling or visibility: (kind, name, package path). */
    class Alternative(val kind: String, val name: String, val pkgPath: String?)

    /**
     * go/types `lookupError` support: a field or method of [type] whose name equals [name] ignoring
     * case (including an exact match that is unexported from another package), or null.
     */
    @JvmOverloads
    fun alternativeMember(type: GoType, name: String, fieldsOnly: Boolean = false): Alternative? {
        var base = (type as? GoPointerType)?.elem ?: type
        // `type P *S`: no methods, but the fields of S are selectable.
        var fieldsOnly = fieldsOnly
        if (base !is GoPointerType && base !is GoTypeParamType) (base.underlying() as? GoPointerType)?.let { base = it.elem; fieldsOnly = true }
        val candidates = ArrayList<Alternative>()
        var t: GoType = base
        var depth = 0
        val seen = HashSet<GoNamedType>()
        val queue = ArrayDeque<GoType>()
        queue += t
        while (queue.isNotEmpty() && depth++ < 8) {
            t = queue.removeFirst()
            // go/types looks up case-insensitively in traversal order: attached methods first, then fields.
            if (t is GoNamedType) { if (!seen.add(t)) continue; if (!fieldsOnly) t.methods.forEach { candidates += Alternative("method", it.name, it.pkgPath) } }
            val u = (if (t is GoTypeParamType) t.bound else t).underlying()
            if (t is GoTypeParamType) (t.coreType as? GoStructType)?.fields?.forEach { f -> candidates += Alternative("field", f.name, f.pkgPath); if (f.embedded) queue += (f.type as? GoPointerType)?.elem ?: f.type }
            when (u) {
                is GoStructType -> u.fields.forEach { f -> candidates += Alternative("field", f.name, f.pkgPath); if (f.embedded) queue += (f.type as? GoPointerType)?.elem ?: f.type }
                is GoInterfaceType -> if (!fieldsOnly) u.allMethods.forEach { candidates += Alternative("method", it.name, it.pkgPath) }
                else -> {}
            }
            if (candidates.any { it.name.equals(name, ignoreCase = true) }) break
        }
        return candidates.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun visible(exported: Boolean, memberPkg: String?, fromPkg: String?): Boolean =
        exported || fromPkg == null || memberPkg == null || memberPkg == fromPkg

    /**
     * The method set of [type] (spec "Method sets"): for `*T` all methods, for `T` only value
     * receiver methods, promoted methods through embedded fields, all methods for interfaces.
     */
    fun methodSet(type: GoType): List<GoMethod> = RecursionManager.doPreventingRecursion(type, false) {
        val result = LinkedHashMap<String, GoMethod>()
        val pointer = type is GoPointerType
        val base = if (type is GoPointerType) type.elem else type
        collectMethods(base, pointer, result, HashSet(), 0)
        result.values.toList()
    } ?: emptyList()

    private fun collectMethods(type: GoType, pointer: Boolean, out: MutableMap<String, GoMethod>, seen: MutableSet<GoNamedType>, depth: Int) {
        if (depth > 16) return
        val level = LinkedHashMap<String, GoMethod>()
        if (type is GoNamedType) {
            if (!seen.add(type)) return
            for (m in type.methods) if ((pointer || !m.pointerReceiver) && m.name !in out) level[m.name] = m
        }
        val u = if (type is GoTypeParamType) type.underlying() else type.underlying()
        when (u) {
            is GoInterfaceType -> for (m in u.allMethods) if (m.name !in out) level[m.name] = m
            is GoStructType -> {
                // Promoted methods: collected after this level's own methods (they shadow deeper ones).
                val embedded = ArrayList<Pair<GoType, Boolean>>()
                for (f in u.fields) if (f.embedded) {
                    val ft = f.type
                    if (ft is GoPointerType) embedded += ft.elem to true else embedded += ft to (pointer || ft.underlying() is GoInterfaceType)
                }
                out.putAll(level)
                val promoted = LinkedHashMap<String, GoMethod>()
                for ((et, ep) in embedded) {
                    val sub = LinkedHashMap<String, GoMethod>()
                    collectMethods(et, ep, sub, HashSet(seen), depth + 1)
                    for ((n, m) in sub) if (n !in out) promoted[n] = m
                }
                out.putAll(promoted)
                return
            }
            else -> {}
        }
        out.putAll(level)
    }
}
