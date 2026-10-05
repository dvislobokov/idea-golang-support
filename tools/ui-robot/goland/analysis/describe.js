// Descriptions and a direct run of the inspections __TOOLS__ (comma-separated short names) on __FILE__; code vision providers matching "syntax".
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.profile.codeInspection.InspectionProjectProfileManager)
importClass(com.intellij.codeInspection.InspectionManager)
importClass(com.intellij.codeInspection.InspectionEngine)
importClass(com.intellij.codeInspection.ex.LocalInspectionToolWrapper)
importClass(com.intellij.psi.PsiManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var out = new java.lang.StringBuilder("@@@")
function plain(s) { return s == null ? "" : String(s).replace(/<[^>]*>/g, " ").replace(/&nbsp;/g, " ").replace(/&quot;/g, "\"").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&").replace(/\s+/g, " ").trim() }
var profile = InspectionProjectProfileManager.getInstance(project).getCurrentProfile()
var names = "__TOOLS__".split(",")
ApplicationManager.getApplication().runReadAction(new java.lang.Runnable({ run: function () {
    var vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath("__FILE__")
    var psi = PsiManager.getInstance(project).findFile(vf)
    for (var i = 0; i < names.length; i++) {
        var w = profile.getInspectionTool(names[i], project)
        if (w == null) { out.append("## " + names[i] + ": not found\n"); continue }
        var enabled = profile.isToolEnabled(com.intellij.codeInsight.daemon.HighlightDisplayKey.find(names[i]), psi)
        out.append("## " + names[i] + " [" + w.getDisplayName() + "] enabled=" + enabled + " level=" + profile.getErrorLevel(com.intellij.codeInsight.daemon.HighlightDisplayKey.find(names[i]), psi) + " class=" + w.getTool().getClass().getName() + "\n")
        out.append("   " + plain(w.loadDescription()) + "\n")
        try {
            if (w instanceof LocalInspectionToolWrapper) {
                var ctx = InspectionManager.getInstance(project).createNewGlobalContext()
                var problems = InspectionEngine.runInspectionOnFile(psi, w, ctx)
                out.append("   problems on file: " + problems.size() + "\n")
                var it = problems.iterator()
                while (it.hasNext()) { var p = it.next(); var el = p.getPsiElement(); out.append("     - " + plain(p.getDescriptionTemplate()) + (el == null ? "" : " «" + String(el.getText()).replace(/\s+/g, " ").substring(0, 60) + "» line " + (psi.getViewProvider().getDocument().getLineNumber(el.getTextOffset()) + 1)) + "\n") }
            } else out.append("   (global tool: " + w.getClass().getName() + ")\n")
        } catch (e) { out.append("   run failed: " + e + "\n") }
    }
} }))
try {
    var eps = com.intellij.codeInsight.codeVision.CodeVisionProviderFactory.Companion.createAllProviders(project)
    var it2 = eps.iterator()
    while (it2.hasNext()) { var pr = it2.next(); var id = String(pr.getId()); if (id.toLowerCase().indexOf("syntax") >= 0 || id.toLowerCase().indexOf("modern") >= 0) out.append("## provider " + id + " name=" + pr.getName() + " group=" + pr.getGroupId() + " class=" + pr.getClass().getName() + "\n") }
} catch (e) { out.append("providers: " + e + "\n") }
try {
    var settings = com.intellij.codeInsight.codeVision.settings.CodeVisionSettings.Companion.getInstance()
    out.append("codeVision enabled=" + settings.getCodeVisionEnabled() + " go.syntax.update enabled=" + settings.isProviderEnabled("go.syntax.update") + "\n")
} catch (e) { out.append("settings: " + e + "\n") }
String(out)
