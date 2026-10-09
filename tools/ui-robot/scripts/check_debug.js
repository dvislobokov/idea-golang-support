// Why a Go file shows no colours and no errors: for the file in the selected editor (last project) prints the DIAGNOSTICS / SEMANTIC_COLORS
// gates, the resolution of every import, the diagnostics of the plugin's checker, and whether the Go inspections are enabled in the profile.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.psi.util.PsiTreeUtil)
importClass(com.intellij.codeInsight.daemon.HighlightDisplayKey)
importClass(com.intellij.profile.codeInspection.InspectionProjectProfileManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ReadAction.compute(function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    if (editor == null) return "no editor"
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
    var out = "project " + project.getName() + ", file " + psi.getName() + "\n"
    var gateClass = cls("io.github.golangsupport.ide.GoIdeFeatureGate")
    var companion = gateClass.getField("Companion").get(null)
    var featureClass = cls("io.github.golangsupport.ide.GoIdeFeature")
    var names = ["DIAGNOSTICS", "SEMANTIC_COLORS", "CODE_ACTIONS", "NAVIGATION"]
    for (var i = 0; i < names.length; i++) {
        try { out += "gate " + names[i] + ": " + companion.enabled(java.lang.Enum.valueOf(featureClass, names[i]), project) + "\n" }
        catch (e) { out += "gate " + names[i] + ": threw " + e + "\n" }
    }
    try {
        var model = cls("io.github.golangsupport.semantic.scope.GoPackageModel").getField("Companion").get(null).getInstance(project)
        var specs = PsiTreeUtil.findChildrenOfType(psi, cls("io.github.golangsupport.lang.psi.GoImportSpec")).toArray()
        for (var j = 0; j < specs.length; j++) {
            var pkg = model.resolveImport(specs[j].getPath(), psi)
            out += "import " + specs[j].getPath() + " -> " + (pkg == null ? "null" : pkg.getName() + " @ " + pkg.getDirectory()) + "\n"
        }
    } catch (e) { out += "resolve: threw " + e + "\n" }
    try {
        var cache = cls("io.github.golangsupport.ide.inspections.GoDiagnosticsCache").getField("INSTANCE").get(null)
        var ds = cache.diagnostics(psi)
        out += "checker diagnostics: " + ds.size() + "\n"
        for (var k = 0; k < Math.min(ds.size(), 6); k++) out += "  " + ds.get(k).getCode() + " " + ds.get(k).getMessage() + "\n"
    } catch (e) { out += "checker: threw " + e + "\n" }
    var profile = InspectionProjectProfileManager.getInstance(project).getCurrentProfile()
    var tools = ["GoChecker", "GoUnresolvedReference", "GoMissingPackage", "GoUnusedImport"]
    out += "profile " + profile.getName() + ":"
    for (var t = 0; t < tools.length; t++) {
        var key = HighlightDisplayKey.find(tools[t])
        out += " " + tools[t] + "=" + (key == null ? "no key" : profile.isToolEnabled(key, psi))
    }
    return out + "\n"
})
