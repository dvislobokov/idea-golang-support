// Why Alt+Enter is empty: at the caret of the selected editor (last project) prints the PSI file class, the CODE_ACTIONS gate, and for every
// registered intention of the plugin whether isAvailable answers true, false or throws.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.intention.IntentionManager)
importClass(com.intellij.codeInsight.intention.IntentionActionDelegate)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ReadAction.compute(function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    if (editor == null) return "no editor"
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
    var out = "project " + project.getName() + ", file " + psi.getName() + " " + psi.getClass().getName() + ", caret " + editor.getCaretModel().getOffset() + "\n"
    try {
        var gateClass = cls("io.github.golangsupport.ide.GoIdeFeatureGate")
        var feature = java.lang.Enum.valueOf(cls("io.github.golangsupport.ide.GoIdeFeature"), "CODE_ACTIONS")
        var companion = gateClass.getField("Companion").get(null)
        out += "gate CODE_ACTIONS: " + companion.enabled(feature, project) + "\n"
    } catch (e) { out += "gate: threw " + e + "\n" }
    var all = IntentionManager.getInstance().getIntentionActions()
    var mine = 0, available = 0, threw = 0, sample = ""
    for (var i = 0; i < all.length; i++) {
        var a = all[i]
        var name = String(IntentionActionDelegate.unwrap(a).getClass().getName())
        if (name.indexOf("golangsupport") < 0) continue
        mine++
        try {
            var ok = a.isAvailable(project, editor, psi)
            if (ok) { available++; sample += "  available: " + a.getText() + " (" + name + ")\n" }
        } catch (e) { threw++; if (threw <= 5) sample += "  threw: " + name + ": " + e + "\n" }
    }
    out += "plugin intentions registered: " + mine + ", available here: " + available + ", threw: " + threw + "\n" + sample
    return out
})
