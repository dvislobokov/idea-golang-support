// The text of the console of the run tab named __NAME__ (its first __LINES__ lines).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.execution.ui.RunContentManager)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var descriptors = RunContentManager.getInstance(project).getAllDescriptors()
var out = "no run tab named __NAME__"
for (var d = 0; d < descriptors.size(); d++) {
    var descriptor = descriptors.get(d)
    if (String(descriptor.getDisplayName()) != "__NAME__") continue
    var console = descriptor.getExecutionConsole()
    var view = console
    try { view = console.getClass().getMethod("getConsole").invoke(console) } catch (e) {}
    var text = String(view.getText())
    var lines = text.split("\n")
    out = "terminated=" + (descriptor.getProcessHandler() == null ? "?" : descriptor.getProcessHandler().isProcessTerminated()) + ", " + lines.length + " lines:\n" + lines.slice(0, __LINES__).join("\n")
}
out
