package io.github.golangsupport.catalogue

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoImport
import io.github.golangsupport.lang.GoTokens
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes

/**
 * The package-level declarations of a Go file read from disk outside the indices of the IDE: the modules of the catalogue (the standard
 * library and the module cache, scanned once per version into a file) and the interfaces of a package the indices do not have
 * ([GoInterfaceSources]). A PSI per file of GOROOT would cost more than the catalogue is worth, so the top level is read from the tokens of
 * the lexer of go-psi: `func`, `type`, `var`, `const`, each closed by a balanced bracket or by the end of a line. Statements and
 * expressions are not looked into; broken code is skipped, never thrown on. Pure.
 */
object GoSourceScanner {
    private const val MAX_SIGNATURE = 120

    class Scanned(val packageName: String?, val imports: List<GoImport>, val declarations: List<GoDeclarationInfo>)

    fun scan(text: CharSequence): Scanned = Scanner(text).scan()

    /** What the scanner tells apart, as the tokens of go-psi map onto it. */
    private enum class Kind { IDENTIFIER, KEYWORD, STRING, RAW_STRING, CHAR, NUMBER, LBRACE, RBRACE, LPAREN, RPAREN, LBRACKET, RBRACKET, SEMICOLON, COMMA, DOT, OPERATOR }

    private class Token(val type: Kind, val start: Int, val end: Int, val newLineBefore: Boolean)

    private class Scanner(private val text: CharSequence) {
        private val tokens = tokenize(text)
        private var i = 0
        private var packageName: String? = null
        private val imports = ArrayList<GoImport>()

        fun scan(): Scanned {
            val declarations = ArrayList<GoDeclarationInfo>()
            while (i < tokens.size) {
                when (keyword(i)) {
                    "package" -> {
                        if (type(i + 1) == Kind.IDENTIFIER && packageName == null) packageName = text(i + 1)
                        i++
                    }
                    "import" -> specs(::importSpec)
                    "func" -> function()?.let(declarations::add)
                    "type" -> specs { typeSpec(it)?.let(declarations::add) }
                    "const" -> specs { declarations += valueSpec(it, GoDeclarationKind.CONST) }
                    "var" -> specs { declarations += valueSpec(it, GoDeclarationKind.VAR) }
                    // a stray bracket of broken code: step over what it encloses
                    else -> if (isOpen(i)) i = closing(i) + 1 else i++
                }
            }
            return Scanned(packageName, imports, declarations)
        }

        /** `keyword spec` or `keyword ( spec; spec )`; [spec] gets the offset a single declaration starts at, or null inside a group. */
        private fun specs(spec: (Int?) -> Unit) {
            val keywordStart = tokens[i].start
            i++
            if (type(i) != Kind.LPAREN) {
                val before = i
                spec(keywordStart)
                if (i == before) i++
                return
            }
            val close = closing(i)
            i++
            while (i < close) {
                val before = i
                if (type(i) == Kind.SEMICOLON) i++ else spec(null)
                if (i == before) i++
            }
            i = maxOf(i, close + 1)
        }

        private fun importSpec(@Suppress("UNUSED_PARAMETER") start: Int?) {
            val first = i
            val alias = if (type(i) == Kind.IDENTIFIER || type(i) == Kind.DOT) text(i++) else null
            if (type(i) == Kind.STRING || type(i) == Kind.RAW_STRING) {
                imports += GoImport(text(i).trim('"', '`'), alias, TextRange(tokens[first].start, tokens[i].end))
                i++
            }
        }

