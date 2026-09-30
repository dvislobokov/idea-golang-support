// Why an intention of the plugin is or is not in Alt+Enter: the caret is put inside the first __TEXT__ of the selected editor, the
// intention whose class name ends with __CLASS__ is asked isAvailable directly, and the text around the caret is printed.
importClass(com.intellij.codeInsight.intention.IntentionManager)
importClass(com.intellij.codeInsight.intention.IntentionActionDelegate)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
var document = editor.getDocument()
var moved = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    editor.getCaretModel().moveToOffset(String(document.getText()).indexOf("__TEXT__") + 1)
    moved.complete(true)
} }))
moved.get(10, TimeUnit.SECONDS)
ReadAction.compute(function () {
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    var offset = editor.getCaretModel().getOffset()
    var text = String(document.getText())
    var out = "caret " + offset + " in: " + text.substring(Math.max(0, offset - 20), Math.min(text.length, offset + 20)).replace(/\n/g, "\\n") + "\n"
    out += "psi: " + (psi == null ? "null" : psi.getClass().getName()) + "\n"
    var all = IntentionManager.getInstance().getAvailableIntentions()
    var seen = 0
    for (var i = 0; i < all.size(); i++) {
        var action = IntentionActionDelegate.unwrap(all.get(i))
        if (!String(action.getClass().getName()).endsWith("__CLASS__")) continue
        seen++
        try { out += action.getClass().getName() + ": available=" + action.isAvailable(project, editor, psi) + " text=" + action.getText() + "\n" }
        catch (e) { out += action.getClass().getName() + ": error " + e + "\n" }
    }
    return out + (seen == 0 ? "no intention __CLASS__ is registered" : "")
})
