// Shooting conditions: product and build, keymap, theme, editor scheme, license state, Go SDK of the project, enabled non-bundled plugins.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ApplicationInfo)
importClass(com.intellij.openapi.project.ProjectManager)
var out = new java.lang.StringBuilder("@@@")
ApplicationManager.getApplication().invokeAndWait(new java.lang.Runnable({ run: function () {
    var ai = ApplicationInfo.getInstance()
    out.append("product: " + ai.getFullApplicationName() + " build " + ai.getBuild().asString() + "\n")
    var km = com.intellij.openapi.keymap.KeymapManager.getInstance().getActiveKeymap()
    out.append("keymap: " + km.getName() + (km.getParent() ? " (parent " + km.getParent().getName() + ")" : "") + "\n")
    try { out.append("theme: " + com.intellij.ide.ui.LafManager.getInstance().getCurrentUIThemeLookAndFeel().getName() + "\n") } catch (e) { out.append("theme: ? " + e + "\n") }
    out.append("editor scheme: " + com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme().getName() + "\n")
    out.append("new UI: " + com.intellij.ui.NewUI.isEnabled() + "\n")
    try {
        var lf = com.intellij.ui.LicensingFacade.getInstance()
        out.append("license: " + (lf == null ? "facade null" : "licensedTo=" + lf.getLicensedToMessage()) + "\n")
    } catch (e) { out.append("license: ? " + e + "\n") }
    var ps = ProjectManager.getInstance().getOpenProjects()
    var project = ps[ps.length - 1]
    out.append("project: " + project.getBasePath() + "\n")
    var ids = com.intellij.ide.plugins.PluginManagerCore.getPlugins()
    var custom = []
    var disabled = 0
    for (var i = 0; i < ids.length; i++) { if (!ids[i].isBundled()) custom.push(ids[i].getName() + " " + ids[i].getVersion()); if (!ids[i].isEnabled()) disabled++ }
    out.append("plugins: " + ids.length + " loaded descriptors, disabled " + disabled + ", non-bundled: " + custom.join(", ") + "\n")
} }))
out.toString()
