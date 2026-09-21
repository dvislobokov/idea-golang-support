// The file and the caret of the selected editor, and the element under the caret as the PSI sees it.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.openapi.application.ReadAction)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
ReadAction.compute(function () {
    const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    const document = editor.getDocument()
    const offset = editor.getCaretModel().getOffset()
    const line = document.getLineNumber(offset)
    const psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    const element = psi.findElementAt(offset)
    return FileDocumentManager.getInstance().getFile(document).getName() + ":" + (line + 1) + ":" + (offset - document.getLineStartOffset(line) + 1) +
        "  element=" + element + " parent=" + element.getParent()
})
