package io.github.golangsupport.ide.editor

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Auto-closes and overtypes `"`, `` ` `` and `'`. The lexer keeps an unterminated literal as one
 * token, so a literal is open when its last character differs from its first.
 */
class GoQuoteHandler : SimpleTokenSetQuoteHandler(GoTypes.STRING, GoTypes.RAW_STRING, GoTypes.CHAR) {
    override fun isNonClosedLiteral(iterator: HighlighterIterator, chars: CharSequence): Boolean {
        val start = iterator.start
        val end = iterator.end
        if (start >= end - 1) return true
        if (chars[end - 1] != chars[start]) return true
        // `"\"` ends with an escaped quote: count the backslashes before the last character.
        if (iterator.tokenType == GoTypes.RAW_STRING) return false
        var backslashes = 0
        var i = end - 2
        while (i > start && chars[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }
}
