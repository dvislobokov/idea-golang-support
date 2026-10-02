package io.github.golangsupport.lang.parser

import io.github.golangsupport.GoParsingTestCase
import io.github.golangsupport.GoTestUtil
import io.github.golangsupport.lang.lexer.CorpusMetrics
import io.github.golangsupport.lang.lexer.GorootLexerDiffCorpusTest
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readLines

/**
 * Corpus gate: the go-psi PSI tree, normalised by [GoAstMapping], against the go/ast tree printed
 * by `tools/astdump walk -ast` (which skips `testdata`, `.`- and `_`-prefixed entries). Nodes are
 * compared as (kind, UTF-8 byte range, a few go/ast fields) pairs, siblings aligned by start
 * offset, so a missing or extra node does not cascade.
 *
 * Root: `$GOROOT/src`, or `-Pgopsi.astdiff.root=<dir>` (for example `$GOMODCACHE/golang.org/x`);
 * metrics are compared only for the default root. Mismatch classes (see [AstMismatch.cls]) listed
 * in `testData/parser/ast-diff-allowlist.txt` as `class <class>` are deliberate differences and
 * are not counted; single mismatches can be allowlisted as `<file>:<offset>`. The full list is
 * written to `go-psi-core/build/astdump/ast-mismatches.txt`.
 */
class GorootAstDiffCorpusTest : GoParsingTestCase("parser") {

    private val repo: Path = File(GoTestUtil.testDataPath()).parentFile.toPath()
    private val buildDir: Path = repo.resolve("go-psi-core/build/astdump")

    fun testAstMatchesGoAst() {
        val custom = System.getProperty("gopsi.astdiff.root")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val root = custom ?: GoTestUtil.goroot().resolve("src")
        assertTrue("corpus root not found: $root", Files.isDirectory(root))

        val astdump = GorootLexerDiffCorpusTest.buildAstdump(repo, buildDir)
        val dumpDir = Files.createTempDirectory("gopsi-ast")
        val mismatches = ArrayList<AstMismatch>()
        val skipped = ArrayList<String>()
        var files = 0
        var nodes = 0L
        try {
            val (code, output) = GorootLexerDiffCorpusTest.runProcess(
                listOf(astdump.toString(), "walk", root.toString(), "-ast", "-out", dumpDir.toString()),
                repo,
            )
            assertEquals("astdump walk failed:\n$output", 0, code)
            println(output.lines().lastOrNull { it.startsWith("walk:") } ?: output)

            val dumps = Files.walk(dumpDir).use { s ->
                s.filter { it.isRegularFile() && it.extension == "ast" }.sorted().toList()
            }
            for (dump in dumps) {
                val rel = dumpDir.relativize(dump).toString().replace(File.separatorChar, '/').removeSuffix(".ast")
                val expected = GoAstDump.read(dump)
                if (expected == null) {
                    skipped += "$rel (go/parser reported errors)"
                    continue
                }
                val bytes = root.resolve(rel).readBytes()
                val text = decodeUtf8(bytes)
                if (text == null) {
                    skipped += "$rel (invalid UTF-8)"
                    continue
                }
                val psi = createPsiFile(root.resolve(rel).name, text)
                if (psi == null) {
                    skipped += "$rel (createPsiFile returned null)"
                    continue
                }
                ensureParsed(psi)
                if (psi.text != text) {
                    skipped += "$rel (PSI text differs from the file, line separators?)"
                    continue
                }
                files++
                nodes += expected.count()
                val actual = GoAstMapping(text).build(psi)
                AstDiff.diff(rel, expected, actual, mismatches)
            }
        } finally {
            dumpDir.toFile().deleteRecursively()
        }
        assertTrue("Expected > 100 files, found $files", files > 100)

        val allowlist = readAllowlist(repo.resolve("testData/parser/ast-diff-allowlist.txt"))
        val (allowed, real) = mismatches.partition { it.cls in allowlist || it.key in allowlist }
        val filesWithMismatches = real.map { it.file }.distinct().size
        val usedAllowEntries = allowed.flatMap { listOf(it.cls, it.key) }.toSet()
        val stale = allowlist.filter { it !in usedAllowEntries }

        Files.createDirectories(buildDir)
        val report = buildDir.resolve("ast-mismatches.txt")
        Files.write(report, real.map { "MISMATCH $it" } + allowed.map { "ALLOWED $it" })

        println(
            """
            |GorootAstDiffCorpusTest summary
            |  root:                  $root
            |  files compared:        $files (skipped ${skipped.size})
            |  go/ast nodes:          $nodes
            |  mismatches:            ${real.size} (in $filesWithMismatches files)
            |  allowlisted:           ${allowed.size} (allowlist entries: ${allowlist.size}, stale: ${stale.size})
            |  full list:             $report
            """.trimMargin(),
        )
        skipped.take(20).forEach { println("  SKIPPED $it") }
        printClasses("mismatch classes", real)
        printClasses("allowlisted classes", allowed)
        printExamples(real, 40)
        stale.sorted().take(20).forEach { println("  STALE ALLOWLIST $it") }

        if (custom == null) {
            CorpusMetrics.check(
                repo.resolve("testData/metrics/goroot-src-ast-diff.json"),
                linkedMapOf(
                    "files" to files.toLong(),
                    "nodes" to nodes,
                    "mismatches" to real.size.toLong(),
                    "filesWithMismatches" to filesWithMismatches.toLong(),
                ),
                informational = setOf("files", "nodes"),
            )
        } else {
            assertTrue("AST mismatches over $root: ${real.size}", true)
        }
    }

