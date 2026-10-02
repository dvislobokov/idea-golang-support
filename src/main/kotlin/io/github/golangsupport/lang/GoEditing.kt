package io.github.golangsupport.lang

import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.codeInsight.template.TextResult
import com.intellij.codeInsight.template.macro.MacroBase
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoArgumentList
import io.github.golangsupport.lang.psi.GoBlock
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoLiteralValue
import io.github.golangsupport.lang.psi.GoParameters
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypeParameters
import io.github.golangsupport.lang.psi.GoTypes

/**
 * Where the live templates of `liveTemplates/Go.xml` apply: the code of a Go file, not its strings and comments. Inside a struct tag
 * `json` + Tab expanded the `json` template into the backquotes that were there already (seen live: ``json:""``). The places within
 * it are the sub-contexts below ([GoTemplateContexts.Place]).
 */
class GoTemplateContext : TemplateContextType("Go") {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file as? GoFile ?: return false
        val offset = templateActionContext.startOffset
        return if (GoTemplateContexts.isCommitted(file)) !GoTemplateContexts.isInLiteralOrComment(file, offset)
        else !GoTemplateContexts.isInLiteralOrComment(file.viewProvider.contents, offset)
    }
}

/** A place in Go code: registered with `baseContextId="GO"`, so a template of the whole language applies in each of them too. */
abstract class GoPlaceTemplateContext(presentableName: String, private val place: GoTemplateContexts.Place) : TemplateContextType(presentableName) {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean {
        val file = templateActionContext.file as? GoFile ?: return false
        return GoTemplateContexts.placeAt(file, templateActionContext.startOffset) == place
    }
}

/** A statement in a function body: `fori`, `err`, `forr`, … */
class GoStatementTemplateContext : GoPlaceTemplateContext("Go statement", GoTemplateContexts.Place.STATEMENT)

/** A declaration of the package: `func`, `meth`, `test`, `main`, … */
class GoTopLevelTemplateContext : GoPlaceTemplateContext("Go top level", GoTemplateContexts.Place.TOP_LEVEL)

/** A field of a struct type: the `json` tag. */
class GoStructFieldTemplateContext : GoPlaceTemplateContext("Go struct field", GoTemplateContexts.Place.STRUCT_FIELD)

/** An expression inside a statement or a declaration: `errf`. */
class GoExpressionTemplateContext : GoPlaceTemplateContext("Go expression", GoTemplateContexts.Place.EXPRESSION)

object GoTemplateContexts {
    enum class Place { TOP_LEVEL, STATEMENT, EXPRESSION, STRUCT_FIELD }

    /**
     * The place of the word that ends at [offset] (the abbreviation being expanded): from the PSI when the document is committed, else
     * from the tokens of the text. Null in strings and comments, in signatures and interface bodies. Read action.
     */
    fun placeAt(file: GoFile, offset: Int): Place? {
        val text = file.viewProvider.contents
        if (offset < 0 || offset > text.length) return null
        val committed = isCommitted(file)
        // the leaf answers for a committed file; the tokens of the whole text otherwise
        if (if (committed) isInLiteralOrComment(file, offset) else isInLiteralOrComment(text, offset)) return null
        var start = offset
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val startsLine = text.subSequence(lineStart, start).isBlank()
        return if (committed) psiPlace(file, start, offset, startsLine) else tokenPlace(text, start, startsLine)
    }

    /** [isInLiteralOrComment] by the leaf of the committed PSI. */
    fun isInLiteralOrComment(file: GoFile, offset: Int): Boolean {
        val leaf = (if (offset > 0) file.findElementAt(offset - 1) else null) ?: return false
        val type = leaf.node.elementType
        if (type !in GoTokenSets.STRING_LITERALS && type != GoTypes.CHAR && type !in GoTokenSets.COMMENTS) return false
        val range = leaf.textRange
        return offset > range.startOffset && (offset < range.endOffset || !isClosed(file.viewProvider.contents, range.startOffset, range.endOffset, type))
    }

