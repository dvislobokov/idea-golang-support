// Selects the first occurrence of __TEXT__ in the selected editor and focuses it.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.wm.IdeFocusManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        var start = String(editor.getDocument().getText()).indexOf("__TEXT__")
        if (start < 0) { done.complete("not found"); return }
        editor.getCaretModel().moveToOffset(start + "__TEXT__".length)
        editor.getSelectionModel().setSelection(start, start + "__TEXT__".length)
        IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true)
        done.complete("selected " + start)
    } catch (e) { done.complete("failed: " + e) }
} }))
done.get(30, TimeUnit.SECONDS)
