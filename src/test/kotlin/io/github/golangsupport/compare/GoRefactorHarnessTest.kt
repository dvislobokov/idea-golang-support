package io.github.golangsupport.compare

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.TargetElementUtil
import com.intellij.ide.DataManager
import com.intellij.lang.LanguageRefactoringSupport
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.PostprocessReformattingAspect
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.refactoring.safeDelete.SafeDeleteHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.diff.Diff
import io.github.golangsupport.ide.refactoring.GoChangeParameter
import io.github.golangsupport.ide.refactoring.GoChangeResult
import io.github.golangsupport.ide.refactoring.GoChangeSignature
import io.github.golangsupport.ide.refactoring.GoChangeSignatureProcessor
import io.github.golangsupport.ide.refactoring.GoExtractFunctionHandler
import io.github.golangsupport.ide.refactoring.GoExtractFunctionOptions
import io.github.golangsupport.ide.refactoring.GoInlineActionHandler
import io.github.golangsupport.ide.refactoring.GoIntroduceConstantHandler
import io.github.golangsupport.ide.refactoring.GoIntroduceFieldHandler
import io.github.golangsupport.ide.refactoring.GoIntroduceFieldOptions
import io.github.golangsupport.ide.refactoring.GoIntroduceOptions
import io.github.golangsupport.ide.refactoring.GoIntroduceParameterHandler
import io.github.golangsupport.ide.refactoring.GoIntroduceVariableHandler
import io.github.golangsupport.ide.refactoring.GoMoveOptions
import io.github.golangsupport.ide.refactoring.GoMoveProcessor
import io.github.golangsupport.lang.GenerateContext
import io.github.golangsupport.lang.GoGenerators
import io.github.golangsupport.lang.GoImports
import io.github.golangsupport.lang.GoInterfaceChooser
import io.github.golangsupport.lang.GoLanguage
import io.github.golangsupport.lang.psi.GoFile
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The refactoring comparison of docs/compare: every scenario of tools/compare/refactor/scenarios.json is run through the plugin's own
 * handlers and processors without dialogs (the options the dialogs would answer are passed in, as the go-psi-ide refactoring tests do),
 * on a clone of a real project. For each one: whether the action is enabled at the place, the Alt+Enter list there, what happened
 * (applied / conflict / refused / exception) and, when anything changed, the new text of every touched file, the deleted files and a
 * unified diff against HEAD of the clone. A refactoring that reports conflicts is run a second time with the conflicts ignored (the
 * "Continue" of the conflicts dialog), so the build check sees what the user would get.
 *
 * Refactorings that move or rename files write to the disk, so the clone must be a git clone of its own (not shared with other
 * stands): after every scenario the documents are reloaded and, when the VFS changed, `git checkout -- . && git clean -fdq` restores
 * it and the touched directories are refreshed; the stand then checks that no document is unsaved, `git status` is empty and the VFS
 * matches the disk. Run:
 * `GO_HARNESS=1 GO_HARNESS_ROOT=<clone> ./gradlew.bat :test --tests "*GoRefactorHarnessTest*" --offline`
 * (GO_HARNESS_SCENARIOS — the scenario file, GO_HARNESS_ONLY — comma-separated ids). Output: <out>/refactor.json, <out>/refactor/<id>/.
 */
class GoRefactorHarnessTest : GoHarnessTestBase() {

    data class Outcome(
        val id: String, val refactoring: String, val what: String, val file: String, val line: Int,
        var available: Boolean? = null, var availability: Map<String, Any?> = emptyMap(), var intentions: List<String> = emptyList(),
        var status: String = "", var message: String? = null, var conflicts: List<String>? = null, var forced: Boolean = false,
        var done: String? = null, var changed: List<String> = emptyList(), var created: List<String> = emptyList(), var deleted: List<String> = emptyList(),
        var diff: String = "", var exception: String? = null, var millis: Long = 0, var restored: String = "",
    )

    private val vfsEvents = ArrayList<VFileEvent>()

