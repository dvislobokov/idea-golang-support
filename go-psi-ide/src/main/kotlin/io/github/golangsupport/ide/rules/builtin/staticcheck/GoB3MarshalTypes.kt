package io.github.golangsupport.ide.rules.builtin.staticcheck

import io.github.golangsupport.ide.inspections.GoAnalysisPsi
import io.github.golangsupport.ide.inspections.GoStructTags
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoField
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoMethod
import io.github.golangsupport.semantic.types.GoNamedType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoStructType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * What `encoding/json` and `encoding/xml` would refuse to marshal, decided on types (staticcheck's `fakejson` / `fakexml`, which
 * mirror the encoders on `go/types`): the first unsupported (or, for xml, cyclic) type on the way and the path to it from the
 * value (`x.Field[0].Map[k]`). Marshalers (`MarshalJSON`, `MarshalXML`, `MarshalText`) stop the descent, as at run time; a pointer
 * method counts when the value is addressable. Types the plugin cannot resolve, and xml tag errors (vet reports those), give null.
 */
internal class GoB3MarshalTypes(private val ctx: GoRuleContext) {

    enum class Kind { UNSUPPORTED, CYCLIC, SILENT }

    class Problem(val kind: Kind, val type: GoType, val path: String)

    /** A type and whether a value of it is addressable (`fakereflect.TypeAndCanAddr`). */
    private data class TA(val type: GoType, val canAddr: Boolean) {
        val u: GoType get() = type.underlying()
        val isPtr: Boolean get() = u is GoPointerType
        val isStruct: Boolean get() = u is GoStructType
        val isSlice: Boolean get() = u is GoSliceType
        val isArray: Boolean get() = u is GoArrayType
        val isInterface: Boolean get() = u is GoInterfaceType || type is GoTypeParamType
        val name: String get() = (type as? GoNamedType)?.name ?: ""
        val fields: List<GoField> get() = (u as? GoStructType)?.fields ?: emptyList()

        fun field(i: Int): TA = TA(fields[i].type, canAddr)

        fun elem(): TA = when (val t = u) {
            is GoPointerType -> TA(t.elem, true)
            is GoSliceType -> TA(t.elem, true)
            is GoArrayType -> TA(t.elem, canAddr)
            is GoMapType -> TA(t.value, false)
            else -> throw Stop()
        }
    }

    /** Ends the walk silently (a type the plugin does not know, an xml error staticcheck leaves to other checks). */
    private class Stop : RuntimeException(null, null, false, false)

    private val seenCanAddr = HashSet<GoType>()
    private val seenCantAddr = HashSet<GoType>()
    private var steps = 0

    private fun step() {
        if (++steps > 2000) throw Stop()
    }

    private fun known(t: TA) {
        if (t.type === GoUnknownType || t.u === GoUnknownType) throw Stop()
    }

    private fun firstVisit(t: TA): Boolean = (if (t.canAddr) seenCanAddr else seenCantAddr).add(t.type)

    private fun hasMethod(type: GoType, name: String, params: Int, results: Int): GoMethod? =
        ctx.semantic.methodsOf(type).firstOrNull { it.name == name && it.signature.params.size == params && it.signature.results.size == results }

    private fun isTextMarshaler(type: GoType) = hasMethod(type, "MarshalText", 0, 2) != null
    private fun isJsonMarshaler(type: GoType) = hasMethod(type, "MarshalJSON", 0, 2) != null

    private fun marshals(t: TA, test: (GoType) -> Boolean): Boolean = test(t.type) || !t.isPtr && t.canAddr && test(GoPointerType(t.type))

    // ---- encoding/json

    fun json(type: GoType): Problem? = run { jsonValue(TA(type, false), "x") }

    private fun run(walk: () -> Problem?): Problem? = try {
        walk()
    } catch (_: Stop) {
        null
    }

