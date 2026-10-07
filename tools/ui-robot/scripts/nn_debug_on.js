importClass(com.intellij.openapi.application.ApplicationManager)
var s = ApplicationManager.getApplication().getService(cls("io.github.golangsupport.ml.GoMlSettings"))
s.setInlineDebugLog(true); s.setInlineThreshold(0.7)
"debug log " + s.getInlineDebugLog()
