// What the daemon is allowed to do for the file in the selected editor (last project): Power Save Mode, the highlighting level of the file
// (None / Syntax / Essential / All), whether highlighting and inspections are available, and the number of highlights by severity.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.ide.PowerSaveMode)
importClass(com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager)
importClass(com.intellij.codeInsight.daemon.DaemonCodeAnalyzer)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
importClass(com.intellij.lang.annotation.HighlightSeverity)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ReadAction.compute(function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    if (editor == null) return "no editor"
    var document = editor.getDocument()
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    var levels = HighlightingLevelManager.getInstance(project)
    var out = "project " + project.getName() + ", file " + psi.getName() + "\n"
    out += "power save: " + PowerSaveMode.isEnabled() + ", dumb: " + com.intellij.openapi.project.DumbService.isDumb(project) + "\n"
    out += "shouldHighlight: " + levels.shouldHighlight(psi) + ", shouldInspect: " + levels.shouldInspect(psi) + ", runEssentialOnly: " + levels.runEssentialHighlightingOnly(psi) + "\n"
    out += "daemon highlighting available: " + DaemonCodeAnalyzer.getInstance(project).isHighlightingAvailable(psi) + "\n"
    var sev = [HighlightSeverity.ERROR, HighlightSeverity.WARNING, HighlightSeverity.WEAK_WARNING, HighlightSeverity.INFORMATION]
    for (var i = 0; i < sev.length; i++) out += "  " + sev[i] + ": " + DaemonCodeAnalyzerImpl.getHighlights(document, sev[i], project).size() + "\n"
    return out
})
