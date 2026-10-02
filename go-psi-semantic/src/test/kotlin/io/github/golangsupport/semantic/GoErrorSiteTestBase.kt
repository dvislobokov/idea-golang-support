package io.github.golangsupport.semantic

import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.project.GoProjectModelTestBase
import io.github.golangsupport.project.ProjectTestUtil
import io.github.golangsupport.semantic.api.GoDiagnostic
import io.github.golangsupport.semantic.api.GoSemanticService
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `go/types` testdata protocol (check_test.go): `/* ERROR "substr" */` and
 * `/* ERRORx "regexp" */` (or the `//` forms) mark lines that must produce a diagnostic whose
 * message contains the substring / matches the regexp. Every diagnostic must match an
 * annotation on its line (otherwise it is a false positive) and every annotation must be matched,
 * unless listed in the allowlist (`<relative path>:<line>  # reason`).
 */
abstract class GoErrorSiteTestBase : GoProjectModelTestBase() {

    class FileResult(val path: String, val sites: Int, val matched: Int, val allowlisted: Int, val missed: List<String>, val falsePositives: List<String>, val diagnostics: Int, val skipped: String? = null)

    override fun setUp() {
        super.setUp()
        com.intellij.openapi.util.RecursionManager.disableAssertOnRecursionPrevention(testRootDisposable)
        com.intellij.openapi.util.RecursionManager.disableMissedCacheAssertions(testRootDisposable)
    }

    protected fun loadAllowlist(file: Path): Map<String, String> {
        if (!Files.exists(file)) return emptyMap()
        return Files.readAllLines(file).filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .associate { line -> line.substringBefore('#').trim() to line.substringAfter('#', "").trim() }
    }

