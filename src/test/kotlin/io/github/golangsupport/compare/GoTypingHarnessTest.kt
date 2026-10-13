package io.github.golangsupport.compare

import com.google.gson.GsonBuilder
import com.intellij.codeInsight.CodeInsightActionHandler
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.generation.surroundWith.SurroundWithHandler
import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.codeInsight.hint.ShowParameterInfoHandler
import com.intellij.codeInsight.hints.declarative.InlayHintsProviderFactory
import com.intellij.codeInsight.hints.declarative.InlayProviderPassInfo
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPass
import com.intellij.codeInsight.multiverse.codeInsightContext
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.CodeInsightActions
import com.intellij.lang.LanguageExpressionTypes
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CodeInsightTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.utils.parameterInfo.MockCreateParameterInfoContext
import com.intellij.testFramework.utils.parameterInfo.MockParameterInfoUIContext
import com.intellij.testFramework.utils.parameterInfo.MockUpdateParameterInfoContext
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import io.github.golangsupport.settings.GoFormatter
import io.github.golangsupport.settings.GoSettings
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Paths

/**
 * The typing / keyboard stand of docs/compare: every scenario of tools/compare/typing/scenarios.txt runs through the plugin as the
 * editor runs it (characters through the typed handlers, actions through the action system, the paste through the clipboard, queries
 * through the platform APIs Ctrl+B / Ctrl+Alt+B / Ctrl+U / Alt+F7 / Ctrl+Shift+F7 / Ctrl+F12 / Ctrl+P / Ctrl+Q / Ctrl+Shift+P / inlays use)
 * and lands in build/compare/out/typing.json; GoLand runs the same file through tools/ui-robot/goland/analysis/typing_batch.js and
 * tools/compare/typing/compare.py puts the two side by side. A light fixture: each scenario is one package directory, the toolchain is
 * the local GOROOT pinned to windows/amd64 without cgo, the language server is off. Silent unless GO_HARNESS=1.
 * GO_HARNESS_SCENARIOS (default tools/compare/typing/scenarios.txt), GO_HARNESS_OUT (default build/compare/out), GO_HARNESS_ONLY
 * (comma-separated ids), GO_HARNESS_FORMATTER (a GoFormatter name, e.g. NATIVE; default: the setting as it is).
 */
class GoTypingHarnessTest : BasePlatformTestCase() {
    private val enabled: Boolean = System.getenv("GO_HARNESS") == "1"
    private var languageServer = true
    private var formatter: GoFormatter? = null
    private lateinit var goroot: String

    override fun setUp() {
        super.setUp()
        if (!enabled) return
        languageServer = GoSettings.getInstance().languageServerEnabled
        GoSettings.getInstance().languageServerEnabled = false
        formatter = GoSettings.getInstance().formatter
        env("GO_HARNESS_FORMATTER")?.let { GoSettings.getInstance().formatter = GoFormatter.valueOf(it) }
        val root = Paths.get(System.getProperty("gopsi.goroot")?.takeIf { it.isNotBlank() } ?: "C:\\Program Files\\Go")
        goroot = root.toString().replace('\\', '/')
        val toolchain = GoToolchainInfo(
            goroot = root, version = DefaultGoToolchainProvider.readVersion(root), gopath = emptyList(),
            gomodcache = Paths.get(System.getProperty("user.home"), "go", "pkg", "mod"), goos = "windows", goarch = "amd64", cgoEnabled = false,
        )
        val fixed = object : GoToolchainProvider {
            override fun toolchainFor(project: com.intellij.openapi.project.Project?): GoToolchainInfo = toolchain
        }
        ApplicationManager.getApplication().replaceService(GoToolchainProvider::class.java, fixed, testRootDisposable)
        // scenario files live on the local disk, as in an IDE: gofmt (Reformat, format on save) runs in the file's directory, temp:// has none
        val dir = File(env("GO_HARNESS_WORK") ?: File(System.getProperty("user.dir"), "build/compare/typing-work").path).absoluteFile
        dir.deleteRecursively()
        dir.mkdirs()
        VfsRootAccess.allowRootAccess(testRootDisposable, root.toString(), dir.path)
        work = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir) ?: error("no $dir")
        PsiTestUtil.addContentRoot(module, work!!)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private var work: VirtualFile? = null

