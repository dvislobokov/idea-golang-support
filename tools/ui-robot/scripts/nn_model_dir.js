// Points the grey-text network at the models directory __DIR__ (empty: the bundled pair), reloads it and prints the status.
importClass(com.intellij.openapi.application.ApplicationManager)
var app = ApplicationManager.getApplication()
var settings = app.getService(cls("io.github.golangsupport.ml.GoMlSettings"))
var models = app.getService(cls("io.github.golangsupport.ml.GoMlModels"))
settings.setModelDirectory("__DIR__")
models.reset()
models.nn(settings.getModelDirectory())   // starts the load on the model thread
var status = ""
for (var i = 0; i < 60; i++) {
    java.lang.Thread.sleep(500)
    status = models.nnStatus(settings.getModelDirectory())
    if (status.indexOf("model ") == 0) break
}
"directory [" + settings.getModelDirectory() + "] | " + status
