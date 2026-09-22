// What Alt+Enter would show with the caret inside the first occurrence of __TEXT__ in the selected editor; when __INVOKE__ is the text of
// one of the actions, that action is invoked and the lines around the caret are printed afterwards.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.daemon.impl.ShowIntentionsPass)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
const projects = ProjectManager.getInstance().getOpenProjects()
const project = projects[projects.length - 1]
const editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
const document = editor.getDocument()
const moved = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    editor.getCaretModel().moveToOffset(String(document.getText()).indexOf("__TEXT__") + 1)
    moved.complete(true)
} }))
moved.get(10, TimeUnit.SECONDS)

const found = ReadAction.compute(function () {
    const psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    const info = ShowIntentionsPass.getActionsToShow(editor, psi)
    const all = new java.util.ArrayList()
    all.addAll(info.errorFixesToShow); all.addAll(info.inspectionFixesToShow); all.addAll(info.intentionsToShow)
    return [psi, all]
})
var out = "actions: " + found[1].size() + "\n"
var chosen = null
for (var i = 0; i < found[1].size(); i++) {
    var action = found[1].get(i).getAction()
    var text = ReadAction.compute(function () { return String(action.getText()) })
    out += "  " + text + "\n"
    if (text == "__INVOKE__") chosen = action
}
if (chosen != null) {
    const done = new CompletableFuture()
    ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
        try {
            if (chosen.startInWriteAction()) com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, new java.lang.Runnable({ run: function () { chosen.invoke(project, editor, found[0]) } }))
            else chosen.invoke(project, editor, found[0])
            done.complete("ok")
        } catch (e) { done.complete("threw " + e) }
    } }))
    out += "invoked __INVOKE__: " + done.get(60, TimeUnit.SECONDS) + "\n"
    out += ReadAction.compute(function () {
        const line = document.getLineNumber(editor.getCaretModel().getOffset())
        const from = Math.max(0, line - 2), to = Math.min(document.getLineCount() - 1, line + 3)
        return String(document.getText().substring(document.getLineStartOffset(from), document.getLineEndOffset(to))).split("\t").join("<TAB>")
    }) + "\n"
}
out
