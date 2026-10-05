package io.github.golangsupport.lang

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTypes

/**
 * A type after a name or a type constructor, GoLand's `GO_TYPE` (the `map` template): `var m map`, `Name map` in a struct, `[]map`,
 * `*map`, `chan map`, `make(map`, a parameter `m map`. By the tokens before the word, so it works before the PSI has seen the text.
 */
class GoTypeTemplateContext : TemplateContextType("Go type") {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file as? GoFile ?: return false
        val text = file.viewProvider.contents
        val offset = templateActionContext.startOffset
        if (offset < 0 || offset > text.length || GoTemplateContexts.isInLiteralOrComment(text, offset)) return false
        var start = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        return isTypePlace(text, start)
    }

    companion object {
        /** Whether a word at [start] of [text] stands where a type goes: right after a name, `]`, `*`, `chan`, `map[K]` or `make(`. */
        fun isTypePlace(text: CharSequence, start: Int): Boolean {
            val tokens = GoTokens.code(text, 0, start).toList()
            val previous = tokens.lastOrNull() ?: return false
            return when (previous.type) {
                // a name ends the line before: the lexer has put a semicolon there, so `x` alone above does not count
                GoTypes.IDENTIFIER, GoTypes.RBRACK, GoTypes.MUL, GoTypes.CHAN -> true
                GoTypes.LPAREN -> tokens.getOrNull(tokens.size - 2)?.let { text.subSequence(it.start, it.end) == "make" || text.subSequence(it.start, it.end) == "new" } == true
                else -> false
            }
        }
    }
}
