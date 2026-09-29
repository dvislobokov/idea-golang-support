// Fold regions of the selected editor (go.mod expected), and the editor notification panels of the project.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
ReadAction.compute(function () {
    const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    const file = FileDocumentManager.getInstance().getFile(editor.getDocument())
    const regions = editor.getFoldingModel().getAllFoldRegions()
    let out = file.getName() + " folds: " + regions.length + "\n"
    for (let i = 0; i < regions.length; i++) out += "  " + regions[i].getStartOffset() + "-" + regions[i].getEndOffset() + " '" + regions[i].getPlaceholderText() + "' expanded=" + regions[i].isExpanded() + "\n"
    return out
})
