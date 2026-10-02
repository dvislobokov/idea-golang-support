package io.github.golangsupport.lang.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.WhitespacesAndCommentsBinder
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.openapi.util.Key
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import io.github.golangsupport.lang.psi.GoTokenSets
import io.github.golangsupport.lang.psi.GoTypes.*

/**
 * External rules for the generated [GoParser]. Grammar-Kit calls these as static methods, so every
 * rule is annotated with [JvmStatic]. Each rule names the go/parser function it ports; the
 * reasoning is in docs/GRAMMAR.md.
 */
object GoParserUtil : GeneratedParserUtilBase() {

    /** Per-parse state: go/parser's `exprLev` (`< 0` inside a control clause header). */
    private class State {
        var exprLev = 0

        /** The builder parses the text of one lazy function body (section N), not a whole file. */
        var bodyChunk = false
    }

    private val STATE_KEY: Key<State> = Key.create("gopsi.parser.state")

    private fun state(b: PsiBuilder): State {
        var s = b.getUserData(STATE_KEY)
        if (s == null) {
            s = State()
            b.putUserData(STATE_KEY, s)
        }
        return s
    }

    /** Tokens that can start a type literal element (go/parser `isTypeElem` on the first token). */
    private val TYPE_ELEM_START: TokenSet = TokenSet.create(LBRACK, STRUCT, FUNC, INTERFACE, MAP, CHAN, TILDE)

    /** Hard limit for lookahead scans so broken input never makes a predicate quadratic. */
    private const val SCAN_LIMIT = 1000

    // ---------------------------------------------------------------------------------------------
    // Section B: composite literal gating (go/parser exprLev)
    // ---------------------------------------------------------------------------------------------

    /** Runs [p] with `exprLev` incremented: inside `(...)`, `[...]`, `{...}` of a literal, func bodies. */
    @JvmStatic
    fun nested(b: PsiBuilder, level: Int, p: Parser): Boolean {
        val s = state(b)
        s.exprLev++
        try {
            return p.parse(b, level)
        } finally {
            s.exprLev--
        }
    }

    /** Runs [p] as a control clause header (`exprLev = -1`), restoring the previous level after. */
    @JvmStatic
    fun controlHeader(b: PsiBuilder, level: Int, p: Parser): Boolean {
        val s = state(b)
        val old = s.exprLev
        s.exprLev = -1
        try {
            return p.parse(b, level)
        } finally {
            s.exprLev = old
        }
    }

    /** go/parser parsePrimaryExpr case LBRACE: a named type may start a composite literal only when `exprLev >= 0`. */
    @JvmStatic
    fun compositeLitAllowed(b: PsiBuilder, level: Int): Boolean = state(b).exprLev >= 0

    // ---------------------------------------------------------------------------------------------
    // Section N: lazy function bodies
    // ---------------------------------------------------------------------------------------------

    /**
     * Marks [b] as the builder of one lazy function body: [columnZeroDeclaration] then never ends a
     * block at a `const`/`type`/`var`/`import` keyword, because [lazyBlock] already decided, with the
     * whole file in view, that the body does not end there.
     */
    @JvmStatic
    fun startBodyChunk(b: PsiBuilder) {
        state(b).bodyChunk = true
    }

