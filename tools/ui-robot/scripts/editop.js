// One editing operation of wave 1 (FEATURES §11) in the selected editor, which must be a scratch copy, then the lines around the caret.
// __OP__: caret TEXT | select TEXT | lines FROM-TO | action ID | type TEXT (typed character by character, `<TAB>` presses Tab, `<NL>` Enter)
//         | surround TITLE | unwrap TITLE | hints | typos | targets TEXT | text FROM-TO (print lines) | setting NAME=VALUE (CodeInsightSettings int field)
//         | problems | lookup N | smart (smart completion items) | pick ITEM / pick smart:ITEM (insert a completion item)
// __ARG__ is the argument; the result is the operation's report plus lines __FROM__..__TO__ of the document (tabs as <TAB>).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ActionPlaces)
importClass(com.intellij.openapi.actionSystem.ex.ActionUtil)
importClass(com.intellij.openapi.editor.actionSystem.EditorActionManager)
importClass(com.intellij.openapi.editor.ex.util.EditorUtil)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.DataManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)

var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
var document = editor.getDocument()
var op = "__OP__", arg = "__ARG__"
var report = new CompletableFuture()

function onEdt(f) {
    ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
        try { report.complete(String(f())) } catch (e) { report.complete("failed: " + e + (e.javaException ? " / " + e.javaException : "")) }
    } }))
}
function ctx() { return EditorUtil.getEditorDataContext(editor) }
function commit() { PsiDocumentManager.getInstance(project).commitAllDocuments() }
function file() { commit(); return PsiDocumentManager.getInstance(project).getPsiFile(document) }
function offsetOf(text) { var at = String(document.getText()).indexOf(text); if (at < 0) throw "not found: " + text; return at }
function focus() { editor.getContentComponent().requestFocusInWindow() }
function performAction(id) {
    var action = ActionManager.getInstance().getAction(id)
    if (action == null) throw "no action " + id
    ActionUtil.invokeAction(action, ctx(), ActionPlaces.KEYBOARD_SHORTCUT, null, null)
}

