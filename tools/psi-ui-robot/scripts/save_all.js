// Saves every document, on EDT.
importClass(com.intellij.openapi.application.ApplicationManager)
importClass(com.intellij.openapi.fileEditor.FileDocumentManager)
importClass(java.util.concurrent.CompletableFuture)
importClass(java.util.concurrent.TimeUnit)
var done = new CompletableFuture()
ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({ run: function () { FileDocumentManager.getInstance().saveAllDocuments(); done.complete("saved") } }))
done.get(30, TimeUnit.SECONDS)
