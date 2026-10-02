package io.github.golangsupport.run

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoDeclarations
import io.github.golangsupport.lang.GoTextLexer
import io.github.golangsupport.lang.GoTextTokens
import io.github.golangsupport.lang.psi.GoCallExpr
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoExpression
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionOrMethodDeclaration
import io.github.golangsupport.lang.psi.GoIndexOrSliceExpr
import io.github.golangsupport.lang.psi.GoLiteral
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoParenthesesExpr
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoReferenceExpression
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes
import io.github.golangsupport.lang.psi.GoUnaryExpr
import io.github.golangsupport.lang.psi.GoVarDeclaration
import io.github.golangsupport.lang.psi.GoVarDefinition
import io.github.golangsupport.semantic.api.GoSemanticService
import io.github.golangsupport.semantic.psi.GoPsiUtil.inner
import io.github.golangsupport.semantic.psi.GoPsiUtil.isSlice

/** What of debugging is pure: how delve is started and what it says, which lines take a breakpoint, what an expression under the mouse is. */
object DlvDap {
    private val LISTENING = Regex("""DAP server listening at:\s*(\S+):(\d+)""")

    /** `DAP server listening at: 127.0.0.1:63940`, the line `dlv dap` prints once it accepts a connection; checked on delve 1.27. */
    fun listeningAt(line: String): Pair<String, Int>? = LISTENING.find(line)?.let { it.groupValues[1] to it.groupValues[2].toInt() }

    /**
     * `--listen=127.0.0.1:0`: delve picks a free port and says which, so there is no race for a port chosen here. No `--log-dest`:
     * with it the line about the port goes to that file as well (seen live), so the log is taken from the output of the process.
     */
    fun arguments(log: Boolean, anyGoVersion: Boolean): List<String> = buildList {
        add("dap")
        add("--listen=127.0.0.1:0")
        // a delve newer than the toolchain refuses to start the program ("Go version ... is too old for this version of Delve")
        if (anyGoVersion) add("--check-go-version=false")
        if (log) addAll(listOf("--log", "--log-output=dap,debugger"))
    }
}

/**
 * Delve and the toolchain out of step: what delve says when it refuses (`Version of Go is too old for this version of Delve (minimum supported
 * version 1.23, ...)`, or `too new ... (maximum supported version 1.24, ...)`), turned into a sentence with the way out. `--check-go-version=false`
 * silences the check, but a delve far off the toolchain then fails in less clear ways, so the refusal is worth explaining when it does come.
 */
object DelveGoVersion {
    enum class Kind { GO_TOO_OLD, GO_TOO_NEW }

    class Mismatch(val kind: Kind, val limit: String) {
        /** [goVersion]: the toolchain the plugin uses, when known. */
        fun explain(goVersion: String?): String {
            val installed = goVersion?.let { ", and Go $it is installed" }.orEmpty()
            return when (kind) {
                Kind.GO_TOO_OLD -> "This delve needs Go $limit or newer$installed: update Go, or install a delve of that time (go install github.com/go-delve/delve/cmd/dlv@vX.Y.Z)."
                Kind.GO_TOO_NEW -> "This delve supports Go up to $limit$installed: update delve (Settings | Tools | Go, or go install github.com/go-delve/delve/cmd/dlv@latest)."
            }
        }
    }

    private val TOO_OLD = Regex("""too old for this version of Delve \(minimum supported version (\d+(?:\.\d+)*)""")
    private val TOO_NEW = Regex("""too new for this version of Delve \(maximum supported version (\d+(?:\.\d+)*)""")

    fun find(text: String): Mismatch? =
        TOO_OLD.find(text)?.let { Mismatch(Kind.GO_TOO_OLD, it.groupValues[1]) } ?: TOO_NEW.find(text)?.let { Mismatch(Kind.GO_TOO_NEW, it.groupValues[1]) }
}

/**
 * A program delve built but could not start: a temp directory that policy (AppLocker, SRP), an antivirus or a `noexec` mount keeps
 * binaries from running in. Told apart from a build that failed, which has the messages of the compiler with it.
 */
object DebugBinaryRefusal {
    private val BUILD = Regex("""Build Error|\.go:\d+(?::\d+)?: """)
    private val REFUSED = Regex(
        """(?i)access is denied|blocked by group policy|contains a virus|permission denied|operation not permitted|could not launch process|""" +
            """could not fork/exec|exec format error|not allowed by|0x800704EC|error 1260""",
    )

