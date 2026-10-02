package io.github.golangsupport.lang.lexer

import com.intellij.psi.TokenType
import io.github.golangsupport.GoTestUtil
import junit.framework.TestCase
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readLines

/**
 * Corpus gate: GoLexer token stream vs go/scanner (via `tools/astdump walk -tokens`) on
 * `$GOROOT/src` (astdump skips `testdata`, `.`- and `_`-prefixed entries). Tokens are compared as
 * (UTF-8 byte offset, go/token name) pairs, skipping whitespace and EOF; see [GoTokenMapping].
 * go/scanner's semicolon at EOF is dropped because GoLexer emits no token there (the parser's
 * `semi` rule accepts EOF).
 *
 * Positions listed in `testData/lexer/corpus-allowlist.txt` are known, documented deviations and
 * are not counted. Metrics are compared with `testData/metrics/goroot-src-lexer.json`: a
 * regression fails the test, an improvement rewrites the file. The full mismatch list is written
 * to `go-psi-core/build/astdump/lexer-mismatches.txt`.
 */
class GorootLexerDiffCorpusTest : TestCase() {

    private data class Token(val offset: Int, val name: String)

    private data class Mismatch(val file: String, val offset: Int, val expected: String?, val actual: String?) {
        val key get() = "$file:$offset"
        val category get() = "${expected ?: "(none)"} -> ${actual ?: "(none)"}"
        override fun toString() = "$key  go/scanner=${expected ?: "(none)"}  GoLexer=${actual ?: "(none)"}"
    }

    private class Result(
        val files: Int,
        val tokens: Long,
        val badCharacters: Int,
        val mismatches: List<Mismatch>,
        val skippedFiles: List<String>,
    )

    private val repo: Path = File(GoTestUtil.testDataPath()).parentFile.toPath()
    private val buildDir: Path = repo.resolve("go-psi-core/build/astdump")

    fun testTokenStreamMatchesGoScanner() {
        val src = GoTestUtil.goroot().resolve("src")
        assertTrue("GOROOT/src not found: $src (set -Pgopsi.goroot=...)", Files.isDirectory(src))
        val result = diffTree(src)
        assertTrue("Expected > 1000 files, found ${result.files}", result.files > 1000)

        val allowlist = readAllowlist(repo.resolve("testData/lexer/corpus-allowlist.txt"))
        val (allowed, mismatches) = result.mismatches.partition { it.key in allowlist }
        val badCharacters = result.badCharacters
        val files = result.files
        val tokens = result.tokens
        val skippedFiles = result.skippedFiles
        val filesWithMismatches = mismatches.map { it.file }.distinct().size
        val staleAllowlist = allowlist - allowed.map { it.key }.toSet()
        Files.createDirectories(buildDir)
        val report = buildDir.resolve("lexer-mismatches.txt")
        Files.write(report, mismatches.map { "MISMATCH $it" } + allowed.map { "ALLOWED $it" })

        println(
            """
            |GorootLexerDiffCorpusTest summary
            |  root:                  $src
            |  files compared:        $files (skipped ${skippedFiles.size})
            |  tokens:                $tokens
            |  BAD_CHARACTER:         $badCharacters
            |  token mismatches:      ${mismatches.size} (in $filesWithMismatches files)
            |  allowlisted:           ${allowed.size} (allowlist entries: ${allowlist.size}, stale: ${staleAllowlist.size})
            |  full list:             $report
            """.trimMargin(),
        )
        skippedFiles.forEach { println("  SKIPPED $it") }
        printCategories("mismatches by category", mismatches)
        printCategories("allowlisted by category", allowed)
        mismatches.take(20).forEach { println("  MISMATCH $it") }
        staleAllowlist.sorted().take(20).forEach { println("  STALE ALLOWLIST $it") }

        CorpusMetrics.check(
            repo.resolve("testData/metrics/goroot-src-lexer.json"),
            linkedMapOf(
                "files" to files.toLong(),
                "badCharacters" to badCharacters.toLong(),
                "tokenMismatches" to mismatches.size.toLong(),
                "filesWithMismatches" to filesWithMismatches.toLong(),
            ),
            informational = setOf("files"),
        )
    }

    /**
     * Cross-checks the inputs of the lexer goldens (the `.go` files in `testData/lexer`) against go/scanner. The only
     * accepted differences are listed here with their reason.
     */
    fun testLexerTestDataMatchesGoScanner() {
        // All entries are class 1 of corpus-allowlist.txt: go/scanner inserts the semicolon at the
        // first newline inside a multi-line block comment; GoLexer cannot split the comment.
        val expected = setOf(
            // `z /* multi\n   line */\n`: semicolon shifted to the newline after the comment.
            "Semicolons.go:303 go/scanner=SEMICOLON GoLexer=(none)",
            "Semicolons.go:314 go/scanner=(none) GoLexer=SEMICOLON",
            // `w /* multi\n   line */ v`: no newline after the comment, so no semicolon at all.
            "Semicolons.go:325 go/scanner=SEMICOLON GoLexer=(none)",
            // `x /* block with\nnewline after ident */\n`: shifted.
            "Comments.go:146 go/scanner=SEMICOLON GoLexer=(none)",
            "Comments.go:169 go/scanner=(none) GoLexer=SEMICOLON",
            // `y /*\n*/ z`: missing.
            "Comments.go:174 go/scanner=SEMICOLON GoLexer=(none)",
        )
        val result = diffTree(Paths.get(GoTestUtil.testDataPath("lexer")))
        val actual = result.mismatches.map { "${it.key} go/scanner=${it.expected ?: "(none)"} GoLexer=${it.actual ?: "(none)"}" }
        assertEquals(expected.sorted().joinToString("\n"), actual.sorted().joinToString("\n"))
    }