    private fun psiPlace(file: GoFile, start: Int, offset: Int, startsLine: Boolean): Place? {
        val leaf = (if (start < offset) file.findElementAt(start) else file.findElementAt(offset) ?: file.findElementAt(offset - 1)) ?: return Place.TOP_LEVEL
        var inExpression = false
        var e: PsiElement? = leaf
        while (e != null && e !is PsiFile) {
            when (e) {
                is GoStructType -> return Place.STRUCT_FIELD.takeIf { inside(e, start) }
                is GoInterfaceType, is GoParameters, is GoTypeParameters -> if (inside(e, start)) return null
                is GoArgumentList, is GoLiteralValue, is GoParenthesesExpr -> if (inside(e, start)) inExpression = true
                is GoBlock -> if (inside(e, start)) return if (inExpression || !startsStatement(leaf, start, startsLine)) Place.EXPRESSION else Place.STATEMENT
            }
            e = e.parent
        }
        return if (startsLine && !inExpression) Place.TOP_LEVEL else Place.EXPRESSION
    }

    /** Strictly between the braces or parentheses of [element]: the word is not its opening token. */
    private fun inside(element: PsiElement, offset: Int): Boolean {
        val first = element.node.findChildByType(GoTokenSets.OPERATORS)?.psi ?: element.firstChild
        return offset >= first.textRange.endOffset && offset <= element.textRange.endOffset
    }

    /** A word on a line of its own, or after `{`, `;` or the `:` of a `case`: the start of a statement. */
    private fun startsStatement(leaf: PsiElement, start: Int, startsLine: Boolean): Boolean {
        if (startsLine) return true
        var previous = com.intellij.psi.util.PsiTreeUtil.prevLeaf(leaf)
        while (previous != null && (previous.textLength == 0 || !GoTokens.isCode(previous.node.elementType))) previous = com.intellij.psi.util.PsiTreeUtil.prevLeaf(previous)
        if (previous != null && previous.textRange.endOffset > start) return false
        return previous?.node?.elementType in setOf(GoTypes.LBRACE, GoTypes.SEMICOLON, GoTypes.COLON)
    }

    /** The place by the tokens before [start]: the unclosed brackets say where it is. */
    fun tokenPlace(text: CharSequence, start: Int, startsLine: Boolean): Place? {
        // each open bracket with what it opens: '(' and '[' an expression (or a signature), '{' a body, a struct or an interface
        val open = ArrayList<Char>()
        var previous: com.intellij.psi.tree.IElementType? = null
        for (token in GoTokens.code(text, 0, start)) {
            when (token.type) {
                GoTypes.LPAREN, GoTypes.LBRACK -> open += '('
                GoTypes.LBRACE -> open += when (previous) { GoTypes.STRUCT -> 's'; GoTypes.INTERFACE -> 'i'; else -> '{' }
                GoTypes.RPAREN, GoTypes.RBRACK, GoTypes.RBRACE -> if (open.isNotEmpty()) open.removeAt(open.lastIndex)
            }
            if (token.type != GoTypes.SEMICOLON_SYNTHETIC) previous = token.type
        }
        return when (open.lastOrNull()) {
            null -> if (startsLine) Place.TOP_LEVEL else Place.EXPRESSION
            's' -> Place.STRUCT_FIELD
            'i' -> null
            '(' -> Place.EXPRESSION
            else -> if (startsLine || previous == GoTypes.LBRACE || previous == GoTypes.SEMICOLON) Place.STATEMENT else Place.EXPRESSION
        }
    }

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

    private val FUNC_LINE = Regex("""(?m)^func\s+(?:\([^)]*\)\s*)?(\w+)\s*(?:\[[^\]]*])?(\(.*?)\s*\{\s*$""")

