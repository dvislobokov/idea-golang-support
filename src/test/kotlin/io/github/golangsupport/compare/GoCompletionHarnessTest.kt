package io.github.golangsupport.compare

import com.google.gson.JsonParser
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import java.io.File

/**
 * Part 2 of docs/compare: "restore the deleted identifier". For each site of the sample (tools/compare/sites, GO_HARNESS_SITES, default
 * build/compare/sites.json) the tail of the identifier is deleted leaving a prefix of 0, 1 or 3 characters, basic completion runs at the
 * caret, and the position of the real identifier in the list is recorded with the time of the call. For a package whose import is
 * deleted the item is also chosen once, to see whether the import comes back. Documents only; the clone on disk is never written.
 * Run: `GO_HARNESS=1 ./gradlew.bat :test --tests "*GoCompletionHarnessTest*" --offline`.
 */
class GoCompletionHarnessTest : GoHarnessTestBase() {

    data class Attempt(val site: Int, val file: String, val line: Int, val ident: String, val kind: String, val sub: String, val prefix: Int,
                       val rank: Int, val size: Int, val millis: Long, val top: List<String>, val note: String? = null)

    data class Insertion(val site: Int, val ident: String, val importPath: String, val prefix: Int, val chosen: Boolean,
                         val importAdded: Boolean, val lineAfter: String, val caretColumn: Int)

    fun testRestoreDeleted() {
        if (!enabled) return
        val sites = JsonParser.parseString(File(env("GO_HARNESS_SITES", "build/compare/sites.json")).absoluteFile.readText()).asJsonArray.map { it.asJsonObject }
        val limit = env("GO_HARNESS_LIMIT", "100000").toInt()
        val from = env("GO_HARNESS_FROM", "0").toInt()
        val settings = CodeInsightSettings.getInstance()
        val autocomplete = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        val attempts = ArrayList<Attempt>()
        val insertions = ArrayList<Insertion>()
        try {
            var openFile: String? = null
            for (s in sites.drop(from).take(limit)) {
                val id = s["id"].asInt
                val path = s["file"].asString
                val ident = s["ident"].asString
                val kind = s["kind"].asString
                val sub = s["sub"].asString
                val vf = file(path)
                if (openFile != path) { myFixture.configureFromExistingVirtualFile(vf); openFile = path }
                val document = FileDocumentManager.getInstance().getDocument(vf)!!
                val original = document.text
                val bytes = original.toByteArray(Charsets.UTF_8)
                fun chars(byteOffset: Int) = String(bytes, 0, byteOffset, Charsets.UTF_8).length
                val at = chars(s["offset"].asInt)
                if (!original.startsWith(ident, at)) {
                    attempts += Attempt(id, path, s["line"].asInt, ident, kind, sub, -1, -1, 0, 0, emptyList(), "site text mismatch")
                    continue
                }
                val importRange = if (kind == "unimported-package") chars(s["importStart"].asInt) to chars(s["importEnd"].asInt) else null
                val prefixes = listOf(0, 1, 3).filter { it < ident.length }
                for (prefix in prefixes) {
                    val caret = edit(document, at, ident, prefix, importRange)
                    myFixture.editor.caretModel.moveToOffset(caret)
                    val t0 = System.nanoTime()
                    val items = myFixture.complete(CompletionType.BASIC)?.toList() ?: emptyList()
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val strings = items.map { it.lookupString }
                    var index = strings.indexOf(ident)
                    // a package offered for import: by its name, its import path, or a qualified member `pkg.Name` (the GoLand way)
                    if (index < 0 && importRange != null) index = strings.indexOfFirst { it == s["importPath"].asString || it.startsWith("$ident.") }
                    val rank = if (index < 0) -1 else index + 1
                    val note = if (items.isEmpty() && LookupManager.getActiveLookup(myFixture.editor) == null && document.text != textAfterEdit) "auto-inserted" else null
                    attempts += Attempt(id, path, s["line"].asInt, ident, kind, sub, prefix, rank, strings.size, ms, strings.take(5), note)
                    if (importRange != null && prefix == prefixes.last()) insertions += choose(id, ident, s["importPath"].asString, prefix, items.firstOrNull { it.lookupString == ident })
                    LookupManager.hideActiveLookup(project)
                    WriteCommandAction.runWriteCommandAction(project) { document.setText(original) }
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                }
                if (id % 20 == 0) log("site $id/${sites.size}: ${attempts.size} attempts")
            }
        } finally {
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
            FileDocumentManager.getInstance().reloadFiles(*sites.mapNotNull { root.findFileByRelativePath(it["file"].asString) }.distinct().toTypedArray())
        }
        writeJson(env("GO_HARNESS_NAME", "completion.json"), mapOf("attempts" to attempts, "insertions" to insertions))
    }

    private var textAfterEdit = ""

    /** Deletes the identifier's tail after [prefix] characters (and the import line); returns the caret offset. */
    private fun edit(document: com.intellij.openapi.editor.Document, at: Int, ident: String, prefix: Int, importRange: Pair<Int, Int>?): Int {
        var caret = at + prefix
        WriteCommandAction.runWriteCommandAction(project) {
            document.deleteString(at + prefix, at + ident.length)
            if (importRange != null) {
                document.deleteString(importRange.first, importRange.second)
                caret -= importRange.second - importRange.first
            }
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        textAfterEdit = document.text
        return caret
    }

    /** Chooses [item] with Enter and records whether the import came back and what the line looks like. */
    private fun choose(site: Int, ident: String, importPath: String, prefix: Int, item: com.intellij.codeInsight.lookup.LookupElement?): Insertion {
        val lookup = LookupManager.getActiveLookup(myFixture.editor) as? LookupImpl
        if (item == null || lookup == null) return Insertion(site, ident, importPath, prefix, false, false, "", -1)
        lookup.currentItem = item
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val document = myFixture.editor.document
        val caret = myFixture.editor.caretModel.offset
        val line = document.getLineNumber(caret)
        val start = document.getLineStartOffset(line)
        val text = document.text
        val imported = Regex("""(?m)^\s*(?:import\s+)?(?:\w+\s+)?"${Regex.escape(importPath)}"""").containsMatchIn(text)
        return Insertion(site, ident, importPath, prefix, true, imported, document.getText(com.intellij.openapi.util.TextRange(start, document.getLineEndOffset(line))).trim(), caret - start)
    }
}
