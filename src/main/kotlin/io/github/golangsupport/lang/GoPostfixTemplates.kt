package io.github.golangsupport.lang

import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoArrayType
import io.github.golangsupport.semantic.types.GoBasicKind
import io.github.golangsupport.semantic.types.GoBasicType
import io.github.golangsupport.semantic.types.GoChanDir
import io.github.golangsupport.semantic.types.GoChanType
import io.github.golangsupport.semantic.types.GoInterfaceType
import io.github.golangsupport.semantic.types.GoMapType
import io.github.golangsupport.semantic.types.GoPointerType
import io.github.golangsupport.semantic.types.GoSignatureType
import io.github.golangsupport.semantic.types.GoSliceType
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypeParamType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * `expr.if`, `err.nil`, `items.for`, `value.return`, `call.err`: the postfix templates. The expression is the [GoExpression] of the PSI
 * that ends right before the key (the platform gives a committed copy of the file without the key); its type decides where a template
 * applies and what it writes (`for k, v := range m` for a map, `v, err := call` for a call returning an error last). An unknown type (a
 * name that does not resolve, dumb mode) does not hide a template: the code is being typed. What comes out is a live template, so the
 * names to type are its stops.
 */
class GoPostfixTemplateProvider : PostfixTemplateProvider {
    private val templates: Set<PostfixTemplate> = GoPostfixKinds.ALL.map { GoPostfixTemplate(it, this) }.toSet()

    override fun getTemplates(): Set<PostfixTemplate> = templates
    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'
    override fun preExpand(file: PsiFile, editor: Editor) {}
    override fun afterExpand(file: PsiFile, editor: Editor) {}
    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
    override fun getId(): String = "go"
    override fun getPresentableName(): String = "Go"
}

/** What a template is given: the expression (its text and range), its type (unknown when the PSI cannot tell) and the PSI, if any. */
class GoPostfixSubject(val text: String, val range: TextRange, val type: GoType, val expression: GoExpression?, val isStatement: Boolean) {
    val isCall: Boolean get() = expression is GoCallExpr || expression == null && text.endsWith(")")
}

/** A template's text: a live template with `$EXPR$` for the expression, the stops in their order, the imports it needs. */
class GoPostfixExpansion(val text: String, val stops: List<Pair<String, String>> = emptyList(), val imports: List<String> = emptyList())

/** One kind of template: where it applies and what it writes for a subject. */
class GoPostfixKind(
    val key: String,
    val example: String,
    val statement: Boolean,
    val applies: (GoPostfixSubject) -> Boolean,
    val expand: (GoPostfixSubject, GoPostfixContext) -> GoPostfixExpansion,
)

/** What a template may want from the place: the `return` that leaves the function with an error, the zero values of its results. */
class GoPostfixContext(val errorExit: (String) -> String, val returnValues: (GoPostfixSubject) -> List<String?>, val importName: (String) -> String)

class GoPostfixTemplate(private val kind: GoPostfixKind, provider: PostfixTemplateProvider) :
    PostfixTemplate("go.${kind.key}", kind.key, kind.example, provider) {

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean {
        val file = context.containingFile as? GoFile ?: return false
        val subject = GoPostfixExpressions.subject(file, copyDocument, newOffset, kind.statement) ?: return false
        return kind.applies(subject)
    }

    override fun expand(context: PsiElement, editor: Editor) {
        val project = context.project
        val document = editor.document
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document) as? GoFile ?: return
        val offset = editor.caretModel.offset
        val subject = GoPostfixExpressions.subject(file, document, offset, kind.statement) ?: return
        val expansion = kind.expand(subject, GoPostfixExpressions.context(file, subject))
        var range = subject.range
        // the imports go above the expression, so its range moves by what they add
        for (path in expansion.imports) {
            val insertion = GoImports.add(document.immutableCharSequence, path) ?: continue
            if (insertion.offset > range.startOffset) continue
            document.insertString(insertion.offset, insertion.text)
            range = range.shiftRight(insertion.text.length)
        }
        document.deleteString(range.startOffset, range.endOffset)
        editor.caretModel.moveToOffset(range.startOffset)
        val manager = TemplateManager.getInstance(project)
        val template = manager.createTemplate("go.postfix.${kind.key}", "go", expansion.text)
        template.isToReformat = false
        if ("\$EXPR$" in expansion.text) template.addVariable("EXPR", ConstantNode(subject.text), false)
        for ((name, default) in expansion.stops) template.addVariable(name, ConstantNode(default), ConstantNode(default), true)
        manager.startTemplate(editor, template)
    }
}

