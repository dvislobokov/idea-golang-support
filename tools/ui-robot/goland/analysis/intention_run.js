// Opens __FILE__, puts the caret at the first occurrence of __AT__ plus __OFF__, invokes the intention whose text starts with __TEXT__
// (from ShowIntentionsPass's list), then prints the popup lists that are open (a chooser, if any) and the document's lines around the caret.
// Pure Rhino (works in GoLand); the file is restored at the end when __RESTORE__ = yes.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.command.WriteCommandAction)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var app = ApplicationManager.getApplication()
var out = new java.lang.StringBuilder("@@@")
var editor = null, psiFile = null, original = null
app.invokeAndWait(new java.lang.Runnable({ run: function () {
    var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    editor = FileEditorManager.getInstance(project).openTextEditor(new com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file, 0), true)
    psiFile = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
    original = String(editor.getDocument().getText())
    var at = original.indexOf("__AT__")
    if (at < 0) { out.append("anchor not found\n"); return }
    editor.getCaretModel().moveToOffset(at + __OFF__)
} }), ModalityState.any())
java.lang.Thread.sleep(2500)
var chosen = null
app.runReadAction(new java.lang.Runnable({ run: function () {
    var info = com.intellij.codeInsight.daemon.impl.ShowIntentionsPass.getActionsToShow(editor, psiFile)
    var lists = [info.intentionsToShow, info.errorFixesToShow, info.inspectionFixesToShow]
    var names = []
    for (var l = 0; l < lists.length; l++) {
        var it = lists[l].iterator()
        while (it.hasNext()) {
            var d = it.next(); var a = d.getAction(); var t = String(a.getText()); names.push(t)
            if (chosen == null && t.indexOf("__TEXT__") == 0) chosen = a
        }
    }
    out.append("available: " + names.join(" | ") + "\n")
} }))
if (chosen != null) {
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler.chooseActionAndInvoke(psiFile, editor, chosen, String(chosen.getText()))
    } }), ModalityState.any())
    java.lang.Thread.sleep(2500)
    app.invokeAndWait(new java.lang.Runnable({ run: function () {
        var windows = java.awt.Window.getWindows()
        for (var i = 0; i < windows.length; i++) {
            var w = windows[i]
            if (!w.isShowing() || !(w instanceof javax.swing.JWindow)) continue
            // UIUtil.uiTraverser: ComponentUtil.findComponentsOfType is ambiguous to Rhino for the package-private Popup$HeavyWeightWindow
            var lists = com.intellij.util.ui.UIUtil.uiTraverser(w).filter(javax.swing.JList).toList()
            var li = lists.iterator()
            while (li.hasNext()) { var list = li.next(); var m = list.getModel(); var rows = []; for (var r = 0; r < m.getSize(); r++) rows.push(String(m.getElementAt(r))); out.append("popup list: " + rows.join(" | ") + "\n") }
            var texts = com.intellij.util.ui.UIUtil.uiTraverser(w).filter(javax.swing.JLabel).toList()
            var ti = texts.iterator(); var labels = []
            while (ti.hasNext()) { var lb = ti.next(); if (lb.getText() != null && String(lb.getText()).length > 0) labels.push(String(lb.getText()).replace(/<[^>]*>/g, "")) }
            if (labels.length > 0) out.append("popup labels: " + labels.slice(0, 20).join(" | ") + "\n")
        }
        var doc = editor.getDocument(); var text = String(doc.getText()); var caret = editor.getCaretModel().getOffset()
        var line = doc.getLineNumber(Math.min(caret, doc.getTextLength()))
        var from = Math.max(0, line - 4), to = Math.min(doc.getLineCount() - 1, line + 4)
        out.append("document " + (text == original ? "unchanged" : "CHANGED") + ":\n")
        for (var ln = from; ln <= to; ln++) out.append("  " + (ln + 1) + ": " + text.substring(doc.getLineStartOffset(ln), doc.getLineEndOffset(ln)) + "\n")
    } }), ModalityState.any())
} else out.append("intention __TEXT__ not available\n")
if ("__RESTORE__" == "yes") app.invokeAndWait(new java.lang.Runnable({ run: function () {
    WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { editor.getDocument().setText(original) } }))
    com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveDocument(editor.getDocument())
} }), ModalityState.any())
String(out)