    /**
     * A function body (of a declaration or a function literal) as one collapsed lazy `BLOCK`
     * ([GoLazyBlockElementType]); [block] parses its text when it is first accessed. The extent is
     * found by brace matching on the tokens, ending where the eager parse of an unclosed body ends
     * (section J): at the matching `}`, at a `func IDENT`, at a column-0 declaration keyword
     * ([columnZeroDeclaration]) or at EOF. An unclosed body leaves its trailing semicolons to the
     * enclosing rule (a block closed early by a stray inner `}` ends before them too), and the
     * missing `}` it reports counts as an error at the stop token, so the enclosing rule does not
     * report another one there. [block] is not called here: it is the rule the Grammar-Kit PSI
     * generator sees, and the root rule of the lazy parse.
     */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun lazyBlock(b: PsiBuilder, level: Int, block: Parser): Boolean {
        if (b.tokenType !== LBRACE) return false
        var m = b.mark()
        b.advanceLexer() // {
        var depth = 1
        var closed = false
        var semicolonsFrom = -1 // raw index of the first trailing semicolon of the scanned tokens
        while (true) {
            val t = b.tokenType ?: break
            when {
                t === LBRACE -> depth++
                t === RBRACE -> if (--depth == 0) {
                    b.advanceLexer()
                    closed = true
                    break
                }
                t === FUNC -> if (b.lookAhead(1) === IDENTIFIER || columnZeroDeclaration(b, level)) break
                DECLARATION_KEYWORDS.contains(t) -> if (columnZeroDeclaration(b, level)) break
            }
            if (t === SEMICOLON || t === SEMICOLON_SYNTHETIC) {
                if (semicolonsFrom < 0) semicolonsFrom = b.rawTokenIndex()
            } else {
                semicolonsFrom = -1
            }
            b.advanceLexer()
        }
        if (!closed && semicolonsFrom >= 0) {
            m.rollbackTo()
            m = b.mark()
            while (b.tokenType != null && b.rawTokenIndex() < semicolonsFrom) b.advanceLexer()
        }
        m.collapse(BLOCK)
        if (!closed) ErrorState.get(b).currentFrame?.let { it.errorReportedAt = b.rawTokenIndex() }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // File level
    // ---------------------------------------------------------------------------------------------

    /** Reports a missing package clause without consuming anything, so the rest of the file still parses. */
    @JvmStatic
    fun missingPackageClause(b: PsiBuilder, level: Int): Boolean {
        b.error("'package' expected")
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Section C: `type Name [` — type parameter list or array type (go/parser parseTypeSpec, extractName)
    // ---------------------------------------------------------------------------------------------

    /**
     * True if the `[` at the current position starts a type parameter list. Mirrors go/parser's
     * decision after `type Name [`: parse `ident [expr]`, then `extractName` splits `P *C`, `P (E)`
     * and `P *C | D` forms; generic iff a name was extracted and either a type followed or the next
     * token is not `]`.
     */
    @JvmStatic
    fun isTypeParams(b: PsiBuilder, level: Int): Boolean {
        if (b.tokenType !== LBRACK) return false
        val m = b.mark()
        try {
            b.advanceLexer() // [
            if (b.tokenType !== IDENTIFIER) return false
            b.advanceLexer() // P
            val t = b.tokenType ?: return false
            return when {
                t === RBRACK -> false // [P]T
                t === LBRACK || t === COMMA -> true // [P []int], [P, Q any]
                t === IDENTIFIER || t === TILDE || t === STRUCT || t === FUNC || t === INTERFACE
                    || t === MAP || t === CHAN -> true
                t === ARROW -> b.lookAhead(1) === CHAN // [P <-chan int]
                t === MUL -> {
                    b.advanceLexer() // *
                    b.isTypeElemOperandOrForced()
                }
                t === LPAREN -> {
                    // P (E): generic only if E is a type element or a comma forces it.
                    b.advanceLexer()
                    val inner = b.tokenType
                    if (inner != null && TYPE_ELEM_START.contains(inner)) return true
                    b.skipBalanced(LPAREN, RPAREN)
                    b.isForcedByCommaOrUnion()
                }
                else -> false // [N]T, [a.b]T, [P | Q]T, [P * 2]T ...
            }
        } finally {
            m.rollbackTo()
        }
    }

    /** After `P *`: the operand is a type element, or the rest of the expression forces a type parameter. */
    private fun PsiBuilder.isTypeElemOperandOrForced(): Boolean {
        val t = tokenType ?: return false
        if (TYPE_ELEM_START.contains(t)) return true
        if (t === ARROW && lookAhead(1) === CHAN) return true
        if (t === LPAREN) {
            advanceLexer()
            val inner = tokenType
            if (inner != null && TYPE_ELEM_START.contains(inner)) return true
            skipBalanced(LPAREN, RPAREN)
            return isForcedByCommaOrUnion()
        }
        if (t !== IDENTIFIER) return false
        skipOperand()
        return isForcedByCommaOrUnion()
    }

    /** After an operand: `,` forces a type parameter; in `a | b | ...` a type-element term does too. */
    private fun PsiBuilder.isForcedByCommaOrUnion(): Boolean {
        var guard = 0
        while (guard++ < SCAN_LIMIT) {
            val t = tokenType ?: return false
            when {
                t === COMMA -> return true
                t === OR -> {
                    advanceLexer()
                    val term = tokenType ?: return false
                    if (TYPE_ELEM_START.contains(term)) return true
                    if (term === ARROW && lookAhead(1) === CHAN) return true
                    if (term === MUL) {
                        advanceLexer()
                        val after = tokenType ?: return false
                        if (TYPE_ELEM_START.contains(after)) return true
                    }
                    if (tokenType === LPAREN) {
                        advanceLexer()
                        val inner = tokenType
                        if (inner != null && TYPE_ELEM_START.contains(inner)) return true
                        skipBalanced(LPAREN, RPAREN)
                    } else if (tokenType === IDENTIFIER) {
                        skipOperand()
                    } else {
                        return false
                    }
                }
                else -> return false
            }
        }
        return false
    }

    /** Skips `ident(.ident)*` with any `[...]` / `(...)` suffixes. */
    private fun PsiBuilder.skipOperand() {
        advanceLexer()
        var guard = 0
        while (guard++ < SCAN_LIMIT) {
            val t = tokenType ?: return
            when {
                t === PERIOD && lookAhead(1) === IDENTIFIER -> { advanceLexer(); advanceLexer() }
                t === LBRACK -> { advanceLexer(); skipBalanced(LBRACK, RBRACK) }
                t === LPAREN -> { advanceLexer(); skipBalanced(LPAREN, RPAREN) }
                else -> return
            }
        }
    }

    /** With one opener already consumed: skips to just after its matching closer. */
    private fun PsiBuilder.skipBalanced(open: IElementType, close: IElementType) {
        var depth = 1
        var guard = 0
        while (guard++ < SCAN_LIMIT) {
            val t = tokenType ?: return
            if (t === open) depth++
            else if (t === close) {
                depth--
                if (depth == 0) {
                    advanceLexer()
                    return
                }
            }
            advanceLexer()
        }
    }

    /**
     * go/parser parseMethodSpec: an interface method must have no type parameters (also in Go 1.27,
     * which allows them only on concrete methods). Parses them anyway, wrapped in an error element.
     */
    @JvmStatic
    fun noTypeParameters(b: PsiBuilder, level: Int, p: Parser): Boolean {
        if (b.tokenType !== LBRACK) return false
        val m = b.mark()
        if (!p.parse(b, level)) {
            m.rollbackTo()
            return false
        }
        m.error("interface method must have no type parameters")
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Section J: recovery of unclosed blocks
    // ---------------------------------------------------------------------------------------------

    private val DECLARATION_KEYWORDS: TokenSet = TokenSet.create(CONST, TYPE_, VAR, IMPORT, FUNC)

    /**
     * True at a declaration keyword that starts a line at column 0. Inside a block such a token can
     * only be a top-level declaration swallowed by an unclosed `{` (gofmt indents every statement),
     * so the statement list stops there and the missing `}` is reported locally.
     */
    @JvmStatic
    fun columnZeroDeclaration(b: PsiBuilder, level: Int): Boolean {
        val t = b.tokenType ?: return false
        if (!DECLARATION_KEYWORDS.contains(t)) return false
        if (!b.atColumnZero(b.currentOffset)) return false
        if (t === FUNC) return true
        // In a lazy body the outer scan (lazyBlock) has already evaluated this with the whole file.
        if (state(b).bodyChunk) return false
        // Unformatted but valid code may have statements at column 0. The block is only
        // considered unclosed when no column-0 `}` appears before the next column-0 `func`
        // (or the end of the file); otherwise the brace closes this block later.
        var i = 1
        var guard = 0
        while (guard++ < SCAN_LIMIT) {
            val next = b.rawLookup(i) ?: return true
            val start = b.rawTokenTypeStart(i)
            if (b.atColumnZero(start)) {
                if (next === RBRACE) return false
                if (next === FUNC) return true
            }
            i++
        }
        return false
    }

    private fun PsiBuilder.atColumnZero(offset: Int): Boolean = offset == 0 || originalText[offset - 1] == '\n'

    // ---------------------------------------------------------------------------------------------
    // Section E: variadic parameters (go/parser parseParameterList: "can only use ... with final
    // parameter in list", parseResult: "invalid use of ...")
    // ---------------------------------------------------------------------------------------------

    /**
     * Consumes an optional `...` before a parameter type. It is wrapped in an error element when it
     * appears in a result list or in any parameter but the last one. Always succeeds.
     */
    @JvmStatic
    fun variadic(b: PsiBuilder, level: Int): Boolean {
        if (b.tokenType !== ELLIPSIS) return true
        val message = when {
            b.isInResultList() -> "invalid use of ..."
            !b.isLastParameter() || b.namesBeforeVariadic() > 1 -> "can only use ... with final parameter in list"
            else -> null
        }
        if (message == null) {
            b.advanceLexer()
        } else {
            val m = b.mark()
            b.advanceLexer()
            m.error(message)
        }
        return true
    }

    /**
     * At `...`: true if the enclosing parenthesised list is the result list of a signature, i.e. its
     * `(` directly follows the `)` of a parameter list (go/parser parseResult). Scans backwards over
     * raw tokens, skipping whitespace and comments.
     */
    private fun PsiBuilder.isInResultList(): Boolean {
        var depth = 0
        var i = -1
        var guard = 0
        while (guard++ < SCAN_LIMIT) {
            val t = rawLookup(i) ?: return false
            i--
            if (t === com.intellij.psi.TokenType.WHITE_SPACE || GoTokenSets.COMMENTS.contains(t)) continue
            if (CLOSERS.contains(t)) { depth++; continue }
            if (OPENERS.contains(t)) {
                if (depth > 0) { depth--; continue }
                if (t !== LPAREN) return false
                while (guard++ < SCAN_LIMIT) {
                    val prev = rawLookup(i) ?: return false
                    i--
                    if (prev === com.intellij.psi.TokenType.WHITE_SPACE || GoTokenSets.COMMENTS.contains(prev)) continue
                    return prev === RPAREN
                }
                return false
            }
        }
        return false
    }

    /**
     * At `...`: how many parameter names share the variadic type (`a, b ...int` has two, which
     * go/parser rejects). Walks backwards over `IDENT (',' IDENT)*`; a chain that does not reach the
     * opening `(` ends in the type of the previous declaration, which is not a name.
     */
    private fun PsiBuilder.namesBeforeVariadic(): Int {
        var i = -1
        var guard = 0
        fun prevSignificant(): IElementType? {
            while (guard++ < SCAN_LIMIT) {
                val t = rawLookup(i) ?: return null
                i--
                if (t === com.intellij.psi.TokenType.WHITE_SPACE || GoTokenSets.COMMENTS.contains(t)) continue
                return t
            }
            return null
        }
        if (prevSignificant() !== IDENTIFIER) return 0
        var chain = 1
        while (true) {
            val t = prevSignificant()
            if (t === LPAREN) return chain
            if (t !== COMMA) return maxOf(1, chain - 1)
            if (prevSignificant() !== IDENTIFIER) return maxOf(1, chain - 1)
            chain++
        }
    }

    /** At `...`: true if no further parameter follows the type (only an optional trailing comma before `)`). */
    private fun PsiBuilder.isLastParameter(): Boolean {
        val m = mark()
        try {
            advanceLexer() // ...
            var depth = 0
            var guard = 0
            while (guard++ < SCAN_LIMIT) {
                val t = tokenType ?: return true
                if (depth == 0) {
                    if (t === RPAREN) return true
                    if (t === COMMA) return lookAhead(1) === RPAREN
                    if (t === SEMICOLON_SYNTHETIC) return true
                }
                if (OPENERS.contains(t)) depth++
                else if (CLOSERS.contains(t)) {
                    depth--
                    if (depth < 0) return true
                }
                advanceLexer()
            }
            return true
        } finally {
            m.rollbackTo()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Section G: statement header lookaheads (go/parser parseSimpleStmt modes, parseIfHeader,
    // parseSwitchStmt, parseForStmt)
    // ---------------------------------------------------------------------------------------------

    private val OPENERS: TokenSet = TokenSet.create(LPAREN, LBRACK, LBRACE)
    private val CLOSERS: TokenSet = TokenSet.create(RPAREN, RBRACK, RBRACE)

    /**
     * Scans forward over balanced brackets (braces included, so composite literals and function
     * literal bodies are skipped), calling [onToken] for each depth-0 token. The scan stops at an
     * inserted semicolon, EOF, [SCAN_LIMIT] tokens, or when [onToken] returns non-null.
     */
    private fun PsiBuilder.scanDepthZero(onToken: (IElementType) -> Boolean?): Boolean =
        scanDepthZero { t, _ -> onToken(t) }

    /** As above; [onToken] also receives the raw previous token (at any depth), or null at the start. */
    private fun PsiBuilder.scanDepthZero(onToken: (IElementType, IElementType?) -> Boolean?): Boolean {
        val m = mark()
        try {
            var depth = 0
            var guard = 0
            var previous: IElementType? = null
            while (guard++ < SCAN_LIMIT) {
                val t = tokenType ?: return false
                if (depth == 0) {
                    if (t === SEMICOLON_SYNTHETIC) return false
                    onToken(t, previous)?.let { return it }
                }
                if (OPENERS.contains(t)) depth++
                else if (CLOSERS.contains(t)) {
                    depth--
                    if (depth < 0) return false
                }
                previous = t
                advanceLexer()
            }
            return false
        } finally {
            m.rollbackTo()
        }
    }

    /** True if an `if`/`switch`/`for` header has an init statement: a depth-0 `;` before the block. */
    @JvmStatic
    fun hasInitStatement(b: PsiBuilder, level: Int): Boolean =
        b.scanDepthZero { t -> if (t === SEMICOLON) true else null }

    /** True if the `for` header is a range clause: a depth-0 `range` before `;` or the block. */
    @JvmStatic
    fun isRangeClause(b: PsiBuilder, level: Int): Boolean =
        b.scanDepthZero { t ->
            when {
                t === RANGE -> true
                t === SEMICOLON -> false
                else -> null
            }
        }

    /** True if the statement at `switch` is a type switch: a depth-0 `.(type)` guard before the block. */
    @JvmStatic
    fun isTypeSwitch(b: PsiBuilder, level: Int): Boolean {
        if (b.tokenType !== SWITCH) return false
        var sawDot = false
        return b.scanDepthZero { t ->
            when {
                t === PERIOD -> { sawDot = true; null }
                t === LPAREN && sawDot -> if (b.lookAhead(1) === TYPE_ && b.lookAhead(2) === RPAREN) true else { sawDot = false; null }
                else -> { sawDot = false; null }
            }
        }
    }

    private val OPERAND_END: TokenSet = TokenSet.create(IDENTIFIER, INT, FLOAT, IMAG, CHAR, STRING, RAW_STRING, RPAREN, RBRACK, RBRACE)

    /**
     * True if a select case is a send statement: a depth-0 binary `<-` (one that follows an operand,
     * so `<-c <- d` sends on `<-c`) before `:`, `=` or `:=`.
     */
    @JvmStatic
    fun isSendStatement(b: PsiBuilder, level: Int): Boolean =
        b.scanDepthZero { t, previous ->
            when {
                t === ARROW && previous != null && OPERAND_END.contains(previous) -> true
                t === COLON || t === ASSIGN || t === DEFINE -> false
                else -> null
            }
        }

    /**
     * go/parser parseArrayFieldOrTypeInstance: after `name [` in a field, parameter or receiver,
     * decides whether `[...]` is an array/slice type of a named declaration (true) or a type
     * instantiation `T[P1, P2]` of an embedded/unnamed type (false). Any other token allows a type.
     */
    @JvmStatic
    fun typeAfterName(b: PsiBuilder, level: Int): Boolean {
        if (b.tokenType !== LBRACK) return true
        if (b.lookAhead(1) === RBRACK) return true // name []T
        val m = b.mark()
        try {
            b.advanceLexer() // [
            var depth = 0
            var guard = 0
            while (guard++ < SCAN_LIMIT) {
                val t = b.tokenType ?: return false
                if (depth == 0) {
                    if (t === COMMA) return false // T[P1, P2]
                    if (t === RBRACK) {
                        b.advanceLexer()
                        val next = b.tokenType ?: return false
                        return TYPE_START.contains(next)
                    }
                }
                if (OPENERS.contains(t)) depth++ else if (CLOSERS.contains(t)) depth--
                b.advanceLexer()
            }
            return false
        } finally {
            m.rollbackTo()
        }
    }

    /** Tokens that can start a type. */
    private val TYPE_START: TokenSet = TokenSet.create(IDENTIFIER, MUL, LBRACK, LPAREN, FUNC, STRUCT, INTERFACE, MAP, CHAN, ARROW)

    // ---------------------------------------------------------------------------------------------
    // Section F / composite literals: bracket-body lookaheads
    // ---------------------------------------------------------------------------------------------

    /** After `[`: true if a depth-0 `:` appears before the closing `]` (go/parser parseIndexOrSliceOrInstance). */
    @JvmStatic
    fun isSliceBody(b: PsiBuilder, level: Int): Boolean =
        b.scanDepthZero { t ->
            when {
                t === COLON -> true
                t === RBRACK || t === COMMA -> false
                else -> null
            }
        }

    /** At an element of a literal value: true if a depth-0 `:` appears before `,` or `}` (go/parser parseElement). */
    @JvmStatic
    fun isKeyedElement(b: PsiBuilder, level: Int): Boolean =
        b.scanDepthZero { t ->
            when {
                t === COLON -> true
                t === COMMA || t === RBRACE -> false
                else -> null
            }
        }

    // ---------------------------------------------------------------------------------------------
    // Section K: doc comments
    // ---------------------------------------------------------------------------------------------

    /**
     * Binds the comment lines immediately preceding a declaration (no blank line in between) to the
     * declaration, so `// Doc` becomes the first child of the declaration like a Go doc comment.
     */
    @JvmField
    val DOC_COMMENT_BINDER: WhitespacesAndCommentsBinder = WhitespacesAndCommentsBinder { tokens, _, getter ->
        var start = tokens.size
        var i = tokens.size - 1
        while (i >= 0) {
            val type = tokens[i]
            if (GoTokenSets.COMMENTS.contains(type)) {
                start = i
            } else {
                val text = getter[i]
                var newlines = 0
                for (c in text) if (c == '\n') newlines++
                if (newlines >= 2) break
            }
            i--
        }
        start
    }
}
