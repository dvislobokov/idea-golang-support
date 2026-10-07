// Caret to the end of the line __PLUS__ lines after the one containing __TEXT__.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
var projects = ProjectManager.getInstance().getOpenProjects(); var project = projects[projects.length - 1]
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor(); var d = editor.getDocument()
    var line = d.getLineNumber(String(d.getText()).indexOf("__TEXT__")) + __PLUS__
    editor.getCaretModel().moveToOffset(d.getLineEndOffset(line))
    editor.getContentComponent().requestFocusInWindow()
} })); "ok"
