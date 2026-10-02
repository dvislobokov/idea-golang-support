// The rename handlers of the platform that are available with the caret put inside the first __TEXT__ of the selected editor
// (what Shift+F6 would pick: one handler means no chooser dialog), plus the Go formatter setting for reference.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.openapi.actionSystem.CommonDataKeys)
importClass(com.intellij.openapi.actionSystem.impl.SimpleDataContext)
importClass(com.intellij.refactoring.rename.RenameHandler)
importClass(com.intellij.codeInsight.TargetElementUtil)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
var document = editor.getDocument()
var moved = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { editor.getCaretModel().moveToOffset(String(document.getText()).indexOf("__TEXT__") + 1); moved.complete(true) } }))
moved.get(10, TimeUnit.SECONDS)
importClass(com.intellij.ide.DataManager)
var result = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var out = ""
    try {
        var context = DataManager.getInstance().getDataContext(editor.getContentComponent())
        var element = context.getData(CommonDataKeys.PSI_ELEMENT)
        out = "element: " + element + "\navailable handlers:\n"
        var handlers = RenameHandler.EP_NAME.getExtensionList()
        for (var i = 0; i < handlers.size(); i++) {
            var h = handlers.get(i)
            var ok
            try { ok = h.isAvailableOnDataContext(context) } catch (e) { ok = "error " + e }
            if (ok !== false) out += "  " + h.getClass().getName() + (ok === true ? "" : " -> " + ok) + "\n"
        }
    } catch (e) { out = "error: " + e }
    result.complete(out)
} }))
result.get(20, TimeUnit.SECONDS)
