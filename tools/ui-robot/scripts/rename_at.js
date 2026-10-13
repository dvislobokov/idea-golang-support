// Renames the declaration at the first occurrence of __ANCHOR__ (+__AT__ characters) in the selected editor to __NEW__ through the
// platform's RenameProcessor, as the Rename dialog does; returns at once (a conflicts dialog may stay open: read it with dlg-like
// scripts, close it with dialog_ok.js or by disposing it). Prints what was found at the caret.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.psi.util.PsiTreeUtil)
importClass(com.intellij.refactoring.rename.RenameProcessor)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var found = "?"
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var doc = editor.getDocument()
    var offset = ("" + doc.getText()).indexOf("__ANCHOR__") + __AT__
    var file = PsiDocumentManager.getInstance(project).getPsiFile(doc)
    var leaf = file.findElementAt(offset)
    var target = PsiTreeUtil.getParentOfType(leaf, com.intellij.psi.PsiNamedElement, false)
    found = target == null ? "nothing" : target.getClass().getSimpleName() + " " + target.getName()
    if (target != null) new RenameProcessor(project, target, "__NEW__", false, false).run()
} }), ModalityState.nonModal())
java.lang.Thread.sleep(4000)
"renaming: " + found
