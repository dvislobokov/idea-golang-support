// Puts the caret of the selected editor inside the first occurrence of __TEXT__ (one character in) and focuses the editor.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.wm.IdeFocusManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    const at = String(editor.getDocument().getText()).indexOf("__TEXT__")
    editor.getCaretModel().moveToOffset(at + 1)
    IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true)
} }))
"ok"