    private fun jsonValue(t: TA, stack: String): Problem? {
        step()
        if (!firstVisit(t)) return null
        known(t)
        if (marshals(t, ::isJsonMarshaler) || marshals(t, ::isTextMarshaler)) return null
        return when (t.u) {
            is GoBasicType, is GoInterfaceType -> null
            is GoStructType -> jsonFields(t, stack)
            is GoMapType -> {
                val key = (t.u as GoMapType).key
                if (key !is GoTypeParamType) {
                    if (key.underlying() === GoUnknownType) throw Stop()
                    if (key.underlying() !is GoBasicType && !isTextMarshaler(key)) return Problem(Kind.UNSUPPORTED, t.type, stack)
                }
                jsonValue(t.elem(), "$stack[k]")
            }
            is GoSliceType -> {
                val elem = t.elem()
                val basic = elem.u as? GoBasicType
                if (basic != null && basic.kind == GoBasicKind.UINT8) {
                    val p = GoPointerType(elem.type)
                    if (!isJsonMarshaler(p) && !isTextMarshaler(p)) return null
                }
                jsonValue(elem, "$stack[0]")
            }
            is GoArrayType -> jsonValue(t.elem(), "$stack[0]")
            is GoPointerType -> jsonValue(t.elem(), stack)
            else -> if (t.type is GoTypeParamType) null else Problem(Kind.UNSUPPORTED, t.type, stack)
        }
    }

    private class JField(val name: String, val tagged: Boolean, val index: List<Int>)

    private fun jsonFields(t: TA, stack: String): Problem? {
        var current = listOf(t to emptyList<Int>())
        var count: Map<TA, Int> = emptyMap()
        val visited = HashSet<TA>()
        val fields = ArrayList<JField>()
        while (current.isNotEmpty()) {
            step()
            val next = ArrayList<Pair<TA, List<Int>>>()
            val nextCount = HashMap<TA, Int>()
            for ((ft0, index0) in current) {
                if (!visited.add(ft0)) continue
                known(ft0)
                for ((i, sf) in ft0.fields.withIndex()) {
                    if (sf.embedded) {
                        var et = TA(sf.type, ft0.canAddr)
                        if (et.isPtr) et = et.elem()
                        if (!sf.isExported && !et.isStruct) continue
                    } else if (!sf.isExported) continue
                    val tag = tagValue(sf, "json")
                    if (tag == "-") continue
                    var name = tag?.substringBefore(',') ?: ""
                    if (!isValidJsonTag(name)) name = ""
                    val index = index0 + i
                    var ft = TA(sf.type, ft0.canAddr)
                    if (ft.name == "" && ft.isPtr) ft = ft.elem()
                    if (name != "" || !sf.embedded || !ft.isStruct) {
                        val field = JField(name.ifEmpty { sf.name }, name != "", index)
                        fields += field
                        if ((count[ft0] ?: 0) > 1) fields += field
                        continue
                    }
                    val n = (nextCount[ft] ?: 0) + 1
                    nextCount[ft] = n
                    if (n == 1) next += ft to index
                }
            }
            current = next
            count = nextCount
        }
        val sorted = fields.sortedWith(Comparator { a, b ->
            if (a.name != b.name) return@Comparator a.name.compareTo(b.name)
            if (a.index.size != b.index.size) return@Comparator a.index.size - b.index.size
            if (a.tagged != b.tagged) return@Comparator if (a.tagged) -1 else 1
            compareIndex(a.index, b.index)
        })
        val out = ArrayList<JField>()
        var i = 0
        while (i < sorted.size) {
            var advance = 1
            while (i + advance < sorted.size && sorted[i + advance].name == sorted[i].name) advance++
            if (advance == 1) out += sorted[i]
            else {
                val a = sorted[i]
                val b = sorted[i + 1]
                if (!(a.index.size == b.index.size && a.tagged == b.tagged)) out += a
            }
            i += advance
        }
        out.sortWith { a, b -> compareIndex(a.index, b.index) }
        for (f in out) {
            jsonValue(typeByIndex(t, f.index), stack + pathByIndex(t, f.index))?.let { return it }
        }
        return null
    }

