importClass(com.intellij.openapi.application.ApplicationManager)
var m = ApplicationManager.getApplication().getService(cls("io.github.golangsupport.ml.GoMlModels"))
m.reset(); java.lang.Thread.sleep(500); "after reset: " + m.nnStatus("")
