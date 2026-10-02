package io.github.golangsupport.ide.completion

import io.github.golangsupport.ide.completion.GoCompletionContext.Kind

/**
 * Context-sensitive keywords: declarations at top level, statements inside blocks (`break` and
 * `continue` only in loops/switches, `fallthrough` in expression switch clauses, `case`/`default`
 * in switch and select bodies, `else` after the block of an `if`, `range` in a `for` header),
 * type constructors in type positions, function literals and composite types in expressions.
 * Never after `.`. `nil`, `true`, `false` and `iota` come from the universe scope.
 */
object GoKeywordCandidates {

    private class Keyword(val name: String, val suffix: String = " ", val caretShift: Int = suffix.length)

    private val TOP_LEVEL = listOf(Keyword("func"), Keyword("type"), Keyword("var"), Keyword("const"))
    private val STATEMENTS = listOf(
        Keyword("var"), Keyword("const"), Keyword("type"), Keyword("if"), Keyword("for"), Keyword("switch"),
        Keyword("select"), Keyword("go"), Keyword("defer"), Keyword("return"), Keyword("goto"),
    )
    private val EXPRESSIONS = listOf(
        Keyword("func", "()", 1), Keyword("map", "[]", 1), Keyword("chan"), Keyword("struct", "{}", 1), Keyword("interface", "{}", 1),
    )
    private val TYPES = listOf(
        Keyword("func", "()", 1), Keyword("map", "[]", 1), Keyword("chan"), Keyword("struct", "{}", 1), Keyword("interface", "{}", 1),
    )

    fun collect(context: GoCompletionContext, out: MutableList<GoCandidate>) {
        val keywords = ArrayList<Keyword>()
        when (context.kind) {
            Kind.TOP_LEVEL -> {
                if (context.packageKeywordAllowed) keywords += Keyword("package")
                if (context.importKeywordAllowed) keywords += Keyword("import")
                keywords += TOP_LEVEL
            }
            Kind.SWITCH_BODY -> {
                keywords += Keyword("case")
                keywords += Keyword("default", ":")
            }
            Kind.STATEMENT -> {
                keywords += STATEMENTS
                if (context.inBreakable) keywords += Keyword("break", "")
                if (context.inLoop) keywords += Keyword("continue", "")
                if (context.inExprCaseClause) keywords += Keyword("fallthrough", "")
                if (context.inCaseClause) {
                    keywords += Keyword("case")
                    keywords += Keyword("default", ":")
                }
                if (context.afterIfBlock) keywords += Keyword("else")
                keywords += Keyword("func", "()", 1)
            }
            Kind.EXPRESSION -> {
                if (context.rangeAllowed) keywords += Keyword("range")
                keywords += EXPRESSIONS
            }
            Kind.TYPE -> keywords += TYPES
            else -> return
        }
        val seen = HashSet<String>()
        for (k in keywords) {
            if (!seen.add(k.name)) continue
            out += GoCandidate(
                k.name, GoCandidateKind.KEYWORD, GoScopeLevel.KEYWORD, bold = true,
                insertHandler = GoLookupElementFactory.keywordHandler(k.suffix, k.caretShift),
            )
        }
    }
}
