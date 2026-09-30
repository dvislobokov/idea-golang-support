// Activates the Build tool window and prints what it shows: the rows of its tree and the text of its console.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.build.BuildContentManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
function collect(c, depth, out) {
    if (c instanceof javax.swing.JTree) for (var i = 0; i < c.getRowCount(); i++) out.push("tree: " + c.getPathForRow(i).getLastPathComponent())
    if (c instanceof com.intellij.execution.impl.ConsoleViewImpl) out.push("console:\n" + c.getText())
    if (c instanceof java.awt.Container && depth < 40) { var kids = c.getComponents(); for (var i = 0; i < kids.length; i++) collect(kids[i], depth + 1, out) }
}
var shown = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    // the window is made on first use
    var window = BuildContentManager.getInstance(project).getOrCreateToolWindow()
    window.activate(new java.lang.Runnable({ run: function () { shown.complete("shown") } }))
} }))
shown.get(20, TimeUnit.SECONDS)
java.lang.Thread.sleep(1500)
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var out = []
    var window = BuildContentManager.getInstance(project).getOrCreateToolWindow()
    var contents = window.getContentManager().getContents()
    for (var i = 0; i < contents.length; i++) { out.push("content: " + contents[i].getDisplayName()); collect(contents[i].getComponent(), 0, out) }
    done.complete(out.join("\n"))
} }))
done.get(20, TimeUnit.SECONDS)
