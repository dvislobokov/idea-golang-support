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

// Shared ML completion engine (git subtree of https://github.com/dvislobokov/idea-ml-completion under ml/; ml/docs/ADAPTER.md).
// Only ml-core (pure Kotlin) is part of the build; ml-train and tools stay offline-only.
include(":ml-core")
project(":ml-core").projectDir = file("ml/ml-core")

