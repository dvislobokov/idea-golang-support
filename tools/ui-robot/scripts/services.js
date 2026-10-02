// The implementations behind the application services of go-psi the plugin overrides: toolchain provider, library roots policy, feature gate.
importClass(com.intellij.openapi.application.ApplicationManager)
var names = ["io.github.golangsupport.project.api.GoToolchainProvider", "io.github.golangsupport.project.impl.GoLibraryRootsPolicy", "io.github.golangsupport.ide.GoIdeFeatureGate"]
var out = ""
for (var i = 0; i < names.length; i++) {
    var service = ApplicationManager.getApplication().getService(cls(names[i]))
    out += names[i].replace(/.*\./, "") + " -> " + (service == null ? "null" : service.getClass().getName()) + "\n"
}
out
