// Appends __APPEND__ to the selected editor (`<NL>` stands for a line break: sed would turn `\n` into a real one inside the string), puts the caret at the end,
// invokes basic completion and prints the lookup items.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.command.WriteCommandAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.codeInsight.completion.CodeCompletionHandlerBase)
importClass(com.intellij.codeInsight.completion.CompletionType)
importClass(com.intellij.codeInsight.lookup.LookupManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var typed = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        var document = editor.getDocument()
        var appended = "__APPEND__".split("<NL>").join("\n")
        WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { document.insertString(document.getTextLength(), appended) } }))
        editor.getCaretModel().moveToOffset(document.getTextLength())
        // a lookup hides when its editor has no focus: ask for it first, and invoke as the user does (explicitly)
        com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(editor.getContentComponent(), true)
        new CodeCompletionHandlerBase(CompletionType.BASIC, true, false, true).invokeCompletion(project, editor)
        typed.complete("invoked")
    } catch (e) { typed.complete("failed: " + e) }
} }))
var first = typed.get(30, TimeUnit.SECONDS)
java.lang.Thread.sleep(__WAIT__)
var read = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    try {
        var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
        var lookup = LookupManager.getActiveLookup(editor)
        if (lookup == null) { read.complete("no lookup"); return }
        var items = lookup.getItems()
        var text = "items: " + items.size()
        for (var i = 0; i < Math.min(items.size(), 15); i++) text += "\n  " + items.get(i).getLookupString()
        lookup.hideLookup(true)
        read.complete(text)
    } catch (e) { read.complete("failed: " + e) }
} }), ModalityState.any())
first + "\n" + read.get(30, TimeUnit.SECONDS)
