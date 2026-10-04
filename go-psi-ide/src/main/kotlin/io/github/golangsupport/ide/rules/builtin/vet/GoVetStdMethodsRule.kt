package io.github.golangsupport.ide.rules.builtin.vet

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.ide.rules.GoRuleContext
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoSpecType
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoLookup
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * govet `stdmethods`: a method named like one of the well-known interfaces of the standard library (`String` is not among them;
 * `Format`, `ReadFrom`, `MarshalJSON`, `Is`/`As`/`Unwrap` of errors …) whose signature does not match it, so `fmt`, `io`, the encoders
 * or `errors` silently do not call it. Methods of interface types are checked too.
 */
class GoVetStdMethodsRule : GoVetFileRule() {
    override val id: String get() = "govet:stdmethods"
    override val title: String get() = "Well-known method with a non-standard signature"
    override val description: String get() =
        "govet <code>stdmethods</code>: <code>func (T) WriteTo(w io.Writer)</code> looks like <code>io.WriterTo</code> but does not implement " +
            "it, so <code>io.Copy</code> never calls it. The signatures of <code>Format</code>, <code>GobEncode</code>, <code>MarshalJSON</code>, " +
            "<code>ReadByte</code>, <code>ReadFrom</code>, <code>Scan</code>, <code>Seek</code>, <code>UnmarshalXML</code>, <code>WriteTo</code>, " +
            "error <code>Is</code> / <code>As</code> / <code>Unwrap</code> and others are checked."

    override fun checkFile(file: GoFile, ctx: GoRuleContext) {
        for (decl in file.methods) {
            val name = decl.name ?: continue
            if (name !in CANONICAL && name != "Unwrap") continue
            check(decl, decl.identifier ?: continue, name, ctx)
        }
        for (spec in PsiTreeUtil.findChildrenOfType(file, GoMethodSpec::class.java)) {
            val name = spec.name ?: continue
            if (name !in CANONICAL && name != "Unwrap") continue
            check(spec, spec.identifier ?: continue, name, ctx)
        }
    }

    private fun check(decl: GoNamedElement, id: PsiElement, name: String, ctx: GoRuleContext) {
        val sign = ctx.semantic.declarationType(decl) as? GoSignatureType ?: return
        val params = sign.params.map { it.type }
        val results = sign.results.map { it.type }
        if (params.any { it is GoUnknownType } || results.any { it is GoUnknownType }) return
        if (name == "WriteTo" && params.size > 1) return
        if (name == "Is" || name == "As" || name == "Unwrap") {
            if (!receiverIsError(decl, ctx)) return
        }
        if (name == "Unwrap") {
            if (params.isEmpty() && results.size == 1) {
                val t = GoVetPsi.nameTypeString(results[0])
                if (t == "error" || t == "[]error") return
            }
            ctx.report(id, "method Unwrap() should have signature Unwrap() error or Unwrap() []error")
            return
        }
        val expect = CANONICAL.getValue(name)
        if (!match(expect.first, params, "=") || !match(expect.second, results, "=")) return
        if (match(expect.first, params, "") && match(expect.second, results, "")) return
        var expectFmt = name + "(" + join(expect.first) + ")"
        if (expect.second.size == 1) expectFmt += " " + join(expect.second) else if (expect.second.size > 1) expectFmt += " (" + join(expect.second) + ")"
        val actual = name + GoVetPsi.nameTypeString(GoSignatureType(sign.params, sign.results, sign.variadic)).removePrefix("func")
        ctx.report(id, "method $actual should have signature $expectFmt")
    }

    /** `types.Implements(recv, error)`; for an interface method, the interface it is declared in (named or literal). */
    private fun receiverIsError(decl: GoNamedElement, ctx: GoRuleContext): Boolean {
        val recv: GoType = when (decl) {
            is GoMethodDeclaration -> {
                val sign = ctx.semantic.declarationType(decl) as? GoSignatureType
                sign?.receiver?.type ?: return false
            }
            is GoMethodSpec -> {
                val iface = decl.parent as? GoInterfaceType ?: return false
                var p = iface.parent
                while (p is GoSpecType) p = p.parent
                val spec = p as? GoTypeSpec
                if (spec != null) ctx.semantic.declarationType(spec) else return iface.methodSpecList.any { it.name == "Error" }
            }
            else -> return false
        }
        return implementsError(recv, ctx)
    }

    private fun implementsError(type: GoType, ctx: GoRuleContext): Boolean {
        val sel = ctx.semantic.lookupFieldOrMethod(type, "Error") as? GoLookup.Selection.Method ?: return false
        val m = sel.method
        val sig = m.signature
        if (sig.params.isNotEmpty() || sig.results.size != 1) return false
        if ((sig.results[0].type as? GoBasicType)?.kind != GoBasicKind.STRING) return false
        // a pointer-receiver Error is not in the method set of the value type
        return !(m.pointerReceiver && type !is GoPointerType && type.underlying() !is io.github.golangsupport.semantic.types.GoInterfaceType)
    }

    private fun match(expect: List<String>, actual: List<GoType>, prefix: String): Boolean {
        for ((i, x) in expect.withIndex()) {
            if (!x.startsWith(prefix)) continue
            if (i >= actual.size) return false
            if (!matchType(x, actual[i])) return false
        }
        return !(prefix == "" && actual.size > expect.size)
    }

    private fun matchType(expect0: String, actual: GoType): Boolean {
        val expect = expect0.removePrefix("=")
        val t = GoVetPsi.nameTypeString(actual)
        return t == expect || (t == "any" || t == "interface{}") && (expect == "any" || expect == "interface{}")
    }

    private fun join(x: List<String>): String = x.joinToString(", ") { it.removePrefix("=") }

    private companion object {
        /** vet's `canonicalMethods`: arguments and results; `=` marks the types that must match for the method to be checked at all. */
        val CANONICAL: Map<String, Pair<List<String>, List<String>>> = mapOf(
            "As" to (listOf("any") to listOf("bool")),
            "Format" to (listOf("=fmt.State", "rune") to emptyList()),
            "GobDecode" to (listOf("[]byte") to listOf("error")),
            "GobEncode" to (emptyList<String>() to listOf("[]byte", "error")),
            "Is" to (listOf("error") to listOf("bool")),
            "MarshalJSON" to (emptyList<String>() to listOf("[]byte", "error")),
            "MarshalXML" to (listOf("*xml.Encoder", "xml.StartElement") to listOf("error")),
            "ReadByte" to (emptyList<String>() to listOf("byte", "error")),
            "ReadFrom" to (listOf("=io.Reader") to listOf("int64", "error")),
            "ReadRune" to (emptyList<String>() to listOf("rune", "int", "error")),
            "Scan" to (listOf("=fmt.ScanState", "rune") to listOf("error")),
            "Seek" to (listOf("=int64", "int") to listOf("int64", "error")),
            "UnmarshalJSON" to (listOf("[]byte") to listOf("error")),
            "UnmarshalXML" to (listOf("*xml.Decoder", "xml.StartElement") to listOf("error")),
            "UnreadByte" to (emptyList<String>() to listOf("error")),
            "UnreadRune" to (emptyList<String>() to listOf("error")),
            "Unwrap" to (emptyList<String>() to listOf("error")),
            "WriteByte" to (listOf("byte") to listOf("error")),
            "WriteTo" to (listOf("=io.Writer") to listOf("int64", "error")),
        )
    }
}
