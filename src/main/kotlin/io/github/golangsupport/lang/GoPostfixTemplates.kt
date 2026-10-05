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
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.golangsupport.lang.psi.GoBinaryExpr
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoLeftHandExprList
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoSimpleStatement
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.types.GoTupleType
import io.github.golangsupport.semantic.types.GoType
import io.github.golangsupport.semantic.types.GoTypePredicates
import io.github.golangsupport.semantic.types.GoUnknownType

/**
 * `expr.if`, `err.nil`, `items.for`, `value.return`, `call.err`: the postfix templates. The expression is the [GoExpression] of the PSI
 * that ends right before the key (the platform gives a committed copy of the file without the key); its type decides where a template
 * applies and what it writes (`for k, v := range m` for a map, `v, err := call` for a call returning an error last). An unknown type (a
 * name that does not resolve, dumb mode) does not hide a template: the code is being typed. What comes out is a live template, so the
 * names to type are its stops. `x.` opens completion with the applicable keys behind the members: the platform's live template
 * contributor lists them, [GoPostfixCompletionWeigher] keeps them last. `.!`, `.&`, `.*` are keys of their own (the platform reads a key
 * back to the first terminal symbol, so `!`, `&`, `*` are terminal too); they apply only right after a dot.
 */
class GoPostfixTemplateProvider : PostfixTemplateProvider {
    private val templates: Set<PostfixTemplate> = GoPostfixKinds.ALL.map { GoPostfixTemplate(it, this) }.toSet()

    override fun getTemplates(): Set<PostfixTemplate> = templates
    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.' || currentChar in GoPostfixKind.SYMBOLS
    override fun preExpand(file: PsiFile, editor: Editor) {}
    override fun afterExpand(file: PsiFile, editor: Editor) {}
    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
    override fun getId(): String = "go"
    override fun getPresentableName(): String = "Go"
}

/** What a template is given: the expression (its text and range), its type (unknown when the PSI cannot tell) and the PSI, if any. */
class GoPostfixSubject(val text: String, val range: TextRange, val type: GoType, val expression: GoExpression?, val isStatement: Boolean) {
    val isCall: Boolean get() = expression is GoCallExpr || expression == null && text.endsWith(")")

    /** A number or a string literal: nothing to take the address of. */
    val isLiteral: Boolean
        get() = expression is GoLiteral || expression is GoStringLiteral || expression == null && text.firstOrNull()?.let { it.isDigit() || it == '"' || it == '`' } == true
}

/** A template's text: a live template with `$EXPR$` for the expression, the stops in their order, the imports it needs. */
class GoPostfixExpansion(val text: String, val stops: List<Pair<String, String>> = emptyList(), val imports: List<String> = emptyList(), val expr: String? = null)

/** One kind of template: where it applies and what it writes for a subject; [description] is what Settings | Postfix Completion shows. */
class GoPostfixKind(
    val key: String,
    val example: String,
    val statement: Boolean,
    val applies: (GoPostfixSubject) -> Boolean,
    val expand: (GoPostfixSubject, GoPostfixContext) -> GoPostfixExpansion,
    val description: String = example,
) {
    /** `!`, `&`, `*`: typed after the dot, the key is the symbol alone (`x.!`). */
    val isSymbol: Boolean get() = key.length == 1 && key[0] in SYMBOLS

    /** The id of the template in the settings: a symbol gets a name there. */
    val id: String get() = "go." + (SYMBOL_NAMES[key] ?: key)

    companion object {
        const val SYMBOLS = "!&*"
        private val SYMBOL_NAMES = mapOf("!" to "bang", "&" to "ampersand", "*" to "star")
    }
}

/**
 * What a template may want from the place: the `return` that leaves the function with an error, the zero values of its results, the
 * local name of an import, the names a new variable must not take, whether the subject implements `sort.Interface`.
 */
class GoPostfixContext(
    val errorExit: (String) -> String,
    val returnValues: (GoPostfixSubject) -> List<String?>,
    val importName: (String) -> String,
    val taken: () -> Set<String> = { emptySet() },
    val sortable: (GoPostfixSubject) -> Boolean = { false },
)

