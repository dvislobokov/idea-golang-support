package io.github.golangsupport.lang.parser

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import io.github.golangsupport.GoParsingTestCase
import io.github.golangsupport.lang.lexer.GoLexer
import io.github.golangsupport.lang.psi.GoTypes
import kotlin.random.Random

/**
 * Shared engine of [GoParserFuzzTest] (fast) and [GoParserFuzzCorpusTest] (GOROOT sample).
 *
 * Every source file is mutated several times with a seeded [Random] (the seed of a file depends only
 * on the base seed and its path, so a failure is reproducible from the printed
 * `file / kind / offset`). Mutation kinds ([Kind]): delete a token, duplicate a token, insert a
 * bracket or keyword before a token, swap two adjacent tokens, truncate at a random offset, delete a
 * random line.
 *
 * Every mutant must satisfy (hard failures, collected and reported together):
 *  - parsing does not throw;
 *  - `psi.text == input` (the tree is lossless whatever the garbage);
 *  - parsing takes less than [MAX_PARSE_MILLIS] ms.
 *
 * Recovery locality (counted as `localityViolations`): for a single-token mutation ([Kind.isSingleToken])
 * inside the body of a top-level function, the number of top-level declarations without a
 * `PsiErrorElement` must be at least `clean(original) - 2`: the error stays near the damage and the
 * parser resynchronises at the next declaration. Truncation and line deletion are exempt.
 */
abstract class GoParserFuzzTestBase : GoParsingTestCase("parser") {

    enum class Kind(val isSingleToken: Boolean) {
        DELETE_TOKEN(true),
        DUPLICATE_TOKEN(true),
        INSERT_BRACKET_OR_KEYWORD(true),
        SWAP_TOKENS(true),
        TRUNCATE(false),
        DELETE_LINE(false),
    }

    class Mutant(val kind: Kind, val offset: Int, val text: String)

    class Finding(val file: String, val kind: Kind, val offset: Int, val message: String) {
        override fun toString() = "$file  ${kind.name}@$offset  $message"
    }

    class Result {
        var files = 0
        var mutants = 0
        var maxParseMillis = 0L
        var slowest = ""
        val perKind = LinkedHashMap<Kind, IntArray>() // [mutants, locality checked, violations]
        val failures = ArrayList<Finding>()
        val violations = ArrayList<Pair<Int, Finding>>() // (clean decls lost, finding)
    }

    private val insertions = listOf(
        "(", ")", "{", "}", "[", "]", "func", "if", "for", "switch", "return", "var", "case", "else", "go", "chan",
        "map", "struct", "type", "interface", "select", "import", "package", "range", "defer", "const",
    )

    /** Runs [mutationsPerFile] mutations on every `(name, text)` pair. */
    protected fun fuzz(sources: List<Pair<String, String>>, mutationsPerFile: Int, seed: Long): Result {
        val result = Result()
        Kind.entries.forEach { result.perKind[it] = IntArray(3) }
        for ((name, text) in sources) {
            val original = parse(name, text) ?: continue
            result.files++
            val cleanOriginal = cleanDeclarations(original.node)
            val bodies = functionBodies(original.node)
            val tokens = significantTokens(text)
            if (tokens.isEmpty()) continue
            val random = Random(seed * 31 + name.hashCode())
            repeat(mutationsPerFile) { index ->
                val mutant = mutate(text, tokens, Kind.entries[(index + result.files) % Kind.entries.size], random)
                result.mutants++
                val stats = result.perKind.getValue(mutant.kind)
                stats[0]++
                var psi: PsiFile?
                val started = System.nanoTime()
                try {
                    psi = parse(name, mutant.text)
                } catch (e: Throwable) {
                    result.failures += Finding(name, mutant.kind, mutant.offset, "exception: $e")
                    return@repeat
                }
                val millis = (System.nanoTime() - started) / 1_000_000
                if (millis > result.maxParseMillis) {
                    result.maxParseMillis = millis
                    result.slowest = "$name ${mutant.kind}@${mutant.offset} ($millis ms)"
                }
                if (psi == null) {
                    result.failures += Finding(name, mutant.kind, mutant.offset, "no PSI")
                    return@repeat
                }
                if (psi.text != mutant.text) {
                    result.failures += Finding(name, mutant.kind, mutant.offset, "PSI text differs from the input")
                    return@repeat
                }
                if (millis >= MAX_PARSE_MILLIS) {
                    result.failures += Finding(name, mutant.kind, mutant.offset, "parse took $millis ms")
                }
                if (mutant.kind.isSingleToken && bodies.any { mutant.offset > it.first && mutant.offset < it.last }) {
                    stats[1]++
                    val clean = cleanDeclarations(psi.node)
                    if (clean < cleanOriginal - 2) {
                        stats[2]++
                        result.violations += (cleanOriginal - clean) to
                            Finding(name, mutant.kind, mutant.offset, "clean declarations $cleanOriginal -> $clean  near: ${snippet(mutant)}")
                    }
                }
            }
        }
        return result
    }

