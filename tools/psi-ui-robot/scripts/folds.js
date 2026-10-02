// The fold regions of the selected editor: the start line, the placeholder and the first characters of each, in order of the text.
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
    var regions = editor.getFoldingModel().getAllFoldRegions()
    var out = (file == null ? "?" : file.getName()) + " folds: " + regions.length + "\n"
    for (var i = 0; i < regions.length; i++) {
        var r = regions[i]
        var text = String(document.getText().substring(r.getStartOffset(), Math.min(r.getEndOffset(), r.getStartOffset() + 24))).replace(/\n/g, "\\n").replace(/\t/g, " ")
        out += "  " + (document.getLineNumber(r.getStartOffset()) + 1) + "-" + (document.getLineNumber(r.getEndOffset()) + 1) + " " + r.getPlaceholderText() + " " + text + "\n"
    }
    return out
})