    fun isExecutionRefused(text: String): Boolean = !BUILD.containsMatchIn(text) && REFUSED.containsMatchIn(text)
}

/** The exception breakpoint filters of delve, as its `initialize` response lists them. */
enum class GoPanicFilter(val id: String, val title: String) {
    UNRECOVERED_PANIC("unrecovered-panic", "Unrecovered panics"),
    FATAL_THROW("runtime-fatal-throw", "Fatal throws of the runtime"),
}

/** The 0-based lines a breakpoint makes sense at: code inside the body of a function, or inside a multi-line initializer of a variable (a function literal). */
object GoBreakpointLines {
    fun find(text: CharSequence): Set<Int> {
        val structure = GoDeclarations.scan(text)
        val bodies = structure.declarations.mapNotNull { declaration ->
            when (declaration.kind) {
                GoDeclarationKind.FUNCTION, GoDeclarationKind.METHOD -> declaration.body
                GoDeclarationKind.VAR -> declaration.range
                else -> null
            }
        }
        if (bodies.isEmpty()) return emptySet()
        val lineStarts = ArrayList<Int>().apply {
            add(0)
            for (i in text.indices) if (text[i] == '\n') add(i + 1)
        }
        fun lineOf(offset: Int): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }

        val result = HashSet<Int>()
        val lexer = GoTextLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            if (type != TokenType.WHITE_SPACE && type !in GoTextTokens.COMMENTS && bodies.any { start > it.startOffset && start < it.endOffset - 1 }) result += lineOf(start)
            lexer.advance()
        }
        // the header of a function is a place to stop at as well: delve puts the breakpoint at its first instruction
        structure.declarations.filter { it.body != null && it.kind != GoDeclarationKind.STRUCT && it.kind != GoDeclarationKind.INTERFACE }.forEach { result += lineOf(it.nameRange.startOffset) }
        return result
    }

    /**
     * The same rules decided by the PSI of go-psi: the line of the name of a function or a method, every line with code inside its body
     * but the braces of the body itself (a lone `}` of a function, a comment, a blank line are not), and the lines of a package-level
     * `var` past its first token. Read action.
     */
    fun find(file: GoFile): Set<Int> {
        val lines = GoDebugPsi.Lines(file.viewProvider.contents)
        val result = HashSet<Int>()
        for (declaration in file.children) {
            when (declaration) {
                is GoFunctionOrMethodDeclaration -> {
                    declaration.identifier?.let { result += lines.lineOf(it.textRange.startOffset) }
                    val block = declaration.block ?: continue
                    GoDebugPsi.codeLeaves(block).filter { it !== block.lbrace && it !== block.rbrace }.forEach { result += lines.lineOf(it.textRange.startOffset) }
                }
                // past the name, and not the closing brace of a function literal: the rules of the text version
                is GoVarDeclaration -> for (spec in declaration.varSpecList) {
                    val leaves = GoDebugPsi.codeLeaves(spec).toList()
                    leaves.drop(1).dropLast(if (leaves.lastOrNull()?.node?.elementType == GoTypes.RBRACE) 1 else 0).forEach { result += lines.lineOf(it.textRange.startOffset) }
                }
            }
        }
        return result
    }
}

/** What the debugger helpers share on the PSI path: the file of a document when its PSI is current, lines of a text, the leaves of code. */
object GoDebugPsi {
    /**
     * [psi] on the Go file of [document] when the document is committed, [text] on its text otherwise; both in one read action, so this
     * may be called from the EDT and from the threads of the debugger alike. The document is never committed here: that would wait for
     * the EDT.
     */
    fun <T> compute(project: Project?, document: Document, psi: (GoFile) -> T, text: (CharSequence) -> T): T = ReadAction.compute<T, RuntimeException> {
        val manager = project?.takeUnless { it.isDisposed }?.let(PsiDocumentManager::getInstance)
        val file = manager?.takeIf { it.isCommitted(document) }?.getPsiFile(document) as? GoFile
        if (file != null) psi(file) else text(document.immutableCharSequence)
    }

    /** Zero-based lines of a text by offset. */
    class Lines(text: CharSequence) {
        private val starts = ArrayList<Int>().apply { add(0); text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) } }
        val count: Int get() = starts.size
        fun lineOf(offset: Int): Int = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        fun startOf(line: Int): Int = starts[line]
    }

    /** The leaves of [element] that are code: no white space, no comments. */
    fun codeLeaves(element: PsiElement): Sequence<PsiElement> = SyntaxTraverser.psiTraverser(element).traverse().filter {
        it.firstChild == null && it !is PsiWhiteSpace && it.textLength > 0 && it.node.elementType !in GoTokenSets.COMMENTS
    }.asSequence()
}

