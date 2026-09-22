// Presses Enter at the end of the line that contains __TEXT__ in the selected editor, the way the keyboard does (through the action of
// the editor, so that typing listeners such as the inline completion see it). With __THEN__ = Tab, presses Tab afterwards.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.wm.IdeFocusManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    const document = editor.getDocument()
    if ("__TEXT__" != "-") {
        const line = document.getLineNumber(String(document.getText()).indexOf("__TEXT__"))
        editor.getCaretModel().moveToOffset(document.getLineEndOffset(line))
    }
    IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true)
    const id = "__THEN__" == "Tab" ? "EditorTab" : "EditorEnter"
    ActionManager.getInstance().tryToExecute(ActionManager.getInstance().getAction(id), null, editor.getContentComponent(), "robot", true)
} }))
"ok"
