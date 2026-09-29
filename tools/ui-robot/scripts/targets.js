// From the caret inside the first __TEXT__ of the selected editor: the target element the platform finds (Find Usages / Go to Implementation start from it),
// the type declarations (Go to Type Declaration) and the type info hint (Ctrl+Shift+P).
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.TargetElementUtil)
importClass(com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider)
importClass(com.intellij.lang.LanguageExpressionTypes)
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
    let out = "leaf: " + leaf + "\n"
    var target = TargetElementUtil.getInstance().findTargetElement(editor, TargetElementUtil.getInstance().getAllAccepted(), offset)
    out += "target: " + target + (target == null ? "" : " in " + target.getContainingFile().getName() + ":" + (PsiDocumentManager.getInstance(project).getDocument(target.getContainingFile()).getLineNumber(target.getTextOffset()) + 1)) + "\n"
    var providers = TypeDeclarationProvider.EP_NAME.getExtensionList()
    for (let i = 0; i < providers.size(); i++) {
        var found = providers.get(i).getSymbolTypeDeclarations(target == null ? leaf : target)
        if (found != null && found.length > 0) out += "type declaration: " + found[0] + " in " + found[0].getContainingFile().getName() + " (" + providers.get(i).getClass().getSimpleName() + ")\n"
    }
    var typeProviders = LanguageExpressionTypes.INSTANCE.allForLanguage(psi.getLanguage())
    for (let i = 0; i < typeProviders.size(); i++) {
        var expressions = typeProviders.get(i).getExpressionsAt(leaf)
        if (expressions.size() > 0) out += "type info: " + typeProviders.get(i).getInformationHint(expressions.get(0)) + "\n"
    }
    return out
})