/**
 * The expression a debugger evaluates when the mouse rests on code: the identifier under the pointer with the selectors to the left of
 * it (`order.Currency` on `Currency`). From the PSI where there is one ([rangeAt] with a file), else by tokens. Nothing that would run code of the program (a name followed by `(`), no package
 * qualifiers the debugger cannot tell from variables anyway: delve answers those with an error and the hint stays empty.
 */
object GoHoverExpression {
    private class Token(val type: IElementType, val start: Int, val end: Int)

    fun rangeAt(text: CharSequence, offset: Int): TextRange? {
        val tokens = ArrayList<Token>()
        val lexer = GoTextLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in GoTextTokens.COMMENTS) tokens += Token(type, lexer.tokenStart, lexer.tokenEnd)
            if (lexer.tokenStart > offset) break // one token past the pointer is enough to see a call
            lexer.advance()
        }
        val index = tokens.indexOfFirst { offset >= it.start && offset < it.end }
        if (index < 0 || tokens[index].type != GoTextTokens.IDENTIFIER) return null
        if (tokens.getOrNull(index + 1)?.type == GoTextTokens.LPAREN) return null
        var first = index
        while (first >= 2 && tokens[first - 1].type == GoTextTokens.DOT && tokens[first - 2].type == GoTextTokens.IDENTIFIER) first -= 2
        // `f().x`, `items[0].Name`: what is to the left is not a plain name, the chain would be evaluated out of its context
        if (first >= 1 && tokens[first - 1].type == GoTextTokens.DOT) return null
        return TextRange(tokens[first].start, tokens[index].end)
    }

    /**
     * The same from the PSI of go-psi: the smallest expression under [offset] that delve evaluates without running code - a name, a
     * selector chain with what is left of it (`a.b[i].c` on `c`), an index on its bracket (`a[i]`), a dereference on its star (`*p`).
     * Not a name that is called, not a chain with a call in it, not a type or a function name; a declared variable is its own name.
     * Read action.
     */
    fun rangeAt(file: GoFile, offset: Int): TextRange? {
        val leaf = file.findElementAt(offset) ?: return null
        val expression: PsiElement = when (leaf.node.elementType) {
            GoTypes.IDENTIFIER -> when (val parent = leaf.parent) {
                is GoReferenceExpression -> parent.takeIf { it.identifier == leaf } ?: return null
                is GoVarDefinition, is GoParamDefinition, is GoReceiver, is GoConstDefinition -> return leaf.textRange
                else -> return null
            }
            GoTypes.LBRACK, GoTypes.RBRACK -> leaf.parent as? GoIndexOrSliceExpr ?: return null
            GoTypes.MUL -> leaf.parent as? GoUnaryExpr ?: return null
            else -> return null
        }
        if ((expression.parent as? GoCallExpr)?.expression === expression) return null
        return expression.textRange.takeIf { evaluable(expression) }
    }

    private fun evaluable(element: PsiElement?): Boolean = when (element) {
        is GoReferenceExpression -> element.expression?.let(::evaluable) ?: true
        is GoIndexOrSliceExpr -> !element.isSlice && PsiTreeUtil.getChildrenOfTypeAsList(element, GoExpression::class.java).let { it.isNotEmpty() && it.all(::evaluable) }
        is GoUnaryExpr -> element.mul != null && evaluable(element.expression)
        is GoParenthesesExpr -> evaluable(element.inner)
        is GoLiteral, is GoStringLiteral -> true
        else -> false
    }
}

/** Delve evaluates a function call only when told to: `call f(x)`. People type `f(x)`. */
object GoEvaluate {
    private val CALL = Regex("""^([A-Za-z_][\w.]*)\s*\(.*\)$""", RegexOption.DOT_MATCHES_ALL)

    /** The builtins and conversions delve evaluates by itself; with `call` in front it would look for a function of that name. */
    private val OWN = setOf("len", "cap", "complex", "imag", "real", "min", "max") + io.github.golangsupport.lang.GoTextTokens.BUILTIN_TYPES

    fun expression(text: String): String {
        val trimmed = text.trim()
        val function = CALL.matchEntire(trimmed)?.groupValues?.get(1) ?: return text
        return if (function in OWN || trimmed.startsWith("call ")) text else "call $trimmed"
    }
}