    /**
     * The function whose body holds [offset] by the text: the last `func` line at column 0 above it (gofmt puts every declaration
     * there), its signature split by [GoIdioms.splitSignature]. For a document the PSI has not seen yet.
     */
    fun functionByText(text: CharSequence, offset: Int, isMainPackage: Boolean): GoIdioms.Function? {
        val match = FUNC_LINE.findAll(text.subSequence(0, offset.coerceIn(0, text.length))).lastOrNull() ?: return null
        val (parameters, results) = GoIdioms.splitSignature(match.groupValues[2])
        val name = match.groupValues[1]
        return GoIdioms.Function(name, parameters, results, isMain = isMainPackage && name == "main" && !match.value.substringAfter("func").trimStart().startsWith("("))
    }

    private val TYPE_OR_RECEIVER = Regex("""(?m)^(?:type\s+(\w+)\s|func\s*\(\s*\w*\s*\*?\s*(\w+))""")

    /** `*T` of the type or the receiver declared last above [offset], by the text. */
    fun receiverByText(text: CharSequence, offset: Int): String? =
        TYPE_OR_RECEIVER.findAll(text.subSequence(0, offset.coerceIn(0, text.length))).lastOrNull()
            ?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }?.takeIf { it.isNotEmpty() && it !in GoNames.KEYWORDS }?.let { "*$it" }

    /** Whether the document of [file] is what its PSI says. */
    fun isCommitted(file: PsiFile): Boolean {
        val documents = PsiDocumentManager.getInstance(file.project)
        val document = documents.getDocument(file) ?: return true
        return documents.isCommitted(document)
    }
}

/**
 * `goErrorReturn()`: what leaves the function at the caret with `err`, for the `err` template: `return nil, err`, `return total, err`
 * (named results), `t.Fatal(err)` in a test, `log.Fatal(err)` in `main`; `return err` when the function is not found. The function
 * comes from the PSI; from its header line when the document is not committed.
 */
class GoErrorReturnMacro : MacroBase("goErrorReturn", "goErrorReturn()") {
    override fun calculateResult(params: Array<Expression>, context: ExpressionContext, quick: Boolean): Result? {
        val element = context.psiElementAtStartOffset ?: return TextResult("return err")
        val file = element.containingFile as? GoFile
        val function = if (file != null && !GoTemplateContexts.isCommitted(file)) {
            GoTemplateContexts.functionByText(context.editor?.document?.immutableCharSequence ?: file.viewProvider.contents, context.startOffset, file.packageName == "main")
        } else GoReturnValues.function(element)
        return TextResult(GoIdioms.returnStatement("err", function))
    }

    override fun isAcceptableInContext(context: TemplateContextType?): Boolean = context is GoTemplateContext || context is GoPlaceTemplateContext
}

/**
 * `goTypeName()`: a pointer to the type declared last above the caret, the receiver the `meth` template most likely wants. From the
 * declarations of the PSI; from the text when the document is not committed.
 */
class GoTypeNameMacro : MacroBase("goTypeName", "goTypeName()") {
    override fun calculateResult(params: Array<Expression>, context: ExpressionContext, quick: Boolean): Result? {
        val file = context.psiElementAtStartOffset?.containingFile as? GoFile ?: return null
        val receiver = if (GoTemplateContexts.isCommitted(file)) receiverFor(file, context.startOffset)
        else GoTemplateContexts.receiverByText(context.editor?.document?.immutableCharSequence ?: file.viewProvider.contents, context.startOffset)
        return receiver?.let(::TextResult)
    }

    override fun isAcceptableInContext(context: TemplateContextType?): Boolean = context is GoTemplateContext || context is GoPlaceTemplateContext

    companion object {
        /** `*T` of the type or of the method of a type that ends last above [offset]. Read action. */
        fun receiverFor(file: GoFile, offset: Int): String? {
            val types = file.types.filter { it.textRange.endOffset <= offset }.map { it.textRange.endOffset to it.name }
            val methods = file.methods.filter { it.textRange.endOffset <= offset }.map { it.textRange.endOffset to it.receiverTypeName }
            return (types + methods).filter { it.second != null }.maxByOrNull { it.first }?.let { "*" + it.second }
        }
    }
}
