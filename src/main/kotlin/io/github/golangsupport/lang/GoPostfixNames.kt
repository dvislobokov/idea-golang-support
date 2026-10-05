package io.github.golangsupport.lang

import com.intellij.openapi.project.DumbService
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.semantic.psi.GoPsiUtil
import io.github.golangsupport.semantic.scope.GoScopes

/**
 * The names postfix templates suggest, read from the expression's text as GoLand does: `.var` on `c.Area()` gives `area`, `.forr` on
 * `names` gives `name`. Syntax only (the last selector or the called function, an English plural cut off); the scope only makes a name
 * unique (`name1` when `name` is taken).
 */
object GoPostfixNames {
    /** What the end of an expression is: the last name, whether it is called (`f()`), qualified (`a.b`), indexed (`xs[0]`). */
    class Source(val name: String, val call: Boolean, val qualified: Boolean, val indexed: Boolean)

    /** Verbs a getter or a constructor starts with: `loadUser()` gives `user`, `NewServer()` gives `server`. */
    private val VERBS = listOf("get", "load", "read", "fetch", "find", "new", "make", "create", "build", "parse", "open", "compute")

    fun source(text: String): Source? {
        var t = text.trim().trimStart('&', '*', '!')
        var call = false
        var indexed = false
        var first = true
        while (t.endsWith(")") || t.endsWith("]")) {
            val open = openingOf(t) ?: return null
            if (first) if (t.endsWith(")")) call = true else indexed = true
            first = false
            t = t.substring(0, open).trimEnd()
        }
        val name = t.substringAfterLast('.')
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' } || name.first().isDigit()) return null
        val qualifier = t.dropLast(name.length)
        return Source(name, call, qualifier.endsWith("."), indexed)
    }

    /**
     * `.var`: `area` for `c.Area()`, `user` for `loadUser()`, `item` for `items[0]`; null for a plain name, a literal or a bare call that
     * is only a verb (`load()`): the caller falls back to `v`.
     */
    fun variableName(text: String): String? {
        val s = source(text) ?: return null
        if (!s.call && !s.qualified && !s.indexed) return null
        var name = s.name
        // a bare `count()` would give the name of the function itself: only a verb cut off (`loadUser()`) leaves a better one
        if (s.call) name = withoutVerb(name)?.takeIf { s.qualified || it != name } ?: if (s.qualified) name else return null
        name = lowerCamel(name)
        if (s.indexed && !s.call) name = singular(name) ?: return null
        return valid(name)
    }

    /** `.forr`: the element of `names` is `name`, of `c.Boxes` `box`, of `getEntries()` `entry`; null when there is no plural. */
    fun elementName(text: String): String? {
        val s = source(text) ?: return null
        val name = lowerCamel(if (s.call) withoutVerb(s.name) ?: s.name else s.name)
        return singular(name)?.let(::valid)
    }

    /** The English singular of [plural]: `entries` → `entry`, `boxes` → `box`, `names` → `name`; null when it is not a plural. */
    fun singular(plural: String): String? {
        val p = plural
        return when {
            p.length > 3 && p.endsWith("ies") -> p.dropLast(3) + "y"
            p.length > 3 && listOf("sses", "shes", "ches", "xes", "zes").any { p.endsWith(it) } -> p.dropLast(2)
            p.endsWith("ss") || p.endsWith("us") || p.endsWith("is") -> null
            p.length > 1 && p.endsWith("s") -> p.dropLast(1)
            else -> null
        }
    }

    /** `Area` → `area`, `ID` → `id`, `HTTPServer` → `httpServer`, `URLs` → `urls`. */
    fun lowerCamel(name: String): String {
        val upper = name.takeWhile { it.isUpperCase() }.length
        return when {
            upper == 0 -> name
            upper == 1 || upper == name.length -> name.substring(0, upper).lowercase() + name.substring(upper)
            // `HTTPServer`: the last capital starts the next word; `URLs`: a plural `s` is not a word
            name[upper] == 's' && upper + 1 == name.length -> name.substring(0, upper).lowercase() + "s"
            else -> name.substring(0, upper - 1).lowercase() + name.substring(upper - 1)
        }
    }

    /** [name], or `name1`, `name2`… when it is taken. */
    fun unique(name: String, taken: Set<String>): String = if (name !in taken) name else generateSequence(1) { it + 1 }.map { "$name$it" }.first { it !in taken }

    /**
     * The names a new variable at [offset] would clash with or hide: the variables and parameters in scope, the package-level names of
     * the file and its imports. Empty in dumb mode or outside a function: then nothing is renamed.
     */
    fun takenAt(file: GoFile, offset: Int): Set<String> {
        if (DumbService.isDumb(file.project)) return emptySet()
        val place = file.findElementAt(offset) ?: return emptySet()
        if (GoPsiUtil.functionOwner(place) == null) return emptySet()
        val names = HashSet<String>()
        GoScopeInputs.visibleNames(place, offset).let(names::addAll)
        file.functions.mapNotNullTo(names) { it.name }
        file.types.mapNotNullTo(names) { it.name }
        file.vars.mapNotNullTo(names) { it.name }
        file.consts.mapNotNullTo(names) { it.name }
        file.imports.forEach { spec -> if (!spec.isBlank && !spec.isDot) names += GoScopes.importName(spec) }
        return names
    }

    private fun withoutVerb(name: String): String? {
        for (verb in VERBS) {
            if (name.length > verb.length && name.startsWith(verb, ignoreCase = true) && name[verb.length].isUpperCase()) return name.substring(verb.length)
        }
        return if (VERBS.any { it.equals(name, ignoreCase = true) }) null else name
    }

    private fun valid(name: String): String? =
        name.takeIf { it.isNotEmpty() && it != "_" && it !in GoNames.KEYWORDS && it !in GoNames.BUILTIN_TYPES && it !in GoNames.BUILTIN_CONSTANTS && it !in GoNames.BUILTIN_FUNCTIONS }

    /** The index of the bracket that opens the group [text] ends with. */
    private fun openingOf(text: String): Int? {
        var depth = 0
        for (i in text.indices.reversed()) {
            when (text[i]) {
                ')', ']', '}' -> depth++
                '(', '[', '{' -> if (--depth == 0) return i
            }
        }
        return null
    }
}