if (op == "caret") onEdt(function () { editor.getSelectionModel().removeSelection(); editor.getCaretModel().moveToOffset(offsetOf(arg) + 1); focus(); return "caret in " + arg })
else if (op == "caretend") onEdt(function () { editor.getSelectionModel().removeSelection(); var at = offsetOf(arg); editor.getCaretModel().moveToOffset(document.getLineEndOffset(document.getLineNumber(at))); focus(); return "caret at end of line of " + arg })
else if (op == "select") onEdt(function () { var at = offsetOf(arg); editor.getCaretModel().moveToOffset(at + arg.length); editor.getSelectionModel().setSelection(at, at + arg.length); focus(); return "selected " + arg })
else if (op == "lines") onEdt(function () {
    var p = arg.split("-"); var from = document.getLineStartOffset(parseInt(p[0]) - 1), to = document.getLineEndOffset(parseInt(p[1]) - 1)
    editor.getCaretModel().moveToOffset(from + 2); editor.getSelectionModel().setSelection(from + 2, to); focus(); return "selected lines " + arg
})
else if (op == "action") onEdt(function () { performAction(arg); commit(); return "performed " + arg })
else if (op == "type") onEdt(function () {
    var typed = EditorActionManager.getInstance().getTypedAction()
    var i = 0
    while (i < arg.length) {
        // the Tab key runs the first enabled action of the keymap: template expansion before the editor's tab
        if (arg.substring(i, i + 5) == "<TAB>") { performAction("ExpandLiveTemplateByTab"); i += 5 }
        else if (arg.substring(i, i + 4) == "<NL>") { performAction("EditorEnter"); i += 4 }
        else { typed.actionPerformed(editor, arg.charAt(i), ctx()); i++ }
        commit()
    }
    return "typed " + arg
})
else if (op == "surround") onEdt(function () {
    importClass(com.intellij.lang.LanguageSurrounders)
    importClass(com.intellij.codeInsight.generation.surroundWith.SurroundWithHandler)
    var f = file()
    var descriptors = LanguageSurrounders.INSTANCE.allForLanguage(f.getLanguage())
    var names = []
    for (var d = 0; d < descriptors.size(); d++) {
        var elements = descriptors.get(d).getElementsToSurround(f, editor.getSelectionModel().getSelectionStart(), editor.getSelectionModel().getSelectionEnd())
        if (elements.length == 0) continue
        var ss = descriptors.get(d).getSurrounders()
        for (var s = 0; s < ss.length; s++) {
            var title = String(ss[s].getTemplateDescription())
            if (ss[s].isApplicable(elements)) names.push(title)
            if (title == arg && ss[s].isApplicable(elements)) {
                // SurroundWithHandler.invoke(project, editor, file, surrounder) asserts (a test-only entry; seen live): do what it does by hand
                var surrounder = ss[s], els = elements
                WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () {
                    var range = surrounder.surroundElements(project, editor, els)
                    if (range != null) { editor.getCaretModel().moveToOffset(range.getStartOffset()); editor.getSelectionModel().setSelection(range.getStartOffset(), range.getEndOffset()) }
                } }))
                commit(); return "surrounded with " + title + "; applicable: " + names.join(" | ")
            }
        }
    }
    return "not applied; applicable: " + names.join(" | ")
})
else if (op == "unwrap") onEdt(function () {
    importClass(com.intellij.codeInsight.unwrap.LanguageUnwrappers)
    importClass(com.intellij.codeInsight.unwrap.UnwrapHandler)
    var f = file()
    var descriptor = LanguageUnwrappers.INSTANCE.forLanguage(f.getLanguage())
    var options = descriptor.collectUnwrappers(project, editor, f)
    var names = []
    for (var i = 0; i < options.size(); i++) {
        var element = options.get(i).getFirst(), unwrapper = options.get(i).getSecond()
        var title = String(unwrapper.getDescription(element))
        names.push(title)
        if (title == arg) {
            var u = unwrapper, el = element
            WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { u.unwrap(editor, el) } }))
            commit(); return "unwrapped: " + title + "; options: " + names.join(" | ")
        }
    }
    return "not applied; options: " + names.join(" | ")
})
else if (op == "hints") onEdt(function () {
    var model = editor.getInlayModel()
    var all = []
    var inline = model.getInlineElementsInRange(0, document.getTextLength()), eol = model.getAfterLineEndElementsInRange(0, document.getTextLength())
    function describe(inlay) {
        var r = inlay.getRenderer(), text = null
        try { var list = r.getPresentationList(); var entries = list.getEntries(); var parts = []; for (var e = 0; e < entries.length; e++) parts.push(String(entries[e].getText())); text = parts.join("") } catch (ex) { text = String(r) }
        return (document.getLineNumber(inlay.getOffset()) + 1) + ":" + text
    }
    for (var i = 0; i < inline.size(); i++) all.push(describe(inline.get(i)))
    for (var j = 0; j < eol.size(); j++) all.push("eol " + describe(eol.get(j)))
    return all.length + " hints: " + all.join(" || ")
})
else if (op == "typos") onEdt(function () {
    importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
    var infos = DaemonCodeAnalyzerImpl.getHighlights(document, null, project)
    var out = []
    for (var i = 0; i < infos.size(); i++) {
        var info = infos.get(i), d = String(info.getDescription() || "")
        if (d.toLowerCase().indexOf("typo") >= 0 || String(info.getSeverity()).indexOf("TYPO") >= 0) out.push((document.getLineNumber(info.getStartOffset()) + 1) + ":" + String(document.getText()).substring(info.getStartOffset(), info.getEndOffset()) + " (" + d + ")")
    }
    return out.length + " typos: " + out.join(" | ")
})
else if (op == "problems") onEdt(function () {
    // every highlight of WEAK WARNING and above (problems.js stops at WARNING): line, severity, text under it, description
    importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
    importClass(com.intellij.lang.annotation.HighlightSeverity)
    var infos = DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.WEAK_WARNING, project)
    var out = [], text = String(document.getText())
    for (var i = 0; i < infos.size(); i++) {
        var info = infos.get(i), d = String(info.getDescription() || "")
        if (d == "") continue
        out.push((document.getLineNumber(info.getStartOffset()) + 1) + " " + String(info.getSeverity().getName()) + " [" + text.substring(info.getStartOffset(), Math.min(info.getEndOffset(), info.getStartOffset() + 40)).split(String.fromCharCode(10)).join(" ") + "] " + d)
    }
    return out.length + " problems: " + out.join(" || ")
})
else if (op == "lookup") onEdt(function () {
    // basic completion at the caret, then the first items of the lookup (arg = how many)
    importClass(com.intellij.codeInsight.lookup.LookupManager)
    performAction("CodeCompletion")
    var lookup = null
    for (var w = 0; w < 50 && lookup == null; w++) { java.lang.Thread.sleep(100); lookup = LookupManager.getActiveLookup(editor) }
    if (lookup == null) return "no lookup"
    var items = lookup.getItems(), out = []
    for (var i = 0; i < items.size() && i < parseInt(arg || "10"); i++) out.push(String(items.get(i).getLookupString()))
    return items.size() + " items: " + out.join(" ")
})
else if (op == "smart" || op == "pick") onEdt(function () {
    // smart: smart completion at the caret, then the first 15 items; pick: basic completion (or the open lookup), then the item whose
    // lookup string is arg is inserted (arg `smart:ITEM` picks from smart completion)
    importClass(com.intellij.codeInsight.lookup.LookupManager)
    importClass(com.intellij.codeInsight.lookup.Lookup)
    var wanted = arg, smart = op == "smart"
    if (op == "pick" && arg.indexOf("smart:") == 0) { smart = true; wanted = arg.substring(6) }
    var lookup = LookupManager.getActiveLookup(editor)
    if (lookup == null) performAction(smart ? "SmartTypeCompletion" : "CodeCompletion")
    for (var w = 0; w < 50 && lookup == null; w++) { java.lang.Thread.sleep(100); lookup = LookupManager.getActiveLookup(editor) }
    if (lookup == null) { commit(); return "no lookup (a single item may have been inserted)" }
    var items = lookup.getItems(), out = []
    for (var i = 0; i < items.size(); i++) {
        var s = String(items.get(i).getLookupString())
        if (op == "pick" && s == wanted) { lookup.setCurrentItem(items.get(i)); lookup.finishLookup(Lookup.NORMAL_SELECT_CHAR); commit(); return "picked " + s }
        if (i < 15) out.push(s)
    }
    if (op == "pick") { lookup.hideLookup(true); return "not found " + wanted + " in " + items.size() + " items: " + out.join(" ") }
    return items.size() + " items: " + out.join(" ")
})
else if (op == "targets") onEdt(function () {
    importClass(com.intellij.codeInsight.navigation.actions.GotoDeclarationAction)
    var at = offsetOf(arg) + 1; commit()
    var targets = GotoDeclarationAction.findAllTargetElements(project, editor, at)
    var out = []
    for (var i = 0; i < targets.length; i++) out.push(String(targets[i]) + " in " + targets[i].getContainingFile().getName() + ":" + (targets[i].getContainingFile().getViewProvider().getDocument().getLineNumber(targets[i].getTextOffset()) + 1))
    return targets.length + " targets: " + out.join(" | ")
})
else if (op == "setting") onEdt(function () {
    importClass(com.intellij.codeInsight.CodeInsightSettings)
    var p = arg.split("="); var s = CodeInsightSettings.getInstance(); var field = s.getClass().getField(p[0]); field.setInt(s, parseInt(p[1])); return p[0] + " = " + field.getInt(s)
})
else if (op == "deleteline") onEdt(function () {
    var at = offsetOf(arg), line = document.getLineNumber(at)
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { document.deleteString(document.getLineStartOffset(line), Math.min(document.getTextLength(), document.getLineEndOffset(line) + 1)) } }))
    commit(); return "deleted line of " + arg
})
else if (op == "text") onEdt(function () { return "text" })
else report.complete("unknown op " + op)

var result = report.get(60, TimeUnit.SECONDS)
java.lang.Thread.sleep(300)
ReadAction.compute(function () {
    var text = String(document.getText()), lines = text.split("\n")
    var range = "__FROM__-__TO__".split("-")
    var from = Math.max(1, parseInt(range[0]) || 1), to = Math.min(lines.length, parseInt(range[1]) || lines.length)
    var out = []
    var caretLine = document.getLineNumber(editor.getCaretModel().getOffset()) + 1
    for (var i = from; i <= to; i++) out.push((i == caretLine ? ">" : " ") + i + "| " + lines[i - 1].split("\t").join("<TAB>"))
    return result + "\n" + out.join("\n")
})
