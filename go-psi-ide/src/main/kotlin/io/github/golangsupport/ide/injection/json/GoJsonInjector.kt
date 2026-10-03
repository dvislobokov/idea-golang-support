package io.github.golangsupport.ide.injection.json

import com.intellij.json.JsonLanguage
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import io.github.golangsupport.ide.GoIdeFeature
import io.github.golangsupport.ide.GoIdeFeatureGate
import io.github.golangsupport.ide.injection.GoInjectionTargets
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoVarDefinition

/**
 * Injects JSON into: the literal of `json.Unmarshal([]byte("..."), &v)` / `json.Valid(...)`, of `json.NewDecoder(strings.NewReader("..."))`,
 * and a raw literal that reads like a JSON document ([GoJsonDetect]) assigned to a const / variable whose name contains `json`.
 * Lives in its own descriptor: the JSON language is a plugin, absent from some IDEs, and this class must not load without it.
 * Gated by [GoIdeFeature.SEMANTIC_COLORS] like the regexp injection (the call forms depend on resolve).
 */
class GoJsonInjector : MultiHostInjector {
    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(GoStringLiteral::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val literal = context as? GoStringLiteral ?: return
        val host = literal as? PsiLanguageInjectionHost ?: return
        if (!host.isValidHost || !GoIdeFeatureGate.enabled(GoIdeFeature.SEMANTIC_COLORS, literal.project)) return
        if (!isJson(literal)) return
        registrar.startInjecting(JsonLanguage.INSTANCE).addPlace(null, null, host, GoInjectionTargets.contentRange(host)).doneInjecting()
    }

    private fun isJson(literal: GoStringLiteral): Boolean {
        val conversion = GoInjectionTargets.conversionOf(literal)
        if (conversion != null) {
            // []byte("...") as the first argument of json.Unmarshal / json.Valid
            val call = GoInjectionTargets.callOfArgument(conversion, 0) ?: return false
            if (GoInjectionTargets.calleeName(call) !in BYTE_SINKS) return false
            return GoInjectionTargets.calleeOf(call).let { it != null && it.pkg == "encoding/json" && it.name in BYTE_SINKS }
        }
        val reader = GoInjectionTargets.callOfArgument(literal, 0)
        if (reader != null) {
            // json.NewDecoder(strings.NewReader("..."))
            if (GoInjectionTargets.calleeName(reader) != "NewReader" || GoInjectionTargets.calleeOf(reader)?.pkg != "strings") return false
            val decoder = GoInjectionTargets.callOfArgument(reader, 0) ?: return false
            if (GoInjectionTargets.calleeName(decoder) != "NewDecoder") return false
            return GoInjectionTargets.calleeOf(decoder).let { it != null && it.pkg == "encoding/json" && it.name == "NewDecoder" }
        }
        return isNamedJsonLiteral(literal)
    }

    /** `const jsonDoc = ` + a raw string `{...}`: the name says JSON and the content looks like it. */
    private fun isNamedJsonLiteral(literal: GoStringLiteral): Boolean {
        if (!literal.text.startsWith("`") || !GoJsonDetect.looksLikeJson(literal.text.trim('`'))) return false
        val spec = literal.parent ?: return false
        // the expression list is a private rule: the values and the definitions are siblings under the spec (or `:=` declaration)
        val index = spec.children.filterIsInstance<GoExpression>().indexOf(literal)
        val names = spec.children.filter { it is GoConstDefinition || it is GoVarDefinition }.mapNotNull { (it as GoNamedElement).name }
        return names.getOrNull(index)?.let { GoJsonDetect.nameSaysJson(it) } == true
    }

    private companion object {
        val BYTE_SINKS = setOf("Unmarshal", "Valid")
    }
}

/** The pure part of the JSON detection: no PSI, no platform. */
object GoJsonDetect {
    fun nameSaysJson(name: String): Boolean = name.contains("json", ignoreCase = true)

    /** Starts with `{` / `[`, ends with the matching bracket, and every bracket outside strings is balanced: cheap, not a parser. */
    fun looksLikeJson(content: String): Boolean {
        val s = content.trim()
        if (s.length < 2 || !(s.first() == '{' && s.last() == '}' || s.first() == '[' && s.last() == ']')) return false
        val stack = ArrayDeque<Char>()
        var inString = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                inString -> if (c == '\\') i++ else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '{' || c == '[' -> stack.addLast(c)
                c == '}' || c == ']' -> if (stack.removeLastOrNull() != (if (c == '}') '{' else '[')) return false
            }
            i++
        }
        return !inString && stack.isEmpty()
    }
}
