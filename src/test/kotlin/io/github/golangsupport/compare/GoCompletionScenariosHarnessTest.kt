package io.github.golangsupport.compare

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import java.io.File

/**
 * Part 2 of docs/compare, the scenarios: tools/compare/completion/scenarios.json on the probe file tools/compare/completion/probe/
 * zz_compare_probe.go, the same steps as tools/ui-robot/goland/analysis/complete_batch.js does in GoLand (insert the text after the
 * anchor, type `typed`, invoke completion, record the list, choose `pick` with Enter / Tab, record the lines with the caret). The probe
 * file is written into rules/ of the clone for the run and deleted after it. Output: build/compare/out/plugin-scenarios.json.
 */
class GoCompletionScenariosHarnessTest : GoHarnessTestBase() {

    data class Result(val id: String, val items: List<String>, val size: Int, val lookup: Boolean, val picked: String?, val after: List<String>,
                      val head: List<String>, val note: String?, val millis: Long)

    private fun present(item: LookupElement): String {
        val p = LookupElementPresentation()
        item.renderElement(p)
        return (if (p.isItemTextBold) "*" else "") + (p.itemText ?: item.lookupString) + (p.tailText ?: "") + (p.typeText?.let { "  : $it" } ?: "")
    }

    private fun lines(from: Int, count: Int): List<String> {
        val editor = myFixture.editor
        val text = editor.document.text.let { it.substring(0, editor.caretModel.offset) + "<caret>" + it.substring(editor.caretModel.offset) }
        return text.split("\n").drop(maxOf(0, from)).take(count)
    }

    fun testScenarios() {
        if (!enabled) return
        val repo = File(System.getProperty("user.dir")).absoluteFile
        val scenarios = JsonParser.parseString(File(repo, "tools/compare/completion/scenarios.json").readText()).asJsonArray.map { it.asJsonObject }
        val probeText = File(repo, "tools/compare/completion/probe/zz_compare_probe.go").readText().replace("\r\n", "\n")
        val dir = file("rules")
        val probe = WriteAction.computeAndWait<com.intellij.openapi.vfs.VirtualFile, Throwable> {
            dir.createChildData(this, "zz_compare_probe.go").also { VfsUtil.saveText(it, probeText) }
        }
        val only = System.getenv("GO_HARNESS_ONLY")?.split(',')?.map { it.trim() }?.toSet()
        val results = ArrayList<Result>()
        try {
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            myFixture.configureFromExistingVirtualFile(probe)
            val document = myFixture.editor.document
            val original = document.text
            for (sc in scenarios) {
                val id = sc["id"].asString
                if (only != null && id !in only) continue
                results += runCatching { run(sc, original) }.getOrElse { Result(id, emptyList(), 0, false, null, emptyList(), emptyList(), "error: $it", 0) }
                LookupManager.hideActiveLookup(project)
                TemplateManagerImpl.getTemplateState(myFixture.editor)?.let { com.intellij.openapi.util.Disposer.dispose(it) }
                WriteCommandAction.runWriteCommandAction(project) { document.setText(original) }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
        } finally {
            WriteAction.runAndWait<Throwable> { probe.delete(this) }
        }
        writeJson("plugin-scenarios.json", results)
    }

    private fun run(sc: JsonObject, original: String): Result {
        val id = sc["id"].asString
        val document = myFixture.editor.document
        val at = original.indexOf("// " + sc["mark"].asString)
        val lineNo = document.getLineNumber(at) + 1
        val start = document.getLineStartOffset(lineNo)
        var text = sc["type"].asString
        val caretAt = text.indexOf("@@")
        text = text.removeRange(caretAt, caretAt + 2)
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(start, text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.editor.caretModel.moveToOffset(start + caretAt)
        sc["typed"]?.asString?.let { myFixture.type(it) }
        LookupManager.hideActiveLookup(project)
        val limit = sc["limit"]?.asInt ?: 12
        val show = sc["show"]?.asInt ?: 3
        val t0 = System.nanoTime()
        val items = myFixture.complete(if (sc["kind"].asString == "SMART") CompletionType.SMART else CompletionType.BASIC)?.toList()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val lookup = LookupManager.getActiveLookup(myFixture.editor) as? LookupImpl
        if (items == null || lookup == null) {
            return Result(id, emptyList(), 0, false, null, lines(lineNo - 1, show), head(sc), if (items == null) null else "no lookup", ms)
        }
        val shown = items.take(limit).map { present(it) }
        val pick = sc["pick"]?.asString.orEmpty()
        if (pick.isEmpty()) return Result(id, shown, items.size, true, null, emptyList(), head(sc), null, ms)
        val chosen = if (pick.startsWith("#")) items.getOrNull(pick.substring(1).toInt() - 1)
        else items.firstOrNull { val p = LookupElementPresentation(); it.renderElement(p); (p.itemText ?: it.lookupString) == pick || it.lookupString == pick }
        if (chosen == null) return Result(id, shown, items.size, true, null, emptyList(), head(sc), "item not found: $pick", ms)
        lookup.currentItem = chosen
        myFixture.finishLookup(if (sc["char"]?.asString == "t") Lookup.REPLACE_SELECT_CHAR else Lookup.NORMAL_SELECT_CHAR)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return Result(id, shown, items.size, true, present(chosen), lines(lineNo - 1, show), head(sc), null, ms)
    }

    private fun head(sc: JsonObject): List<String> = sc["head"]?.asInt?.let { myFixture.editor.document.text.split("\n").take(it) } ?: emptyList()
}
