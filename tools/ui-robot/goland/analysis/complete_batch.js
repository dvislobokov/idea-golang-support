// docs/compare part 2, scenarios: runs tools/compare/completion/scenarios.json (__SCENARIOS__) on __FILE__ (the probe file copied into
// the project) in GoLand or in the plugin sandbox — plain platform APIs only. Per scenario: insert `type` on the line after the line
// containing `mark` ("@@" = caret), type `typed` char by char through TypedAction, invoke completion `kind` (BASIC / SMART), wait
// until the lookup stops calculating, record the first `limit` items (presentation, bold, tail, type), choose `pick` ("#1" or the item
// text) with `char` ("n" Enter / "t" Tab) and record the resulting lines with <caret> (and the first `head` lines of the file, for
// imports). The document is restored after each scenario. One JSON line per scenario is appended to __OUT__.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.completion.CodeCompletionHandlerBase)
importClass(com.intellij.codeInsight.completion.CompletionType)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.lookup.LookupElementPresentation)
importClass(com.intellij.openapi.editor.actionSystem.TypedAction)
importClass(com.intellij.ide.DataManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var any = ModalityState.any()
var scenarios = JSON.parse(String(new java.lang.String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("__SCENARIOS__")), "UTF-8")))
var only = "__ONLY__"
var out = new java.io.OutputStreamWriter(new java.io.FileOutputStream("__OUT__", true), "UTF-8")
var editor = null, original = null
function edt(f) { app.invokeAndWait(new java.lang.Runnable({ run: f }), any) }
edt(function () {
    var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0), true)
    original = String(editor.getDocument().getText())
})
java.lang.Thread.sleep(3000)
function restore() {
    var doc = editor.getDocument()
    LookupManager.getInstance(project).hideActiveLookup()
    try { var ts = com.intellij.codeInsight.template.impl.TemplateManagerImpl.getTemplateState(editor); if (ts != null) ts.gracefullyFinishTemplate() } catch (e) {}
    var cur = String(doc.getText())
    if (cur != original) WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { doc.setText(original) } }))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
}
function lines(from, count) {
    var doc = editor.getDocument()
    var off = editor.getCaretModel().getOffset()
    var t = String(doc.getText())
    t = t.substring(0, off) + "<caret>" + t.substring(off)
    return t.split("\n").slice(Math.max(0, from), from + count)
}
function present(item) {
    var p = new LookupElementPresentation()
    item.renderElement(p)
    return (p.isItemTextBold() ? "*" : "") + (p.getItemText() || item.getLookupString()) + (p.getTailText() || "") + (p.getTypeText() ? "  : " + p.getTypeText() : "")
}
var count = 0
for (var si = 0; si < scenarios.length; si++) {
    var sc = scenarios[si]
    if (only.length > 0 && ("," + only + ",").indexOf("," + sc.id + ",") < 0) continue
    var r = { id: sc.id, items: [], size: 0, lookup: false, picked: null, after: [], head: [], note: null }
    var lineNo = 0
    try {
        edt(function () {
            var doc = editor.getDocument()
            var at = original.indexOf("// " + sc.mark)
            lineNo = doc.getLineNumber(at) + 1
            var start = doc.getLineStartOffset(lineNo)
            var typed = sc.type, caretAt = typed.indexOf("@@")
            typed = typed.substring(0, caretAt) + typed.substring(caretAt + 2)
            WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { doc.insertString(start, typed) } }))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            editor.getCaretModel().moveToOffset(start + caretAt)
            editor.getContentComponent().requestFocusInWindow()
        })
        java.lang.Thread.sleep(400)
        var keys = sc.typed || ""
        for (var k = 0; k < keys.length; k++) {
            var ch = keys.charAt(k)
            edt(function () { TypedAction.getInstance().actionPerformed(editor, ch, DataManager.getInstance().getDataContext(editor.getContentComponent())) })
            java.lang.Thread.sleep(120)
        }
        edt(function () { LookupManager.getInstance(project).hideActiveLookup() })
        var t0 = java.lang.System.nanoTime()
        edt(function () { new CodeCompletionHandlerBase(CompletionType[sc.kind], true, false, true).invokeCompletion(project, editor, 1) })
        for (var tick = 0; tick < 250; tick++) {
            java.lang.Thread.sleep(20)
            var state = null
            edt(function () {
                var lookup = LookupManager.getInstance(project).getActiveLookup()
                if (lookup == null) { state = "none"; return }
                if (lookup.isCalculating()) { state = "calc"; return }
                state = "ready"
                var items = lookup.getItems()
                r.lookup = true
                r.size = items.size()
                for (var i = 0; i < items.size() && i < (sc.limit || 12); i++) r.items.push(String(present(items.get(i))))
            })
            if (state == "ready" || (state == "none" && tick > 100)) break
        }
        r.millis = Math.round((java.lang.System.nanoTime() - t0) / 1e6)
        if (!r.lookup) edt(function () { r.after = lines(lineNo - 1, sc.show || 3) })  // completion may have inserted a single item by itself
        if (sc.pick && r.lookup) {
            edt(function () {
                var lookup = LookupManager.getInstance(project).getActiveLookup()
                var items = lookup.getItems(), chosen = null
                if (sc.pick.charAt(0) == "#") chosen = items.get(parseInt(sc.pick.substring(1)) - 1)
                else for (var i = 0; i < items.size(); i++) {
                    var p = new LookupElementPresentation(); items.get(i).renderElement(p)
                    if ((p.getItemText() || items.get(i).getLookupString()) == sc.pick || items.get(i).getLookupString() == sc.pick) { chosen = items.get(i); break }
                }
                if (chosen == null) { r.note = "item not found: " + sc.pick; return }
                r.picked = String(present(chosen))
                lookup.setCurrentItem(chosen)
                lookup.finishLookup((sc.char || "n") == "t" ? "\t" : "\n")
            })
            java.lang.Thread.sleep(1500)
            edt(function () { r.after = lines(lineNo - 1, sc.show || 3) })
        }
        if (sc.head) edt(function () { r.head = String(editor.getDocument().getText()).split("\n").slice(0, sc.head) })
    } catch (e) {
        r.note = "error: " + e
    }
    edt(restore)
    out.write(JSON.stringify(r) + "\n")
    out.flush()
    count++
}
edt(function () { restore(); com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(editor.getDocument()) })
out.close()
"@@@" + "scenarios: " + count