    private fun printClasses(title: String, list: List<AstMismatch>) {
        if (list.isEmpty()) return
        println("  $title:")
        list.groupingBy { it.cls }.eachCount().entries.sortedByDescending { it.value }
            .forEach { (cls, count) -> println("    %7d  %s".format(count, cls)) }
    }

    /** First [limit] examples, spread over the classes (up to 3 per class, most frequent classes first). */
    private fun printExamples(list: List<AstMismatch>, limit: Int) {
        if (list.isEmpty()) return
        println("  first examples:")
        val byClass = list.groupBy { it.cls }.entries.sortedByDescending { it.value.size }
        var printed = 0
        for (round in 0 until 3) {
            for ((_, items) in byClass) {
                if (printed >= limit) return
                items.getOrNull(round)?.let { println("    $it"); printed++ }
            }
        }
    }

    private fun readAllowlist(file: Path): Set<String> =
        if (!Files.exists(file)) {
            emptySet()
        } else {
            file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
                .map { it.removePrefix("class ").trim() }.toSet()
        }

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

/** One difference between the go/ast tree and the normalised PSI tree. */
class AstMismatch(val file: String, val offset: Int, val cls: String, private val expected: String, private val actual: String) {
    val key get() = "$file:$offset"
    override fun toString() = "$key  [$cls]  expected $expected got $actual"
}

object AstDiff {
    /**
     * Compares [expected] (go/ast) with [actual] (PSI). Classes: `Kind missing` (go/ast has it, PSI not),
     * `Kind extra`, `Expected -> Actual kind`, `Kind range` and `Kind value` (Ident name, operator, ...).
     */
    fun diff(file: String, expected: AstNode, actual: AstNode, out: MutableList<AstMismatch>) {
        pair(file, expected, actual, out)
    }

    private fun pair(file: String, e: AstNode, a: AstNode, out: MutableList<AstMismatch>) {
        if (e.kind != a.kind) {
            out += AstMismatch(file, e.start, "${e.kind} -> ${a.kind} kind", e.toString(), a.toString())
            return
        }
        // A label without a statement (`L:` before `}`) gets an implicit go/ast EmptyStmt positioned at the
        // next token, which stretches LabeledStmt.End(); go-psi ends the node at the colon.
        val implicitEmptyStmt = e.kind == "LabeledStmt" && e.children.size == 1 && a.children.size == 1 && e.end > a.end
        if (e.start != a.start || (e.end != a.end && !implicitEmptyStmt)) {
            out += AstMismatch(file, e.start, "${e.kind} range", e.toString(), a.toString())
        } else if (e.extra != a.extra) {
            out += AstMismatch(file, e.start, "${e.kind} value", e.toString(), a.toString())
        }
        children(file, e.children, a.children, out)
    }

    private fun children(file: String, exp: List<AstNode>, act: List<AstNode>, out: MutableList<AstMismatch>) {
        var i = 0
        var j = 0
        while (i < exp.size || j < act.size) {
            val e = exp.getOrNull(i)
            val a = act.getOrNull(j)
            when {
                e != null && a != null && e.start == a.start -> {
                    pair(file, e, a, out)
                    i++
                    j++
                }
                e != null && a != null && e.kind == a.kind && e.start < a.end && a.start < e.end -> {
                    pair(file, e, a, out) // same node, different start
                    i++
                    j++
                }
                e != null && (a == null || e.start < a.start) -> {
                    out += AstMismatch(file, e.start, "${e.kind} missing", e.toString(), "(none)")
                    i++
                }
                else -> {
                    out += AstMismatch(file, a!!.start, "${a.kind} extra", "(none)", a.toString())
                    j++
                }
            }
        }
    }
}
