// Classes of the plugin live in its own class loader: Rhino resolves `io.github...` against the loader of the robot server and fails.
importClass(com.intellij.ide.plugins.PluginManagerCore)
importClass(com.intellij.openapi.extensions.PluginId)
const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId("io.github.golangsupport")).getPluginClassLoader()
function cls(name) { return java.lang.Class.forName(name, true, pluginLoader) }
function kotlinObject(name) { return cls(name).getField("INSTANCE").get(null) }
