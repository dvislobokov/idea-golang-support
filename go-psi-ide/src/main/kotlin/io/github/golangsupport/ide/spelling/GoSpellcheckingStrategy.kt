package io.github.golangsupport.ide.spelling

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.spellchecker.inspections.CommentSplitter
import com.intellij.spellchecker.inspections.IdentifierSplitter
import com.intellij.spellchecker.inspections.PlainTextSplitter
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.TokenConsumer
import com.intellij.spellchecker.tokenizer.Tokenizer
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoImportDeclaration
import io.github.golangsupport.lang.psi.GoImportSpec
import io.github.golangsupport.lang.psi.GoNamedElement
import io.github.golangsupport.lang.psi.GoPackageClause
import io.github.golangsupport.lang.psi.GoStringLiteral
import io.github.golangsupport.lang.psi.GoTag

/**
 * `spellchecker.support` for Go (registered from `go-psi-ide-spelling.xml`, an optional dependency on the spellchecker): what the
 * platform's Typo inspection reads in a Go file.
 *
 * - identifiers where they are declared, split by camel case and underscores; not imports, embedded types or the package name;
 * - comments without directives (`//go:build`, `//nolint`, `//export`), indented code lines, URLs, `[pkg.Name]` doc links and
 *   back-quoted code; not the cgo preamble above `import "C"`;
 * - string literals without escapes and `fmt` verbs (`%-8s` is not a word); not import paths and struct tags;
 * - never rune literals.
 */
class GoSpellcheckingStrategy : SpellcheckingStrategy() {

    override fun isDumbAware(): Boolean = true

    override fun getTokenizer(element: PsiElement): Tokenizer<*> = when (element) {
        is PsiComment -> if (isCgoPreamble(element)) EMPTY_TOKENIZER else COMMENTS
        is GoStringLiteral -> if (element.parent is GoImportSpec || element.parent is GoTag) EMPTY_TOKENIZER else STRINGS
        is GoImportSpec, is GoAnonymousFieldDefinition, is GoPackageClause -> EMPTY_TOKENIZER
        is GoNamedElement -> IDENTIFIERS
        else -> EMPTY_TOKENIZER
    }

    override fun isLiteral(element: PsiElement): Boolean = element is GoStringLiteral

    override fun isComment(element: PsiElement): Boolean = element is PsiComment

    /** The C code in the comment right above `import "C"`. */
    private fun isCgoPreamble(comment: PsiComment): Boolean {
        val owner = comment.parent
        if (owner is GoImportSpec) return owner.path == "C"
        if (owner is GoImportDeclaration) return owner.importSpecList.any { it.path == "C" }
        var next: PsiElement? = comment.nextSibling
        while (next is PsiWhiteSpace || next is PsiComment) next = next.nextSibling
        return next is GoImportDeclaration && next.importSpecList.any { it.path == "C" }
    }

    private object IdentifierTokenizer : Tokenizer<GoNamedElement>() {
        override fun tokenize(element: GoNamedElement, consumer: TokenConsumer) {
            val identifier = element.nameIdentifier ?: return
            consumer.consumeToken(identifier, true, IdentifierSplitter.getInstance())
        }
    }

    private object CommentTokenizer : Tokenizer<PsiComment>() {
        override fun tokenize(element: PsiComment, consumer: TokenConsumer) {
            val text = element.text
            for (range in GoSpellingText.commentRanges(text)) consumer.consumeToken(element, text, false, 0, range, CommentSplitter.getInstance())
        }
    }

    private object StringTokenizer : Tokenizer<GoStringLiteral>() {
        override fun tokenize(element: GoStringLiteral, consumer: TokenConsumer) {
            val text = element.text
            val range = GoSpellingText.stringContent(text) ?: return
            consumer.consumeToken(element, GoSpellingText.maskString(text), false, 0, range, PlainTextSplitter.getInstance())
        }
    }

    private companion object {
        val IDENTIFIERS: Tokenizer<GoNamedElement> = IdentifierTokenizer
        val COMMENTS: Tokenizer<PsiComment> = CommentTokenizer
        val STRINGS: Tokenizer<GoStringLiteral> = StringTokenizer
    }
}

/** The text side of [GoSpellcheckingStrategy]: which ranges of a comment or a string literal are prose. Pure functions. */
object GoSpellingText {

    /** `//go:build`, `//line`, `//export`, `//nolint`, `// +build`, `//lint:ignore`: the comment is for a tool. */
    private val DIRECTIVE = Regex("""^//(go:|line |extern |export |\s*nolint|\s*\+build|[a-z0-9]+:[a-z0-9])""")
    private val URL = Regex("""\b[a-zA-Z][a-zA-Z0-9+.\-]*://\S+""")
    /** `[Name]`, `[pkg.Name]`, `[*T]`, `[encoding/json.Marshal]`: go/doc links, not words. */
    private val DOC_LINK = Regex("""\[\*?[\p{L}_][\p{L}\p{Nd}_./\-]*]""")
    private val BACK_QUOTED = Regex("""`[^`\n]*`""")
    private val FORMAT_VERB = Regex("""%(\[\d+])?[-+# 0]*(\d+|\*)?(\.(\d+|\*)?)?(\[\d+])?[a-zA-Z%]""")

    /** The ranges of [text] (a whole comment) the spellchecker reads. */
    fun commentRanges(text: String): List<TextRange> {
        val body = when {
            DIRECTIVE.containsMatchIn(text) -> return emptyList()
            text.startsWith("//") -> {
                // An indented line of a doc comment is code (go/doc): `//\tx := f()`.
                if (text.startsWith("//\t") || text.startsWith("//  ")) return emptyList()
                TextRange(2, text.length)
            }
            text.startsWith("/*") -> TextRange(2, if (text.length >= 4 && text.endsWith("*/")) text.length - 2 else text.length)
            else -> return emptyList()
        }
        val skipped = (URL.findAll(text) + DOC_LINK.findAll(text) + BACK_QUOTED.findAll(text)).map { TextRange(it.range.first, it.range.last + 1) }
            .filter { body.intersects(it) }.sortedBy { it.startOffset }.toList()
        val result = ArrayList<TextRange>()
        var start = body.startOffset
        for (s in skipped) {
            if (s.startOffset > start) result += TextRange(start, s.startOffset)
            start = maxOf(start, s.endOffset)
        }
        if (start < body.endOffset) result += TextRange(start, body.endOffset)
        return result.filter { r -> text.substring(r.startOffset, r.endOffset).isNotBlank() }
    }

    /** The content of a string literal between its quotes; null for an empty or broken one. */
    fun stringContent(text: String): TextRange? {
        if (text.length < 2) return null
        val quote = text[0]
        if (quote != '"' && quote != '`') return null
        val end = if (text.length >= 2 && text.last() == quote) text.length - 1 else text.length
        return if (end > 1) TextRange(1, end) else null
    }

    /** [text] (a string literal) with escapes and `fmt` verbs replaced by spaces: same length, so offsets stay. */
    fun maskString(text: String): String {
        val chars = text.toCharArray()
        if (text.startsWith("\"")) {
            var i = 1
            while (i < chars.size - 1) {
                if (chars[i] != '\\') { i++; continue }
                val length = when (chars[i + 1]) {
                    'x' -> 4
                    'u' -> 6
                    'U' -> 10
                    in '0'..'7' -> 4
                    else -> 2
                }
                val end = minOf(chars.size - 1, i + length)
                for (j in i until end) chars[j] = ' '
                i = end
            }
        }
        val masked = String(chars)
        return FORMAT_VERB.replace(masked) { " ".repeat(it.value.length) }
    }
}
