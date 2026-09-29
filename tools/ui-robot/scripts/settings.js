// Sets the property __NAME__ of the settings of the plugin to __VALUE__ (a JS expression: true, false, a string) and prints the value read back.
importClass(com.intellij.openapi.application.ApplicationManager)
var settings = ApplicationManager.getApplication().getService(cls("io.github.golangsupport.settings.GoSettings"))
var value = __VALUE__
var setter = null, getter = null
var methods = settings.getClass().getMethods()
for (var i = 0; i < methods.length; i++) {
    if (String(methods[i].getName()) == "set__NAME__" && methods[i].getParameterCount() == 1) setter = methods[i]
    if (String(methods[i].getName()) == "get__NAME__" && methods[i].getParameterCount() == 0) getter = methods[i]
}
if (setter == null) throw new java.lang.IllegalArgumentException("no setter for __NAME__")
var type = setter.getParameterTypes()[0]
var converted = value
if (String(type.getName()) == "boolean") converted = java.lang.Boolean.valueOf(value)
else if (type.isEnum()) converted = java.lang.Enum.valueOf(type, String(value))
setter.invoke(settings, [converted])
"__NAME__ = " + getter.invoke(settings, [])