/** The expression a postfix key applies to. */
object GoPostfixExpressions {
    private val SUBJECT = Key.create<Pair<Pair<Long, Int>, List<GoPostfixSubject?>>>("go.postfix.subject")

    /**
     * The expression that ends at [offset] in [file]: for a statement template the whole expression of an expression statement
     * (`items` in `items.for`, not `x := items.for`), for an expression template the outermost expression that ends there. From the PSI
     * when [document] is committed (always so in the copy the platform checks against), else from the text before [offset].
     */
    fun subject(file: GoFile, document: Document, offset: Int, statement: Boolean): GoPostfixSubject? {
        val documents = PsiDocumentManager.getInstance(file.project)
        if (documents.getPsiFile(document) === file && !documents.isCommitted(document)) return textSubject(document.immutableCharSequence, offset, statement)
        // the platform asks every template against the same copy: the answer is computed once per file state and offset
        val stamp = file.modificationStamp to offset
        val cached = file.getUserData(SUBJECT)?.takeIf { it.first == stamp }?.second
        val both = cached ?: listOf(psiSubject(file, offset, false), psiSubject(file, offset, true)).also { file.putUserData(SUBJECT, stamp to it) }
        return both[if (statement) 1 else 0]
    }

    private fun psiSubject(file: GoFile, offset: Int, statement: Boolean): GoPostfixSubject? {
        var end = offset
        val text = file.viewProvider.contents
        // a dot left over from the key belongs to nothing
        if (end > 0 && text[end - 1] == '.') end--
        if (end <= 0) return null
        val leaf = file.findElementAt(end - 1) ?: return null
        var top: GoExpression? = null
        var e: PsiElement? = leaf
        while (e != null && e !is PsiFile && e.textRange.endOffset == end) {
            if (e is GoExpression) top = e
            e = e.parent
        }
        val expression = top ?: return null
        if (statement && !isExpressionStatement(expression)) return null
        return GoPostfixSubject(expression.text, expression.textRange, typeOf(expression), expression, isExpressionStatement(expression))
    }

    /** `x` of the statement `x`: the only expression of a simple statement that is nothing else (no `:=`, no assignment). */
    private fun isExpressionStatement(expression: GoExpression): Boolean {
        val list = expression.parent as? GoLeftHandExprList ?: return false
        val statement = list.parent as? GoSimpleStatement ?: return false
        return list.expressionList.size == 1 && statement.statement == null && statement.textRange == expression.textRange
    }

    private fun typeOf(expression: GoExpression): GoType {
        if (DumbService.isDumb(expression.project)) return GoUnknownType
        return try { GoSemanticService.getInstance(expression.project).typeOf(expression) } catch (_: IndexNotReadyException) { GoUnknownType }
    }

    private fun textSubject(text: CharSequence, offset: Int, statement: Boolean): GoPostfixSubject? {
        val range = rangeBefore(text, offset) ?: return null
        val lineStart = text.lastIndexOf('\n', range.startOffset - 1) + 1
        val atStatement = text.subSequence(lineStart, range.startOffset).isBlank()
        if (statement && !atStatement) return null
        return GoPostfixSubject(text.substring(range.startOffset, range.endOffset), range, GoUnknownType, null, atStatement)
    }

