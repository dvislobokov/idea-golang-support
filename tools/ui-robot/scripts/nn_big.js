// Switches the grey-text network to the bundled big model (__BIG__ = true/false), drops a models directory override, reloads and prints the status.
importClass(com.intellij.openapi.application.ApplicationManager)
var app = ApplicationManager.getApplication()
var settings = app.getService(cls("io.github.golangsupport.ml.GoMlSettings"))
var models = app.getService(cls("io.github.golangsupport.ml.GoMlModels"))
settings.setModelDirectory("")
settings.setInlineBigModel(__BIG__)
models.reset()
models.nn("")
var status = ""
for (var i = 0; i < 60; i++) {
    java.lang.Thread.sleep(500)
    status = models.nnStatus("")
    if (status.indexOf("model ") == 0) break
}
"big " + settings.getInlineBigModel() + " | " + status
