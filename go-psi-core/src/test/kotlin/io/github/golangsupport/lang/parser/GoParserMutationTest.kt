package io.github.golangsupport.lang.parser

import io.github.golangsupport.GoParsingTestCase
import io.github.golangsupport.GoTestUtil
import io.github.golangsupport.lang.lexer.GoLexer
import java.io.File
import kotlin.random.Random

/**
 * Robustness: random token deletions and stray bracket insertions must never throw, and the PSI
 * text must always equal the input (fixed seed, so failures are reproducible).
 */
class GoParserMutationTest : GoParsingTestCase("parser/cases") {

    private val sources = listOf("Statements", "SimpleStmts", "Structs", "IfCompositeLit", "Decls")

    fun testMutations() {
        val random = Random(42)
        var iterations = 0
        for (name in sources) {
            val original = File(GoTestUtil.testDataPath("parser/cases/$name.go")).readText()
            repeat(40) {
                val mutated = mutate(original, random)
                iterations++
                try {
                    val psi = createPsiFile("$name-$it", mutated)
                    ensureParsed(psi)
                    assertEquals("PSI text must equal the input ($name #$it)", mutated, psi.text)
                } catch (e: Throwable) {
                    throw AssertionError("Parser failed on mutation $name #$it:\n$mutated", e)
                }
            }
        }
        println("mutation test: $iterations mutated files parsed")
    }

    private fun mutate(text: String, random: Random): String {
        val lexer = GoLexer()
        lexer.start(text)
        val ranges = mutableListOf<IntRange>()
        while (lexer.tokenType != null) {
            ranges += lexer.tokenStart until lexer.tokenEnd
            lexer.advance()
        }
        if (ranges.isEmpty()) return text
        val target = ranges[random.nextInt(ranges.size)]
        return when (random.nextInt(3)) {
            0 -> text.removeRange(target)
            1 -> text.substring(0, target.first) + "{" + text.substring(target.first)
            else -> text.substring(0, target.first) + ")" + text.substring(target.first)
        }
    }
}
