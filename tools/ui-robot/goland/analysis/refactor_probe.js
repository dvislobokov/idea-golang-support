// docs/compare part 3 in GoLand: one scenario __ID__ of tools/compare/refactor/scenarios.json (__SCENARIOS__) in the project __DIR__.
// Records the Alt+Enter list (intentions and fixes at the place) and the enabled items of Refactor This (RefactoringMenu) at the place;
// with __DO__ = yes performs the refactorings that platform APIs can run without a dialog: rename (RenameProcessor: usages, collisions,
// then the refactoring), safe delete (SafeDeleteProcessor: usages, then the refactoring when none is found... or forced) and intentions
// (by text). Saves all documents; the caller diffs, builds and restores the clone. Prints one JSON object.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.ide.DataManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var any = ModalityState.any()
function edt(f) { app.invokeAndWait(new java.lang.Runnable({ run: f }), any) }
var all = JSON.parse(String(new java.lang.String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("__SCENARIOS__")), "UTF-8"))).scenarios
var sc = null
for (var i = 0; i < all.length; i++) if (all[i].id == "__ID__") sc = all[i]
var r = { id: "__ID__", intentions: [], refactorThis: [], done: null, usages: null, conflicts: [], note: null }
var editor = null, psi = null, sdProc = null, chosenIntention = null
// runs [f] on the EDT without waiting (a refactoring may open a modal dialog), then records and cancels any dialog that appears
function runLater(f) {
    app.invokeLater(new java.lang.Runnable({ run: function () { try { f() } catch (e) { r.note = (r.note || "") + " run: " + e } } }), any)
    for (var t = 0; t < 20; t++) {
        java.lang.Thread.sleep(500)
        var dlg = null
        var ws = java.awt.Window.getWindows()
        for (var w = 0; w < ws.length; w++) if (ws[w].isShowing() && ws[w] instanceof java.awt.Dialog && ws[w].isModal()) dlg = ws[w]
        if (dlg != null) {
            var texts = []
            function walk(c) {
                if (c instanceof javax.swing.JLabel && c.getText()) texts.push(String(c.getText()))
                if (c instanceof javax.swing.text.JTextComponent && c.getText()) texts.push(String(c.getText()))
                if (c instanceof javax.swing.JList) { var m = c.getModel(); for (var q = 0; q < m.getSize() && q < 30; q++) texts.push(String(m.getElementAt(q))) }
                if (c instanceof javax.swing.AbstractButton && c.getText()) texts.push("[" + c.getText() + "]")
                if (c instanceof java.awt.Container) { var kids = c.getComponents(); for (var q = 0; q < kids.length; q++) walk(kids[q]) }
            }
            walk(dlg)
            r.dialog = { title: String(dlg.getTitle()), texts: texts.map(function (x) { return x.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim() }).filter(function (x) { return x.length > 0 }).map(function (x) { return x.substring(0, 300) }) }
            edt(function () { var dw = com.intellij.openapi.ui.DialogWrapper.findInstance(dlg); if (dw != null) dw.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE); else dlg.dispose() })
            return
        }
        var busy = false
        edt(function () { busy = false })
        if (t >= 4) return
    }
}
edt(function () {
    var vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__DIR__/" + sc.file)
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf, 0), true)
    // the clone was restored on disk by the caller: drop what the editor still holds
    FileDocumentManager.getInstance().reloadFromDisk(editor.getDocument())
    var text = String(editor.getDocument().getText())
    if ((sc.select && text.indexOf(sc.select) < 0) || (!sc.select && text.indexOf(sc.anchor) < 0)) r.note = "place not found"
    if (sc.select) {
        var base = sc.within ? text.indexOf(sc.within) : 0
        var s = text.indexOf(sc.select, base)
        editor.getSelectionModel().setSelection(s, s + sc.select.length)
        editor.getCaretModel().moveToOffset(s + sc.select.length)
    } else {
        editor.getCaretModel().moveToOffset(text.indexOf(sc.anchor) + (sc.at || 0))
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
})
java.lang.Thread.sleep(__SYNC__)
// Alt+Enter: what the light bulb would list (GoLand 2026.2 computes it in a background read action, not on the EDT)
function intentionsInfo() {
    return app.runReadAction(new com.intellij.openapi.util.Computable({ compute: function () {
        return com.intellij.codeInsight.daemon.impl.ShowIntentionsPass.getActionsToShow(editor, psi)
    } }))
}
try {
    var info = intentionsInfo()
    var lists = [info.errorFixesToShow, info.inspectionFixesToShow, info.intentionsToShow]
    for (var l = 0; l < lists.length; l++) for (var k = 0; k < lists[l].size(); k++) r.intentions.push(String(lists[l].get(k).getAction().getText()))
} catch (e) { r.note = "intentions: " + e }
// Refactor This: the enabled actions of the refactoring menu group in the editor's context
try {
    edt(function () {
        var group = ActionManager.getInstance().getAction("RefactoringMenu")
        var ctx = DataManager.getInstance().getDataContext(editor.getContentComponent())
        var kids = group.getChildren(ActionManager.getInstance())
        for (var k = 0; k < kids.length; k++) {
            var a = kids[k]
            if (a instanceof com.intellij.openapi.actionSystem.Separator) continue
            var ev = com.intellij.openapi.actionSystem.AnActionEvent.createFromAnAction(a, null, "EditorPopup", ctx)
            try { com.intellij.openapi.actionSystem.ex.ActionUtil.performDumbAwareUpdate(a, ev, false) } catch (e2) {}
            var p = ev.getPresentation()
            if (p.isEnabled() && p.isVisible() && p.getText()) r.refactorThis.push(String(p.getText()))
        }
    })
} catch (e) { r.note = (r.note || "") + " refactorThis: " + e }
if ("__DO__" == "yes") {
    try {
        if (sc.refactoring == "rename") {
            var target = null
            edt(function () {
                if (sc.params && sc.params.target == "file") target = psi
                else {
                    var util = com.intellij.codeInsight.TargetElementUtil.getInstance()
                    target = util.findTargetElement(editor, util.getAllAccepted(), editor.getCaretModel().getOffset())
                }
            })
            if (target == null) r.note = "no rename target"
            else {
                var proc = null, usages = null
                edt(function () {
                    proc = new com.intellij.refactoring.rename.RenameProcessor(project, target, sc.params.newName, false, false)
                    usages = proc.findUsages()
                    r.usages = usages.length
                    for (var u = 0; u < usages.length; u++) if (usages[u] instanceof com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo) r.conflicts.push(String(usages[u].getDescription()))
                    // the conflicts dialog of a real rename: GoLand-specific checks go through the processor's preprocessing
                    try {
                        var conflicts = new com.intellij.util.containers.MultiMap()
                        var renames = new java.util.LinkedHashMap(); renames.put(target, sc.params.newName)
                        com.intellij.refactoring.rename.RenamePsiElementProcessor.forElement(target).findExistingNameConflicts(target, sc.params.newName, conflicts, renames)
                        var vs = conflicts.values().toArray()
                        for (var c = 0; c < vs.length; c++) r.conflicts.push(String(vs[c]))
                    } catch (e3) { r.note = (r.note || "") + " conflicts: " + e3 }
                    proc.setPreviewUsages(false)
                })
                runLater(function () { proc.run() })
                r.done = "rename run"
            }
        } else if (sc.refactoring == "safe-delete") {
            edt(function () {
                var util = com.intellij.codeInsight.TargetElementUtil.getInstance()
                var target = util.findTargetElement(editor, util.getAllAccepted(), editor.getCaretModel().getOffset())
                var proc = com.intellij.refactoring.safeDelete.SafeDeleteProcessor.createInstance(project, null, [target], false, false, true)
                proc.setPreviewUsages(false)
                sdProc = proc
            })
            runLater(function () { sdProc.run() })
            r.done = "safe delete run"
        } else if (sc.refactoring == "intention") {
            var info = intentionsInfo()
            ;(function () {
                var found = null
                var wanted = sc.params.intention
                var lists = [info.intentionsToShow, info.inspectionFixesToShow, info.errorFixesToShow]
                for (var l = 0; l < lists.length && found == null; l++) for (var k = 0; k < lists[l].size() && found == null; k++) {
                    var t = String(lists[l].get(k).getAction().getText())
                    for (var w = 0; w < wanted.length; w++) if (t.toLowerCase().indexOf(wanted[w].toLowerCase()) >= 0) { found = lists[l].get(k).getAction(); break }
                }
                if (found == null) { r.note = "intention not offered"; return }
                r.done = "invoked: " + found.getText()
                chosenIntention = found
            })()
            if (chosenIntention != null) runLater(function () { com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler.chooseActionAndInvoke(psi, editor, chosenIntention, chosenIntention.getText()) })
        } else {
            r.note = "not run by the script (dialog or in-place UI): manual steps in tools/compare/refactor/goland-steps.json"
        }
    } catch (e) { r.note = (r.note || "") + " perform: " + e }
    java.lang.Thread.sleep(1500)
    edt(function () { PsiDocumentManager.getInstance(project).commitAllDocuments(); FileDocumentManager.getInstance().saveAllDocuments() })
}
"@@@" + JSON.stringify(r)
