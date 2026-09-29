// The test tree of the last Run tab: names and statuses, and the recorded statuses of the plugin.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.execution.ui.RunContentManager)
importClass(com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var descriptors = RunContentManager.getInstance(project).getAllDescriptors()
let out = "descriptors: " + descriptors.size() + "\n"
for (let d = 0; d < descriptors.size(); d++) {
    var descriptor = descriptors.get(d)
    var console = descriptor.getExecutionConsole()
    out += "  " + descriptor.getDisplayName() + " terminated=" + (descriptor.getProcessHandler() == null ? "?" : descriptor.getProcessHandler().isProcessTerminated()) + "\n"
    if (console instanceof SMTRunnerConsoleView) {
        var root = console.getResultsViewer().getTestsRootNode()
        function walk(node, depth) {
            var kids = node.getChildren()
            for (let i = 0; i < kids.size(); i++) {
                var k = kids.get(i)
                out += "    " + "  ".repeat(depth) + k.getName() + " [" + k.getMagnitudeInfo() + "]\n"
                walk(k, depth + 1)
            }
        }
        walk(root, 0)
    }
}
out