    override fun tearDown() {
        try {
            if (enabled) {
                GoSettings.getInstance().languageServerEnabled = languageServer
                formatter?.let { GoSettings.getInstance().formatter = it }
                work?.let { PsiTestUtil.removeContentEntry(module, it) }
            }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun log(text: String) {
        println("[typing] $text")
        System.out.flush()
    }

    fun testTypingScenarios() {
        if (!enabled) return
        val repo = File(System.getProperty("user.dir")).absoluteFile
        val source = File(env("GO_HARNESS_SCENARIOS") ?: File(repo, "tools/compare/typing/scenarios.txt").path)
        val out = File(env("GO_HARNESS_OUT") ?: File(repo, "build/compare/out").path).also { it.mkdirs() }
        val only = env("GO_HARNESS_ONLY")?.split(',')?.map { it.trim() }?.toSet()
        val scenarios = TypingScenarios.parse(source.readText()).filter { only == null || it.id in only }
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
        File(out, "typing-scenarios.parsed.json").writeText(gson.toJson(scenarios))
        val results = scenarios.map { s ->
            val t0 = System.currentTimeMillis()
            val r = try {
                run(s)
            } catch (e: Throwable) {
                Result(s.id, s.category, s.title, s.query, error = e.toString().take(300), stack = e.stackTrace.take(8).map { it.toString() })
            } finally {
                cleanup(s)
            }
            r.durationMs = System.currentTimeMillis() - t0
            log("${s.id} ${if (r.error != null) "ERROR ${r.error}" else "ok"} (${r.durationMs} ms)")
            r
        }
        File(out, "typing.json").writeText(gson.toJson(Output("plugin", settingsLine(), results)))
        log("wrote ${File(out, "typing.json")}: ${results.size} scenarios, ${results.count { it.error != null }} errors")
    }

    private fun settingsLine(): String = GoSettings.getInstance().let { "formatter=${it.formatter.name} formatOnSave=${it.formatOnSave} languageServer=${it.languageServerEnabled}" }

    data class Output(val ide: String, val settings: String, val results: List<Result>)

    data class Step(val key: String, val line: String, val selection: String?)

    data class Result(
        val id: String, val category: String, val title: String, val query: String?,
        var text: String? = null, var steps: List<Step>? = null, var items: List<String>? = null, var value: String? = null,
        var notes: MutableList<String> = mutableListOf(), var error: String? = null, var stack: List<String>? = null, var durationMs: Long = 0,
    )

    // ------------------------------------------------------------------ running one scenario

    private fun dirOf(s: TypingScenarios.Scenario) = "typingcmp/" + s.id.lowercase()

    private fun run(s: TypingScenarios.Scenario): Result {
        val result = Result(s.id, s.category, s.title, s.query)
        val dir = dirOf(s)
        val (plain, caret, selection) = TypingScenarios.markers(s.code)
        val main = WriteAction.computeAndWait<VirtualFile, Throwable> {
            val folder = VfsUtil.createDirectoryIfMissing(work!!, dir)
            for (extra in s.extras) VfsUtil.saveText(folder.findOrCreateChildData(this, extra.name), extra.text)
            folder.findOrCreateChildData(this, s.file).also { VfsUtil.saveText(it, plain) }
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        myFixture.configureFromExistingVirtualFile(main)
        val editor = myFixture.editor
        editor.caretModel.moveToOffset(caret)
        if (selection != null) editor.selectionModel.setSelection(selection.startOffset, selection.endOffset)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        // lists of popups are taken at the start state: the keys may then apply one of the items
        if (s.query == "surround") result.items = surroundItems(editor, myFixture.file).map { it.first }
        if (s.query == "generate") result.items = generateItems(editor, myFixture.file)
        val steps = mutableListOf<Step>()
        for (token in s.keys) {
            key(s, token, result)
            if (s.steps) steps += Step(token, caretLine(myFixture.editor), myFixture.editor.selectionModel.selectedText)
        }
        if (s.steps) result.steps = steps
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        when (s.query) {
            null, "surround", "generate" -> {}
            "declaration" -> result.items = declaration(myFixture.editor, myFixture.file, dir)
            "implementations" -> result.items = implementations(myFixture.editor, myFixture.file, dir)
            "super" -> superMethod(myFixture.editor, myFixture.file, dir, result)
            "usages" -> result.items = usages(myFixture.editor, dir)
            "highlight" -> result.items = highlightUsages(myFixture.editor, myFixture.file)
            "structure" -> result.items = structure(myFixture.editor, myFixture.file)
            "paraminfo" -> result.items = parameterInfo(myFixture.editor, myFixture.file)
            "quickdoc" -> quickDoc(myFixture.editor, myFixture.file, result)
            "exprtype" -> result.items = expressionTypes(myFixture.editor, myFixture.file)
            "inlays" -> result.items = inlays(myFixture.editor, myFixture.file)
            else -> error("unknown query ${s.query}")
        }
        if (s.query == null || s.keys.isNotEmpty()) result.text = withMarkers(myFixture.editor)
        return result
    }

    private fun cleanup(s: TypingScenarios.Scenario) {
        try {
            FileEditorManager.getInstance(project).let { m -> m.openFiles.forEach { m.closeFile(it) } }
            val dir = work?.findFileByRelativePath(dirOf(s)) ?: return
            WriteAction.runAndWait<Throwable> { dir.delete(this) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        } catch (e: Throwable) {
            log("cleanup ${s.id}: $e")
        }
    }

    private val simpleKeys = mapOf(
        "{ENTER}" to "EditorEnter", "{TAB}" to "EditorTab", "{BACKSPACE}" to "EditorBackSpace", "{DELETE}" to "EditorDelete", "{ESC}" to "EditorEscape",
        "{LEFT}" to "EditorLeft", "{RIGHT}" to "EditorRight", "{UP}" to "EditorUp", "{DOWN}" to "EditorDown", "{HOME}" to "EditorLineStart", "{END}" to "EditorLineEnd",
    )

    /** Actions whose result may come later (a process, a pooled thread, invokeLater): the document is given time to settle. */
    private val slowActions = setOf("ReformatCode", "OptimizeImports", "SAVE")

    private fun key(s: TypingScenarios.Scenario, token: String, result: Result) {
        val document = myFixture.editor.document
        val stamp = document.modificationStamp
        when {
            token.length == 1 -> myFixture.type(token[0])
            token == "{SPACE}" -> myFixture.type(' ')
            token in simpleKeys -> myFixture.performEditorAction(simpleKeys.getValue(token))
            token == "{SAVE}" -> {
                FileDocumentManager.getInstance().saveAllDocuments()
                settle(document, stamp, "SAVE", result)
            }
            token == "{PASTE}" -> {
                CopyPasteManager.getInstance().setContents(StringSelection(s.paste ?: error("no paste section")))
                myFixture.performEditorAction("EditorPaste")
            }
            token.startsWith("{ACTION:") -> {
                val id = token.removePrefix("{ACTION:").removeSuffix("}")
                myFixture.performEditorAction(id)
                if (id in slowActions) settle(document, stamp, id, result)
            }
            token.startsWith("{SURROUND:") -> {
                val title = token.removePrefix("{SURROUND:").removeSuffix("}")
                val items = surroundItems(myFixture.editor, myFixture.file)
                val pick = items.firstOrNull { it.first == title } ?: items.firstOrNull { it.first.startsWith(title) }
                    ?: error("no Surround With item '$title' in ${items.map { it.first }}")
                result.notes += "surround: applied '${pick.first}'"
                ActionUtil.performAction(pick.second, eventFor(pick.second, dataContext(myFixture.editor, myFixture.file)))
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            }
            else -> error("unknown key token $token")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    /** Waits up to 5 s for a change of the document since [initial] (the stamp before the key) and then for 700 ms of quiet; notes no change. */
    private fun settle(document: Document, initial: Long, what: String, result: Result) {
        val start = System.currentTimeMillis()
        var stamp = document.modificationStamp
        var changedAt = if (stamp != initial) start else -1L
        while (System.currentTimeMillis() - start < 5000) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(50)
            val now = System.currentTimeMillis()
            if (document.modificationStamp != stamp) {
                stamp = document.modificationStamp
                changedAt = now
            } else if (changedAt > 0 && now - changedAt > 700) break
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        if (stamp == initial && document.modificationStamp == initial) result.notes += "$what: the document did not change within 5 s"
    }

    private fun dataContext(editor: Editor, file: PsiFile): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor).add(CommonDataKeys.HOST_EDITOR, editor)
        .add(CommonDataKeys.PSI_FILE, file).add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile).add(CommonDataKeys.CARET, editor.caretModel.currentCaret)
        .add(CommonDataKeys.PSI_ELEMENT, TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().allAccepted))
        .build()

    // ------------------------------------------------------------------ markers

    private fun withMarkers(editor: Editor): String {
        val text = editor.document.text
        val inserts = mutableListOf<Pair<Int, String>>()
        val sel = editor.selectionModel
        if (sel.hasSelection()) {
            inserts += sel.selectionStart to "0<selection>"
            inserts += sel.selectionEnd to "2</selection>"
        }
        inserts += editor.caretModel.offset to "1<caret>"
        val sb = StringBuilder(text)
        for ((offset, marker) in inserts.sortedWith(compareByDescending<Pair<Int, String>> { it.first }.thenByDescending { it.second })) sb.insert(offset, marker.substring(1))
        return sb.toString()
    }

    private fun caretLine(editor: Editor): String {
        val marked = withMarkers(editor)
        val at = marked.indexOf("<caret>")
        val start = marked.lastIndexOf('\n', at - 1) + 1
        val end = marked.indexOf('\n', at).let { if (it < 0) marked.length else it }
        return marked.substring(start, end)
    }

    // ------------------------------------------------------------------ describing targets

    private fun path(file: VirtualFile?, dir: String): String {
        if (file == null) return "<no file>"
        val p = file.path
        if (p.startsWith("$goroot/")) return "\$GOROOT/" + p.removePrefix("$goroot/")
        val marker = "/$dir/"
        val i = p.indexOf(marker)
        return if (i >= 0) p.substring(i + marker.length) else p
    }

    private fun describe(element: PsiElement, dir: String): String {
        if (element is PsiDirectory) return "dir:" + path(element.virtualFile, dir)
        val anchor = (element as? PsiNameIdentifierOwner)?.nameIdentifier ?: element
        val file = anchor.containingFile ?: return element.toString()
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return element.toString()
        val offset = anchor.textRange?.startOffset ?: anchor.textOffset
        return lineAt(document, offset, path(file.virtualFile, dir))
    }

    private fun lineAt(document: Document, offset: Int, path: String): String {
        val line = document.getLineNumber(offset)
        val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
        return "$path:${line + 1}: $text"
    }

    // ------------------------------------------------------------------ queries

    private fun declaration(editor: Editor, file: PsiFile, dir: String): List<String> =
        GotoDeclarationAction.findAllTargetElements(project, editor, editor.caretModel.offset).map { describe(it, dir) }

    private fun implementations(editor: Editor, file: PsiFile, dir: String): List<String> {
        // what Ctrl+Alt+B collects (GotoImplementationHandler, its background list updater included)
        val data = CodeInsightTestUtil.gotoImplementation(editor, file) ?: return listOf("<no source element>")
        return data.targets.map { describe(it, dir) }.sorted()
    }

    private fun superMethod(editor: Editor, file: PsiFile, dir: String, result: Result) {
        val handler: CodeInsightActionHandler = CodeInsightActions.GOTO_SUPER.forLanguage(file.language) ?: run {
            result.value = "<no Go to Super handler>"
            return
        }
        val before = editor.caretModel.offset
        handler.invoke(project, editor, file)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val selected = FileEditorManager.getInstance(project).selectedTextEditor ?: editor
        val moved = selected.document != editor.document || selected.caretModel.offset != before
        val vf = FileDocumentManager.getInstance().getFile(selected.document)
        result.value = if (moved) lineAt(selected.document, selected.caretModel.offset, path(vf, dir)) else "<no navigation>"
        result.items = result.value?.let { listOf(it) }
        // back to the scenario file, so that the result text is the main file's
        myFixture.openFileInEditor(file.virtualFile)
    }

    private fun usages(editor: Editor, dir: String): List<String> {
        val target = TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().allAccepted) ?: return listOf("<no target element>")
        val found = myFixture.findUsages(target)
        val lines = found.mapNotNull { usage ->
            val element = usage.element ?: return@mapNotNull null
            val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile) ?: return@mapNotNull null
            val offset = element.textRange.startOffset + (usage.rangeInElement?.startOffset ?: 0)
            lineAt(document, offset, path(element.containingFile.virtualFile, dir))
        }.sorted()
        return listOf("count: ${lines.size}") + lines
    }

    private fun highlightUsages(editor: Editor, file: PsiFile): List<String> {
        val before = editor.markupModel.allHighlighters.toSet()
        HighlightUsagesHandler.invoke(project, editor, file)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val document = editor.document
        return editor.markupModel.allHighlighters.filter { it !in before && it.isValid }.sortedBy { it.startOffset }.map { h ->
            val sl = document.getLineNumber(h.startOffset)
            val el = document.getLineNumber(h.endOffset)
            val range = "${sl + 1}:${h.startOffset - document.getLineStartOffset(sl) + 1}-${el + 1}:${h.endOffset - document.getLineStartOffset(el) + 1}"
            "$range '${document.getText(TextRange(h.startOffset, h.endOffset))}' ${h.textAttributesKey?.externalName ?: "?"}"
        }
    }

    private fun structure(editor: Editor, file: PsiFile): List<String> {
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as? TreeBasedStructureViewBuilder
            ?: return listOf("<no tree structure view builder>")
        val model = builder.createStructureViewModel(editor)
        try {
            val lines = mutableListOf<String>()
            fun walk(element: TreeElement, depth: Int) {
                val p = element.presentation
                val text = listOfNotNull(p.presentableText, p.locationString?.takeIf { it.isNotBlank() }?.let { "($it)" }).joinToString(" ")
                lines += "  ".repeat(depth) + text
                for (child in element.children) walk(child, depth + 1)
            }
            for (child in (model.root as StructureViewTreeElement).children) walk(child, 0)
            return lines
        } finally {
            model.dispose()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parameterInfo(editor: Editor, file: PsiFile): List<String> {
        val element = file.findElementAt(editor.caretModel.offset) ?: file.findElementAt(editor.caretModel.offset - 1)
        val language = element?.language ?: file.language
        val handlers = ShowParameterInfoHandler.getHandlers(project, language, file.language) as Array<ParameterInfoHandler<PsiElement, Any>>
        val out = mutableListOf<String>()
        for (handler in handlers) {
            val create = MockCreateParameterInfoContext(editor, file)
            val owner = handler.findElementForParameterInfo(create) ?: continue
            val items = create.itemsToShow ?: emptyArray()
            val update = MockUpdateParameterInfoContext(editor, file, items)
            val updateOwner = handler.findElementForUpdatingParameterInfo(update)
            if (updateOwner != null) handler.updateParameterInfo(updateOwner, update) else handler.updateParameterInfo(owner, update)
            out += "handler: ${handler.javaClass.simpleName}, current parameter: ${update.currentParameter}"
            for (item in items) {
                val ui = MockParameterInfoUIContext(owner)
                ui.currentParameterIndex = update.currentParameter
                handler.updateUI(item, ui)
                val text = ui.text ?: "<no text>"
                out += if (ui.highlightStart in 0..text.length && ui.highlightEnd in ui.highlightStart..text.length && ui.highlightEnd > ui.highlightStart)
                    text.substring(0, ui.highlightStart) + "[[" + text.substring(ui.highlightStart, ui.highlightEnd) + "]]" + text.substring(ui.highlightEnd)
                else text
            }
            break
        }
        return out.ifEmpty { listOf("<no parameter info>") }
    }

    private fun quickDoc(editor: Editor, file: PsiFile, result: Result) {
        val targets = IdeDocumentationTargetProvider.getInstance(project).documentationTargets(editor, file, editor.caretModel.offset)
        if (targets.isEmpty()) {
            result.value = "<no documentation target>"
            return
        }
        val html = targets.firstNotNullOfOrNull { target ->
            val pointer = target.createPointer()
            // the blocking variant refuses the EDT in some versions: compute it on a pooled thread and pump the EDT meanwhile
            val future = ApplicationManager.getApplication().executeOnPooledThread<String?> { computeDocumentationBlocking(pointer)?.html }
            val start = System.currentTimeMillis()
            while (!future.isDone && System.currentTimeMillis() - start < 20000) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                Thread.sleep(20)
            }
            future.get()
        }
        result.value = html?.let { TypingScenarios.plainText(it).take(300) } ?: "<empty documentation>"
        result.items = html?.let { listOf(it.take(3000)) }
    }

    private fun expressionTypes(editor: Editor, file: PsiFile): List<String> {
        // as ShowExpressionTypeHandler: the adjusted caret offset, then up to two characters back until some provider answers
        val offset = TargetElementUtil.adjustOffset(file, editor.document, editor.caretModel.offset)
        val out = mutableListOf<String>()
        for (i in 0 until 3) {
            if (out.isNotEmpty() || offset - i < 0) break
            val at = file.findElementAt(offset - i) ?: continue
            for (provider in LanguageExpressionTypes.INSTANCE.allForLanguage(at.language)) {
                @Suppress("UNCHECKED_CAST")
                val p = provider as com.intellij.lang.ExpressionTypeProvider<PsiElement>
                for (expression in p.getExpressionsAt(at)) out += "${expression.text} -> ${TypingScenarios.plainText(p.getInformationHint(expression))}"
            }
        }
        return out.ifEmpty { listOf("<no expression type>") }
    }

    private fun inlays(editor: Editor, file: PsiFile): List<String> {
        val infos = InlayHintsProviderFactory.getProvidersForLanguage(file.language).filter { it.isEnabledByDefault }.map { info ->
            InlayProviderPassInfo(info.provider, info.providerId, info.options.associate { it.id to it.isEnabledByDefault })
        }
        val pass = ActionUtil.underModalProgress(project, "") { DeclarativeInlayHintsPass(file, editor, infos, isPreview = false) }
        pass.setContext(file.codeInsightContext)
        ActionUtil.underModalProgress(project, "") { pass.doCollectInformation(EmptyProgressIndicator()) }
        pass.applyInformationToEditor()
        return readInlays(editor)
    }

    /** The texts of every inlay of the editor, the way typing_batch.js reads them in GoLand (renderer reflection). */
    private fun readInlays(editor: Editor): List<String> {
        val document = editor.document
        val model = editor.inlayModel
        val all = model.getInlineElementsInRange(0, document.textLength) + model.getAfterLineEndElementsInRange(0, document.textLength) +
            model.getBlockElementsInRange(0, document.textLength)
        return all.sortedBy { it.offset }.map { inlay ->
            val line = document.getLineNumber(inlay.offset)
            "${line + 1}:${inlay.offset - document.getLineStartOffset(line) + 1} ${inlay.placement.name.lowercase()} ${TypingScenarios.rendererText(inlay.renderer)}"
        }
    }

    // ------------------------------------------------------------------ popups as lists

    /** The event of a popup item, as typing_batch.js makes it in GoLand. */
    private fun eventFor(action: AnAction, context: DataContext): AnActionEvent =
        AnActionEvent.createEvent(action, context, action.templatePresentation.clone(), "EditorPopup", ActionUiKind.POPUP, null)

    private fun clean(text: String?): String = (text ?: "").replace("_", "").replace("&", "").replace(Regex("^\\s*\\w\\.\\s+"), "").trim()

    private fun surroundItems(editor: Editor, file: PsiFile): List<Pair<String, AnAction>> =
        SurroundWithHandler.buildSurroundActions(project, editor, file).orEmpty().filter { it !is Separator }.map { action ->
            val event = eventFor(action, dataContext(editor, file))
            ActionUtil.performDumbAwareUpdate(action, event, false)
            clean(event.presentation.text ?: action.templatePresentation.text) to action
        }

    private fun generateItems(editor: Editor, file: PsiFile): List<String> {
        val group = ActionManager.getInstance().getAction("GenerateGroup") as? ActionGroup ?: return listOf("<no GenerateGroup>")
        val context = dataContext(editor, file)
        val out = mutableListOf<String>()
        fun walk(g: ActionGroup, depth: Int) {
            val groupEvent = eventFor(g, context)
            for (child in g.getChildren(groupEvent)) {
                if (child is Separator) continue
                val event = eventFor(child, context)
                ActionUtil.performDumbAwareUpdate(child, event, false)
                if (!event.presentation.isVisible || !event.presentation.isEnabled) continue // the Generate popup hides disabled items
                val text = clean(event.presentation.text)
                if (child is ActionGroup && !event.presentation.isPopupGroup) walk(child, depth)
                else {
                    out += "  ".repeat(depth) + text
                    if (child is ActionGroup) walk(child, depth + 1)
                }
            }
        }
        walk(group, 0)
        return out
    }
}

/** The scenario file: the same rules as tools/compare/typing/to_json.py (see the header of scenarios.txt). */
object TypingScenarios {
    data class Extra(val name: String, val text: String)

    data class Scenario(
        val id: String, val category: String, val title: String, var file: String = "a.go", var steps: Boolean = false, var gofmt: Boolean = false,
        var query: String? = null, val notes: MutableList<String> = mutableListOf(), var code: String = "", val extras: MutableList<Extra> = mutableListOf(),
        var paste: String? = null, var keys: List<String> = emptyList(),
    )

    private val token = Regex("\\{[A-Z_]+(?::[^}]*)?}")
    private val section = Regex("^--- (code|extra|paste|keys)(?: (.*))?$")
    private val header = Regex("^=== (\\S+) \\| ([^|]+?) \\| (.+)$")

    fun tokens(keys: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < keys.length) {
            val m = token.matchAt(keys, i)
            if (m != null) {
                out += m.value
                i = m.range.last + 1
            } else {
                out += keys[i].toString()
                i++
            }
        }
        return out
    }

    fun parse(text: String): List<Scenario> {
        val scenarios = mutableListOf<Scenario>()
        var current: Scenario? = null
        var kind: String? = null
        var arg: String? = null
        val buf = mutableListOf<String>()
        fun flush() {
            val s = current
            if (s != null && kind != null) {
                val body = buf.dropLastWhile { it.isBlank() }.joinToString("\n")
                when (kind) {
                    "code" -> s.code = body + "\n"
                    "extra" -> s.extras += Extra(arg!!.trim(), body + "\n")
                    "paste" -> s.paste = if (arg?.trim() == "inline") body else body + "\n"
                    "keys" -> s.keys = tokens(body.replace("\n", ""))
                }
            }
            kind = null
            arg = null
            buf.clear()
        }
        for (raw in text.split("\n")) {
            val line = raw.removeSuffix("\r")
            header.matchEntire(line)?.let { m ->
                flush()
                current = Scenario(m.groupValues[1], m.groupValues[2].trim(), m.groupValues[3].trim()).also { scenarios += it }
                return@let
            } ?: run {
                val s = section.matchEntire(line)
                if (s != null && current != null) {
                    flush()
                    kind = s.groupValues[1]
                    arg = s.groups[2]?.value
                } else if (line.startsWith("#")) {
                    // a comment everywhere, sections included
                } else if (kind != null) {
                    buf += line
                } else if (current != null && !line.startsWith("#") && line.isNotBlank()) {
                    val key = line.substringBefore(':').trim()
                    val value = line.substringAfter(':', "").trim()
                    val c = current!!
                    when (key) {
                        "file" -> c.file = value
                        "steps" -> c.steps = value == "yes"
                        "gofmt" -> c.gofmt = value == "yes"
                        "query" -> c.query = value
                        "note" -> c.notes += value
                        else -> error("${c.id}: unknown header line '$line'")
                    }
                }
            }
        }
        flush()
        return scenarios
    }

    /** The text without markers, the caret offset and the selection range. */
    fun markers(code: String): Triple<String, Int, TextRange?> {
        val sb = StringBuilder(code)
        var caret = -1
        var selStart = -1
        var selEnd = -1
        while (true) {
            val c = sb.indexOf("<caret>")
            val s = sb.indexOf("<selection>")
            val e = sb.indexOf("</selection>")
            val first = listOf(c, s, e).filter { it >= 0 }.minOrNull() ?: break
            when (first) {
                c -> { caret = c; sb.delete(c, c + "<caret>".length) }
                s -> { selStart = s; sb.delete(s, s + "<selection>".length) }
                else -> { selEnd = e; sb.delete(e, e + "</selection>".length) }
            }
        }
        if (caret < 0) caret = if (selEnd >= 0) selEnd else 0
        return Triple(sb.toString(), caret, if (selStart >= 0 && selEnd >= 0) TextRange(selStart, selEnd) else null)
    }

    /** HTML to the text a reader sees: tags dropped, the common entities decoded, blanks collapsed. */
    fun plainText(html: String?): String {
        if (html == null) return ""
        return html.replace(Regex("<(br|p|div|tr|li|h\\d|pre)[^>]*>", RegexOption.IGNORE_CASE), " ").replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .replace(Regex("\\s+"), " ").trim()
    }

    /** The text of an inlay renderer: declarative (presentation lists of text entries), the old HintRenderer, else toString. */
    fun rendererText(renderer: Any): String {
        fun call(target: Any, name: String): Any? = try {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)
        } catch (_: Throwable) {
            null
        }
        val lists = call(renderer, "getPresentationLists") as? List<*>
        if (lists != null) {
            return lists.joinToString(" | ") { list ->
                val entries = list?.let { call(it, "getEntries") } as? Array<*> ?: return@joinToString "?"
                entries.joinToString("") { e -> e?.let { call(it, "getText") }?.toString() ?: "" }
            }
        }
        (call(renderer, "getText") as? String)?.let { return it }
        return renderer.toString()
    }
}
