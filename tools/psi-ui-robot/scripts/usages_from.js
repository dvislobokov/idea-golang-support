// Find Usages as the IDE would start it from the caret inside the first __TEXT__ of the selected editor: the target element the platform
// finds, whether the find usages provider accepts it, and what the custom usage searchers return for it.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.TargetElementUtil)
importClass(com.intellij.lang.findUsages.LanguageFindUsages)
importClass(com.intellij.find.findUsages.CustomUsageSearcher)
importClass(com.intellij.find.findUsages.FindUsagesOptions)
importClass(com.intellij.util.Processor)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var editor = ReadAction.compute(function () { return FileEditorManager.getInstance(project).getSelectedTextEditor() })
var document = editor.getDocument()
var moved = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { editor.getCaretModel().moveToOffset(String(document.getText()).indexOf("__TEXT__") + 1); moved.complete(true) } }))
moved.get(10, TimeUnit.SECONDS)
var target = ReadAction.compute(function () { return TargetElementUtil.getInstance().findTargetElement(editor, TargetElementUtil.getInstance().getAllAccepted(), editor.getCaretModel().getOffset()) })
var out = "target: " + target + "\n"
if (target != null) {
    out += ReadAction.compute(function () {
        var doc = PsiDocumentManager.getInstance(project).getDocument(target.getContainingFile())
        var provider = LanguageFindUsages.INSTANCE.forLanguage(target.getLanguage())
        return "  at " + target.getContainingFile().getName() + ":" + (doc.getLineNumber(target.getTextOffset()) + 1) + " '" + target.getText() + "' canFindUsages=" + provider.canFindUsagesFor(target) + " type=" + provider.getType(target) + "\n"
    })
    var usages = new java.util.ArrayList()
    var options = ReadAction.compute(function () { return new FindUsagesOptions(project) })
    var searchers = CustomUsageSearcher.EP_NAME.getExtensionList()
    for (var i = 0; i < searchers.size(); i++) searchers.get(i).processElementUsages(target, new Processor({ process: function (u) { usages.add(u); return true } }), options)
    out += "usages: " + usages.size() + "\n"
    for (var i = 0; i < usages.size(); i++) {
        var info = usages.get(i).getUsageInfo()
        out += ReadAction.compute(function () {
            var doc = PsiDocumentManager.getInstance(project).getDocument(info.getFile())
            var line = doc.getLineNumber(info.getNavigationOffset())
            return "  " + info.getFile().getName() + ":" + (line + 1) + "  " + String(doc.getText().substring(doc.getLineStartOffset(line), doc.getLineEndOffset(line))).trim() + "\n"
        })
    }
}
out
