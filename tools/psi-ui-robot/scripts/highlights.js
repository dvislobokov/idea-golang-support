// The warnings and errors of the file in the selected editor: what the annotators and inspections have found.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.editor.impl.DocumentMarkupModel)
importClass(com.intellij.codeInsight.daemon.impl.HighlightInfo)
importClass(com.intellij.lang.annotation.HighlightSeverity)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
let text = ""
if (editor == null) text = "no editor"
else {
    const document = editor.getDocument()
    const highlighters = DocumentMarkupModel.forDocument(document, project, true).getAllHighlighters()
    for (let i = 0; i < highlighters.length; i++) {
        const info = HighlightInfo.fromRangeHighlighter(highlighters[i])
        if (info == null || info.getSeverity().compareTo(HighlightSeverity.WEAK_WARNING) < 0) continue
        text += info.getSeverity() + " line " + (document.getLineNumber(info.getStartOffset()) + 1) + " '" + info.getText() + "': " + info.getDescription() + "\n"
    }
    if (text == "") text = "no warnings"
}
text