    private fun compareIndex(a: List<Int>, b: List<Int>): Int {
        for (k in a.indices) {
            if (k >= b.size) return 1
            if (a[k] != b[k]) return a[k] - b[k]
        }
        return a.size - b.size
    }

    private fun typeByIndex(t0: TA, index: List<Int>): TA {
        var t = t0
        for (i in index) {
            if (t.isPtr) t = t.elem()
            t = t.field(i)
        }
        return t
    }

    private fun pathByIndex(t0: TA, index: List<Int>): String {
        var t = t0
        val sb = StringBuilder()
        for (i in index) {
            if (t.isPtr) t = t.elem()
            sb.append('.').append(t.fields[i].name)
            t = t.field(i)
        }
        return sb.toString()
    }

    private fun isValidJsonTag(s: String): Boolean {
        if (s.isEmpty()) return false
        return s.codePoints().allMatch { c -> "!#$%&()*+-./:;<=>?@[]^_{|}~ ".indexOf(c.toChar()) >= 0 && c < 0x10000 || Character.isLetter(c) || Character.isDigit(c) }
    }

    // ---- encoding/xml

    fun xml(type: GoType): Problem? = run { xmlValue(TA(type, false), "x") }

    private fun isXmlMarshaler(type: GoType): Boolean {
        val m = ctx.semantic.methodsOf(type).firstOrNull { it.name == "MarshalXML" } ?: return false
        val p = m.signature.params
        val r = m.signature.results
        return p.size == 2 && r.size == 1 && (p[0].type as? GoPointerType)?.elem?.let { isNamed(it, "encoding/xml", "Encoder") } == true &&
            isNamed(p[1].type, "encoding/xml", "StartElement") && isError(r[0].type)
    }

    private fun isXmlAttrMarshaler(type: GoType): Boolean {
        val m = ctx.semantic.methodsOf(type).firstOrNull { it.name == "MarshalXMLAttr" } ?: return false
        val p = m.signature.params
        val r = m.signature.results
        return p.size == 1 && r.size == 2 && isNamed(p[0].type, "encoding/xml", "Name") && isNamed(r[0].type, "encoding/xml", "Attr") && isError(r[1].type)
    }

    private fun isNamed(t: GoType, path: String, name: String) = t is GoNamedType && t.name == name && t.pkgPath == path

    private fun isError(t: GoType) = GoAnalysisPsi.isError(t)

    private fun isByteSlice(t: TA) = ((t.u as? GoSliceType)?.elem?.underlying() as? GoBasicType)?.kind == GoBasicKind.UINT8
    private fun isByteArray(t: TA) = ((t.u as? GoArrayType)?.elem?.underlying() as? GoBasicType)?.kind == GoBasicKind.UINT8

    private fun xmlValue(v0: TA, stack: String): Problem? {
        step()
        if (!firstVisit(v0)) return null
        var v = v0
        val seen = HashSet<TA>()
        while (v.isInterface || v.isPtr) {
            if (v.isInterface) return null
            v = v.elem()
            if (!seen.add(v)) return Problem(Kind.CYCLIC, v.type, stack)
        }
        known(v)
        if (isXmlMarshaler(v.type) || v.canAddr && isXmlMarshaler(GoPointerType(v.type))) return null
        if (isTextMarshaler(v.type) || v.canAddr && isTextMarshaler(GoPointerType(v.type))) return null
        if ((v.isSlice || v.isArray) && !isByteArray(v) && !isByteSlice(v)) return xmlValue(v.elem(), "$stack[0]")
        val info = typeInfo(v, 0)
        for (f in info) {
            if (f.flags and ATTR == 0) continue
            xmlAttr(f.value(v), stack + pathByIndex(v, f.index))?.let { return it }
        }
        return if (v.isStruct) xmlStruct(info, v, stack) else xmlSimple(v, stack)
    }

