// The code vision entries of the selected editor: what the gopls providers and the PSI providers of go-psi-ide compute for the file
// (provider id, anchor line, hint text), so the source per mode is visible without reading the painted hints.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.openapi.extensions.ExtensionPointName)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ReadAction.compute(function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var document = editor.getDocument()
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    var providers = ExtensionPointName.create("com.intellij.codeInsight.daemonBoundCodeVisionProvider").getExtensionList()
    var out = ""
    for (var i = 0; i < providers.size(); i++) {
        var p = providers.get(i)
        var id = String(p.getId())
        if (id.indexOf("go") != 0) continue
        var entries = p.computeForEditor(editor, psi)
        out += id + ": " + entries.size() + "\n"
        for (var j = 0; j < entries.size(); j++) {
            var e = entries.get(j)
            out += "  line " + (document.getLineNumber(e.getFirst().getStartOffset()) + 1) + " " + e.getSecond().getLongPresentation() + "\n"
        }
    }
    return out == "" ? "no Go code vision providers" : out
})
