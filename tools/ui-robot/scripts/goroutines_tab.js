// The Goroutines tab of the current debug session: selects it and prints the rows of its tree (__HIDE_RUNTIME__ = true/false sets the checkbox first).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.xdebugger.XDebuggerManager)
importClass(com.intellij.ui.components.JBCheckBox)
importClass(javax.swing.JTree)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var session = XDebuggerManager.getInstance(project).getCurrentSession()
        if (session == null) { done.complete("no session"); return }
        var ui = session.getUI()
        var content = ui.findContent("GoGoroutines")
        if (content == null) { done.complete("no Goroutines content; contents: " + ui.getContents().length); return }
        ui.selectAndFocus(content, false, false)
        var panel = content.getComponent()
        function find(c, type) { if (c instanceof type) return c; if (c instanceof java.awt.Container) { var k = c.getComponents(); for (var i = 0; i < k.length; i++) { var f = find(k[i], type); if (f != null) return f } } return null }
        var box = find(panel, JBCheckBox)
        if (box != null && box.isSelected() != __HIDE_RUNTIME__) box.doClick()
        var tree = find(panel, JTree)
        var text = "content: " + content.getDisplayName() + "; hideRuntime=" + (box == null ? "?" : box.isSelected()) + "\n"
        for (var i = 0; i < tree.getRowCount(); i++) {
            var path = tree.getPathForRow(i)
            var node = path.getLastPathComponent()
            var item = node.getUserObject()
            var label = String(item)
            try {
                var f = item.getClass().getDeclaredFields()
                var parts = []
                for (var j = 0; j < f.length; j++) { f[j].setAccessible(true); var v = f[j].get(item); parts.push(f[j].getName() + "=" + (v == null ? "null" : (v.getClass().getName().indexOf("GoExecutionStack") >= 0 ? v.getDisplayName() : String(v)))) }
                label = item.getClass().getSimpleName() + "{" + parts.join(", ") + "}"
            } catch (e) {}
            text += "  ".repeat(path.getPathCount() - 1) + label + "\n"
        }
        done.complete(text)
    } catch (e) { done.complete("failed: " + e + (e.stack ? "\n" + e.stack : "")) }
} }), ModalityState.any())
done.get(30, TimeUnit.SECONDS)
