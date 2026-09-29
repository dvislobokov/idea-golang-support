package io.github.golangsupport.lang

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * `expr.if`, `err.nil`, `items.for`, `value.return`, `call.var`: the postfix templates of GoLand, by text. The expression is what stands
 * left of the caret: a chain of names, calls, indexes and literals, found by [GoPostfixExpressions]. What comes out is a live template,
 * so that the names to type (`v`, the variable of `.var`) are stops of it.
 */
class GoPostfixTemplateProvider : PostfixTemplateProvider {
    private val templates: Set<PostfixTemplate> = setOf(
        GoPostfixTemplate("if", "if expr {}", "if \$EXPR$ {\n\t\$END$\n}", this),
        GoPostfixTemplate("else", "if !expr {}", "if !\$EXPR$ {\n\t\$END$\n}", this),
        GoPostfixTemplate("nil", "if expr == nil {}", "if \$EXPR$ == nil {\n\t\$END$\n}", this),
        GoPostfixTemplate("notnil", "if expr != nil {}", "if \$EXPR$ != nil {\n\t\$END$\n}", this),
        GoPostfixTemplate("err", "if err := expr; err != nil {}", "if err := \$EXPR$; err != nil {\n\treturn \$RESULT$\n}\$END$", this, "RESULT" to "err"),
        GoPostfixTemplate("errv", "v, err := expr; if err != nil {}", "\$VAR$, err := \$EXPR$\nif err != nil {\n\treturn \$RESULT$\n}\$END$", this, "VAR" to "v", "RESULT" to "err"),
        GoPostfixTemplate("return", "return expr", "return \$EXPR$\$END$", this),
        GoPostfixTemplate("rr", "return expr, nil", "return \$EXPR$, nil\$END$", this),
        GoPostfixTemplate("var", "v := expr", "\$VAR$ := \$EXPR$\$END$", this, "VAR" to "v"),
        GoPostfixTemplate("for", "for _, v := range expr {}", "for \$KEY$, \$VALUE$ := range \$EXPR$ {\n\t\$END$\n}", this, "KEY" to "_", "VALUE" to "v"),
        GoPostfixTemplate("fori", "for i := 0; i < expr; i++ {}", "for \$I$ := 0; \$I$ < \$EXPR$; \$I$++ {\n\t\$END$\n}", this, "I" to "i"),
        GoPostfixTemplate("forr", "for i := len(expr) - 1; i >= 0; i-- {}", "for \$I$ := len(\$EXPR$) - 1; \$I$ >= 0; \$I$-- {\n\t\$END$\n}", this, "I" to "i"),
        GoPostfixTemplate("len", "len(expr)", "len(\$EXPR$)\$END$", this),
        GoPostfixTemplate("print", "fmt.Println(expr)", "fmt.Println(\$EXPR$)\$END$", this),
        GoPostfixTemplate("printf", "fmt.Printf(\"%v\", expr)", "fmt.Printf(\"\$FORMAT$\\n\", \$EXPR$)\$END$", this, "FORMAT" to "%v"),
        GoPostfixTemplate("panic", "panic(expr)", "panic(\$EXPR$)\$END$", this),
        GoPostfixTemplate("go", "go expr", "go \$EXPR$\$END$", this),
        GoPostfixTemplate("defer", "defer expr", "defer \$EXPR$\$END$", this),
        GoPostfixTemplate("append", "expr = append(expr, v)", "\$EXPR$ = append(\$EXPR$, \$VALUE$)\$END$", this, "VALUE" to ""),
        GoPostfixTemplate("not", "!expr", "!\$EXPR$\$END$", this),
        GoPostfixTemplate("switch", "switch expr {}", "switch \$EXPR$ {\ncase \$CASE$:\n\t\$END$\n}", this, "CASE" to ""),
        GoPostfixTemplate("range", "for range expr {}", "for range \$EXPR$ {\n\t\$END$\n}", this),
        GoPostfixTemplate("wrap", "fmt.Errorf(\"…: %w\", expr)", "fmt.Errorf(\"\$MESSAGE$: %w\", \$EXPR$)\$END$", this, "MESSAGE" to ""),
        GoPostfixTemplate("errn", "if err := expr; err != nil { return nil, err }", "if err := \$EXPR$; err != nil {\n\treturn nil, err\n}\$END$", this),
        GoPostfixTemplate("sort", "sort.Slice(expr, func(i, j int) bool {})", "sort.Slice(\$EXPR$, func(i, j int) bool {\n\treturn \$EXPR$[i]\$FIELD$ < \$EXPR$[j]\$FIELD$\n})\$END$", this, "FIELD" to ""),
    )