    /** Dumps every file under [root] with astdump and diffs it against GoLexer. */
    private fun diffTree(root: Path): Result {
        val astdump = buildAstdump(repo, buildDir)
        val dumpDir = Files.createTempDirectory("gopsi-tokens")
        try {
            val command = listOf(astdump.toString(), "walk", root.toString(), "-tokens", "-out", dumpDir.toString())
            val (code, output) = runProcess(command, repo)
            assertEquals("astdump walk failed:\n$output", 0, code)
            println(output.lines().lastOrNull { it.startsWith("walk:") } ?: output)
            return diffDumps(root, dumpDir)
        } finally {
            dumpDir.toFile().deleteRecursively()
        }
    }

    private fun diffDumps(root: Path, dumpDir: Path): Result {
        val dumps = Files.walk(dumpDir).use { s ->
            s.filter { it.isRegularFile() && it.extension == "tokens" }.sorted().toList()
        }
        val lexer = GoLexer()
        val mismatches = mutableListOf<Mismatch>()
        val skippedFiles = mutableListOf<String>()
        var files = 0
        var badCharacters = 0
        var tokens = 0L

        for (dump in dumps) {
            val rel = dumpDir.relativize(dump).toString().replace(File.separatorChar, '/').removeSuffix(".tokens")
            val bytes = root.resolve(rel).readBytes()
            val text = decodeUtf8(bytes)
            if (text == null) {
                skippedFiles += "$rel (invalid UTF-8: char offsets cannot be mapped to byte offsets)"
                continue
            }
            files++
            val byteOffsets = byteOffsets(text)
            val expected = readDump(dump, bytes.size)

            val actual = ArrayList<Token>(expected.size)
            lexer.start(text)
            while (true) {
                val type = lexer.tokenType ?: break
                if (type == TokenType.BAD_CHARACTER) badCharacters++
                GoTokenMapping.goName(type)?.let { actual += Token(byteOffsets[lexer.tokenStart], it) }
                lexer.advance()
            }
            tokens += actual.size
            mismatches += diff(rel, expected, actual)
        }
        return Result(files, tokens, badCharacters, mismatches, skippedFiles)
    }

    private fun printCategories(title: String, list: List<Mismatch>) {
        if (list.isEmpty()) return
        println("  $title:")
        list.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }
            .forEach { (category, count) -> println("    %6d  %s".format(count, category)) }
    }

    /** Merge-joins both streams by offset, so one missing or extra token does not cascade. */
    private fun diff(file: String, expected: List<Token>, actual: List<Token>): List<Mismatch> {
        val result = mutableListOf<Mismatch>()
        var i = 0
        var j = 0
        while (i < expected.size || j < actual.size) {
            val e = expected.getOrNull(i)
            val a = actual.getOrNull(j)
            if (e != null && a != null && e.offset == a.offset) {
                if (e.name != a.name) result += Mismatch(file, e.offset, e.name, a.name)
                i++
                j++
            } else if (e != null && (a == null || e.offset < a.offset)) {
                result += Mismatch(file, e.offset, e.name, null)
                i++
            } else if (a != null) {
                result += Mismatch(file, a.offset, null, a.name)
                j++
            }
        }
        return result
    }

    /** Reads an astdump token dump; drops EOF, ERROR lines and the auto-semicolon at EOF. */
    private fun readDump(dump: Path, length: Int): List<Token> =
        dump.readLines().mapNotNull { line ->
            if (line.startsWith("ERROR ")) return@mapNotNull null
            val parts = line.split('\t', limit = 3)
            val offset = parts[0].toInt()
            val name = parts[1]
            when {
                name == "EOF" -> null
                name == "SEMICOLON" && offset == length -> null
                else -> Token(offset, name)
            }
        }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** UTF-8 byte offset of every char index (and of text.length). */
    private fun byteOffsets(text: String): IntArray {
        val result = IntArray(text.length + 1)
        var bytes = 0
        var i = 0
        while (i < text.length) {
            result[i] = bytes
            val c = text[i]
            if (c.code < 0x80) {
                bytes += 1
            } else if (c.code < 0x800) {
                bytes += 2
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                result[i + 1] = bytes
                bytes += 4
                i++
            } else {
                bytes += 3
            }
            i++
        }
        result[text.length] = bytes
        return result
    }

    private fun readAllowlist(file: Path): Set<String> =
        if (!Files.exists(file)) {
            emptySet()
        } else {
            file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
        }

    companion object {
        /** Builds tools/astdump into [buildDir]; fails with a clear message when `go` is missing. */
        fun buildAstdump(repo: Path, buildDir: Path): Path {
            Files.createDirectories(buildDir)
            val exe = buildDir.resolve(if (File.separatorChar == '\\') "astdump.exe" else "astdump")
            val result = try {
                runProcess(listOf("go", "build", "-o", exe.toString(), "."), repo.resolve("tools/astdump"))
            } catch (e: IOException) {
                fail("Cannot run `go build` for tools/astdump: the `go` binary is not on PATH ($e)")
                throw e
            }
            assertEquals("go build ./tools/astdump failed:\n${result.second}", 0, result.first)
            return exe
        }

        fun runProcess(command: List<String>, workDir: Path): Pair<Int, String> {
            val process = ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(30, TimeUnit.MINUTES)) { "Timed out: $command" }
            return process.exitValue() to output
        }
    }
}
