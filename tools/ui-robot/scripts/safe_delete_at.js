// Safe Delete of the declaration at the first occurrence of __ANCHOR__ (+__AT__ characters) in the selected editor through the platform's
// SafeDeleteHandler, as Alt+Delete does (search in comments off); returns at once. A conflicts dialog may stay open: press one of its
// buttons with dialog_button.js.
importClass(com.intellij.openapi.project.ProjectManager)
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.application.ModalityState)
importClass(com.intellij.openapi.fileEditor.FileEditorManager)
importClass(com.intellij.psi.PsiDocumentManager)
importClass(com.intellij.psi.util.PsiTreeUtil)
importClass(com.intellij.refactoring.safeDelete.SafeDeleteHandler)
var projects = ProjectManager.getInstance().getOpenProjects()
var project = projects[projects.length - 1]
var found = "?"
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () {
    var editor = FileEditorManager.getInstance(project).getSelectedTextEditor()
    var doc = editor.getDocument()
    var offset = ("" + doc.getText()).indexOf("__ANCHOR__") + __AT__
    var file = PsiDocumentManager.getInstance(project).getPsiFile(doc)
    var target = PsiTreeUtil.getParentOfType(file.findElementAt(offset), com.intellij.psi.PsiNamedElement, false)
    found = target == null ? "nothing" : target.getClass().getSimpleName() + " " + target.getName()
    if (target != null) { var arr = java.lang.reflect.Array.newInstance(com.intellij.psi.PsiElement, 1); arr[0] = target; SafeDeleteHandler.invoke(project, arr, false) }
} }), ModalityState.nonModal())
java.lang.Thread.sleep(4000)
"safe delete: " + found
