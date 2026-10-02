package io.github.golangsupport.lang

import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.macro.MacroBase
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Where the live templates of `liveTemplates/Go.xml` apply: the code of a Go file, not its strings and comments. Inside a struct tag
 * `json` + Tab expanded the `json` template into the backquotes that were there already (seen live: ``json:""``).
 */
class GoTemplateContext : TemplateContextType("Go") {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file as? GoFile ?: return false
        return !GoTemplateContexts.isInLiteralOrComment(file.viewProvider.contents, templateActionContext.startOffset)
    }
}

object GoTemplateContexts {
    /** Whether [offset] is inside a string, a rune or a comment; the token that ends at [offset] counts, the caret stands at its end while typing. */
    fun isInLiteralOrComment(text: CharSequence, offset: Int): Boolean {
        for (token in GoTokens.all(text)) {
            if (token.start >= offset) return false
            val type = token.type
            val inside = offset > token.start && (offset < token.end || offset == token.end && !isClosed(text, token.start, token.end, type))
            if (inside && (type in GoTokenSets.STRING_LITERALS || type == GoTypes.CHAR || type in GoTokenSets.COMMENTS)) return true
        }
        return false
    }

    /** A string or a block comment that has its closing quote or `*` + `/`; a line comment never closes before the line ends. */
    private fun isClosed(text: CharSequence, start: Int, end: Int, type: com.intellij.psi.tree.IElementType): Boolean = when (type) {
        GoTypes.LINE_COMMENT -> false
        GoTypes.BLOCK_COMMENT -> end - start >= 4 && text[end - 1] == '/' && text[end - 2] == '*'
        else -> end - start >= 2 && text[end - 1] == text[start]
    }
}

/**
 * `goErrorReturn()`: what leaves the function at the caret with `err`, for the `err` template: `return nil, err`, `return total, err`
 * (named results), `t.Fatal(err)` in a test, `log.Fatal(err)` in `main`; `return err` when the function is not found.
 */
class GoErrorReturnMacro : MacroBase("goErrorReturn", "goErrorReturn()") {
    override fun calculateResult(params: Array<Expression>, context: ExpressionContext, quick: Boolean): Result? {
        val element = context.psiElementAtStartOffset ?: return TextResult("return err")
        return TextResult(GoIdioms.returnStatement("err", GoReturnValues.function(element)))
    }

    override fun isAcceptableInContext(context: TemplateContextType?): Boolean = context is GoTemplateContext
}

/** `goTypeName()`: a pointer to the type declared last above the caret, the receiver the `meth` template most likely wants. */
class GoTypeNameMacro : MacroBase("goTypeName", "goTypeName()") {
    override fun calculateResult(params: Array<Expression>, context: ExpressionContext, quick: Boolean): Result? {
        val file = context.psiElementAtStartOffset?.containingFile as? GoFile ?: return null
        return receiverFor(file, context.startOffset)?.let(::TextResult)
    }

    override fun isAcceptableInContext(context: TemplateContextType?): Boolean = context is GoTemplateContext

    companion object {
        /** `*T` of the type or of the method of a type that ends last above [offset]. Read action. */
        fun receiverFor(file: GoFile, offset: Int): String? {
            val types = file.types.filter { it.textRange.endOffset <= offset }.map { it.textRange.endOffset to it.name }
            val methods = file.methods.filter { it.textRange.endOffset <= offset }.map { it.textRange.endOffset to it.receiverTypeName }
            return (types + methods).filter { it.second != null }.maxByOrNull { it.first }?.let { "*" + it.second }
        }
    }
}
