package io.github.golangsupport.lang

import com.intellij.application.options.CodeStyle
import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import com.intellij.psi.tree.IElementType

/**
 * The indent of a line while typing, the way gofmt would have it: gofmt is the only style there is, so unlike the C# sibling of this
 * engine there are no rules to configure. A level per line that has brackets still open (not per bracket: `foo(func() {` opens two and
 * indents one), `case` and `default` at the level of their `switch`, a closing bracket at the level of the line that opened it, a level
 * more for a line that continues an expression. Whole files are formatted by gofmt itself.
 */
object GoIndentEngine {
    private class Token(val type: IElementType, val start: Int, val text: CharSequence)
    private class Open(val line: Int, val isSwitchBrace: Boolean)

    /** The number of indent levels of the line that starts at [lineStart]; null inside a raw string or a block comment, which are as they are. */
    fun levelOf(text: CharSequence, lineStart: Int): Int? {
        val stack = ArrayList<Open>()
        var line = 0
        var lineHasSwitch = false
        var lastCode: Token? = null
        var beforeLast: Token? = null
        val lexer = GoTextLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            if (start >= lineStart) break
            // a token that spans the line start: the line is a part of it
            if (lexer.tokenEnd > lineStart && (type == GoTextTokens.RAW_STRING || type == GoTextTokens.BLOCK_COMMENT)) return null
            val tokenText = text.subSequence(start, lexer.tokenEnd)
            when {
                type == TokenType.WHITE_SPACE || type == GoTextTokens.RAW_STRING || type == GoTextTokens.BLOCK_COMMENT -> {
                    val breaks = tokenText.count { it == '\n' }
                    if (breaks > 0) {
                        line += breaks
                        lineHasSwitch = false
                    }
                    // a raw string is a value: the statement it ends is over, however many lines it took
                    if (type == GoTextTokens.RAW_STRING) {
                        beforeLast = lastCode
                        lastCode = Token(type, start, tokenText)
                    }
                }
                type in GoTextTokens.COMMENTS -> {}
                else -> {
                    when (type) {
                        GoTextTokens.KEYWORD -> if (tokenText.toString() == "switch" || tokenText.toString() == "select") lineHasSwitch = true
                        GoTextTokens.LBRACE -> stack += Open(line, lineHasSwitch)
                        GoTextTokens.LPAREN, GoTextTokens.LBRACKET -> stack += Open(line, false)
                        GoTextTokens.RBRACE, GoTextTokens.RPAREN, GoTextTokens.RBRACKET -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    }
                    beforeLast = lastCode
                    lastCode = Token(type, start, tokenText)
                }
            }
            lexer.advance()
        }

        // what the line itself starts with
        var first = lineStart
        while (first < text.length && (text[first] == ' ' || text[first] == '\t')) first++
        var closers = 0
        var probe = first
        while (probe < text.length && text[probe] in "})]") {
            closers++
            probe++
        }
        val word = StringBuilder().also { while (probe < text.length && closers == 0 && text[probe].isLetter()) it.append(text[probe++]) }.toString()

        val remaining = stack.dropLast(closers.coerceAtMost(stack.size))
        var level = remaining.map { it.line }.distinct().size
        // the brackets a closer closes and the ones that stay may share a line: `})` of `foo(func() {` closes that line as a whole
        if (closers > 0 && remaining.isNotEmpty() && stack.size > remaining.size && stack[remaining.size].line == remaining.last().line) level--
        if ((word == "case" || word == "default") && stack.lastOrNull()?.isSwitchBrace == true) level--
        if (closers == 0 && continues(lastCode, beforeLast)) level++
        return level.coerceAtLeast(0)
    }

    /** A line whose last token cannot end a statement is continued by the next one: `total :=`, `a +`, `x.` - and not `i++`, not a label's or a case's `:`. */
    private fun continues(last: Token?, beforeLast: Token?): Boolean {
        if (last == null) return false
        if (last.type == GoTextTokens.DOT) return true
        if (last.type != GoTextTokens.OPERATOR) return false
        val c = last.text[0]
        if (c == ':') return false
        val doubled = beforeLast != null && beforeLast.type == GoTextTokens.OPERATOR && beforeLast.text[0] == c && beforeLast.start + 1 == last.start
        return !(doubled && (c == '+' || c == '-'))
    }

    fun indentText(level: Int, options: CommonCodeStyleSettings.IndentOptions): String =
        if (options.USE_TAB_CHARACTER) "\t".repeat(level) else " ".repeat(level * options.INDENT_SIZE)
}