    /** What the place gives the templates: the exit with an error, the values of a `return`, the names of imports. */
    fun context(file: GoFile, subject: GoPostfixSubject): GoPostfixContext {
        val place = subject.expression
        val function = place?.let { runCatching { GoReturnValues.function(it) }.getOrNull() }
        return GoPostfixContext(
            errorExit = { error -> GoIdioms.returnStatement(error, function) },
            returnValues = { s -> returnValues(file, s) },
            importName = { path -> (if (place != null) GoReturnValues.importName(file, path) else null) ?: path.substringAfterLast('/') },
        )
    }

    /**
     * The values of the `return` with `x` placed among the results of the function around by its type (null there), the others their zero
     * values: `0, err` for an error in a function of `(int, error)`, `n, nil` for an int; just `x` where the PSI cannot tell or there is one result.
     */
    private fun returnValues(file: GoFile, subject: GoPostfixSubject): List<String?> {
        val place = subject.expression ?: return listOf(null)
        if (DumbService.isDumb(file.project)) return listOf(null)
        val results = try { GoSemanticService.getInstance(file.project).enclosingResultTypes(place) } catch (_: IndexNotReadyException) { null }
        if (results == null || results.size < 2) return listOf(null)
        val type = subject.type
        val index = when {
            type is GoUnknownType -> 0
            GoReturnValues.isError(type) -> results.indexOfLast { GoReturnValues.isError(it) }.takeIf { it >= 0 } ?: 0
            else -> results.indexOfFirst { !GoReturnValues.isError(it) && GoTypePredicates.assignable(type, it) }.takeIf { it >= 0 }
                ?: results.indexOfFirst { !GoReturnValues.isError(it) }.takeIf { it >= 0 } ?: 0
        }
        return results.mapIndexed { i, r -> if (i == index) null else GoReturnValues.zeroValue(r, file) }
    }

    /**
     * `a.b(c)[0]`, `f(x, y)`, `"text"`, `42`, `!ok`, `&x`, `*p`: from the caret back over names, dots, balanced brackets and literals,
     * then over a prefix operator. Null when there is nothing (the line starts here) or the piece is not an expression (a keyword).
     * The text fallback for a document that is not committed.
     */
    fun rangeBefore(text: CharSequence, offset: Int): TextRange? {
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
        if (head in GoNames.KEYWORDS && head != "func") return null
        return TextRange(i, end)
    }

    /** The index of the bracket that [closeIndex] (`)` or `]`) matches; strings inside are skipped. */
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

/** The type questions of the templates; an unknown type answers yes to all of them but [isVoid]. */
object GoPostfixTypes {
    fun known(type: GoType): Boolean = type !is GoUnknownType && !(type is GoBasicType && type.kind == GoBasicKind.INVALID)

    private fun basic(type: GoType): GoBasicType? = type.underlying() as? GoBasicType

    fun isBool(type: GoType): Boolean = !known(type) || basic(type)?.kind?.isBoolean == true
    fun isInteger(type: GoType): Boolean = basic(type)?.kind?.isInteger == true
    fun isString(type: GoType): Boolean = basic(type)?.kind?.isString == true
    fun isError(type: GoType): Boolean = !known(type) || GoReturnValues.isError(type)
    fun isVoid(type: GoType): Boolean = type is GoTupleType && type.types.isEmpty()
    fun isValue(type: GoType): Boolean = !isVoid(type) && type !is GoTupleType

    fun isNillable(type: GoType): Boolean {
        if (!known(type)) return true
        return when (val u = type.underlying()) {
            is GoPointerType, is GoSliceType, is GoMapType, is GoChanType, is GoSignatureType -> true
            is GoInterfaceType -> !u.isConstraintOnly
            is GoBasicType -> u.kind == GoBasicKind.UNSAFE_POINTER || u.kind == GoBasicKind.UNTYPED_NIL
            else -> false
        }
    }

    fun isSlice(type: GoType): Boolean = !known(type) || type.underlying() is GoSliceType || type is GoTypeParamType

