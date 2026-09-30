// Selects lines __FROM__..__TO__ (1-based, whole lines) of the selected editor and gives the editor the focus.
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
        var document = editor.getDocument()
        var start = document.getLineStartOffset(__FROM__ - 1)
        var end = document.getLineEndOffset(__TO__ - 1)
        editor.getCaretModel().moveToOffset(end)
        editor.getSelectionModel().setSelection(start, end)
        IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true)
        done.complete("selected " + start + ".." + end + ": " + document.getText(new com.intellij.openapi.util.TextRange(start, end)).replace("\t", "<TAB>"))
    } catch (e) { done.complete("failed: " + e) }
} }))
done.get(30, TimeUnit.SECONDS)