    fun testRefactorings() {
        if (!enabled) return
        val scenarioFile = File(env("GO_HARNESS_SCENARIOS", "tools/compare/refactor/scenarios.json")).absoluteFile
        val scenarios = JsonParser.parseString(scenarioFile.readText()).asJsonObject.getAsJsonArray("scenarios").map { it.asJsonObject }
        val only = System.getenv("GO_HARNESS_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        check(git("status", "--short").isBlank()) { "the clone ${root.path} is not clean: ${git("status", "--short")}" }
        project.messageBus.connect(testRootDisposable).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                synchronized(vfsEvents) { vfsEvents += events }
            }
        })
        TestDialogManager.setTestDialog(TestDialog.OK)
        val outDir = File(out, "refactor").also { it.mkdirs() }
        val results = ArrayList<Outcome>()
        try {
            for (s in scenarios) {
                val id = s.str("id")!!
                if (only != null && id !in only) continue
                File(outDir, id).deleteRecursively()
                val outcome = try {
                    run(s, File(outDir, id))
                } catch (e: Throwable) {
                    // a broken scenario (anchor not found, the editor setup failed): recorded, the clone restored, the run goes on
                    Outcome(id, s.str("refactoring") ?: "", s.str("what") ?: "", s.str("file") ?: "", 0, status = "exception", message = e.message,
                        exception = describe(e)).also { it.restored = restore() }
                }
                results += outcome
                log("$id: ${outcome.status}${outcome.message?.let { " - " + it.lines().first() } ?: ""}; changed ${outcome.changed.size}, created ${outcome.created.size}, deleted ${outcome.deleted.size}; ${outcome.restored}")
                writeJson("refactor.json", results)
            }
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
        val unsaved = FileDocumentManager.getInstance().unsavedDocuments
        val status = git("status", "--short")
        log("final: ${unsaved.size} unsaved documents, git status '${status.trim()}', vfs vs disk: ${vfsMatchesDisk()}")
        writeJson("refactor.json", results)
    }

    private fun JsonObject.str(name: String): String? = get(name)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.obj(name: String): JsonObject = get(name)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

    private fun run(s: JsonObject, dir: File): Outcome {
        val id = s.str("id")!!
        val path = s.str("file")!!
        val vf = file(path)
        val document = FileDocumentManager.getInstance().getDocument(vf)!!
        val text = document.text
        val params = s.obj("params")
        val refactoring = s.str("refactoring")!!
        // the place: a caret at anchor + at, or a selection of `select` (after `within`)
        var caret: Int
        var selection: IntRange? = null
        if (s.has("select")) {
            val from = s.str("within")?.let { text.indexOf(it).also { at -> check(at >= 0) { "$id: within not found" } } } ?: 0
            val at = text.indexOf(s.str("select")!!, from)
            check(at >= 0) { "$id: selection not found" }
            selection = at until at + s.str("select")!!.length
            caret = at
        } else {
            val at = text.indexOf(s.str("anchor")!!)
            check(at >= 0) { "$id: anchor not found" }
            caret = at + (s.get("at")?.asInt ?: 0)
        }
        val outcome = Outcome(id, refactoring, s.str("what") ?: "", path, document.getLineNumber(caret) + 1)
        myFixture.configureFromExistingVirtualFile(vf)
        val editor = myFixture.editor
        fun place() {
            editor.caretModel.moveToOffset(caret)
            if (selection != null) editor.selectionModel.setSelection(selection.first, selection.last + 1) else editor.selectionModel.removeSelection()
        }
        place()
        outcome.intentions = try {
            intentions().map { it.text }.distinct()
        } catch (e: Throwable) {
            listOf("<intentions failed: ${e.javaClass.simpleName}: ${e.message?.lines()?.firstOrNull()}>")
        }
        place()
        outcome.availability = availability(refactoring, params)
        outcome.available = outcome.availability.values.any { it == true }
        synchronized(vfsEvents) { vfsEvents.clear() }
        val t0 = System.currentTimeMillis()
        try {
            place()
            outcome.done = perform(refactoring, params, outcome)
            if (outcome.status.isEmpty()) outcome.status = "applied"
        } catch (e: BaseRefactoringProcessor.ConflictsInTestsException) {
            outcome.status = "conflict"
            outcome.conflicts = e.messages.sorted()
            outcome.message = e.messages.sorted().joinToString("\n")
            // what "Continue" in the conflicts dialog gives
            try {
                place()
                BaseRefactoringProcessor.ConflictsInTestsException.withIgnoredConflicts<Throwable> { outcome.done = perform(refactoring, params, outcome) }
                outcome.forced = true
            } catch (e2: Throwable) {
                outcome.exception = "forced run: " + describe(e2)
            }
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            outcome.status = "refused"
            outcome.message = e.message
        } catch (e: Throwable) {
            val refusal = e.javaClass.simpleName.endsWith("Refusal") || e is com.intellij.refactoring.util.CommonRefactoringUtil.RefactoringErrorHintException
            outcome.status = if (refusal) "refused" else "exception"
            outcome.message = e.message
            if (!refusal) outcome.exception = describe(e)
        }
        outcome.millis = System.currentTimeMillis() - t0
        try {
            collect(outcome, dir)
        } catch (e: Throwable) {
            outcome.exception = (outcome.exception?.let { "$it\n" } ?: "") + "collect: " + describe(e)
        }
        if (outcome.status == "applied" && outcome.changed.isEmpty() && outcome.created.isEmpty() && outcome.deleted.isEmpty()) outcome.status = "no-change"
        outcome.restored = restore()
        return outcome
    }

    private fun describe(e: Throwable): String {
        val cause = generateSequence(e) { it.cause }.last()
        val top = cause.stackTrace.take(12).joinToString("\n") { "  at $it" }
        return "${e.javaClass.name}: ${e.message}" + (if (cause !== e) "\ncaused by ${cause.javaClass.name}: ${cause.message}" else "") + "\n" + top
    }

    // --- availability: is the action enabled at the place (the editor's data context, as a shortcut would see it) ---

    private val actionIds = mapOf(
        "rename" to listOf("RenameElement"),
        "extract-variable" to listOf("IntroduceVariable"),
        "extract-constant" to listOf("IntroduceConstant"),
        "extract-function" to listOf("ExtractMethod"),
        "introduce-parameter" to listOf("IntroduceParameter"),
        "introduce-field" to listOf("IntroduceField"),
        "inline" to listOf("Inline"),
        "change-signature" to listOf("ChangeSignature"),
        "move" to listOf("Move"),
        "safe-delete" to listOf("SafeDelete"),
    )

    private val generateIds = mapOf(
        "implement" to "Go.Generate.Implement", "constructor" to "Go.Generate.Constructor", "getters" to "Go.Generate.Getters",
        "setters" to "Go.Generate.Setters", "getters-setters" to "Go.Generate.GettersSetters", "string" to "Go.Generate.String",
    )

    private fun dataContext(fileTarget: Boolean = false): DataContext {
        val editor = myFixture.editor
        // a file rename starts in the Project view: the file is the selected element and there is no editor
        if (fileTarget) return SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_ELEMENT, myFixture.file)
            .add(CommonDataKeys.PSI_FILE, myFixture.file).add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile)
            .add(com.intellij.openapi.actionSystem.LangDataKeys.PSI_ELEMENT_ARRAY, arrayOf(myFixture.file)).build()
        val parent = DataManager.getInstance().getDataContext(editor.contentComponent)
        val element = TargetElementUtil.findTargetElement(editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED)
        return SimpleDataContext.builder().setParent(parent)
            .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor).add(CommonDataKeys.PSI_FILE, myFixture.file)
            .add(CommonDataKeys.VIRTUAL_FILE, myFixture.file.virtualFile).add(CommonDataKeys.CARET, editor.caretModel.currentCaret)
            .apply { if (element != null) add(CommonDataKeys.PSI_ELEMENT, element) }.build()
    }

    private fun actionEnabled(id: String, fileTarget: Boolean = false): Any? {
        val action = ActionManager.getInstance().getAction(id) ?: return "no action $id"
        return try {
            val event = TestActionEvent.createTestEvent(action, dataContext(fileTarget))
            action.update(event)
            event.presentation.isEnabled && event.presentation.isVisible
        } catch (e: Throwable) {
            "update failed: ${e.javaClass.simpleName}: ${e.message?.lines()?.firstOrNull()}"
        }
    }

    private fun availability(refactoring: String, params: JsonObject): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for (id in actionIds[refactoring].orEmpty()) result[id] = actionEnabled(id, params.str("target") == "file")
        if (refactoring == "generate") {
            for ((what, id) in generateIds) result[id] = actionEnabled(id)
            // only the action of this scenario counts for "available"
            val mine = generateIds[params.str("what")]
            return result.mapValues { (k, v) -> if (k == mine) v else "other: $v" }
        }
        if (refactoring == "intention") {
            result["intention"] = intentions().any { a -> patterns(params).any { a.text.contains(it, ignoreCase = true) } }
        }
        return result
    }

    private val meta = listOf("Edit intention settings", "Disable ", "Assign shortcut", "Adjust code style settings", "Edit inspection profile setting",
        "Run inspection on", "Suppress ", "Fix all ", "Edit '", "Configure ")

    /** The Alt+Enter list without the entries of the options submenus (settings, disable, shortcut). */
    private fun intentions() = myFixture.availableIntentions.filter { a -> meta.none { a.text.startsWith(it) } }

    private fun patterns(params: JsonObject): List<String> = params.getAsJsonArray("intention")?.map { it.asString }.orEmpty()

    // --- the refactorings, without dialogs ---

    private fun target(): PsiElement =
        TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED)
            ?: error("no element at the caret")

    private fun goFile(): GoFile = myFixture.file as GoFile

    private fun perform(refactoring: String, p: JsonObject, outcome: Outcome): String {
        val editor = myFixture.editor
        val file = myFixture.file
        val name = p.str("name") ?: p.str("newName")
        when (refactoring) {
            "rename" -> {
                val element = if (p.str("target") == "file") file else target()
                val substituted = RenamePsiElementProcessor.forElement(element).substituteElementToRename(element, editor) ?: error("rename: substitute is null for $element")
                val oldName = (substituted as? com.intellij.psi.PsiNamedElement)?.name
                val processor = RenameProcessor(project, substituted, name!!, false, false)
                // as the dialog with its defaults: the linked renames with a check box join when the box is on
                val factories = AutomaticRenamerFactory.EP_NAME.extensionList.filter { it.isApplicable(substituted) && it.optionName != null && it.isEnabled }
                factories.forEach(processor::addRenamerFactory)
                processor.run()
                return "renamed ${substituted.javaClass.simpleName} '$oldName' to '$name'" +
                    (if (factories.isNotEmpty()) "; linked: " + factories.joinToString { it.optionName ?: "?" } else "")
            }
            "extract-variable" -> GoIntroduceVariableHandler(GoIntroduceOptions(p.bool("replaceAll"), name)).invoke(project, editor, file, null)
            "extract-constant" -> GoIntroduceConstantHandler(GoIntroduceOptions(p.bool("replaceAll"), name)).invoke(project, editor, file, null)
            "extract-function" -> GoExtractFunctionHandler(GoExtractFunctionOptions(name)).invoke(project, editor, file, null)
            "introduce-parameter" -> GoIntroduceParameterHandler(GoIntroduceOptions(p.bool("replaceAll"), name)).invoke(project, editor, file, null)
            "introduce-field" -> GoIntroduceFieldHandler(GoIntroduceFieldOptions(p.bool("replaceAll"), name, p.get("initializeHere")?.asBoolean ?: true)).invoke(project, editor, file, null)
            "inline" -> {
                val element = target()
                val handler = GoInlineActionHandler()
                if (!handler.canInlineElement(element)) {
                    outcome.status = "not-available"
                    return "GoInlineActionHandler.canInlineElement is false for ${element.javaClass.simpleName}"
                }
                handler.inlineElement(project, editor, element)
                return "inlined ${element.javaClass.simpleName} (${handler.getActionName(element)})"
            }
            "change-signature" -> {
                val handler = LanguageRefactoringSupport.getInstance().forLanguage(GoLanguage)?.changeSignatureHandler ?: error("no change signature handler")
                val target = handler.findTargetMember(file, editor) ?: run { outcome.status = "not-available"; return "findTargetMember is null" }
                val initial = GoChangeSignature.initial(target)
                val parameters = p.getAsJsonArray("parameters")?.map { e ->
                    val o = e.asJsonObject
                    if (o.has("old")) initial.parameters.first { it.oldIndex == o.get("old").asInt }
                    else GoChangeParameter(o.str("name")!!, o.str("type")!!, -1, o.str("default") ?: "")
                } ?: initial.parameters
                val results = p.getAsJsonArray("results")?.map { e -> e.asJsonObject.let { GoChangeResult(it.str("name") ?: "", it.str("type")!!) } } ?: initial.results
                val options = initial.copy(name = p.str("newName") ?: initial.name, parameters = parameters, results = results)
                GoChangeSignatureProcessor(project, target, options).run()
                return "${GoChangeSignature.displayName(target)}: ${GoChangeSignature.parametersText(parameters)}${GoChangeSignature.resultsText(results)}"
            }
            "move" -> {
                val f = goFile()
                val names = p.getAsJsonArray("names").map { it.asString }
                val elements = names.map { n -> (f.functions + f.types + f.vars + f.consts).first { it.name == n } as PsiElement }
                GoMoveProcessor.moveForTests(project, elements, GoMoveOptions(root.path + "/" + p.str("targetDir"), p.str("targetFile")!!, p.get("moveMethods")?.asBoolean ?: true))
                return "moved $names to ${p.str("targetDir")}/${p.str("targetFile")}"
            }
            "safe-delete" -> {
                val element = target()
                SafeDeleteHandler.invoke(project, arrayOf(element), true)
                return "safe delete of ${element.javaClass.simpleName}"
            }
            "generate" -> return generate(p, outcome)
            "intention" -> {
                val all = intentions()
                val pats = patterns(p)
                val picked = pats.firstNotNullOfOrNull { pat -> all.firstOrNull { it.text.contains(pat, ignoreCase = true) } }
                    ?: run { outcome.status = "not-available"; return "no intention matching $pats" }
                myFixture.launchAction(picked)
                return "launched '${picked.text}'"
            }
            else -> error("unknown refactoring $refactoring")
        }
        return "invoked ${refactoring} handler" + (name?.let { " with name '$it'" } ?: "")
    }

    private fun JsonObject.bool(name: String): Boolean = get(name)?.asBoolean ?: false

    /** Alt+Insert with what the dialogs would answer by default: every field ticked, the interface chosen by name. */
    private fun generate(p: JsonObject, outcome: Outcome): String {
        val context = GenerateContext(project, myFixture.editor, goFile())
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        when (val what = p.str("what")) {
            "implement" -> {
                val i = p.obj("interface")
                val spec = ReadAction.compute<io.github.golangsupport.lang.psi.GoTypeSpec?, RuntimeException> { context.typeSpecAtCaret }
                    ?: run { outcome.status = "not-available"; return "no type at the caret" }
                val type = context.typeAtCaret!!
                val dirVf = i.str("dir")?.let { root.findFileByRelativePath(it) }
                val importPath = i.str("importPath")
                val plan = ReadAction.compute<GoInterfaceChooser.Plan?, RuntimeException> { GoInterfaceChooser.plan(spec, goFile(), importPath, dirVf, i.str("name")!!) }
                    ?: run { outcome.status = "refused"; return "Cannot read the methods of ${i.str("name")}" }
                if (plan.stubs.isEmpty()) { outcome.status = "refused"; return "${plan.typeName} has every method of ${i.str("name")}" }
                WriteCommandAction.runWriteCommandAction(project, "Implement Interface", null, {
                    context.insertAfter(type, plan.stubs, "Implement Interface")
                    for (path in plan.imports) GoImports.add(context.document.immutableCharSequence, path)?.let { context.document.insertString(it.offset, it.text) }
                    PsiDocumentManager.getInstance(project).commitDocument(context.document)
                }, goFile())
                return "implemented ${i.str("name")}: imports ${plan.imports}"
            }
            else -> {
                val struct = context.structAtCaret ?: run { outcome.status = "not-available"; return "no struct at the caret" }
                val fields = GoGenerators.fields(struct)
                val code = when (what) {
                    "constructor" -> GoGenerators.constructor(struct, fields)
                    "getters" -> GoGenerators.getters(struct, fields)
                    "setters" -> GoGenerators.setters(struct, fields)
                    "getters-setters" -> GoGenerators.getters(struct, fields) + "\n" + GoGenerators.setters(struct, fields)
                    "string" -> GoGenerators.stringMethod(struct, fields)
                    else -> error("unknown generate $what")
                }
                context.insertAfter(struct, code, "Generate $what")
                return "generated $what for ${struct.name} over ${fields.size} fields"
            }
        }
    }

    // --- what changed: unsaved documents and VFS events, against HEAD of the clone ---

    private fun rel(path: String): String? = path.replace('\\', '/').let { p -> root.path.let { r -> if (p.startsWith("$r/")) p.removePrefix("$r/") else null } }

    private fun collect(outcome: Outcome, dir: File) {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        PostprocessReformattingAspect.getInstance(project).doPostponedFormatting()
        val now = LinkedHashSet<String>()
        val before = LinkedHashSet<String>()
        for (doc in FileDocumentManager.getInstance().unsavedDocuments) FileDocumentManager.getInstance().getFile(doc)?.let { vf -> rel(vf.path)?.let(now::add) }
        val events = synchronized(vfsEvents) { ArrayList(vfsEvents) }
        for (e in events) {
            when (e) {
                is VFileCreateEvent, is VFileContentChangeEvent, is VFileCopyEvent -> e.path.let { p -> expand(p, now) }
                is VFileDeleteEvent -> rel(e.path)?.let { before += gitFiles(it) }
                is VFileMoveEvent -> { rel(e.oldPath)?.let { before += gitFiles(it) }; expand(e.newPath, now) }
                is VFilePropertyChangeEvent -> if (e.propertyName == VirtualFile.PROP_NAME) { rel(e.oldPath)?.let { before += gitFiles(it) }; expand(e.newPath, now) }
            }
        }
        val changed = ArrayList<String>()
        val created = ArrayList<String>()
        val deleted = ArrayList<String>()
        val diff = StringBuilder()
        for (p in now.sorted()) {
            val vf = root.findFileByRelativePath(p) ?: continue
            if (vf.isDirectory) continue
            val after = FileDocumentManager.getInstance().getCachedDocument(vf)?.text ?: String(vf.contentsToByteArray(), vf.charset)
            val original = gitShow(p)
            if (original == after) continue
            if (original == null) created += p else changed += p
            File(dir, "files/$p").also { it.parentFile.mkdirs() }.writeText(after)
            diff.append(unifiedDiff(p, original, after))
        }
        for (p in before.sorted()) {
            if (p in now || root.findFileByRelativePath(p) != null) continue
            deleted += p
            diff.append(unifiedDiff(p, gitShow(p), null))
        }
        outcome.changed = changed
        outcome.created = created
        outcome.deleted = deleted
        outcome.diff = diff.toString()
        if (diff.isNotEmpty()) File(dir, "diff.patch").also { it.parentFile.mkdirs() }.writeText(diff.toString())
        if (deleted.isNotEmpty()) File(dir, "deleted.txt").writeText(deleted.joinToString("\n", postfix = "\n"))
    }

    /** [path] (a file, or a directory with every file below it) into [into] as paths relative to the root. */
    private fun expand(path: String, into: MutableSet<String>) {
        val vf = LocalFileSystem.getInstance().findFileByPath(path)
        if (vf == null || !vf.isDirectory) { rel(path)?.let(into::add); return }
        VfsUtil.iterateChildrenRecursively(vf, null) { f -> if (!f.isDirectory) rel(f.path)?.let(into::add); true }
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor(2, TimeUnit.MINUTES)
        return output
    }

    private fun gitShow(rel: String): String? {
        val process = ProcessBuilder("git", "-C", root.path, "show", "HEAD:$rel").redirectErrorStream(false).start()
        val bytes = process.inputStream.readBytes()
        process.errorStream.readBytes()
        process.waitFor(1, TimeUnit.MINUTES)
        return if (process.exitValue() == 0) String(bytes, Charsets.UTF_8) else null
    }

    private fun gitFiles(rel: String): List<String> = git("ls-files", "--", rel).lines().map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(rel) }

    private fun unifiedDiff(path: String, before: String?, after: String?): String {
        val a = before?.split('\n')?.toTypedArray() ?: emptyArray()
        val b = after?.split('\n')?.toTypedArray() ?: emptyArray()
        val sb = StringBuilder()
        sb.append("--- ").append(if (before == null) "/dev/null" else "a/$path").append('\n')
        sb.append("+++ ").append(if (after == null) "/dev/null" else "b/$path").append('\n')
        val changes = ArrayList<Diff.Change>()
        var c = Diff.buildChanges(a, b)
        while (c != null) { changes += c; c = c.link }
        val context = 3
        var i = 0
        while (i < changes.size) {
            var j = i
            while (j + 1 < changes.size && changes[j + 1].line0 - (changes[j].line0 + changes[j].deleted) <= 2 * context) j++
            val start0 = maxOf(0, changes[i].line0 - context)
            val start1 = maxOf(0, changes[i].line1 - context)
            val end0 = minOf(a.size, changes[j].line0 + changes[j].deleted + context)
            val end1 = minOf(b.size, changes[j].line1 + changes[j].inserted + context)
            sb.append("@@ -${start0 + 1},${end0 - start0} +${start1 + 1},${end1 - start1} @@\n")
            var x = start0
            for (k in i..j) {
                val ch = changes[k]
                while (x < ch.line0) sb.append(' ').append(a[x++]).append('\n')
                for (d in 0 until ch.deleted) sb.append('-').append(a[ch.line0 + d]).append('\n')
                for (n in 0 until ch.inserted) sb.append('+').append(b[ch.line1 + n]).append('\n')
                x = ch.line0 + ch.deleted
            }
            while (x < end0) sb.append(' ').append(a[x++]).append('\n')
            i = j + 1
        }
        return sb.toString()
    }

    // --- restore: documents back, the clone back to HEAD, the VFS refreshed ---

    private fun restore(): String {
        val fdm = FileDocumentManager.getInstance()
        val events = synchronized(vfsEvents) { ArrayList(vfsEvents) }
        ApplicationManager.getApplication().runWriteAction {
            for (doc in fdm.unsavedDocuments) fdm.reloadFromDisk(doc)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val dirty = git("status", "--short").isNotBlank()
        if (events.isNotEmpty() || dirty) {
            git("checkout", "--", ".")
            git("clean", "-fdq")
            val dirs = LinkedHashSet<String>()
            for (e in events) {
                dirs += e.path.substringBeforeLast('/')
                when (e) {
                    is VFileMoveEvent -> dirs += e.oldPath.substringBeforeLast('/')
                    is VFilePropertyChangeEvent -> if (e.propertyName == VirtualFile.PROP_NAME) dirs += e.oldPath.substringBeforeLast('/')
                    else -> Unit
                }
            }
            val refresh = dirs.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }.ifEmpty { listOf(root) }
            VfsUtil.markDirtyAndRefresh(false, true, true, *refresh.toTypedArray())
            ApplicationManager.getApplication().runWriteAction { for (doc in fdm.unsavedDocuments) fdm.reloadFromDisk(doc) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        synchronized(vfsEvents) { vfsEvents.clear() }
        val unsaved = fdm.unsavedDocuments.size
        val status = git("status", "--short").trim()
        val touched = events.map { it.path } + events.filterIsInstance<VFileMoveEvent>().map { it.oldPath } +
            events.filterIsInstance<VFilePropertyChangeEvent>().filter { it.propertyName == VirtualFile.PROP_NAME }.map { it.oldPath }
        val mismatch = touched.distinct().filter { p ->
            val io = File(p)
            val vf = LocalFileSystem.getInstance().findFileByPath(p)
            (vf != null) != io.exists() || (vf != null && !vf.isDirectory && !vf.contentsToByteArray().contentEquals(io.readBytes()))
        }
        return "restored: unsaved=$unsaved git='${status}' vfsMismatch=${mismatch.map { rel(it) ?: it }}"
    }

    /** The loaded part of the VFS of the clone against the disk: names of children and file lengths. */
    private fun vfsMatchesDisk(): String {
        val problems = ArrayList<String>()
        VfsUtil.iterateChildrenRecursively(root, { it.name != ".git" }) { vf ->
            val io = File(vf.path)
            if (!io.exists()) problems += "${rel(vf.path)} not on disk"
            else if (!vf.isDirectory && vf.length != io.length()) problems += "${rel(vf.path)} length ${vf.length} != ${io.length()}"
            problems.size < 20
        }
        return if (problems.isEmpty()) "ok" else problems.joinToString("; ")
    }
}
