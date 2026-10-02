// Where the caret of the selected editor is: the file, the line number and the text of the line.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ReadAction.compute(function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    if (editor == null) return "no editor"
    var document = editor.getDocument()
    var file = FileDocumentManager.getInstance().getFile(document)
    var offset = editor.getCaretModel().getOffset()
    var line = document.getLineNumber(offset)
    return (file == null ? "?" : file.getName()) + ":" + (line + 1) + ": " + String(document.getText().substring(document.getLineStartOffset(line), document.getLineEndOffset(line))).replace(/\t/g, "<TAB>")
})
