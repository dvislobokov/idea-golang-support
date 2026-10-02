// The file of the selected editor: its PsiErrorElements, the ERROR highlights the daemon holds for it, and whether the daemon is done.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.psi.PsiErrorElement)
importClass(com.intellij.psi.util.PsiTreeUtil)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
importClass(com.intellij.lang.annotation.HighlightSeverity)
importClass(com.intellij.openapi.project.DumbService)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ReadAction.compute(function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    if (editor == null) return "no editor"
    var document = editor.getDocument()
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    var out = "file: " + psi.getName() + " (" + psi.getClass().getSimpleName() + "), dumb=" + DumbService.isDumb(project) + "\n"
    var errs = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement).toArray()
    out += "psi error elements: " + errs.length + "\n"
    for (var i = 0; i < errs.length; i++) out += "  @" + errs[i].getTextOffset() + " " + errs[i].getErrorDescription() + "\n"
    var infos = DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.ERROR, project)
    out += "daemon ERROR highlights: " + infos.size() + "\n"
    for (var j = 0; j < infos.size(); j++) out += "  " + infos.get(j).getStartOffset() + "-" + infos.get(j).getEndOffset() + " " + infos.get(j).getDescription() + "\n"
    out += "daemon finished: " + DaemonCodeAnalyzerImpl.getInstanceEx(project).isErrorAnalyzingFinished(psi) + "\n"
    return out
})
