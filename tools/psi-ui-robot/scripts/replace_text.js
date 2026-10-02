// Replaces the first __FROM__ with __TO__ in the selected editor and saves the document; prints the line that holds __TO__ afterwards (after a pause for format on save).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
var document = editor.getDocument()
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var at = String(document.getText()).indexOf("__FROM__")
        if (at < 0) { done.complete("not found: __FROM__"); return }
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { document.replaceString(at, at + "__FROM__".length, "__TO__") } }))
        // the actions on save of the platform (format on save among them) run for "save all", not for one document
        FileDocumentManager.getInstance().saveAllDocuments()
        done.complete("replaced")
    } catch (e) { done.complete("failed: " + e) }
} }))
var first = done.get(30, TimeUnit.SECONDS)
java.lang.Thread.sleep(__WAIT__)
ReadAction.compute(function () {
    var text = String(document.getText())
    var at = text.indexOf("__TO__".split("     ").join(" "))
    if (at < 0) at = text.indexOf("__TO__")
    var line = at < 0 ? -1 : document.getLineNumber(at)
    return first + "; line: [" + (line < 0 ? "?" : text.substring(document.getLineStartOffset(line), document.getLineEndOffset(line))).split("\t").join("<TAB>") + "] unsaved=" + FileDocumentManager.getInstance().isDocumentUnsaved(document)
})
