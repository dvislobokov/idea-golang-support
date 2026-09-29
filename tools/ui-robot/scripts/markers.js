// The line markers of the selected editor: line, icon and tooltip (gutter ▶ of tests, subtests, implementations).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ReadAction.compute(function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var document = editor.getDocument()
    var markers = DaemonCodeAnalyzerImpl.getLineMarkers(document, project)
    let out = "markers: " + markers.size() + "\n"
    for (let i = 0; i < markers.size(); i++) {
        var m = markers.get(i)
        var line = document.getLineNumber(m.startOffset) + 1
        var text = String(document.getText().substring(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1))).trim()
        out += "  line " + line + " icon=" + m.getIcon() + " tooltip=" + m.getLineMarkerTooltip() + "  | " + text.substring(0, 60) + "\n"
    }
    return out
})
