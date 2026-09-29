package io.github.golangsupport.lang

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.ActionPlan
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.editor.actionSystem.TypedActionHandlerEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import io.github.golangsupport.settings.GoSettings

/**
 * Code typed with the Russian layout left on: `зкштедт` is `println`, `аьею` is `fmt.`. Go is written with Latin letters, so a
 * Cyrillic letter in code is a key of the other layout; in a string, a rune and a comment it is what was meant. The keys are the ones
 * of the standard ЙЦУКЕН layout over QWERTY: the letters, and the signs that stand on letter keys of the Russian one.
 */
object GoLayout {
    private const val CYRILLIC = "йцукенгшщзхъфывапролджэячсмитьбюёЙЦУКЕНГШЩЗХЪФЫВАПРОЛДЖЭЯЧСМИТЬБЮЁ"
    private const val LATIN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`QWERTYUIOP{}ASDFGHJKL:\"ZXCVBNM<>~"

    /** The character of the same key in the Latin layout; null for anything but a Cyrillic letter of the layout. */
    fun latin(c: Char): Char? = CYRILLIC.indexOf(c).takeIf { it >= 0 }?.let { LATIN[it] }

    fun latin(text: String): String = if (text.none { it in CYRILLIC }) text else buildString(text.length) { for (c in text) append(latin(c) ?: c) }

    /**
     * Whether [offset] is inside a string, a rune or a comment: where what is typed is text, and a Cyrillic letter is one. The whole
     * text before the caret is looked through: a raw string and a block comment begin lines above.
     */
    fun isText(text: CharSequence, offset: Int): Boolean {
        val end = offset.coerceIn(0, text.length)
        var i = 0
        while (i < end) {
            val c = text[i]
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            when {
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                    // the caret at the end of the line is still in the comment
                    if (i >= end) return true
                }
                c == '/' && next == '*' -> {
                    val close = text.indexOf("*/", i + 2)
                    if (close < 0 || close + 2 > end) return true
                    i = close + 1
                }
                c == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close < 0 || close >= end) return true
                    i = close
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != c && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                    // not closed on its line: what follows it on the line is the rest of it
                    if (j >= end) return true
                    i = j
                }
            }
            i++
        }
        return false
    }
}

/**
 * How the items of the plugin are matched with what is typed: whatever the case of the letters, by the humps of a name (`NR` is
 * `NewRequest`), and by the keys when the layout is Russian. The setting of the IDE (Match case) is for the items of the language
 * server, which the platform matches itself.
 */
class GoPrefixMatcher(typed: String) : PrefixMatcher(typed) {
    /** What is looked for: the Latin letters of the keys that were pressed. */
    val latin: String = GoLayout.latin(typed)

    private val delegate = CamelHumpMatcher(latin, false)

    override fun prefixMatches(name: String): Boolean = delegate.prefixMatches(name)
    override fun isStartMatch(name: String): Boolean = delegate.isStartMatch(name)
    override fun cloneWithPrefix(prefix: String): PrefixMatcher = GoPrefixMatcher(prefix)
}

/**
 * Gives the handlers of the platform the Latin character of the key when a Cyrillic one comes in code (a setting): the one that
 * types, with the pairs of brackets and the completion on a dot, and the one of the completion list, which takes the characters
 * typed while it is open.
 *
 * Around all of them, as a handler of the editor, not as a delegate of the typing one: a delegate cannot change the character, and
 * typing the other one from inside of it enters the handlers again, which they refuse with an exception (`Unexpected reentrancy of
 * DefaultRawTypedHandler`, recursive `runForEachCaret`) - the character was lost then (reported by the user: characters went missing
 * in fast typing). And a delegate is not asked at all while the list is open.
 */
class GoLayoutTypedHandler(private val original: TypedActionHandler) : TypedActionHandlerEx {
    override fun execute(editor: Editor, charTyped: Char, dataContext: DataContext) = original.execute(editor, key(editor, charTyped), dataContext)

    /** What is painted at once, before the handlers have run: the same character they are going to type. */
    override fun beforeExecute(editor: Editor, c: Char, context: DataContext, plan: ActionPlan) {
        (original as? TypedActionHandlerEx)?.beforeExecute(editor, key(editor, c), context, plan)
    }

    private fun key(editor: Editor, c: Char): Char {
        val latin = GoLayout.latin(c) ?: return c
        if (!GoSettings.getInstance().latinInCode || FileDocumentManager.getInstance().getFile(editor.document)?.fileType != GoFileType) return c
        return if (GoLayout.isText(editor.document.immutableCharSequence, editor.caretModel.offset)) c else latin
    }
}
