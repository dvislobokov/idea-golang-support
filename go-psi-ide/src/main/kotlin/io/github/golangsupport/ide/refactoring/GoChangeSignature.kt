package io.github.golangsupport.ide.refactoring

import com.intellij.psi.PsiElement
import io.github.golangsupport.ide.navigation.GoImplementations
import io.github.golangsupport.ide.rename.GoNamesValidator
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoSignature
import io.github.golangsupport.semantic.psi.GoPsiUtil.isVariadic

/**
 * A parameter of the new signature. [oldIndex] is its argument slot in the old signature, -1 for a new parameter whose [defaultValue]
 * is inserted at every call. [type] holds the `...` of a variadic parameter. An empty [name] is an unnamed parameter (`func(int)`).
 */
data class GoChangeParameter(val name: String, val type: String, val oldIndex: Int = -1, val defaultValue: String = "") {
    val isVariadic: Boolean get() = type.trimStart().startsWith("...")
}

/** A result of the new signature; an empty [name] is an unnamed result. */
data class GoChangeResult(val name: String, val type: String)

/** What Change Signature makes of a function, method or interface method: its [name], [parameters] in the new order and [results]. */
data class GoChangeSignatureOptions(val name: String, val parameters: List<GoChangeParameter>, val results: List<GoChangeResult>)

/** The pure part of Change Signature: the model read from a declaration, its validation and the text of the new signature. */
object GoChangeSignature {

    /** The declarations Change Signature serves: function and method declarations and interface method specs. */
    fun isSupported(element: PsiElement?): Boolean = element is GoFunctionOrMethodDeclaration || element is GoMethodSpec

    fun signatureOf(target: PsiElement): GoSignature? = (target as? GoFunctionOrMethodDeclaration)?.signature ?: (target as? GoMethodSpec)?.signature

    fun nameOf(target: PsiElement): String = (target as? GoFunctionOrMethodDeclaration)?.name ?: (target as? GoMethodSpec)?.name ?: ""

    /** `T.M` for a method, `I.M` for an interface method, the name for a function. */
    fun displayName(target: PsiElement): String = when (target) {
        is GoMethodDeclaration -> (GoImplementations.receiverTypeSpec(target)?.name ?: target.receiverTypeName ?: "?") + "." + target.name
        is GoMethodSpec -> (GoImplementations.interfaceSpecOf(target)?.name ?: "interface") + "." + target.name
        else -> nameOf(target)
    }

    /** The parameters of [signature], one per argument slot (`a, b int` gives two, a bare type one). */
    fun parametersOf(signature: GoSignature?): List<GoChangeParameter> {
        val result = ArrayList<GoChangeParameter>()
        for (pd in GoParameterRemoval.declarations(signature)) {
            val type = (if (pd.isVariadic) "..." else "") + (pd.type?.text ?: "")
            val names = pd.paramDefinitionList
            if (names.isEmpty()) result += GoChangeParameter("", type, result.size)
            else for (def in names) result += GoChangeParameter(def.name ?: "_", type, result.size)
        }
        return result
    }

    /** The results of [signature]: a bare type is one unnamed result, `(n int, err error)` two named ones. */
    fun resultsOf(signature: GoSignature?): List<GoChangeResult> {
        val result = signature?.result ?: return emptyList()
        result.type?.let { return listOf(GoChangeResult("", it.text)) }
        return result.parameters?.parameterDeclarationList.orEmpty().flatMap { pd ->
            val type = pd.type?.text ?: ""
            if (pd.paramDefinitionList.isEmpty()) listOf(GoChangeResult("", type)) else pd.paramDefinitionList.map { GoChangeResult(it.name ?: "_", type) }
        }
    }

    /** The options that change nothing: the starting point of the dialog and of the tests. */
    fun initial(target: PsiElement): GoChangeSignatureOptions =
        signatureOf(target).let { GoChangeSignatureOptions(nameOf(target), parametersOf(it), resultsOf(it)) }

    /** `(a int, b string)` */
    fun parametersText(parameters: List<GoChangeParameter>): String =
        parameters.joinToString(", ", "(", ")") { if (it.name.isEmpty()) it.type.trim() else "${it.name} ${it.type.trim()}" }

    /** The results with their leading space: ``, ` int`, ` (int, error)`, ` (n int)`. */
    fun resultsText(results: List<GoChangeResult>): String = when {
        results.isEmpty() -> ""
        results.size == 1 && results[0].name.isEmpty() -> " " + results[0].type.trim()
        else -> results.joinToString(", ", " (", ")") { if (it.name.isEmpty()) it.type.trim() else "${it.name} ${it.type.trim()}" }
    }