    /** What `len` takes: slices, arrays (and pointers to them), strings, maps, channels. */
    fun hasLength(type: GoType): Boolean {
        if (!known(type) || type is GoTypeParamType) return true
        return when (val u = type.underlying()) {
            is GoSliceType, is GoArrayType, is GoMapType, is GoChanType -> true
            is GoPointerType -> u.elem.underlying() is GoArrayType
            is GoBasicType -> u.kind.isString
            else -> false
        }
    }

    /** What `for range` goes over: slices, arrays, pointers to arrays, strings, maps, receiving channels, integers, iterator functions. */
    fun isRangeable(type: GoType): Boolean {
        if (!known(type) || type is GoTypeParamType) return true
        return when (val u = type.underlying()) {
            is GoSliceType, is GoArrayType, is GoMapType -> true
            is GoChanType -> u.dir != GoChanDir.SEND
            is GoPointerType -> u.elem.underlying() is GoArrayType
            is GoBasicType -> u.kind.isString || u.kind.isInteger
            is GoSignatureType -> u.results.isEmpty() && (u.params.singleOrNull()?.type?.underlying() as? GoSignatureType)?.params?.size in 0..2
            else -> false
        }
    }

    /** The variables of `for … := range x` with their defaults: `k, v` for a map, `v` for a channel, `i` for an int. */
    fun rangeVariables(type: GoType): List<Pair<String, String>> {
        if (!known(type)) return listOf("KEY" to "_", "VALUE" to "v")
        return when (val u = type.underlying()) {
            is GoMapType -> listOf("KEY" to "k", "VALUE" to "v")
            is GoChanType -> listOf("VALUE" to "v")
            is GoBasicType -> if (u.kind.isInteger) listOf("KEY" to "i") else listOf("KEY" to "i", "VALUE" to "r")
            is GoSignatureType -> when ((u.params.singleOrNull()?.type?.underlying() as? GoSignatureType)?.params?.size) {
                0 -> emptyList()
                1 -> listOf("VALUE" to "v")
                else -> listOf("KEY" to "k", "VALUE" to "v")
            }
            else -> listOf("KEY" to "_", "VALUE" to "v")
        }
    }

    /** The element of a slice when it is ordered (`slices.Sort` takes it), else null. */
    fun orderedElement(type: GoType): Boolean = ((type.underlying() as? GoSliceType)?.elem?.underlying() as? GoBasicType)?.kind?.isOrdered == true
}

/** The templates. */
object GoPostfixKinds {
    private fun block(head: String) = "$head {\n\t\$END$\n}"

    private fun kind(key: String, example: String, statement: Boolean = true, applies: (GoPostfixSubject) -> Boolean = { true },
                     expand: (GoPostfixSubject, GoPostfixContext) -> GoPostfixExpansion) = GoPostfixKind(key, example, statement, applies, expand)

    private fun fixed(key: String, example: String, text: String, statement: Boolean = true, vararg stops: Pair<String, String>,
                      applies: (GoPostfixSubject) -> Boolean = { true }) =
        kind(key, example, statement, applies) { _, _ -> GoPostfixExpansion(text, stops.toList()) }

    private fun value(s: GoPostfixSubject) = GoPostfixTypes.isValue(s.type)

    /** The variables of `a, b, err := call` for a call of several results: the last error is `err`, the others `v`, `v2`, … */
    private fun tupleNames(type: GoType): List<String>? {
        val tuple = type as? GoTupleType ?: return null
        var n = 0
        return tuple.types.mapIndexed { i, t -> if (i == tuple.types.lastIndex && GoReturnValues.isError(t)) "err" else if (n++ == 0) "v" else "v$n" }
    }

    /** `.err`: an `error` value or a call whose last result is an error. */
    private fun errorSubject(s: GoPostfixSubject): Boolean {
        if (!GoPostfixTypes.known(s.type)) return true
        val t = s.type
        return GoReturnValues.isError(t) || s.isCall && t is GoTupleType && t.types.lastOrNull()?.let(GoReturnValues::isError) == true
    }