object GoIndenter {
    /** The indent for the line of [offset], as text; null when the line is to be left alone. */
    fun indentFor(project: Project, document: Document, offset: Int): String? {
        val file = PsiDocumentManager.getInstance(project).getCachedPsiFile(document)
        val options = file?.let { CodeStyle.getIndentOptions(it) } ?: CodeStyle.getSettings(project).getIndentOptions(GoFileType)
        val lineStart = document.getLineStartOffset(document.getLineNumber(offset.coerceIn(0, document.textLength)))
        return GoIndentEngine.levelOf(document.immutableCharSequence, lineStart)?.let { GoIndentEngine.indentText(it, options) }
    }
}

/** Enter, and everything else of the platform that asks "what is the indent of this line" of a language without a formatter model. */
class GoLineIndentProvider : LineIndentProvider {
    override fun isSuitableFor(language: Language?): Boolean = language == GoLanguage

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? =
        GoIndenter.indentFor(project, editor.document, offset) ?: LineIndentProvider.DO_NOT_ADJUST
}

/**
 * Enter between a pair of brackets, `{|}`, `(|)`, `[|]`: the pair opens into three lines with the caret on the indented middle one.
 * Done here because the platform does it through the formatter of the language, which there is none of.
 */
class GoEnterBetweenBracketsHandler : EnterHandlerDelegateAdapter() {
    override fun preprocessEnter(
        file: PsiFile, editor: Editor, caretOffset: Ref<Int>, caretAdvance: Ref<Int>, dataContext: DataContext, originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (file !is GoFile || editor.caretModel.caretCount > 1 || editor.selectionModel.hasSelection()) return EnterHandlerDelegate.Result.Continue
        val document = editor.document
        val text = document.immutableCharSequence
        val offset = caretOffset.get()
        if (offset < 1 || offset >= text.length || PAIRS[text[offset - 1]] != text[offset]) return EnterHandlerDelegate.Result.Continue

        document.insertString(offset, "\n\n")
        val closing = GoIndenter.indentFor(file.project, document, offset + 2).orEmpty()
        document.insertString(offset + 2, closing)
        val middle = GoIndenter.indentFor(file.project, document, offset + 1).orEmpty()
        document.insertString(offset + 1, middle)
        editor.caretModel.moveToOffset(offset + 1 + middle.length)
        editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        return EnterHandlerDelegate.Result.Stop
    }

    private companion object {
        val PAIRS = mapOf('{' to '}', '(' to ')', '[' to ']')
    }
}

/**
 * Settings | Editor | Code Style | Go. Tabs, as gofmt writes them; the width of a tab is the only thing that is a matter of taste.
 * The other tabs of a code style page (Spaces, Wrapping, Blank Lines) would promise a formatter of the IDE: the formatter is gofmt.
 */
class GoCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {
    override fun getLanguage(): Language = GoLanguage
    override fun getCodeSample(settingsType: SettingsType): String = SAMPLE
    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    override fun customizeDefaults(commonSettings: CommonCodeStyleSettings, indentOptions: CommonCodeStyleSettings.IndentOptions) {
        indentOptions.USE_TAB_CHARACTER = true
        indentOptions.INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 4
        indentOptions.CONTINUATION_INDENT_SIZE = 4
    }

    override fun createConfigurable(baseSettings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(baseSettings, modelSettings, "Go") {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel = object : TabbedLanguageCodeStylePanel(GoLanguage, currentSettings, settings) {
                override fun initTabs(settings: CodeStyleSettings) = addIndentOptionsTab(settings)
            }
        }

    private companion object {
        val SAMPLE = "package shop\n\nfunc Total(items []Item) int {\n\ttotal := 0\n\tfor _, item := range items {\n\t\tswitch {\n\t\tcase item.Quantity > 0:\n\t\t\ttotal += item.Price * item.Quantity\n\t\t}\n\t}\n\treturn total\n}\n"
    }
}