/** The hit conditions delve understands: a positive number, optionally after `==`, `!=`, `>=`, `>`, `<=`, `<` or `%`. */
object HitCondition {
    private val SYNTAX = Regex("""(==|!=|>=|>|<=|<|%)?\s*([1-9]\d*)""")

    fun isValid(text: String?): Boolean = text.isNullOrBlank() || SYNTAX.matches(text.trim())

    /** Delve wants a space between the operator and the number (`>= 3`) and takes a bare number as `==`; blank is no condition. */
    fun normalize(text: String?): String? {
        val match = SYNTAX.matchEntire(text?.trim().orEmpty()) ?: return null
        val (operator, number) = match.destructured
        return if (operator.isEmpty()) number else "$operator $number"
    }
}

/**
 * What is being completed in an expression typed for the debugger (Evaluate, a watch, a condition of a breakpoint). There is no parser
 * behind it: the names come from the stopped program, so all that is needed is where the caret is - at a new name (locals, arguments)
 * or after `value.` (the fields of that value).
 */
object GoDebugCompletion {
    /** [qualifier] is the expression before the dot, null at a name that stands alone; [prefix] is what is typed of the name so far. */
    class Context(val qualifier: String?, val prefix: String)

    private class Token(val type: IElementType, val start: Int, val end: Int)

    /**
     * Null where names are not completed: in strings, comments and numbers, and after a dot whose left side is not a plain chain of
     * names (`Make().`, `items[0].`): finding the fields would mean evaluating it, i.e. running code of the program while typing.
     */
    fun contextAt(text: CharSequence, offset: Int): Context? {
        val tokens = ArrayList<Token>()
        val lexer = GoTextLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            if (lexer.tokenStart >= offset) break
            // the caret inside or at the end of a literal or a comment
            if ((type in GoTextTokens.COMMENTS || type in GoTextTokens.STRINGS) && offset <= lexer.tokenEnd) return null
            if (type != TokenType.WHITE_SPACE && type !in GoTextTokens.COMMENTS) tokens += Token(type, lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        val last = tokens.lastOrNull() ?: return Context(null, "")
        if (last.type == GoTextTokens.NUMBER && last.end >= offset) return null

        val typing = last.end >= offset && (last.type == GoTextTokens.IDENTIFIER || last.type == GoTextTokens.KEYWORD)
        val prefix = if (typing) text.subSequence(last.start, offset).toString() else ""
        val before = if (typing) tokens.size - 2 else tokens.size - 1
        if (before < 0 || tokens[before].type != GoTextTokens.DOT) return Context(null, prefix)

        // `name.`: the chain that ends with that name, found the way a hover finds it
        val name = before - 1
        if (name < 0) return null
        val range = GoHoverExpression.rangeAt(text.subSequence(0, tokens[name].end), tokens[name].start) ?: return null
        if (range.endOffset != tokens[name].end) return null
        return Context(text.subSequence(range.startOffset, range.endOffset).toString(), prefix)
    }

    /** Delve lists more than fields under a value: `[0]`, `[key]`. Only what can be typed is offered. */
    fun isName(name: String): Boolean = name.isNotEmpty() && (name[0].isLetter() || name[0] == '_') && name.drop(1).all { it.isLetterOrDigit() || it == '_' }
}

/**
 * Where the value of a variable is shown in the editor while the program stands at a line, as in GoLand: on the lines of the current
 * function, up to the line of execution, that mention the variable. By tokens: there is no parser to tell a declaration from a use,
 * and both are worth the value.
 */
object GoInlineValues {
    /**
     * Zero-based lines that mention [name] as a name of its own (`total`, not `order.total` and not `"total"`), inside the function that
     * contains [currentLine] and not below that line: what is below has not happened yet, the value there would be a guess.
     */
    fun lines(text: CharSequence, name: String, currentLine: Int): List<Int> {
        if (name.isEmpty()) return emptyList()
        val lineStarts = ArrayList<Int>().apply { add(0); text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) } }
        if (currentLine !in lineStarts.indices) return emptyList()
        val lineEnd = if (currentLine + 1 < lineStarts.size) lineStarts[currentLine + 1] else text.length

        // the function around the line: a method, a function, or the variable whose initializer is a function literal
        val offset = lineStarts[currentLine]
        val from = GoDeclarations.scan(text).declarations.filter { it.body != null || it.kind == GoDeclarationKind.VAR }
            .filter { offset >= it.range.startOffset && offset < it.range.endOffset }.maxByOrNull { it.range.startOffset }?.range?.startOffset ?: 0

