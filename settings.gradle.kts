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

// Shared ML completion engine: ml-core/ is a plain copy of the pure-Kotlin ml-core module of
// https://github.com/dvislobokov/idea-ml-completion (refreshed with tools/ml/sync-ml-core.sh; the training CLI and the corpus
// tools stay in that repository). Contract for the adapter: docs/ADAPTER.md there.
include(":ml-core")