    override fun getTemplates(): Set<PostfixTemplate> = templates
    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'
    override fun preExpand(file: PsiFile, editor: Editor) {}
    override fun afterExpand(file: PsiFile, editor: Editor) {}
    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
    override fun getId(): String = "go"
    override fun getPresentableName(): String = "Go"
}

/** One template: [text] is a live template with `$EXPR$` for the expression and stops for what is to be typed, in the order of [stops]. */
class GoPostfixTemplate(key: String, example: String, private val text: String, provider: PostfixTemplateProvider, private vararg val stops: Pair<String, String>) :
    PostfixTemplate("go.$key", key, example, provider) {

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean =
        context.containingFile is GoFile && GoPostfixExpressions.rangeBefore(copyDocument.immutableCharSequence, newOffset) != null

    override fun expand(context: PsiElement, editor: Editor) {
        val document = editor.document
        val offset = editor.caretModel.offset
        val range = GoPostfixExpressions.rangeBefore(document.immutableCharSequence, offset) ?: return
        val expression = document.getText(range)
        document.deleteString(range.startOffset, range.endOffset)
        editor.caretModel.moveToOffset(range.startOffset)
        val template = TemplateManager.getInstance(context.project).createTemplate("go.postfix.$key", "go", text)
        template.isToReformat = false
        template.addVariable("EXPR", ConstantNode(expression), false)
        for ((name, default) in stops) template.addVariable(name, ConstantNode(default), ConstantNode(default), true)
        TemplateManager.getInstance(context.project).startTemplate(editor, template)
    }
}

/** The expression a postfix key applies to: what stands right before the caret, read backwards. */
object GoPostfixExpressions {
    /**
     * `a.b(c)[0]`, `f(x, y)`, `"text"`, `42`, `!ok`, `&x`, `*p`: from the caret back over names, dots, balanced brackets and literals,
     * then over a prefix operator. Null when there is nothing (the line starts here) or the piece is not an expression (a keyword).
     */
    fun rangeBefore(text: CharSequence, offset: Int): com.intellij.openapi.util.TextRange? {
        var end = offset.coerceIn(0, text.length)
        // the key has been deleted; a dot that is left over is not a part of the expression
        if (end > 0 && text[end - 1] == '.') end--
        var i = end
        while (i > 0) {
            val c = text[i - 1]
            when {
                c.isLetterOrDigit() || c == '_' -> i--
                c == '.' && i - 1 > 0 && (text[i - 2].isLetterOrDigit() || text[i - 2] == '_' || text[i - 2] == ')' || text[i - 2] == ']') -> i--
                c == ')' || c == ']' -> { i = matching(text, i - 1) ?: return null }
                c == '"' || c == '`' -> { i = stringStart(text, i - 1, c) ?: return null }
                else -> break
            }
        }
        // `!ok`, `&x`, `*p`, `-n`
        if (i > 0 && text[i - 1] in "!&*-" && (i == 1 || !text[i - 2].isLetterOrDigit())) i--
        if (i >= end) return null
        val expression = text.substring(i, end)
        if (expression.trim().isEmpty() || expression.first().isDigit() && !expression.all { it.isDigit() || it == '.' || it == '_' }) return null
        val head = expression.takeWhile { it.isLetterOrDigit() || it == '_' }
        if (head in GoTokenTypes.KEYWORDS && head != "func") return null
        return com.intellij.openapi.util.TextRange(i, end)
    }

    /** The index of the bracket that [close] (`)` or `]`) at [closeIndex] matches; strings inside are skipped. */
    private fun matching(text: CharSequence, closeIndex: Int): Int? {
        var depth = 0
        var i = closeIndex
        while (i >= 0) {
            when (text[i]) {
                ')', ']', '}' -> depth++
                '(', '[', '{' -> if (--depth == 0) return i
                '"', '`' -> i = stringStart(text, i, text[i]) ?: return null
            }
            i--
        }
        return null
    }

    /** The opening quote of the string that ends with the quote at [closeIndex]. */
    private fun stringStart(text: CharSequence, closeIndex: Int, quote: Char): Int? {
        var i = closeIndex - 1
        while (i >= 0) {
            if (text[i] == quote && (quote == '`' || text.getOrNull(i - 1) != '\\')) return i
            if (text[i] == '\n' && quote != '`') return null
            i--
        }
        return null
    }
}
