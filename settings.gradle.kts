rootProject.name = "idea-golang-support"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// go-psi (native Go PSI, docs/PSI-README.md): library modules, not yet consumed by the plugin (root project).
include(
    "go-psi-core",
    "go-psi-semantic",
    "go-psi-ide",
)
