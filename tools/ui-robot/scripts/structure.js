// The structure view tree of the selected editor, two levels deep.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ReadAction)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.lang.LanguageStructureViewBuilder)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
ReadAction.compute(function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var psi = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument())
    var builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psi)
    var model = builder.createStructureViewModel(editor)
    let out = ""
    var top = model.getRoot().getChildren()
    for (let i = 0; i < top.length; i++) {
        out += top[i].getPresentation().getPresentableText() + "\n"
        var kids = top[i].getChildren()
        for (let j = 0; j < kids.length; j++) out += "    " + kids[j].getPresentation().getPresentableText() + "\n"
    }
    model.dispose()
    return out
})
