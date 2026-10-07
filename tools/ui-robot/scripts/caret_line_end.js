// Caret to the end of line __LINE__ (1-based) of the selected editor, focus in the editor.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
var projects = ProjectManager.getInstance().getOpenProjects(); var project = projects[projects.length - 1]
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    editor.getCaretModel().moveToOffset(editor.getDocument().getLineEndOffset(__LINE__ - 1))
    editor.getContentComponent().requestFocusInWindow()
} })); "ok"