class GoPostfixTemplate(private val kind: GoPostfixKind, provider: PostfixTemplateProvider) :
    PostfixTemplate(kind.id, kind.key, if (kind.isSymbol) kind.key else ".${kind.key}", kind.example, provider) {

    // one class serves every key: the description is the kind's, not a resource folder named after the class
    override fun calcDescription(): String = kind.description

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean {
        val file = context.containingFile as? GoFile ?: return false
        // `x!` is not `x.!`
        if (kind.isSymbol && copyDocument.immutableCharSequence.getOrNull(newOffset - 1) != '.') return false
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
        // `x.!`: the dot before a symbol key is still in the document
        val tail = if (offset == subject.range.endOffset + 1 && document.immutableCharSequence.getOrNull(subject.range.endOffset) == '.') 1 else 0
        var range = TextRange(subject.range.startOffset, subject.range.endOffset + tail)
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
        if ("\$EXPR$" in expansion.text) template.addVariable("EXPR", ConstantNode(expansion.expr ?: subject.text), false)
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
        // `fmt.`, `Circle.`: a package or a type is not a value, its members are what the list is for
        if (expression is GoReferenceExpression && expression.expression == null && isPackageOrType(expression)) return null
        return GoPostfixSubject(expression.text, expression.textRange, typeOf(expression), expression, isExpressionStatement(expression))
    }

    /** `x` of the statement `x`: the only expression of a simple statement that is nothing else (no `:=`, no assignment). */
    private fun isExpressionStatement(expression: GoExpression): Boolean {
        val list = expression.parent as? GoLeftHandExprList ?: return false
        val statement = list.parent as? GoSimpleStatement ?: return false
        return list.expressionList.size == 1 && statement.statement == null && statement.textRange == expression.textRange
    }

    private fun isPackageOrType(reference: GoReferenceExpression): Boolean {
        if (DumbService.isDumb(reference.project)) return false
        val target = try { GoSemanticService.getInstance(reference.project).resolve(reference).firstOrNull() } catch (_: IndexNotReadyException) { null }
        return target is GoImportSpec || target is GoTypeSpec || target is PsiDirectory
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

    /** What the place gives the templates: the exit with an error, the values of a `return`, the names of imports and of the scope. */
    fun context(file: GoFile, subject: GoPostfixSubject): GoPostfixContext {
        val place = subject.expression
        val function = place?.let { runCatching { GoReturnValues.function(it) }.getOrNull() }
        return GoPostfixContext(
            errorExit = { error -> GoIdioms.returnStatement(error, function) },
            returnValues = { s -> returnValues(file, s) },
            importName = { path -> (if (place != null) GoReturnValues.importName(file, path) else null) ?: path.substringAfterLast('/') },
            taken = { place?.let { GoPostfixNames.takenAt(file, it.textRange.startOffset) }.orEmpty() },
            sortable = { s -> s.expression?.let { GoPostfixTypes.implementsSort(it, s.type) } == true },
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

/** The templates; their type questions are [GoPostfixTypes], the names they suggest [GoPostfixNames]. */
object GoPostfixKinds {
    private fun block(head: String) = "$head {\n\t\$END$\n}"

    private fun kind(key: String, example: String, description: String, statement: Boolean = true, applies: (GoPostfixSubject) -> Boolean = { true },
                     expand: (GoPostfixSubject, GoPostfixContext) -> GoPostfixExpansion) = GoPostfixKind(key, example, statement, applies, expand, description)

    private fun fixed(key: String, example: String, description: String, text: String, statement: Boolean = true, vararg stops: Pair<String, String>,
                      imports: List<String> = emptyList(), applies: (GoPostfixSubject) -> Boolean = { true }) =
        kind(key, example, description, statement, applies) { _, _ -> GoPostfixExpansion(text, stops.toList(), imports) }

    /** `pkg.F(…)`: a call of package [path] with its local name; [args] has `$EXPR$` and the stops. */
    private fun call(key: String, example: String, description: String, path: String, function: String, args: String, statement: Boolean,
                     vararg stops: Pair<String, String>, applies: (GoPostfixSubject) -> Boolean) =
        kind(key, example, description, statement, applies) { _, c -> GoPostfixExpansion("${c.importName(path)}.$function($args)\$END$", stops.toList(), listOf(path)) }

    private fun value(s: GoPostfixSubject) = GoPostfixTypes.isValue(s.type)

    /** The variables of `a, b, err := call` for a call of several results: the last error is `err`, the first other [first], then `v2`, … */
    private fun tupleNames(type: GoType, first: String = "v"): List<String>? {
        val tuple = type as? GoTupleType ?: return null
        var n = 0
        return tuple.types.mapIndexed { i, t -> if (i == tuple.types.lastIndex && GoReturnValues.isError(t)) "err" else if (n++ == 0) first else "v$n" }
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

    /** `n, err := strconv.ParseInt(s, 10, 64)` with the check of the error. */
    private fun parse(s: GoPostfixSubject, c: GoPostfixContext, name: String, call: String): GoPostfixExpansion {
        val variable = GoPostfixNames.unique(name, c.taken())
        return GoPostfixExpansion("\$VAR$, err := ${c.importName("strconv")}.$call\nif err != nil {\n\t\$RESULT$\n}\$END$",
            listOf("VAR" to variable, "RESULT" to c.errorExit("err")), listOf("strconv"))
    }

    /**
     * The stops of `for … := range x`: the value named after the collection (`name` of `names`; `.forr` says `item` when the name is no
     * plural, `.for` keeps `v`), the index `i` when [index] asks for one.
     */
    private fun rangeStops(s: GoPostfixSubject, c: GoPostfixContext, index: Boolean): List<Pair<String, String>> {
        val element = if (GoPostfixTypes.hasElements(s.type)) GoPostfixNames.elementName(s.text) ?: "item".takeIf { index } else null
        return GoPostfixTypes.rangeVariables(s.type).map { (name, default) ->
            when {
                name == "VALUE" && element != null && default == "v" -> name to GoPostfixNames.unique(element, c.taken())
                name == "KEY" && index && default == "_" -> name to GoPostfixNames.unique("i", c.taken())
                else -> name to default
            }
        }
    }

    private fun rangeLoop(s: GoPostfixSubject, c: GoPostfixContext, index: Boolean): GoPostfixExpansion {
        val vars = rangeStops(s, c, index)
        val head = if (vars.isEmpty()) "for range \$EXPR$" else "for " + vars.joinToString(", ") { "\$${it.first}$" } + " := range \$EXPR$"
        return GoPostfixExpansion(block(head), vars)
    }

    private val notKind: (GoPostfixSubject, GoPostfixContext) -> GoPostfixExpansion = { s, _ ->
        val e = s.expression
        when {
            // the operand goes through the EXPR variable: a `$` in its text would read as a template variable
            e is GoUnaryExpr && e.not != null -> GoPostfixExpansion("\$EXPR$\$END$", expr = e.expression?.text ?: s.text.removePrefix("!"))
            e is GoBinaryExpr ->GoPostfixExpansion("!(\$EXPR$)\$END$")
            else -> GoPostfixExpansion("!\$EXPR$\$END$")
        }
    }

    private const val NOT = "Negates the boolean expression."
    private const val ADDRESS = "Adds the & operator before the expression."
    private const val DEREFERENCE = "Creates a dereference expression."
    private const val APPEND_ASSIGN = "Wraps the expression with the append() built-in function and assigns its result to the expression."

    private fun addressable(s: GoPostfixSubject) = value(s) && !s.isCall && !s.isLiteral

    val ALL: List<GoPostfixKind> = listOf(
        fixed("if", "if expr {}", "Turns the E expression into if E {}.", block("if \$EXPR$"), applies = { GoPostfixTypes.isBool(it.type) }),
        fixed("else", "if !expr {}", "Turns the E expression into if !E {}.", block("if !\$EXPR$"), applies = { GoPostfixTypes.isBool(it.type) }),
        fixed("nil", "if expr == nil {}", "Wraps the expression with the if statement that checks whether the expression is nil.",
            block("if \$EXPR$ == nil"), applies = { GoPostfixTypes.isNillable(it.type) }),
        fixed("nn", "if expr != nil {}", "Wraps the expression with the if statement that checks whether the expression is not nil.",
            block("if \$EXPR$ != nil"), applies = { GoPostfixTypes.isNillable(it.type) }),
        fixed("notnil", "if expr != nil {}", "Wraps the expression with the if statement that checks whether the expression is not nil.",
            block("if \$EXPR$ != nil"), applies = { GoPostfixTypes.isNillable(it.type) }),
        kind("err", "if err := expr; err != nil {}", "Checks the error of an error value or of a call: if err := f(); err != nil { return … }; " +
            "the results of a call of several values are declared first. The return values are the zero values of the function's results.",
            applies = ::errorSubject, expand = ::errorCheck),
        kind("errv", "v, err := expr; if err != nil {}", "Assigns the results of a call to new variables and checks the error: v, err := f(); if err != nil { return … }.",
            applies = { !GoPostfixTypes.known(it.type) || it.isCall && tupleNames(it.type)?.lastOrNull() == "err" }) { s, c ->
            if (tupleNames(s.type) != null) errorCheck(s, c)
            else GoPostfixExpansion("\$VAR$, err := \$EXPR$\nif err != nil {\n\t\$RESULT$\n}\$END$", listOf("VAR" to "v", "RESULT" to c.errorExit("err")))
        },
        kind("errn", "if err := expr; err != nil { return nil, err }", "Checks the error of a call and returns nil with it.",
            applies = { it.isCall && (!GoPostfixTypes.known(it.type) || GoReturnValues.isError(it.type)) }) { _, _ ->
            GoPostfixExpansion("if err := \$EXPR$; err != nil {\n\treturn nil, err\n}\$END$")
        },
        kind("return", "return expr", "Adds the return keyword before the expression; the other results of the function get their zero values.", applies = ::value) { s, c ->
            GoPostfixExpansion("return " + c.returnValues(s).joinToString(", ") { it ?: "\$EXPR$" } + "\$END$")
        },
        fixed("rr", "return expr, nil", "Returns the expression with a nil error.", "return \$EXPR$, nil\$END$", applies = ::value),
        kind("var", "name := expr", "Assigns the expression to a new variable by using :=. The name comes from the expression: area := c.Area(), " +
            "user := loadUser(), item := items[0]; v when the expression gives none.", applies = { !GoPostfixTypes.isVoid(it.type) }) { s, c ->
            val name = GoPostfixNames.variableName(s.text)?.let { GoPostfixNames.unique(it, c.taken()) } ?: "v"
            val names = tupleNames(s.type, name)
            if (names == null) GoPostfixExpansion("\$VAR$ := \$EXPR$\$END$", listOf("VAR" to name))
            else {
                val stops = names.mapIndexed { i, n -> "V$i" to n }
                GoPostfixExpansion(stops.joinToString(", ") { "\$${it.first}$" } + " := \$EXPR$\$END$", stops)
            }
        },
        kind("for", "for _, v := range expr {}", "Creates the range form of the for loop over a slice, an array, a string, a map, a channel, " +
            "an integer or an iterator function; the value is named after the collection (name of names).",
            applies = { GoPostfixTypes.isRangeable(it.type) }) { s, c -> rangeLoop(s, c, index = false) },
        kind("forr", "for index, elem := range expr {}", "Creates the range form of the for loop with an index and a value to iterate over " +
            "a slice or an array; the value is named after the collection (name of names).",
            applies = { GoPostfixTypes.isRangeable(it.type) }) { s, c -> rangeLoop(s, c, index = true) },
        fixed("range", "for range expr {}", "Creates the range form of the for loop without variables.", block("for range \$EXPR$"),
            applies = { GoPostfixTypes.isRangeable(it.type) }),
        kind("fori", "for i := 0; i < expr; i++ {}", "Creates the for loop with an index up to an integer or to the length of the expression.",
            applies = { GoPostfixTypes.isInteger(it.type) || GoPostfixTypes.hasLength(it.type) }) { s, _ ->
            val limit = if (GoPostfixTypes.known(s.type) && !GoPostfixTypes.isInteger(s.type)) "len(\$EXPR$)" else "\$EXPR$"
            GoPostfixExpansion(block("for \$I$ := 0; \$I$ < $limit; \$I$++"), listOf("I" to "i"))
        },
        kind("forrev", "for i := len(expr) - 1; i >= 0; i-- {}", "Creates the for loop that goes from the last index of the expression down to 0.",
            applies = { GoPostfixTypes.isInteger(it.type) || GoPostfixTypes.hasLength(it.type) }) { s, _ ->
            val start = if (GoPostfixTypes.isInteger(s.type)) "\$EXPR$ - 1" else "len(\$EXPR$) - 1"
            GoPostfixExpansion(block("for \$I$ := $start; \$I$ >= 0; \$I$--"), listOf("I" to "i"))
        },
        fixed("len", "len(expr)", "Wraps the expression with the len() built-in function.", "len(\$EXPR$)\$END$", statement = false,
            applies = { GoPostfixTypes.hasLength(it.type) }),
        fixed("cap", "cap(expr)", "Wraps the expression with the cap() built-in function.", "cap(\$EXPR$)\$END$", statement = false,
            applies = { GoPostfixTypes.hasCapacity(it.type) }),
        kind("print", "fmt.Println(expr)", "Wraps the expression with fmt.Println() and imports fmt.", applies = ::value) { _, c ->
            GoPostfixExpansion("${c.importName("fmt")}.Println(\$EXPR$)\$END$", imports = listOf("fmt"))
        },
        fixed("println", "println(expr)", "Wraps the expression with the println() built-in function.", "println(\$EXPR$)\$END$", applies = ::value),
        kind("printf", "fmt.Printf(\"%v\", expr)", "Wraps the expression with fmt.Printf() and imports fmt.", applies = ::value) { _, c ->
            GoPostfixExpansion("${c.importName("fmt")}.Printf(\"\$FORMAT$\\n\", \$EXPR$)\$END$", listOf("FORMAT" to "%v"), listOf("fmt"))
        },
        fixed("panic", "panic(expr)", "Wraps the expression with the panic() built-in function.", "panic(\$EXPR$)\$END$", applies = ::value),
        fixed("go", "go expr", "Runs the call in a new goroutine.", "go \$EXPR$\$END$", applies = { it.isCall }),
        fixed("defer", "defer expr", "Defers the call to the end of the function.", "defer \$EXPR$\$END$", applies = { it.isCall }),
        fixed("append", "append(expr, …)", "Wraps the expression with the append() built-in function.", "append(\$EXPR$, \$VALUE$)\$END$", false,
            "VALUE" to "", applies = { GoPostfixTypes.isSlice(it.type) }),
        fixed("aappend", "xs = append(xs, …)", APPEND_ASSIGN, "\$EXPR$ = append(\$EXPR$, \$VALUE$)\$END$", true, "VALUE" to "",
            applies = { GoPostfixTypes.isSlice(it.type) }),
        fixed("appendAssign", "xs = append(xs, …)", APPEND_ASSIGN, "\$EXPR$ = append(\$EXPR$, \$VALUE$)\$END$", true, "VALUE" to "",
            applies = { GoPostfixTypes.isSlice(it.type) }),
        fixed("copy", "copy(expr, …)", "Wraps the expression with the copy() built-in function.", "copy(\$EXPR$, \$SOURCE$)\$END$", false,
            "SOURCE" to "", applies = { GoPostfixTypes.isSlice(it.type) }),
        fixed("remove", "xs = append(xs[:i], xs[i+1:]...)", "Removes an element from a slice in place (Slice Tricks): xs = append(xs[:i], xs[i+1:]...).",
            "\$EXPR$ = append(\$EXPR$[:\$I$], \$EXPR$[\$I$+1:]...)\$END$", true, "I" to "i", applies = { GoPostfixTypes.isSlice(it.type) }),
        fixed("close", "close(expr)", "Wraps the expression with the close() built-in function.", "close(\$EXPR$)\$END$",
            applies = { GoPostfixTypes.isClosable(it.type) }),
        fixed("delete", "delete(expr, key)", "Wraps the expression with the delete() built-in function.", "delete(\$EXPR$, \$KEY$)\$END$", true,
            "KEY" to "", applies = { GoPostfixTypes.isMap(it.type) }),
        fixed("complex", "complex(expr, …)", "Wraps the expression with the complex() built-in function.", "complex(\$EXPR$, \$IMAGINARY$)\$END$", false,
            "IMAGINARY" to "", applies = { GoPostfixTypes.isFloat(it.type) }),
        fixed("real", "real(expr)", "Wraps the expression with the real() built-in function.", "real(\$EXPR$)\$END$", statement = false,
            applies = { GoPostfixTypes.isComplex(it.type) }),
        fixed("imag", "imag(expr)", "Wraps the expression with the imag() built-in function.", "imag(\$EXPR$)\$END$", statement = false,
            applies = { GoPostfixTypes.isComplex(it.type) }),
        kind("not", "!expr", NOT, statement = false, applies = { GoPostfixTypes.isBool(it.type) }, expand = notKind),
        kind("!", "!expr", NOT, statement = false, applies = { GoPostfixTypes.isBool(it.type) }, expand = notKind),
        fixed("&", "&expr", ADDRESS, "&\$EXPR$\$END$", statement = false, applies = ::addressable),
        fixed("p", "&expr", ADDRESS, "&\$EXPR$\$END$", statement = false, applies = ::addressable),
        fixed("pointer", "&expr", ADDRESS, "&\$EXPR$\$END$", statement = false, applies = ::addressable),
        fixed("*", "*expr", DEREFERENCE, "*\$EXPR$\$END$", statement = false, applies = { GoPostfixTypes.isPointer(it.type) }),
        fixed("d", "*expr", DEREFERENCE, "*\$EXPR$\$END$", statement = false, applies = { GoPostfixTypes.isPointer(it.type) }),
        fixed("dereference", "*expr", DEREFERENCE, "*\$EXPR$\$END$", statement = false, applies = { GoPostfixTypes.isPointer(it.type) }),
        fixed("par", "(expr)", "Wraps the expression with parentheses.", "(\$EXPR$)\$END$", statement = false),
        fixed("switch", "switch expr {}", "Creates the switch statement over the expression.", "switch \$EXPR$ {\ncase \$CASE$:\n\t\$END$\n}", true,
            "CASE" to "", applies = ::value),
        kind("wrap", "fmt.Errorf(\"…: %w\", expr)", "Wraps the error with fmt.Errorf() and %w.", statement = false, applies = { GoPostfixTypes.isError(it.type) }) { _, c ->
            GoPostfixExpansion("${c.importName("fmt")}.Errorf(\"\$MESSAGE$: %w\", \$EXPR$)\$END$", listOf("MESSAGE" to ""), listOf("fmt"))
        },
        call("as", "errors.As(expr, &target)", "Wraps the expression with the errors.As() function.", "errors", "As", "\$EXPR$, &\$TARGET$", false,
            "TARGET" to "target", applies = { GoPostfixTypes.isError(it.type) }),
        call("is", "errors.Is(expr, target)", "Wraps the expression with the errors.Is() function.", "errors", "Is", "\$EXPR$, \$TARGET$", false,
            "TARGET" to "", applies = { GoPostfixTypes.isError(it.type) }),
        kind("parseInt", "n, err := strconv.ParseInt(expr, 10, 64)", "Generates the code to parse int from string, with the check of the error.",
            applies = { GoPostfixTypes.isString(it.type) || !GoPostfixTypes.known(it.type) }) { s, c -> parse(s, c, "n", "ParseInt(\$EXPR$, 10, 64)") },
        kind("parseFloat", "f, err := strconv.ParseFloat(expr, 64)", "Generates the code to parse float64 from string, with the check of the error.",
            applies = { GoPostfixTypes.isString(it.type) || !GoPostfixTypes.known(it.type) }) { s, c -> parse(s, c, "f", "ParseFloat(\$EXPR$, 64)") },
        kind("sort", "sort.Strings(expr)", "Sorts the expression by its type: sort.Strings, sort.Ints, sort.Float64s for []string, []int, []float64, " +
            "sort.Sort for a sort.Interface, slices.Sort for a slice of other ordered elements, sort.Slice for the rest.",
            applies = { GoPostfixTypes.isSlice(it.type) || GoPostfixTypes.known(it.type) && it.expression != null && GoPostfixTypes.implementsSort(it.expression, it.type) }) { s, c ->
            val sort = GoPostfixTypes.sortFunction(s.type)
            when {
                sort != null -> GoPostfixExpansion("${c.importName("sort")}.$sort(\$EXPR$)\$END$", imports = listOf("sort"))
                c.sortable(s) -> GoPostfixExpansion("${c.importName("sort")}.Sort(\$EXPR$)\$END$", imports = listOf("sort"))
                GoPostfixTypes.orderedElement(s.type) -> GoPostfixExpansion("${c.importName("slices")}.Sort(\$EXPR$)\$END$", imports = listOf("slices"))
                else -> GoPostfixExpansion(
                    "${c.importName("sort")}.Slice(\$EXPR$, func(i, j int) bool {\n\treturn \$EXPR$[i]\$FIELD$ < \$EXPR$[j]\$FIELD$\n})\$END$",
                    listOf("FIELD" to ""), listOf("sort"),
                )
            }
        },
    )
}