        private fun function(): GoDeclarationInfo? {
            val start = tokens[i].start
            i++
            var receiver: String? = null
            if (type(i) == Kind.LPAREN) {
                val close = closing(i)
                receiver = receiverType(i + 1, close)
                i = close + 1
            }
            if (type(i) != Kind.IDENTIFIER) return null
            val name = i++
            val signatureStart = i
            if (type(i) == Kind.LBRACKET) i = closing(i) + 1
            if (type(i) == Kind.LPAREN) i = closing(i) + 1
            // the results, up to the body or, for a function implemented elsewhere, to the end of the line
            var body: TextRange? = null
            while (i < tokens.size && !endsHere(i)) {
                if (type(i) == Kind.LBRACE && !isTypeLiteralBrace(i)) {
                    val close = closing(i)
                    body = TextRange(tokens[i].start, tokens[close.coerceAtMost(tokens.lastIndex)].end)
                    break
                }
                i = if (isOpen(i)) closing(i) + 1 else i + 1
            }
            val signatureEnd = i
            if (body != null) i = closing(i) + 1
            val end = body?.endOffset ?: endBefore(signatureEnd, name)
            return GoDeclarationInfo(
                if (receiver != null) GoDeclarationKind.METHOD else GoDeclarationKind.FUNCTION, text(name), range(name), TextRange(start, end),
                receiver, signature(signatureStart, signatureEnd), body,
            )
        }

        /** `s *Server[T]` -> `Server`: the last identifier outside the type parameters. */
        private fun receiverType(from: Int, to: Int): String? {
            var result: String? = null
            var j = from
            while (j < to.coerceAtMost(tokens.size)) {
                if (type(j) == Kind.LBRACKET) {
                    j = closing(j) + 1
                    continue
                }
                if (type(j) == Kind.IDENTIFIER) result = text(j)
                j++
            }
            return result
        }

        private fun typeSpec(keywordStart: Int?): GoDeclarationInfo? {
            if (type(i) != Kind.IDENTIFIER) return null
            val name = i++
            val start = keywordStart ?: tokens[name].start
            // type parameters; `type A [4]int` goes the same way and loses nothing
            if (type(i) == Kind.LBRACKET) i = closing(i) + 1
            if (isOperator(i, '=')) i++
            val typeStart = i
            val literal = keyword(i).takeIf { (it == "struct" || it == "interface") && type(i + 1) == Kind.LBRACE }
            if (literal != null) {
                val open = i + 1
                val close = closing(open)
                val children = if (literal == "struct") fields(open + 1, close) else interfaceMethods(open + 1, close)
                i = close + 1
                val end = tokens[close.coerceAtMost(tokens.lastIndex)].end
                val kind = if (literal == "struct") GoDeclarationKind.STRUCT else GoDeclarationKind.INTERFACE
                return GoDeclarationInfo(kind, text(name), range(name), TextRange(start, end), body = TextRange(tokens[open].start, end), children = children)
            }
            skipToEndOfSpec()
            val end = endBefore(i, name)
            return GoDeclarationInfo(GoDeclarationKind.TYPE, text(name), range(name), TextRange(start, end), signature = signature(typeStart, i))
        }

        /** `a, b int = 1, 2`: a declaration per name; the blank identifier declares nothing. */
        private fun valueSpec(keywordStart: Int?, kind: GoDeclarationKind): List<GoDeclarationInfo> {
            val names = ArrayList<Int>()
            while (type(i) == Kind.IDENTIFIER) {
                names += i++
                if (type(i) == Kind.COMMA) i++ else break
            }
            if (names.isEmpty()) return emptyList()
            val typeStart = i
            var typeEnd = i
            while (typeEnd < tokens.size && !endsHere(typeEnd) && !isOperator(typeEnd, '=')) typeEnd = if (isOpen(typeEnd)) closing(typeEnd) + 1 else typeEnd + 1
            skipToEndOfSpec()
            val range = TextRange(keywordStart ?: tokens[names.first()].start, endBefore(i, names.last()))
            val valueType = signature(typeStart, typeEnd.coerceAtMost(i))
            // several names of one spec cannot share its range: the PSI nodes made of them would nest
            return names.filter { text(it) != "_" }.map { GoDeclarationInfo(kind, text(it), range(it), if (names.size == 1) range else range(it), signature = valueType) }
        }

