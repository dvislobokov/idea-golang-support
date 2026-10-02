// Navigation and usages of the declaration at the first __TEXT__ of the selected editor, as the IDE runs them: the target element, the
// Find Usages handler the platform picks and the usages it finds (its own search + every custom usage searcher), the implementations
// (Go to Implementation), the type declarations (Go to Type Declaration), the Go to Super handler for Go, and the gutter markers of the file.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.codeInsight.TargetElementUtil)
importClass(com.intellij.find.FindManager)
importClass(com.intellij.find.findUsages.CustomUsageSearcher)
importClass(com.intellij.find.findUsages.FindUsagesOptions)
importClass(com.intellij.psi.search.searches.DefinitionsScopedSearch)
importClass(com.intellij.codeInsight.navigation.actions.GotoTypeDeclarationAction)
importClass(com.intellij.lang.CodeInsightActions)
importClass(com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl)
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
function where(e) {
    return ReadAction.compute(function () {
        var f = e.getContainingFile()
        if (f == null) return String(e)
        var doc = PsiDocumentManager.getInstance(project).getDocument(f)
        return f.getName() + ":" + (doc.getLineNumber(e.getTextOffset()) + 1)
    })
}
var target = ReadAction.compute(function () { return TargetElementUtil.getInstance().findTargetElement(editor, TargetElementUtil.getInstance().getAllAccepted(), editor.getCaretModel().getOffset()) })
var out = "target: " + (target == null ? "null" : target.getClass().getSimpleName() + " " + where(target)) + "\n"
if (target != null) {
    var handler = FindManager.getInstance(project).getFindUsagesManager().getFindUsagesHandler(target, false)
    out += "handler: " + (handler == null ? "null" : handler.getClass().getName()) + "\n"
    var own = new java.util.ArrayList()
    if (handler != null) {
        var options = ReadAction.compute(function () { return handler.getFindUsagesOptions() })
        ReadAction.run(function () { handler.processElementUsages(target, new Processor({ process: function (u) { own.add(u); return true } }), options) })
    }
    out += "handler usages: " + own.size() + "\n"
    for (var i = 0; i < own.size(); i++) out += "  " + where(own.get(i).getElement()) + "\n"
    var custom = new java.util.ArrayList()
    var options2 = ReadAction.compute(function () { return new FindUsagesOptions(project) })
    var searchers = CustomUsageSearcher.EP_NAME.getExtensionList()
    for (var s = 0; s < searchers.size(); s++) searchers.get(s).processElementUsages(target, new Processor({ process: function (u) { custom.add(u); return true } }), options2)
    out += "custom searcher usages: " + custom.size() + "\n"
    for (var c = 0; c < custom.size(); c++) {
        var info = custom.get(c).getUsageInfo()
        out += ReadAction.compute(function () { var doc = PsiDocumentManager.getInstance(project).getDocument(info.getFile()); return "  " + info.getFile().getName() + ":" + (doc.getLineNumber(info.getNavigationOffset()) + 1) + "\n" })
    }
    var impls = ReadAction.compute(function () { return DefinitionsScopedSearch.search(target).findAll().toArray() })
    out += "implementations: " + impls.length + "\n"
    for (var k = 0; k < impls.length; k++) out += "  " + impls[k].getClass().getSimpleName() + " " + where(impls[k]) + "\n"
    var types = ReadAction.compute(function () { return GotoTypeDeclarationAction.findSymbolTypes(editor, editor.getCaretModel().getOffset()) })
    out += "type declarations: " + (types == null ? "null" : types.length) + "\n"
    if (types != null) for (var t = 0; t < types.length; t++) out += "  " + where(types[t]) + "\n"
}
var psi = ReadAction.compute(function () { return PsiDocumentManager.getInstance(project).getPsiFile(document) })
out += "goto super handler: " + CodeInsightActions.GOTO_SUPER.forLanguage(psi.getLanguage()).getClass().getName() + "\n"
var markers = ReadAction.compute(function () { return DaemonCodeAnalyzerImpl.getLineMarkers(document, project) })
out += "line markers: " + markers.size() + "\n"
for (var m = 0; m < markers.size(); m++) out += "  line " + (document.getLineNumber(markers.get(m).startOffset) + 1) + " " + markers.get(m).getLineMarkerTooltip() + "\n"
out