        val result = LinkedHashSet<Int>()
        val lexer = GoTextLexer()
        lexer.start(text, 0, text.length, 0)
        var previousCode: String? = null
        var line = 0
        var position = 0
        while (true) {
            val type = lexer.tokenType ?: break
            val start = lexer.tokenStart
            if (start >= lineEnd) break
            while (position < start) if (text[position++] == '\n') line++
            val token = text.subSequence(start, lexer.tokenEnd).toString()
            if (start >= from && type == GoTextTokens.IDENTIFIER && token == name && previousCode != ".") result += line
            if (type != TokenType.WHITE_SPACE && type !in GoTextTokens.COMMENTS) previousCode = token
            lexer.advance()
        }
        return result.toList()
    }

    /** A variable mentioned in code: [expression] is what delve is asked (`b.c` for a field of `b`), [name] the variable it starts with. */
    class Mention(val name: String, val expression: String, val offset: Int)

    /**
     * The variables the code on [line] (zero-based) declares or uses, by resolve: locals, parameters, receivers, with the fields selected
     * from them (`x := f(a, b.c)` -> `x`, `a`, `b.c`). Not a function, a type, a package, a constant. Read action, smart mode.
     */
    fun of(file: GoFile, line: Int): List<String> {
        val lines = GoDebugPsi.Lines(file.viewProvider.contents)
        if (line !in 0 until lines.count) return emptyList()
        val end = if (line + 1 < lines.count) lines.startOf(line + 1) else file.textLength
        return mentions(file, lines.startOf(line), end).map { it.expression }.distinct().toList()
    }

    /** [lines] on the PSI: the lines of the function around [currentLine], up to it, that mention the variable [name]. Read action, smart mode. */
    fun lines(file: GoFile, name: String, currentLine: Int): List<Int> {
        if (name.isEmpty()) return emptyList()
        val lines = GoDebugPsi.Lines(file.viewProvider.contents)
        if (currentLine !in 0 until lines.count) return emptyList()
        val lineStart = lines.startOf(currentLine)
        val lineEnd = if (currentLine + 1 < lines.count) lines.startOf(currentLine + 1) else file.textLength
        // the top-level declaration around the line: a function, a method, a variable with a function literal
        val code = (lineStart until lineEnd).firstOrNull { !file.viewProvider.contents[it].isWhitespace() } ?: lineStart
        var declaration = file.findElementAt(code)
        while (declaration != null && declaration.parent !is GoFile) declaration = declaration.parent
        val from = declaration?.takeIf { it.parent is GoFile }?.textRange?.startOffset ?: 0
        return mentions(file, from, lineEnd).filter { it.name == name }.map { lines.lineOf(it.offset) }.distinct().toList()
    }

    private fun mentions(file: GoFile, from: Int, to: Int): Sequence<Mention> {
        val semantic = GoSemanticService.getInstance(file.project)
        var leaf = file.findElementAt(from)
        return generateSequence { leaf?.takeIf { it.textRange.startOffset < to }?.also { leaf = PsiTreeUtil.nextLeaf(it) } }.mapNotNull { identifier ->
            if (identifier.node.elementType != GoTypes.IDENTIFIER || identifier.textRange.startOffset < from || identifier.text == "_") return@mapNotNull null
            when (val parent = identifier.parent) {
                is GoVarDefinition, is GoParamDefinition, is GoReceiver -> Mention(identifier.text, identifier.text, identifier.textRange.startOffset)
                is GoReferenceExpression -> {
                    if (parent.identifier != identifier || parent.expression != null || !isVariable(semantic, parent)) return@mapNotNull null
                    // the fields selected from the variable go with it; a method call does not
                    var expression: GoReferenceExpression = parent
                    while (true) {
                        val outer = expression.parent as? GoReferenceExpression ?: break
                        if (outer.expression !== expression || (outer.parent as? GoCallExpr)?.expression === outer) break
                        if (runCatching { semantic.resolve(outer) }.getOrDefault(emptyList()).none { it is GoFieldDefinition }) break
                        expression = outer
                    }
                    Mention(identifier.text, expression.text, identifier.textRange.startOffset)
                }
                else -> null
            }
        }
    }

    private fun isVariable(semantic: GoSemanticService, reference: GoReferenceExpression): Boolean =
        runCatching { semantic.resolve(reference) }.getOrDefault(emptyList()).any { it is GoVarDefinition || it is GoParamDefinition || it is GoReceiver }
}
