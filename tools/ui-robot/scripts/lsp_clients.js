// Every LSP client of the last open project: its state, descriptor, whether the server supports rename, and the rename customizer.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.platform.lsp.api.LspClientManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var manager = LspClientManager.getInstance(project)
var out = ""
var methods = manager.getClass().getMethods()
var names = []
for (var i = 0; i < methods.length; i++) if (String(methods[i].getName()).indexOf("lient") >= 0) names.push(String(methods[i].getName()) + "/" + methods[i].getParameterCount())
out += "manager methods: " + names.join(", ") + "\n"
var clients = null
for (var i = 0; i < methods.length; i++) if (String(methods[i].getName()) == "getClients" && methods[i].getParameterCount() == 0) clients = methods[i].invoke(manager)
if (clients == null) for (var i = 0; i < methods.length; i++) if (String(methods[i].getName()) == "getAllClients" && methods[i].getParameterCount() == 0) clients = methods[i].invoke(manager)
if (clients == null) return out + "no zero-arg clients getter"
var arr = clients.toArray()
out += "clients: " + arr.length + "\n"
for (var k = 0; k < arr.length; k++) {
    var c = arr[k]
    out += "  " + c.getDescriptor().getClass().getSimpleName() + " state=" + c.getState()
    try { out += " supportsRename=" + c.supportsRename() } catch (e) { out += " supportsRename=? (" + e + ")" }
    try { out += " renameCustomizer=" + c.getDescriptor().getLspCustomization().getRenameCustomizer().getClass().getSimpleName() } catch (e) {}
    out += "\n"
}
out