        /** The lines of a struct body: `a, b T`, `T`, `*pkg.T`, each with an optional tag. */
        private fun fields(from: Int, to: Int): List<GoDeclarationInfo> = lines(from, to).flatMap { (first, last) ->
            // `data []byte` is a named field, `List[int]` an embedded generic type: gofmt keeps the bracket of the latter next to the name
            val genericEmbedded = type(first + 1) == Kind.LBRACKET && tokens[first].end == tokens[first + 1].start
            val named = type(first) == Kind.IDENTIFIER && first < last && type(first + 1) != Kind.DOT && type(first + 1) != Kind.STRING &&
                type(first + 1) != Kind.RAW_STRING && !genericEmbedded
            val lineRange = TextRange(tokens[first].start, tokens[last].end)
            if (named) {
                val names = ArrayList<Int>()
                var j = first
                while (j <= last && type(j) == Kind.IDENTIFIER) {
                    names += j++
                    if (j <= last && type(j) == Kind.COMMA) j++ else break
                }
                val fieldType = signature(j, tagStart(j, last))
                names.filter { text(it) != "_" }.map { GoDeclarationInfo(GoDeclarationKind.FIELD, text(it), range(it), if (names.size == 1) lineRange else range(it), signature = fieldType) }
            } else {
                // an embedded field is named by its type: `*pkg.T` and `T[int]` are `T`
                var name = -1
                var j = first
                while (j <= last && type(j) != Kind.LBRACKET && type(j) != Kind.STRING && type(j) != Kind.RAW_STRING) {
                    if (type(j) == Kind.IDENTIFIER) name = j
                    j++
                }
                if (name < 0) emptyList() else listOf(GoDeclarationInfo(GoDeclarationKind.FIELD, text(name), range(name), lineRange))
            }
        }

        private fun tagStart(from: Int, last: Int): Int {
            val isTag = last >= from && (type(last) == Kind.STRING || type(last) == Kind.RAW_STRING)
            return if (isTag) last else last + 1
        }

        /** `Name(params) results`; embedded interfaces and type unions are not members. */
        private fun interfaceMethods(from: Int, to: Int): List<GoDeclarationInfo> = lines(from, to).mapNotNull { (first, last) ->
            if (type(first) != Kind.IDENTIFIER || type(first + 1) != Kind.LPAREN || first + 1 > last) return@mapNotNull null
            GoDeclarationInfo(GoDeclarationKind.INTERFACE_METHOD, text(first), range(first), TextRange(tokens[first].start, tokens[last].end), signature = signature(first + 1, last + 1))
        }

        /** The token ranges of the lines between two braces; a line goes on through the brackets opened in it. */
        private fun lines(from: Int, to: Int): List<Pair<Int, Int>> {
            val result = ArrayList<Pair<Int, Int>>()
            var j = from
            val end = to.coerceAtMost(tokens.size)
            while (j < end) {
                if (type(j) == Kind.SEMICOLON) {
                    j++
                    continue
                }
                val first = j
                j = if (isOpen(j)) closing(j) + 1 else j + 1
                while (j < end && !endsHere(j)) j = if (isOpen(j)) closing(j) + 1 else j + 1
                result += first to (j - 1).coerceAtMost(end - 1)
            }
            return result
        }

        private fun skipToEndOfSpec() {
            while (i < tokens.size && !endsHere(i) && type(i) != Kind.RPAREN) i = if (isOpen(i)) closing(i) + 1 else i + 1
        }

        /**
         * Does a statement end before the token [j]: an explicit `;`, or the automatic one, which Go inserts at a line break after an
         * identifier, a literal, a closing bracket or one of a few keywords.
         */
        private fun endsHere(j: Int): Boolean {
            if (j >= tokens.size || type(j) == Kind.SEMICOLON) return true
            if (!tokens[j].newLineBefore || j == 0) return false
            val previous = j - 1
            return when (type(previous)) {
                Kind.IDENTIFIER, Kind.NUMBER, Kind.STRING, Kind.RAW_STRING, Kind.CHAR,
                Kind.RPAREN, Kind.RBRACKET, Kind.RBRACE -> true
                Kind.KEYWORD -> text(previous) in ENDING_KEYWORDS
                // `x++` and `x--` end a statement, other operators continue it
                Kind.OPERATOR -> text(previous) == "++" || text(previous) == "--"
                else -> false
            }
        }

        /** `interface{` and `struct{` open a type, `[]T{`, `map[K]V{` and `T{` a composite literal: neither is the body of a function. */
        private fun isTypeLiteralBrace(j: Int): Boolean = keyword(j - 1) == "struct" || keyword(j - 1) == "interface"

