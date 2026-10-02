package io.github.golangsupport.testing

import com.intellij.openapi.util.TextRange
import com.intellij.psi.TokenType
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.golangsupport.lang.GoDeclarationInfo
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.GoTextLexer
import io.github.golangsupport.lang.GoStructure
import io.github.golangsupport.lang.GoTextTokens

/**
 * A subtest a test function names in its body: `t.Run("empty", ...)`, or a case of a table, `{name: "empty", ...}`. [nameRange] is the string
 * literal with its quotes; [name] is what `go test -run` knows the subtest by (spaces become underscores, as `go test` rewrites them).
 */
class GoSubtest(val name: String, val nameRange: TextRange, val function: GoDeclarationInfo) {
    /** `TestTotal/empty`: the name of the subtest as the test tree and `-run` see it. */
    val fullName: String get() = "${function.name}/$name"
}

/** Found by tokens, without knowing types: the string that follows `.Run(` or `name:` in the body of a `TestXxx` or `FuzzXxx` function. */
object GoSubtests {
    /** The fields a table test names its cases by, as people call them. */
    private val CASE_FIELDS = setOf("name", "Name", "desc", "description", "testName", "title", "scenario", "tc", "caseName", "label")

    fun find(text: CharSequence, function: GoDeclarationInfo): List<GoSubtest> {
        val body = function.body ?: return emptyList()
        val lexer = GoTextLexer()
        lexer.start(text, body.startOffset, body.endOffset, 0)
        val tokens = ArrayList<Pair<com.intellij.psi.tree.IElementType, TextRange>>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type !in GoTextTokens.COMMENTS) tokens += type to TextRange(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        val result = ArrayList<GoSubtest>()
        val seen = HashSet<String>()
        for (i in tokens.indices) {
            val (type, range) = tokens[i]
            if (type != GoTextTokens.STRING && type != GoTextTokens.RAW_STRING) continue
            val word = { j: Int -> tokens.getOrNull(j)?.takeIf { it.first == GoTextTokens.IDENTIFIER }?.let { text.subSequence(it.second.startOffset, it.second.endOffset).toString() } }
            val isRun = tokens.getOrNull(i - 1)?.first == GoTextTokens.LPAREN && word(i - 2) == "Run" && tokens.getOrNull(i - 3)?.first == GoTextTokens.DOT && word(i - 4) != null
            val isCase = tokens.getOrNull(i - 1)?.let { it.first == GoTextTokens.OPERATOR && text[it.second.startOffset] == ':' } == true && word(i - 2) in CASE_FIELDS &&
                tokens.getOrNull(i - 3)?.first.let { it == GoTextTokens.LBRACE || it == GoTextTokens.COMMA }
            if (!isRun && !isCase) continue
            val name = subtestName(text.subSequence(range.startOffset, range.endOffset).toString()) ?: continue
            if (seen.add(name)) result += GoSubtest(name, range, function)
        }
        return result
    }

    /** `"two items"` -> `two_items`; a name with an escape or a format verb is not a name `-run` can be given. */
    fun subtestName(literal: String): String? {
        val inner = when {
            literal.length >= 2 && literal.startsWith("\"") && literal.endsWith("\"") -> literal.substring(1, literal.length - 1)
            literal.length >= 2 && literal.startsWith("`") && literal.endsWith("`") -> literal.substring(1, literal.length - 1)
            else -> return null
        }
        if (inner.isEmpty() || '\\' in inner || '%' in inner) return null
        return inner.replace(' ', '_')
    }

    /** The subtests of every test function of the file, by the offset of their name: for the gutter and the run producer. */
    fun ofFile(file: GoFile): Map<Int, GoSubtest> = CachedValuesManager.getCachedValue(file) {
        val text = file.viewProvider.contents
        val structure = GoStructure.of(file)
        val all = GoTests.find(structure, file.name).filter { it.second == GoTestKind.TEST || it.second == GoTestKind.FUZZ }
            .flatMap { (function, _) -> find(text, function) }
        CachedValueProvider.Result.create(all.associateBy { it.nameRange.startOffset }, file)
    }
}
