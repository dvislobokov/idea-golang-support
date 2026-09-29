// Why the platform finds no target at __TEXT__: the evaluator of the language, what it answers, and what getNamedElement of the platform says.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.TargetElementUtil)
importClass(com.intellij.lang.LanguageExtension)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
var document = editor.getDocument()
var moved = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { editor.getCaretModel().moveToOffset(String(document.getText()).indexOf("__TEXT__") + 1); moved.complete(true) } }))
moved.get(10, TimeUnit.SECONDS)
ReadAction.compute(function () {
    var offset = editor.getCaretModel().getOffset()
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(document)
    var leaf = psi.findElementAt(offset)
    var out = "leaf: " + leaf + " '" + leaf.getText() + "' language=" + psi.getLanguage() + "\n"
    var extension = new LanguageExtension("com.intellij.targetElementEvaluator")
    var evaluator = extension.forLanguage(psi.getLanguage())
    out += "evaluator: " + evaluator + "\n"
    if (evaluator != null) {
        try { out += "evaluator.getNamedElement: " + evaluator.getNamedElement(leaf) + "\n" } catch (e) { out += "evaluator threw: " + e + "\n" }
    }
    try { out += "platform getNamedElement: " + TargetElementUtil.getInstance().getNamedElement(leaf, offset - leaf.getTextRange().getStartOffset()) + "\n" } catch (e) { out += "platform threw: " + e + "\n" }
    try { out += "findTargetElement: " + TargetElementUtil.findTargetElement(editor, TargetElementUtil.ELEMENT_NAME_ACCEPTED | TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED) + "\n" } catch (e) { out += "findTargetElement threw: " + e + "\n" }
    return out
})
