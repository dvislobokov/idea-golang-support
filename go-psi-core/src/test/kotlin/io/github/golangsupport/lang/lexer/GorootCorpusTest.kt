package io.github.golangsupport.lang.lexer

import com.intellij.psi.TokenType
import io.github.golangsupport.GoTestUtil
import junit.framework.TestCase
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Corpus gate over `$GOROOT/src` (run by the `corpusTest` task, excluded from `test`).
 * Every file (including `testdata` directories) must lex without exceptions, gaps or empty
 * tokens, and without BAD_CHARACTER, except the files in [INTENTIONALLY_INVALID].
 */
class GorootCorpusTest : TestCase() {

    private companion object {
        /**
         * Test inputs that contain characters go/scanner also rejects (ILLEGAL), with the exact
         * BAD_CHARACTER count expected; paths relative to `$GOROOT/src` with '/' separators.
         */
        val INTENTIONALLY_INVALID = mapOf(
            // U+2639 in identifiers: "invalid character" type checker test.
            "cmd/compile/internal/types2/testdata/local/issue68183.go" to 8,
            // U+00AB / U+00BB markers in a cmd/cover test input (Go 1.27).
            "cmd/cover/testdata/ranges/ranges.go" to 46,
        )
    }

    fun testLexGorootSources() {
        val src = GoTestUtil.goroot().resolve("src")
        assertTrue("GOROOT/src not found: $src (set -Pgopsi.goroot=...)", Files.isDirectory(src))

        val files = Files.walk(src).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "go" }.sorted().toList()
        }
        assertTrue("Expected > 1000 .go files under $src, found ${files.size}", files.size > 1000)

        val lexer = GoLexer()
        val failures = mutableListOf<String>()
        val badCharFiles = mutableListOf<String>()
        var tokens = 0L
        var badChars = 0L
        var exemptBadChars = 0L
        var filesWithBadChars = 0
        var bytes = 0L
        val started = System.nanoTime()

        for (file in files) {
            val text = file.readText()
            bytes += text.length
            try {
                var fileBad = 0
                lexer.start(text)
                var expectedStart = 0
                while (lexer.tokenType != null) {
                    check(lexer.tokenStart == expectedStart) { "gap at offset $expectedStart" }
                    check(lexer.tokenEnd > lexer.tokenStart) { "empty token at ${lexer.tokenStart}" }
                    tokens++
                    if (lexer.tokenType == TokenType.BAD_CHARACTER) fileBad++
                    expectedStart = lexer.tokenEnd
                    lexer.advance()
                }
                check(expectedStart == text.length) { "lexer stopped at $expectedStart of ${text.length}" }
                val rel = src.relativize(file).toString().replace('\\', '/')
                val exempt = INTENTIONALLY_INVALID[rel]
                if (exempt != null) {
                    check(fileBad == exempt) { "expected $exempt BAD_CHARACTER tokens, found $fileBad" }
                    exemptBadChars += fileBad
                } else if (fileBad > 0) {
                    badChars += fileBad
                    filesWithBadChars++
                    badCharFiles += "$rel ($fileBad)"
                }
            } catch (e: Throwable) {
                failures += "${src.relativize(file)}: $e"
            }
        }

        val millis = (System.nanoTime() - started) / 1_000_000
        println(
            """
            |GorootCorpusTest summary
            |  root:               $src
            |  files:              ${files.size}
            |  chars:              $bytes
            |  tokens:             $tokens
            |  BAD_CHARACTER:      $badChars (in $filesWithBadChars files; plus $exemptBadChars in ${INTENTIONALLY_INVALID.size} intentionally invalid files)
            |  lexer failures:     ${failures.size}
            |  time:               $millis ms
            """.trimMargin(),
        )
        failures.take(20).forEach { println("  FAIL $it") }
        badCharFiles.take(50).forEach { println("  BAD_CHARACTER $it") }
        assertTrue("Lexer failed on ${failures.size} files, first: ${failures.firstOrNull()}", failures.isEmpty())
        assertEquals("BAD_CHARACTER tokens in GOROOT/src, first files: ${badCharFiles.take(5)}", 0L, badChars)
    }
}
