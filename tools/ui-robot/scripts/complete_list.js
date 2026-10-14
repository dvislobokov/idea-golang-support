// Appends __APPEND__ to the selected editor (`<NL>` for a line break), invokes basic completion at the end and prints the first __N__ items
// as the list shows them: lookup string, tail and type text (`Int() math/rand : int`); "inserted: …" when a single item went in without a
// list. The appended text stays: revert the document after (reload_doc / FileDocumentManager.reloadFromDisk).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.completion.CodeCompletionHandlerBase)
importClass(com.intellij.codeInsight.completion.CompletionType)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(com.intellij.codeInsight.lookup.LookupElementPresentation)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var typed = new CompletableFuture()
var before = null
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        var document = editor.getDocument()
        var appended = "__APPEND__".split("<NL>").join("\n")
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { document.insertString(document.getTextLength(), appended) } }))
        before = "" + document.getText()
        editor.getCaretModel().moveToOffset(document.getTextLength())
        com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true)
        new CodeCompletionHandlerBase(CompletionType.BASIC, true, false, true).invokeCompletion(project, editor)
        // synchronous: the list is there now; read it before the lookup closes for a window without focus (Xvfb)
        var lookup = LookupManager.getActiveLookup(editor)
        if (lookup != null) {
            var items = lookup.getItems()
            var text = "items: " + items.size()
            for (var i = 0; i < Math.min(items.size(), __N__); i++) {
                var p = new LookupElementPresentation()
                items.get(i).renderElement(p)
                var tail = ""
                var fr = p.getTailFragments()
                for (var k = 0; k < fr.size(); k++) tail += fr.get(k).text
                text += "\n  " + p.getItemText() + tail + (p.getTypeText() ? "  : " + p.getTypeText() : "")
            }
            lookup.hideLookup(true)
            typed.complete(text)
        } else typed.complete("invoked")
    } catch (e) { typed.complete("failed: " + e) }
} }))
var first = typed.get(30, TimeUnit.SECONDS)
java.lang.Thread.sleep(__WAIT__)
var read = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        var lookup = LookupManager.getActiveLookup(editor)
        if (lookup == null) {
            var now = "" + editor.getDocument().getText()
            read.complete(now == before ? "no lookup" : "inserted: " + now.substring(before.lastIndexOf("\n") + 1).split("\n")[0])
            return
        }
        var items = lookup.getItems()
        var text = "items: " + items.size()
        for (var i = 0; i < Math.min(items.size(), __N__); i++) {
            var p = new LookupElementPresentation()
            items.get(i).renderElement(p)
            var tail = ""
            var fr = p.getTailFragments()
            for (var k = 0; k < fr.size(); k++) tail += fr.get(k).text
            text += "\n  " + p.getItemText() + tail + (p.getTypeText() ? "  : " + p.getTypeText() : "")
        }
        lookup.hideLookup(true)
        read.complete(text)
    } catch (e) { read.complete("failed: " + e) }
} }), ModalityState.any())
first + "\n" + read.get(30, TimeUnit.SECONDS)