    /** The new signature as the dialog previews it: `func (r T) name(a int) error` for a method. */
    fun preview(target: PsiElement, options: GoChangeSignatureOptions): String {
        val receiver = (target as? GoMethodDeclaration)?.receiver?.text?.let { "$it " } ?: ""
        val typeParameters = (target as? GoFunctionOrMethodDeclaration)?.typeParameters?.text ?: ""
        val prefix = if (target is GoMethodSpec) "" else "func $receiver"
        return prefix + options.name + typeParameters + parametersText(options.parameters) + resultsText(options.results)
    }

    /**
     * Results typed as text in the dialog: `int`, `(int, error)`, `n int, err error`. An item is `name type` when its first word is an
     * identifier that is not a keyword starting a type (`chan int`, `func() error`, `map[K]V`) and something follows it.
     */
    fun parseResults(text: String): List<GoChangeResult> {
        var t = text.trim()
        if (t.startsWith("(") && closingParen(t, 0) == t.length - 1) t = t.substring(1, t.length - 1).trim()
        if (t.isEmpty()) return emptyList()
        return splitTopLevel(t).map { item ->
            val s = item.trim()
            val space = s.indexOfFirst { it == ' ' || it == '\t' }
            val first = if (space > 0) s.substring(0, space) else ""
            if (space > 0 && GoNamesValidator.isValidIdentifier(first) && !first.contains('.')) GoChangeResult(first, s.substring(space + 1).trim())
            else GoChangeResult("", s)
        }
    }

    /** [s] split at the commas outside brackets, parentheses and braces. */
    fun splitTopLevel(s: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in s.withIndex()) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    parts += s.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += s.substring(start)
        return parts.filter { it.isNotBlank() }
    }

    private fun closingParen(s: String, open: Int): Int {
        var depth = 0
        for (i in open until s.length) {
            if (s[i] == '(') depth++ else if (s[i] == ')' && --depth == 0) return i
        }
        return -1
    }

    /**
     * Why [options] cannot be applied to [old] (the parameters of the declaration), or null. Refused: an invalid name, parameters or
     * results mixing names and none, a parameter without a type, a variadic parameter not last, an old parameter that becomes variadic or
     * stops being so (its calls could not be kept), a new parameter without a default value for the calls (unless variadic), and the
     * same old slot twice.
     */
    fun validate(old: List<GoChangeParameter>, options: GoChangeSignatureOptions): String? {
        if (!GoNamesValidator.isValidIdentifier(options.name)) return "'${options.name}' is not a valid Go identifier"
        val params = options.parameters
        for (p in params) {
            if (p.name.isNotEmpty() && p.name != "_" && !GoNamesValidator.isValidIdentifier(p.name)) return "'${p.name}' is not a valid parameter name"
            if (p.type.isBlank() || p.type.trim() == "...") return "Parameter ${p.name.ifEmpty { "#" + (params.indexOf(p) + 1) }} has no type"
        }
        if (params.any { it.name.isEmpty() } && params.any { it.name.isNotEmpty() }) return "Either every parameter has a name or none has"
        val named = params.filter { it.name.isNotEmpty() && it.name != "_" }.map { it.name }
        named.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { return "Parameter ${it.key} is declared twice" }
        if (params.dropLast(1).any { it.isVariadic }) return "Only the last parameter can be variadic"
        for (p in params) {
            if (p.oldIndex < 0) {
                if (p.defaultValue.isBlank() && !p.isVariadic) return "New parameter ${p.name.ifEmpty { p.type }} needs a default value for the calls"
                continue
            }
            val was = old.getOrNull(p.oldIndex) ?: return "Unknown parameter ${p.name}"
            if (was.isVariadic != p.isVariadic) return "Parameter ${p.name.ifEmpty { was.name }} cannot become variadic or stop being variadic: its calls could not be kept"
        }
        params.filter { it.oldIndex >= 0 }.groupBy { it.oldIndex }.entries.firstOrNull { it.value.size > 1 }?.let { return "Parameter ${old[it.key].name} is listed twice" }
        val results = options.results
        for (r in results) {
            if (r.type.isBlank()) return "A result has no type"
            if (r.name.isNotEmpty() && r.name != "_" && !GoNamesValidator.isValidIdentifier(r.name)) return "'${r.name}' is not a valid result name"
        }
        if (results.any { it.name.isEmpty() } && results.any { it.name.isNotEmpty() }) return "Either every result has a name or none has"
        return null
    }

    /** Whether the argument lists of the calls change: a parameter added, removed or moved. */
    fun argumentsChange(old: List<GoChangeParameter>, options: GoChangeSignatureOptions): Boolean =
        options.parameters.map { it.oldIndex } != old.indices.toList()

    /** Whether the old slots kept by [options] keep their relative order (evaluation order of the arguments at the calls). */
    fun keepsOrder(options: GoChangeSignatureOptions): Boolean = options.parameters.map { it.oldIndex }.filter { it >= 0 }.zipWithNext().all { (a, b) -> a < b }
}