    /** Checks one file; [relPath] is used for allowlist keys and reports. */
    protected fun checkFile(file: File, relPath: String, allowlist: Map<String, String>, packageDir: String): FileResult {
        val text = file.readText().replace("\r\n", "\n")
        val first = text.lineSequence().firstOrNull() ?: ""
        if (first.startsWith("// -")) return FileResult(relPath, 0, 0, 0, emptyList(), emptyList(), 0, skipped = first)
        val psi = myFixture.addFileToProject("$packageDir/${file.name}", text) as GoFile
        val annotations = parseAnnotations(text)
        // `assert` and `trace` are test-only builtins of the go/types test harness.
        val diagnostics = GoSemanticService.getInstance(project).check(psi).filter { it.message != "undefined: assert" && it.message != "undefined: trace" }
        val lineStarts = lineStarts(text)
        fun lineOf(offset: Int): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 } + 1
        val remaining = annotations.groupBy { it.line }.mapValues { it.value.toMutableList() }
        val consumed = ArrayList<Annotation>()
        val falsePositives = ArrayList<String>()
        var allowlistedFps = 0
        // The checker reports each problem once; an identical diagnostic twice is a checker bug (no `distinct()` downstream).
        diagnostics.groupBy { Triple(it.range, it.message, it.code) }.filter { it.value.size > 1 }.keys.forEach { (range, message, code) ->
            falsePositives += "$relPath:${lineOf(range.startOffset)}: duplicate diagnostic: \"${message.lineSequence().first()}\" [$code]"
        }
        for (d in diagnostics) {
            val line = lineOf(d.range.startOffset)
            val candidates = remaining[line] ?: mutableListOf()
            val hit = candidates.firstOrNull { it.matches(d.message) }
            if (hit != null) { candidates.remove(hit); consumed += hit; continue }
            if (consumed.any { it.line == line && it.matches(d.message) }) continue // a second diagnostic for the same site
            if (allowlist.containsKey("$relPath:$line")) { allowlistedFps++; continue }
            falsePositives += "$relPath:$line: no error expected: \"${d.message.lineSequence().first()}\" [${d.code}]"
        }
        val missed = ArrayList<String>()
        var allowlisted = 0
        for ((line, list) in remaining) for (a in list) {
            val key = "$relPath:$line"
            if (allowlist.containsKey(key)) allowlisted++ else missed += "$key: expected ${a.raw}${nearby(diagnostics, line, ::lineOf)}"
        }
        return FileResult(relPath, annotations.size, consumed.size, allowlisted + allowlistedFps, missed, falsePositives, diagnostics.size)
    }

    private fun nearby(diagnostics: List<GoDiagnostic>, line: Int, lineOf: (Int) -> Int): String {
        val same = diagnostics.filter { lineOf(it.range.startOffset) == line }
        return if (same.isEmpty()) "" else " (got: ${same.joinToString(" | ") { "\"" + it.message.lineSequence().first() + "\"" }})"
    }

    protected fun report(results: List<FileResult>): String {
        val sb = StringBuilder()
        var sites = 0; var matched = 0; var allow = 0; var fp = 0; var missed = 0
        for (r in results) {
            if (r.skipped != null) { sb.append("${r.path}: skipped (${r.skipped})\n"); continue }
            sites += r.sites; matched += r.matched; allow += r.allowlisted; fp += r.falsePositives.size; missed += r.missed.size
            val pct = if (r.sites == 0) 100 else r.matched * 100 / r.sites
            sb.append("${r.path}: sites=${r.sites} matched=${r.matched} ($pct%) allowlisted=${r.allowlisted} missed=${r.missed.size} falsePositives=${r.falsePositives.size} diagnostics=${r.diagnostics}\n")
        }
        val pct = if (sites == 0) 100 else matched * 100 / sites
        sb.append("TOTAL sites=$sites matched=$matched ($pct%) allowlisted=$allow missed=$missed falsePositives=$fp\n")
        sb.append("FALSE POSITIVES:\n").append(results.flatMap { it.falsePositives }.take(600).joinToString("\n") { "  $it" }).append('\n')
        sb.append("MISSED:\n").append(results.flatMap { it.missed }.take(900).joinToString("\n") { "  $it" }).append('\n')
        return sb.toString()
    }

    private fun lineStarts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts += 0
        text.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
        return starts.toIntArray()
    }

    class Annotation(val line: Int, val raw: String, val regex: Regex?, val substring: String?) {
        fun matches(message: String): Boolean {
            val m = normalize(message)
            return if (regex != null) regex.containsMatchIn(m) else m.contains(normalize(substring!!))
        }
        /** go/types elides literal bodies with U+2026; patterns may spell it as `...`. */
        private fun normalize(s: String): String = s.replace("…", "...")
    }

    private fun parseAnnotations(text: String): List<Annotation> {
        val out = ArrayList<Annotation>()
        val lineStarts = lineStarts(text)
        fun lineOf(offset: Int): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 } + 1
        for (m in ANNOTATION.findAll(text)) {
            // A `/* ERROR */` inside a `//` comment (commented-out test code) is not an annotation in go/types either.
            val line = lineOf(m.range.first)
            if (m.value.startsWith("/*") && text.substring(lineStarts[line - 1], m.range.first).contains("//")) continue
            val x = m.groupValues[1] == "x"
            val quoted = m.groupValues[2]
            val pattern = unquote(quoted)
            out += Annotation(lineOf(m.range.first), "ERROR${if (x) "x" else ""} $quoted", if (x) Regex(pattern.replace("…", "...")) else null, if (x) null else pattern)
        }
        return out
    }

    private fun unquote(q: String): String {
        if (q.startsWith("`")) return q.removePrefix("`").removeSuffix("`")
        val body = q.removePrefix("\"").removeSuffix("\"")
        return io.github.golangsupport.semantic.types.GoConstant.unescape(body) ?: body
    }

    companion object {
        /** `/* ERROR "x" */`, `/* ERRORx `re` */`, `// ERROR "x"` up to the end of the line. */
        private val ANNOTATION = Regex("""(?:/\*|//) ERROR(x?) ("(?:[^"\\]|\\.)*"|`[^`]*`)""")

        fun testDataPath(rel: String): Path = ProjectTestUtil.testDataPath().resolve(rel)
    }
}
