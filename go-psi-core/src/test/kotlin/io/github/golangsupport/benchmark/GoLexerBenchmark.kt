package io.github.golangsupport.benchmark

import io.github.golangsupport.lang.lexer.GoLexer
import junit.framework.TestCase

/**
 * Lexer throughput over `net/http/server.go` and all `.go` files of go/types (inputs read from
 * `$GOROOT/src` at runtime). Unit: ms per MB (million characters).
 */
class GoLexerBenchmark : TestCase() {

    fun testServer() = lex("GoLexerBenchmark.server", List(10) { BenchmarkSupport.readGoroot("net/http/server.go") }) // 10 passes: one pass is too short to time reliably

    fun testGoTypes() = lex("GoLexerBenchmark.gotypes", List(5) { BenchmarkSupport.goFilesIn("go/types").map { it.second } }.flatten()) // 5 passes, see testServer

    private fun lex(name: String, texts: List<String>) {
        val megabytes = texts.sumOf { it.length } / 1_000_000.0
        val lexer = GoLexer()
        var tokens = 0L
        BenchmarkSupport.run(name, "ms/MB", megabytes) {
            tokens = 0
            for (text in texts) {
                lexer.start(text)
                while (lexer.tokenType != null) {
                    tokens++
                    lexer.advance()
                }
            }
        }
        assertTrue("no tokens", tokens > 0)
    }
}
