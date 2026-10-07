importClass(com.intellij.openapi.application.ApplicationManager)
var app = ApplicationManager.getApplication()
app.getService(cls("io.github.golangsupport.ml.GoMlModels")).status(app.getService(cls("io.github.golangsupport.ml.GoMlSettings")).getModelDirectory())
