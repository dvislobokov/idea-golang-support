// Parameter info (Ctrl+P) with the caret put inside the first __TEXT__ of the selected editor: invokes the action on the EDT, waits,
// and reports the parameter-info controller of the editor at the caret (its handler tells the source) and the text of its hint.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.openapi.actionSystem.ActionManager)
importClass(com.intellij.openapi.actionSystem.ActionPlaces)
importClass(com.intellij.openapi.actionSystem.AnActionEvent)
importClass(com.intellij.openapi.actionSystem.DataContext)
importClass(com.intellij.openapi.actionSystem.CommonDataKeys)
importClass(com.intellij.openapi.actionSystem.impl.SimpleDataContext)
importClass(com.intellij.codeInsight.hint.ParameterInfoController)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
var document = editor.getDocument()
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    editor.getCaretModel().moveToOffset(String(document.getText()).indexOf("__TEXT__") + 1)
    editor.getContentComponent().requestFocusInWindow()
    var action = ActionManager.getInstance().getAction("ParameterInfo")
    var psi = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(document)
    var context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor).add(CommonDataKeys.PSI_FILE, psi).build()
    action.actionPerformed(AnActionEvent.createFromAnAction(action, null, ActionPlaces.KEYBOARD_SHORTCUT, context))
    done.complete(true)
} }))
done.get(10, TimeUnit.SECONDS)
java.lang.Thread.sleep(3000)
var result = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var out = ""
    try {
        // the list of controllers lives in the editor's user data (ParameterInfoControllerBase.ALL_CONTROLLERS_KEY, not public)
        var keyField = null, kc = java.lang.Class.forName("com.intellij.codeInsight.hint.ParameterInfoControllerBase")
        var fields = kc.getDeclaredFields()
        for (var fi = 0; fi < fields.length; fi++) if (String(fields[fi].getName()).indexOf("CONTROLLERS") >= 0) keyField = fields[fi]
        if (keyField == null) throw "no controllers key field"
        keyField.setAccessible(true)
        var all = editor.getUserData(keyField.get(null))
        var controller = (all == null || all.size() == 0) ? null : all.get(0)
        if (controller == null) out = "no parameter info controller in the editor"
        else {
            out = "controller: " + controller.getClass().getName() + "\n"
            var f = null
            var c = controller.getClass()
            while (c != null && f == null) { try { f = c.getDeclaredField("myHandler") } catch (e) { c = c.getSuperclass() } }
            if (f != null) { f.setAccessible(true); out += "handler: " + f.get(controller).getClass().getName() + "\n" }
            var cmp = null
            c = controller.getClass()
            while (c != null && cmp == null) { try { cmp = c.getDeclaredField("myComponent") } catch (e) { c = c.getSuperclass() } }
            if (cmp != null) {
                cmp.setAccessible(true)
                var comp = cmp.get(controller)
                var texts = []
                function walk(x) { if (x == null) return; if (x instanceof javax.swing.JLabel || x instanceof javax.swing.JEditorPane || x instanceof javax.swing.text.JTextComponent) texts.push(String(x.getText()).replace(/<[^>]*>/g, "")); if (x instanceof java.awt.Container) { var cs = x.getComponents(); for (var i = 0; i < cs.length; i++) walk(cs[i]) } }
                walk(comp)
                out += "hint: " + texts.join(" | ").substring(0, 200) + "\n"
            }
        }
    } catch (e) { out = "error: " + e }
    result.complete(out)
} }))
result.get(10, TimeUnit.SECONDS)