    protected fun printSummary(title: String, result: Result, worst: Int = 10) {
        println("$title summary")
        println("  files:              ${result.files}")
        println("  mutants:            ${result.mutants}")
        println("  hard failures:      ${result.failures.size}")
        println("  localityViolations: ${result.violations.size}")
        println("  max parse time:     ${result.maxParseMillis} ms (${result.slowest})")
        println("  by kind (mutants / locality checked / locality violations):")
        result.perKind.forEach { (kind, s) -> println("    %-26s %6d %6d %6d".format(kind, s[0], s[1], s[2])) }
        result.failures.take(worst).forEach { println("  FAILURE $it") }
        result.violations.sortedByDescending { it.first }.take(worst).forEach { println("  WORST   ${it.second}") }
    }

    protected fun assertNoHardFailures(result: Result) {
        assertTrue(
            "${result.failures.size} fuzz failures, first:\n" + result.failures.take(10).joinToString("\n"),
            result.failures.isEmpty(),
        )
    }

    /** The mutated text around the mutation, on one line. */
    private fun snippet(m: Mutant): String =
        m.text.substring((m.offset - 30).coerceAtLeast(0), (m.offset + 40).coerceAtMost(m.text.length)).replace("\n", "\\n").replace("\t", " ")

    private fun parse(name: String, text: String): PsiFile? {
        val psi = createPsiFile(name, text) ?: return null
        ensureParsed(psi)
        return psi
    }

    // --- locality --------------------------------------------------------------------------------

    private val declarationTypes = setOf(
        GoTypes.FUNCTION_DECLARATION, GoTypes.METHOD_DECLARATION, GoTypes.CONST_DECLARATION,
        GoTypes.VAR_DECLARATION, GoTypes.TYPE_DECLARATION,
    )

    /** Number of top-level declarations that contain no error element. */
    private fun cleanDeclarations(file: ASTNode): Int {
        var count = 0
        var c = file.firstChildNode
        while (c != null) {
            if (c.elementType in declarationTypes && PsiTreeUtil.findChildOfType(c.psi, PsiErrorElement::class.java) == null) count++
            c = c.treeNext
        }
        return count
    }

    /** Offset ranges of the bodies of top-level functions and methods. */
    private fun functionBodies(file: ASTNode): List<IntRange> {
        val result = ArrayList<IntRange>()
        var c = file.firstChildNode
        while (c != null) {
            if (c.elementType == GoTypes.FUNCTION_DECLARATION || c.elementType == GoTypes.METHOD_DECLARATION) {
                c.findChildByType(GoTypes.BLOCK)?.let { result += it.startOffset..(it.startOffset + it.textLength) }
            }
            c = c.treeNext
        }
        return result
    }

    // --- mutations -------------------------------------------------------------------------------

    private fun significantTokens(text: String): List<IntRange> {
        val lexer = GoLexer()
        lexer.start(text)
        val tokens = ArrayList<IntRange>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE && type != GoTypes.LINE_COMMENT && type != GoTypes.BLOCK_COMMENT &&
                type != GoTypes.SEMICOLON_SYNTHETIC
            ) {
                tokens += lexer.tokenStart until lexer.tokenEnd
            }
            lexer.advance()
        }
        return tokens
    }

    private fun mutate(text: String, tokens: List<IntRange>, kind: Kind, random: Random): Mutant {
        val i = random.nextInt(tokens.size)
        val token = tokens[i]
        return when (kind) {
            Kind.DELETE_TOKEN -> Mutant(kind, token.first, text.removeRange(token))
            Kind.DUPLICATE_TOKEN ->
                Mutant(kind, token.first, text.substring(0, token.last + 1) + " " + text.substring(token) + text.substring(token.last + 1))
            Kind.INSERT_BRACKET_OR_KEYWORD ->
                Mutant(kind, token.first, text.substring(0, token.first) + " " + insertions[random.nextInt(insertions.size)] + " " + text.substring(token.first))
            Kind.SWAP_TOKENS -> {
                if (i + 1 >= tokens.size) {
                    Mutant(kind, token.first, text)
                } else {
                    val next = tokens[i + 1]
                    Mutant(
                        kind,
                        token.first,
                        text.substring(0, token.first) + text.substring(next) + text.substring(token.last + 1, next.first) +
                            text.substring(token) + text.substring(next.last + 1),
                    )
                }
            }
            Kind.TRUNCATE -> {
                val offset = random.nextInt(text.length + 1)
                Mutant(kind, offset, text.substring(0, offset))
            }
            Kind.DELETE_LINE -> {
                val offset = random.nextInt(text.length + 1)
                val start = text.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (offset == 0) 0 else it + 1 }
                val end = text.indexOf('\n', offset).let { if (it < 0) text.length else it + 1 }
                Mutant(kind, start, text.removeRange(start, end))
            }
        }
    }

    companion object {
        const val MAX_PARSE_MILLIS = 2000L
    }
}