    private fun xmlAttr(v0: TA, stack: String): Problem? {
        step()
        var v = v0
        known(v)
        if (isXmlAttrMarshaler(v.type) || v.canAddr && isXmlAttrMarshaler(GoPointerType(v.type))) return null
        if (isTextMarshaler(v.type) || v.canAddr && isTextMarshaler(GoPointerType(v.type))) return null
        if (v.isPtr) v = v.elem()
        if (v.isSlice && !isByteSlice(v)) return xmlAttr(v.elem(), "$stack[0]")
        if (isNamed(v.type, "encoding/xml", "Attr")) return null
        return xmlSimple(v, stack)
    }

    private fun xmlSimple(v: TA, stack: String): Problem? {
        known(v)
        return when (v.u) {
            is GoBasicType, is GoInterfaceType -> null
            is GoSliceType, is GoArrayType -> if ((v.elem().u as? GoBasicType)?.kind == GoBasicKind.UINT8) null else Problem(Kind.UNSUPPORTED, v.type, stack)
            else -> if (v.type is GoTypeParamType) null else Problem(Kind.UNSUPPORTED, v.type, stack)
        }
    }

    private fun xmlStruct(info: List<XField>, v: TA, stack: String): Problem? {
        for (f in info) {
            if (f.flags and ATTR != 0) continue
            var vf = f.value(v)
            when (f.flags and MODE) {
                CDATA, CHARDATA -> continue
                COMMENT -> {
                    vf = indirect(vf)
                    if (!(isByteSlice(vf) || isByteArray(vf))) throw Stop()
                    continue
                }
                INNERXML -> {
                    vf = indirect(vf)
                    val t = vf.type
                    if (t is GoSliceType && (t.elem as? GoBasicType)?.kind == GoBasicKind.UINT8 || t is GoBasicType && t.kind == GoBasicKind.STRING) continue
                }
            }
            xmlValue(vf, stack + pathByIndex(v, f.index))?.let { return it }
        }
        return null
    }

    private fun indirect(t: TA): TA {
        var v = t
        var n = 0
        while (v.isPtr && n++ < 32) v = v.elem()
        return v
    }

    private class XField(val index: List<Int>, val name: String, val xmlns: String, val flags: Int, val parents: List<String>) {
        fun withIndexPrefix(i: Int) = XField(listOf(i) + index, name, xmlns, flags, parents)

        fun value(v0: TA): TA {
            var v = v0
            for ((k, x) in index.withIndex()) {
                if (k > 0 && v.isPtr && v.elem().isStruct) v = v.elem()
                v = v.field(x)
            }
            return v
        }
    }

    private fun typeInfo(t: TA, depth: Int): List<XField> {
        if (depth > 16) throw Stop()
        val fields = ArrayList<XField>()
        if (!t.isStruct || isNamed(t.type, "encoding/xml", "Name")) return fields
        for ((i, f) in t.fields.withIndex()) {
            val tag = tagValue(f, "xml")
            if (!f.isExported && !f.embedded || tag == "-") continue
            if (f.embedded) {
                var et = TA(f.type, t.canAddr)
                if (et.isPtr) et = et.elem()
                if (et.isStruct) {
                    for (inner in typeInfo(et, depth + 1)) addField(fields, inner.withIndexPrefix(i))
                    continue
                }
            }
            val info = fieldInfo(f, listOf(i), TA(f.type, t.canAddr))
            if (f.name == XML_NAME) continue
            addField(fields, info)
        }
        return fields
    }