        private fun signature(from: Int, to: Int): String? {
            if (from >= to || from >= tokens.size) return null
            val builder = StringBuilder()
            for (j in from until to.coerceAtMost(tokens.size)) {
                if (j > from && tokens[j - 1].end < tokens[j].start) builder.append(' ')
                builder.append(text, tokens[j].start, tokens[j].end)
                if (builder.length > MAX_SIGNATURE) return builder.substring(0, MAX_SIGNATURE) + "…"
            }
            return builder.toString().replace(WHITESPACE, " ")
        }

        /** The end of the token before [j], which a bracket that is never closed puts past the last one. */
        private fun endBefore(j: Int, atLeast: Int): Int = tokens[(j - 1).coerceIn(atLeast, tokens.lastIndex)].end

        private fun type(j: Int): Kind? = tokens.getOrNull(j)?.type
        private fun text(j: Int): String = tokens[j].let { text.subSequence(it.start, it.end).toString() }
        private fun range(j: Int): TextRange = TextRange(tokens[j].start, tokens[j].end)
        private fun keyword(j: Int): String? = if (type(j) == Kind.KEYWORD) text(j) else null
        private fun isOperator(j: Int, c: Char): Boolean = type(j) == Kind.OPERATOR && text[tokens[j].start] == c
        private fun isOpen(j: Int): Boolean = type(j) in CLOSING

        /** The index of the bracket that closes the one at [open]; the last token when it is never closed. */
        private fun closing(open: Int): Int {
            var depth = 0
            for (j in open until tokens.size) {
                when (type(j)) {
                    Kind.LPAREN, Kind.LBRACKET, Kind.LBRACE -> depth++
                    Kind.RPAREN, Kind.RBRACKET, Kind.RBRACE -> if (--depth == 0) return j
                    else -> {}
                }
            }
            return tokens.size
        }
    }

    private val CLOSING = mapOf(Kind.LPAREN to Kind.RPAREN, Kind.LBRACKET to Kind.RBRACKET, Kind.LBRACE to Kind.RBRACE)
    private val ENDING_KEYWORDS = setOf("break", "continue", "fallthrough", "return")
    private val WHITESPACE = Regex("\\s+")

    /** The tokens that matter, each knowing whether a line break separates it from the previous one. */
    private fun tokenize(text: CharSequence): List<Token> {
        val result = ArrayList<Token>()
        var newLine = false
        for (token in GoTokens.all(text)) {
            val type = token.type
            when {
                // the semicolon the lexer inserts at a line end is the line break itself
                type == GoTypes.SEMICOLON_SYNTHETIC -> newLine = true
                type == TokenType.WHITE_SPACE || type in GoTokenSets.COMMENTS -> if (containsLineBreak(text, token.start, token.end)) newLine = true
                else -> {
                    result += Token(kindOf(type), token.start, token.end, newLine)
                    newLine = false
                }
            }
        }
        return result
    }

    private fun kindOf(type: com.intellij.psi.tree.IElementType): Kind = when {
        type == GoTypes.IDENTIFIER -> Kind.IDENTIFIER
        type in GoTokenSets.KEYWORDS -> Kind.KEYWORD
        type == GoTypes.STRING -> Kind.STRING
        type == GoTypes.RAW_STRING -> Kind.RAW_STRING
        type == GoTypes.CHAR -> Kind.CHAR
        type in GoTokenSets.NUMBERS -> Kind.NUMBER
        type == GoTypes.LBRACE -> Kind.LBRACE
        type == GoTypes.RBRACE -> Kind.RBRACE
        type == GoTypes.LPAREN -> Kind.LPAREN
        type == GoTypes.RPAREN -> Kind.RPAREN
        type == GoTypes.LBRACK -> Kind.LBRACKET
        type == GoTypes.RBRACK -> Kind.RBRACKET
        type == GoTypes.SEMICOLON -> Kind.SEMICOLON
        type == GoTypes.COMMA -> Kind.COMMA
        type == GoTypes.PERIOD -> Kind.DOT
        else -> Kind.OPERATOR
    }

    private fun containsLineBreak(text: CharSequence, from: Int, to: Int): Boolean {
        for (j in from until to) if (text[j] == '\n') return true
        return false
    }
}