    private fun errorCheck(s: GoPostfixSubject, c: GoPostfixContext): GoPostfixExpansion {
        val names = tupleNames(s.type)
        return when {
            names != null -> {
                val stops = names.dropLast(1).mapIndexed { i, n -> "V$i" to n }
                val left = (stops.map { "\$${it.first}$" } + "err").joinToString(", ")
                GoPostfixExpansion("$left := \$EXPR$\nif err != nil {\n\t\$RESULT$\n}\$END$", stops + ("RESULT" to c.errorExit("err")))
            }
            s.isCall -> GoPostfixExpansion("if err := \$EXPR$; err != nil {\n\t\$RESULT$\n}\$END$", listOf("RESULT" to c.errorExit("err")))
            // a variable of type error: checked as it is
            else -> GoPostfixExpansion("if \$EXPR$ != nil {\n\t\$RESULT$\n}\$END$", listOf("RESULT" to c.errorExit(s.text)))
        }
    }

    val ALL: List<GoPostfixKind> = listOf(
        fixed("if", "if expr {}", block("if \$EXPR$"), applies = { GoPostfixTypes.isBool(it.type) }),
        fixed("else", "if !expr {}", block("if !\$EXPR$"), applies = { GoPostfixTypes.isBool(it.type) }),
        fixed("nil", "if expr == nil {}", block("if \$EXPR$ == nil"), applies = { GoPostfixTypes.isNillable(it.type) }),
        fixed("nn", "if expr != nil {}", block("if \$EXPR$ != nil"), applies = { GoPostfixTypes.isNillable(it.type) }),
        fixed("notnil", "if expr != nil {}", block("if \$EXPR$ != nil"), applies = { GoPostfixTypes.isNillable(it.type) }),
        kind("err", "if err := expr; err != nil {}", applies = ::errorSubject, expand = ::errorCheck),
        kind("errv", "v, err := expr; if err != nil {}", applies = { !GoPostfixTypes.known(it.type) || it.isCall && tupleNames(it.type)?.lastOrNull() == "err" }) { s, c ->
            if (tupleNames(s.type) != null) errorCheck(s, c)
            else GoPostfixExpansion("\$VAR$, err := \$EXPR$\nif err != nil {\n\t\$RESULT$\n}\$END$", listOf("VAR" to "v", "RESULT" to c.errorExit("err")))
        },
        kind("errn", "if err := expr; err != nil { return nil, err }", applies = { it.isCall && (!GoPostfixTypes.known(it.type) || GoReturnValues.isError(it.type)) }) { _, _ ->
            GoPostfixExpansion("if err := \$EXPR$; err != nil {\n\treturn nil, err\n}\$END$")
        },
        kind("return", "return expr", applies = { value(it) }) { s, c ->
            GoPostfixExpansion("return " + c.returnValues(s).joinToString(", ") { it ?: "\$EXPR$" } + "\$END$")
        },
        fixed("rr", "return expr, nil", "return \$EXPR$, nil\$END$", applies = ::value),
        kind("var", "v := expr", applies = { !GoPostfixTypes.isVoid(it.type) }) { s, _ ->
            val names = tupleNames(s.type)
            if (names == null) GoPostfixExpansion("\$VAR$ := \$EXPR$\$END$", listOf("VAR" to "v"))
            else {
                val stops = names.mapIndexed { i, n -> "V$i" to n }
                GoPostfixExpansion(stops.joinToString(", ") { "\$${it.first}$" } + " := \$EXPR$\$END$", stops)
            }
        },
        kind("for", "for _, v := range expr {}", applies = { GoPostfixTypes.isRangeable(it.type) }) { s, _ ->
            val vars = GoPostfixTypes.rangeVariables(s.type)
            val head = if (vars.isEmpty()) "for range \$EXPR$" else "for " + vars.joinToString(", ") { "\$${it.first}$" } + " := range \$EXPR$"
            GoPostfixExpansion(block(head), vars)
        },
        fixed("range", "for range expr {}", block("for range \$EXPR$"), applies = { GoPostfixTypes.isRangeable(it.type) }),
        kind("fori", "for i := 0; i < expr; i++ {}", applies = { GoPostfixTypes.isInteger(it.type) || GoPostfixTypes.hasLength(it.type) }) { s, _ ->
            val limit = if (GoPostfixTypes.known(s.type) && !GoPostfixTypes.isInteger(s.type)) "len(\$EXPR$)" else "\$EXPR$"
            GoPostfixExpansion(block("for \$I$ := 0; \$I$ < $limit; \$I$++"), listOf("I" to "i"))
        },
        kind("forr", "for i := len(expr) - 1; i >= 0; i-- {}", applies = { GoPostfixTypes.isInteger(it.type) || GoPostfixTypes.hasLength(it.type) }) { s, _ ->
            val start = if (GoPostfixTypes.isInteger(s.type)) "\$EXPR$ - 1" else "len(\$EXPR$) - 1"
            GoPostfixExpansion(block("for \$I$ := $start; \$I$ >= 0; \$I$--"), listOf("I" to "i"))
        },
        fixed("len", "len(expr)", "len(\$EXPR$)\$END$", statement = false, applies = { GoPostfixTypes.hasLength(it.type) }),
        kind("print", "fmt.Println(expr)", applies = ::value) { _, c -> GoPostfixExpansion("${c.importName("fmt")}.Println(\$EXPR$)\$END$", imports = listOf("fmt")) },
        kind("printf", "fmt.Printf(\"%v\", expr)", applies = ::value) { _, c ->
            GoPostfixExpansion("${c.importName("fmt")}.Printf(\"\$FORMAT$\\n\", \$EXPR$)\$END$", listOf("FORMAT" to "%v"), listOf("fmt"))
        },
        fixed("panic", "panic(expr)", "panic(\$EXPR$)\$END$", applies = ::value),
        fixed("go", "go expr", "go \$EXPR$\$END$", applies = { it.isCall }),
        fixed("defer", "defer expr", "defer \$EXPR$\$END$", applies = { it.isCall }),
        fixed("append", "expr = append(expr, v)", "\$EXPR$ = append(\$EXPR$, \$VALUE$)\$END$", true, "VALUE" to "", applies = { GoPostfixTypes.isSlice(it.type) }),
        kind("not", "!expr", statement = false, applies = { GoPostfixTypes.isBool(it.type) }) { s, _ ->
            val e = s.expression
            when {
                e is GoUnaryExpr && e.not != null -> GoPostfixExpansion((e.expression?.text ?: s.text.removePrefix("!")) + "\$END$")
                e is GoBinaryExpr -> GoPostfixExpansion("!(\$EXPR$)\$END$")
                else -> GoPostfixExpansion("!\$EXPR$\$END$")
            }
        },
        fixed("par", "(expr)", "(\$EXPR$)\$END$", statement = false),
        fixed("switch", "switch expr {}", "switch \$EXPR$ {\ncase \$CASE$:\n\t\$END$\n}", true, "CASE" to "", applies = ::value),
        kind("wrap", "fmt.Errorf(\"…: %w\", expr)", statement = false, applies = { GoPostfixTypes.isError(it.type) }) { _, c ->
            GoPostfixExpansion("${c.importName("fmt")}.Errorf(\"\$MESSAGE$: %w\", \$EXPR$)\$END$", listOf("MESSAGE" to ""), listOf("fmt"))
        },
        kind("sort", "sort.Slice(expr, func(i, j int) bool {})", applies = { GoPostfixTypes.isSlice(it.type) }) { s, c ->
            if (GoPostfixTypes.orderedElement(s.type)) GoPostfixExpansion("${c.importName("slices")}.Sort(\$EXPR$)\$END$", imports = listOf("slices"))
            else GoPostfixExpansion(
                "${c.importName("sort")}.Slice(\$EXPR$, func(i, j int) bool {\n\treturn \$EXPR$[i]\$FIELD$ < \$EXPR$[j]\$FIELD$\n})\$END$",
                listOf("FIELD" to ""), listOf("sort"),
            )
        },
    )
}
