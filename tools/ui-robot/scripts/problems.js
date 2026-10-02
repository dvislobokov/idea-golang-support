// The problems of the file in the selected editor as the daemon holds them (severity WARNING and above): offset range, severity,
// inspection id when it is an inspection, the description. Tells the gopls annotations (no tool id) from the inspections of the PSI.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
importClass(com.intellij.lang.annotation.HighlightSeverity)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ReadAction.compute(function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    if (editor == null) return "no editor"
    var document = editor.getDocument()
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    var infos = DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.WARNING, project)
    var out = "file: " + psi.getName() + ", problems: " + infos.size() + ", daemon finished: " + DaemonCodeAnalyzerImpl.getInstanceEx(project).isErrorAnalyzingFinished(psi) + "\n"
    for (var j = 0; j < infos.size(); j++) {
        var i = infos.get(j)
        var line = document.getLineNumber(i.getStartOffset()) + 1
        out += "  line " + line + " " + i.getSeverity() + (i.getInspectionToolId() == null ? "" : " [" + i.getInspectionToolId() + "]") + " " + i.getDescription() + "\n"
    }
    return out
})