    private fun fieldInfo(f: GoField, index: List<Int>, type: TA): XField {
        var tag = tagValue(f, "xml") ?: ""
        var xmlns = ""
        val space = tag.indexOf(' ')
        if (space >= 0) {
            xmlns = tag.substring(0, space)
            tag = tag.substring(space + 1)
        }
        val tokens = tag.split(',')
        var flags = 0
        if (tokens.size == 1) flags = ELEMENT
        else {
            tag = tokens[0]
            for (flag in tokens.drop(1)) {
                flags = flags or when (flag) {
                    "attr" -> ATTR
                    "cdata" -> CDATA
                    "chardata" -> CHARDATA
                    "innerxml" -> INNERXML
                    "comment" -> COMMENT
                    "any" -> ANY
                    "omitempty" -> OMITEMPTY
                    else -> 0
                }
            }
            when (val mode = flags and MODE) {
                0 -> flags = flags or ELEMENT
                ATTR, CDATA, CHARDATA, INNERXML, COMMENT, ANY, ANY or ATTR -> if (f.name == XML_NAME || tag != "" && mode != ATTR) throw Stop()
                else -> throw Stop()
            }
            if (flags and MODE == ANY) flags = flags or ELEMENT
            if (flags and OMITEMPTY != 0 && flags and (ELEMENT or ATTR) == 0) throw Stop()
        }
        if (xmlns != "" && tag == "") throw Stop()
        if (f.name == XML_NAME) return XField(index, tag, xmlns, flags, emptyList())
        if (tag == "") {
            val xmlName = lookupXmlName(type)
            return if (xmlName != null) XField(index, xmlName.name, xmlName.xmlns, flags, emptyList()) else XField(index, f.name, xmlns, flags, emptyList())
        }
        val parents = tag.split('>').toMutableList()
        if (parents[0] == "") parents[0] = f.name
        if (parents.last() == "") throw Stop()
        val name = parents.last()
        if (parents.size > 1 && flags and ELEMENT == 0) throw Stop()
        if (flags and ELEMENT != 0) {
            val xmlName = lookupXmlName(type)
            if (xmlName != null && xmlName.name != name) throw Stop()
        }
        return XField(index, name, xmlns, flags, parents.dropLast(1))
    }

    private fun lookupXmlName(t0: TA): XField? {
        var t = t0
        val seen = HashSet<TA>()
        while (t.isPtr) {
            t = t.elem()
            if (!seen.add(t)) return null
        }
        if (!t.isStruct) return null
        val i = t.fields.indexOfFirst { it.name == XML_NAME }
        if (i < 0) return null
        val info = try {
            fieldInfo(t.fields[i], listOf(i), t.field(i))
        } catch (_: Stop) {
            return null
        }
        return info.takeIf { it.name != "" }
    }

    private fun addField(fields: MutableList<XField>, new: XField) {
        val conflicts = ArrayList<Int>()
        loop@ for ((i, old) in fields.withIndex()) {
            if (old.flags and MODE != new.flags and MODE) continue
            if (old.xmlns != "" && new.xmlns != "" && old.xmlns != new.xmlns) continue
            for (p in 0 until minOf(new.parents.size, old.parents.size)) if (old.parents[p] != new.parents[p]) continue@loop
            when {
                old.parents.size > new.parents.size -> if (old.parents[new.parents.size] == new.name) conflicts += i
                old.parents.size < new.parents.size -> if (new.parents[old.parents.size] == old.name) conflicts += i
                else -> if (new.name == old.name) conflicts += i
            }
        }
        if (conflicts.isEmpty()) {
            fields += new
            return
        }
        if (conflicts.any { fields[it].index.size < new.index.size }) return
        if (conflicts.any { fields[it].index.size == new.index.size }) throw Stop()
        for (c in conflicts.reversed()) fields.removeAt(c)
        fields += new
    }

    companion object {
        private const val ELEMENT = 1
        private const val ATTR = 2
        private const val CDATA = 4
        private const val CHARDATA = 8
        private const val INNERXML = 16
        private const val COMMENT = 32
        private const val ANY = 64
        private const val OMITEMPTY = 128
        private const val MODE = ELEMENT or ATTR or CDATA or CHARDATA or INNERXML or COMMENT or ANY
        private const val XML_NAME = "XMLName"

        /** `reflect.StructTag.Get(key)` of [field]'s tag; null when the tag has no such key. */
        fun tagValue(field: GoField, key: String): String? {
            val raw = field.tag ?: return null
            val text = if (raw.startsWith("`")) raw.removePrefix("`").removeSuffix("`") else GoStructTags.unquote(raw) ?: return null
            return GoStructTags.parse(text).lookup(key)
        }
    }
}
