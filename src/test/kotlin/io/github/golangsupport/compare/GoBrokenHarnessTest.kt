package io.github.golangsupport.compare

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import java.io.File

/**
 * Part 1 of docs/compare: the errors the plugin shows on a clean clone (every one is extra) and on broken code. Breakages come from a
 * file of `path|was|becomes` lines (`\n`, `\t`, `\|` escaped; `## category` lines name the category of the lines below, `#` lines are
 * comments). Each is applied to the document only (the clone on disk is never written), the non-test files of its package are
 * highlighted, and the edit is undone. Run: `GO_HARNESS=1 ./gradlew.bat :test --tests "*GoBrokenHarnessTest*" --offline`.
 * GO_HARNESS_PACKAGES — the directories of the clean measurement (comma-separated), GO_HARNESS_BREAKS — the breakage file.
 */
class GoBrokenHarnessTest : GoHarnessTestBase() {

    data class Breakage(val id: Int, val category: String, val path: String, val was: String, val becomes: String)

    data class BrokenResult(val id: Int, val category: String, val path: String, val line: Int, val was: String, val becomes: String,
                            val applied: Boolean, val note: String?, val millis: Long, val problems: List<Problem>)

    private val defaultPackages = listOf(
        "promql", "promql/parser", "tsdb", "tsdb/chunkenc", "storage/remote", "scrape", "rules", "discovery/kubernetes", "model/labels",
        "util/annotations", "web/api/v1", "cmd/promtool",
    )

    private fun goFiles(dir: VirtualFile, tests: Boolean): List<VirtualFile> =
        dir.children.filter { !it.isDirectory && it.name.endsWith(".go") && (tests || !it.name.endsWith("_test.go")) && (tests || inBuild(it)) }.sortedBy { it.name }

    private fun highlight(files: List<VirtualFile>): List<Problem> = files.flatMap { vf ->
        myFixture.configureFromExistingVirtualFile(vf)
        problems()
    }

    fun testClean() {
        if (!enabled) return
        val dirs = env("GO_HARNESS_PACKAGES", defaultPackages.joinToString(",")).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val result = linkedMapOf<String, Any>()
        for (dir in dirs) {
            val files = goFiles(file(dir), tests = true)
            val t0 = System.currentTimeMillis()
            val found = highlight(files)
            val ms = System.currentTimeMillis() - t0
            log("clean $dir: ${files.size} files, ${found.size} problems, $ms ms")
            result[dir] = mapOf("files" to files.map { it.name }, "excludedByBuild" to files.filterNot { inBuild(it) }.map { it.name }, "millis" to ms, "problems" to found)
        }
        writeJson("clean.json", result)
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\|", "\u0000").replace("\u0000", "|")

    private fun readBreakages(file: File): List<Breakage> {
        var category = ""
        var id = 0
        val list = ArrayList<Breakage>()
        for (raw in file.readLines()) {
            val line = raw.trimEnd()
            if (line.startsWith("## ")) { category = line.removePrefix("## ").trim(); continue }
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.replace("\\|", "\u0001").split('|').map { it.replace("\u0001", "\\|") }
            require(parts.size == 3) { "bad breakage line: $line" }
            list += Breakage(++id, category, parts[0], unescape(parts[1]), unescape(parts[2]))
        }
        return list
    }

    fun testBroken() {
        if (!enabled) return
        val breaks = readBreakages(File(env("GO_HARNESS_BREAKS", "docs/compare/prometheus-broken-dumps/breakages.txt")).absoluteFile)
        val only = System.getenv("GO_HARNESS_ONLY")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
        val baseline = HashMap<String, List<Problem>>()
        val results = ArrayList<BrokenResult>()
        for (b in breaks) {
            if (only != null && b.id !in only) continue
            val vf = file(b.path)
            val dir = vf.parent
            val files = goFiles(dir, tests = false)
            baseline.getOrPut(dir.path) { highlight(files) }
            val document = FileDocumentManager.getInstance().getDocument(vf)!!
            val text = document.text
            val at = text.indexOf(b.was)
            if (at < 0) {
                results += BrokenResult(b.id, b.category, b.path, 0, b.was, b.becomes, false, "text not found", 0, emptyList())
                log("#${b.id} NOT FOUND in ${b.path}: ${b.was}")
                continue
            }
            val note = if (text.indexOf(b.was, at + 1) >= 0) "ambiguous: first occurrence used" else null
            val line = document.getLineNumber(at) + 1
            WriteCommandAction.runWriteCommandAction(project) { document.replaceString(at, at + b.was.length, b.becomes) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val t0 = System.currentTimeMillis()
            val found = try { highlight(files) } finally {
                WriteCommandAction.runWriteCommandAction(project) { document.replaceString(at, at + b.becomes.length, b.was) }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            val ms = System.currentTimeMillis() - t0
            check(document.text == text) { "#${b.id}: the document was not restored" }
            results += BrokenResult(b.id, b.category, b.path, line, b.was, b.becomes, true, note, ms, found)
            log("#${b.id} ${b.category} ${b.path}:$line -> ${found.count { it.severity == "ERROR" }} errors, ${found.size} problems, $ms ms")
        }
        writeJson("broken-baseline.json", baseline.mapKeys { it.key.removePrefix(root.path + "/") })
        writeJson("broken.json", results)
        // nothing reaches the disk: unsaved documents are dropped, the clone stays clean
        FileDocumentManager.getInstance().reloadFiles(*breaks.mapNotNull { root.findFileByRelativePath(it.path) }.distinct().toTypedArray())
    }
}
