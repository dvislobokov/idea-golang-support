import java.security.MessageDigest
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    // Applied by the go-psi library modules (go-psi-core, go-psi-semantic, go-psi-ide); versions in gradle/libs.versions.toml.
    alias(libs.plugins.intellij.platform.module) apply false
    alias(libs.plugins.intellij.platform.grammarkit) apply false
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/** Whether the IDE at [ide] bundles the plugin [id] (its product-info.json lists it); true when unknown. IntelliJ IDEA Community has no Database plugin. */
fun ideBundles(ide: String?, id: String): Boolean =
    ide?.let { file("$it/product-info.json") }?.takeIf { it.exists() }?.readText()?.contains("\"$id\"") ?: true

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null && file(localIde).exists()) {
            local(localIde)
        } else {
            intellijIdea(providers.gradleProperty("platformVersion"))
        }
        // for the content module io.github.golangsupport.lsp only: the rest of the plugin must not touch these classes
        bundledModule("intellij.platform.lsp")
        // the coverage of the platform (go-coverage.xml, optional dependency on com.intellij.modules.coverage): package io.github.golangsupport.coverage only
        bundledModule("intellij.platform.coverage")
        bundledModule("intellij.platform.coverage.agent")
        // JSON in Go strings (go-psi-ide-injection-json.xml, optional dependency of plugin.xml)
        bundledPlugin("com.intellij.modules.json")
        // the copyright updater of Go files (go-copyright.xml, optional dependency of plugin.xml)
        bundledPlugin("com.intellij.copyright")
        // SQL in Go strings (go-psi-ide-injection-sql.xml, optional dependency of plugin.xml): only for the tests, the code finds SQL by id;
        // IntelliJ IDEA Community has no Database plugin
        if (ideBundles(localIde?.takeIf { file(it).exists() }, "com.intellij.database")) bundledPlugin("com.intellij.database")
        // GOROOT shared indexes (go-shared-indexes.xml, optional dependency of plugin.xml): package io.github.golangsupport.sharedindex only
        if (ideBundles(localIde?.takeIf { file(it).exists() }, "intellij.indexing.shared.core")) bundledPlugin("intellij.indexing.shared.core")
        testFramework(TestFrameworkType.Platform)
        // The native Go PSI (go-psi-core, go-psi-semantic, go-psi-ide; MIGRATION.md): composed, so the classes go into the main jar, which
        // a v1 descriptor loads (lib/modules only serves declared content modules). What of their META-INF/go-psi-*.xml plugin.xml
        // includes is decided per migration step; the lsp content module sees these classes (same classloader), never the other way round.
        pluginComposedModule(implementation(project(":go-psi-core")))
        pluginComposedModule(implementation(project(":go-psi-semantic")))
        pluginComposedModule(implementation(project(":go-psi-ide")))
    }
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // The platform bundles its own Kotlin stdlib (2.3.20 in 2026.1), don't use newer API.
        apiVersion = KotlinVersion.KOTLIN_2_3
        languageVersion = KotlinVersion.KOTLIN_2_3
    }
}

// `./gradlew runIdeForUiTests`: a sandbox IDE with the plugin and the Remote Robot server (https://github.com/JetBrains/intellij-ui-test-robot)
// on http://127.0.0.1:<robotPort>, for driving the UI from outside: component tree, clicks, actions, screenshots. See tools/ui-robot.
// The port is 8083 and not the 8082 of the examples: the sandbox of ../idea-dotnet-support listens there, and two agents working at
// the same time would drive (and close) each other's IDE. Another one: `-ProbotPort=8090`, and ROBOT_PORT=8090 for robot.py.
// The robot-server plugin is the one thing the build downloads (from the JetBrains plugin repository), and only for this task.
val robotPort = providers.gradleProperty("robotPort").orElse("8083")

val runIdeForUiTests by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Drobot-server.port=${robotPort.get()}",
                "-Dide.mac.message.dialogs.as.sheets=false",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Djb.consents.confirmation.enabled=false",
                "-Dide.show.tips.on.startup.default.value=false",
                "-Didea.trust.all.projects=true",
            )
        }
    }
    plugins {
        robotServerPlugin()
    }
}

// The pages of the plugin have one source each, in docs/ (opened from the repository for demos): the page about the plugin and the
// reference of its keys, actions and settings. The plugin carries them in welcome/ and shows them in an editor tab (GoPages).
tasks.processResources {
    from("docs/demo.html") {
        into("welcome")
        rename { "index.html" }
    }
    from("docs/guide.html") {
        into("welcome")
    }
}

// Smart Completion (docs/ML.md): `-PmlEnabled=true` (or MLENABLED=true in the environment) puts the ML features into the plugin —
// META-INF/go-ml.xml (the completionRanker, the grey-text inline provider and the Settings | Go | Smart Completion page), the transformer
// go-nn-31m-e2.cml with its tokenizer go-16384.bpe from ml-models/go/ (required), with `-Pml.big=true` the 50 M transformer
// go-nn-50m-e3-lr2e3.cml too (the setting "Big model" switches), the import statistics go-imports-e20.cml when present (GoImportStats:
// the plain build reads it from the model directory setting or from ml-models/go next to the plugin), and the ranker pair lm.cml / rank.cml under ml/go/: by default the
// n-gram e14-b.cml and the real-list GBDT ranker e19-rank-gbdt.cml of ml-models/go/ (MRR 0.834 vs linear e17b 0.799, rules 0.513), renamed; `-Pml.models=<dir>`
// takes lm.cml / rank.cml from that directory instead (a newer training; missing there: no ranker, only the grey text works).
// The proxy ranker e14-b-rank.cml is never shipped. A build without the flag has no trace of any of it.
val mlEnabled = providers.gradleProperty("mlEnabled").orElse(providers.environmentVariable("MLENABLED")).map { it.equals("true", ignoreCase = true) }.getOrElse(false)
if (mlEnabled) {
    val mlBig = providers.gradleProperty("ml.big").map { it.equals("true", ignoreCase = true) }.getOrElse(false)
    val mlModels = providers.gradleProperty("ml.models").map { file(it) }.orNull
    val nnDir = file("ml-models/go")
    val nnFiles = listOf("go-nn-31m-e2.cml", "go-16384.bpe") + (if (mlBig) listOf("go-nn-50m-e3-lr2e3.cml") else emptyList())
    for (name in nnFiles) check(File(nnDir, name).isFile) { "mlEnabled: $name not found in $nnDir" }
    // the corpus import statistics (GoImportStats, e20): optional, the feature is silently off without it
    val importFiles = listOf("go-imports-e20.cml").filter { File(nnDir, it).isFile }
    val bundledRanker = mapOf("e14-b.cml" to "lm.cml", "e19-rank-gbdt.cml" to "rank.cml")
    val rankerFiles = if (mlModels != null) listOf("lm.cml", "rank.cml").filter { File(mlModels, it).isFile } else bundledRanker.keys.filter { File(nnDir, it).isFile }
    tasks.processResources {
        from("src/ml/resources")
        from(nnDir) {
            include(nnFiles + importFiles)
            into("ml/go")
        }
        if (mlModels != null && rankerFiles.isNotEmpty()) from(mlModels) {
            include(rankerFiles)
            into("ml/go")
        }
        if (mlModels == null && rankerFiles.size == bundledRanker.size) from(nnDir) {
            include(rankerFiles)
            rename { bundledRanker.getValue(it) }
            into("ml/go")
        }
    }
    // the ML build is a separate file next to the plain one: idea-golang-support-<version>-ml.zip
    tasks.buildPlugin { archiveClassifier.set("ml") }
}

// delve (third_party/delve: a git submodule at a release tag, vendor/ included) ships as sources in delve/ of the plugin and is built with
// the user's go in the background (GoBundledDelve): no network, no `go install`. SOURCE-HASH names the build, so changed sources give a new
// hash and a rebuild after the plugin is updated. Tests and fixtures stay out of the ZIP.
val delveSources = fileTree("third_party/delve") {
    include("go.mod", "go.sum", "LICENSE", "cmd/**", "pkg/**", "service/**", "vendor/**")
    exclude("**/*_test.go", "**/testdata/**", "**/_fixtures/**")
}
val delveSourceHash = tasks.register("delveSourceHash") {
    val output = layout.buildDirectory.file("delve/SOURCE-HASH")
    val sources = delveSources
    inputs.files(sources)
    outputs.file(output)
    doLast {
        val files = sortedMapOf<String, File>()
        sources.visit { if (!isDirectory) files[relativePath.pathString] = file }
        check(files.isNotEmpty()) { "third_party/delve is empty: run `git submodule update --init`" }
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { (path, file) -> digest.update(path.toByteArray()); digest.update(file.readBytes()) }
        output.get().asFile.writeText(digest.digest().joinToString("") { "%02x".format(it) }.take(16))
    }
}
// every sandbox the IDE runs from (runIde, runIdeForUiTests and the one buildPlugin zips), not the test sandboxes
tasks.withType<PrepareSandboxTask>().configureEach {
    // "Test" alone also matched prepareSandbox_runIdeForUiTests: the robot's IDE ran without the bundled delve
    if (!name.contains("Test") || name.endsWith("runIdeForUiTests")) {
        from(delveSources) { into(pluginName.map { "$it/delve" }) }
        from(delveSourceHash) { into(pluginName.map { "$it/delve" }) }
    }
}

// The change-notes shown in the Plugins dialog are the latest released sections of CHANGELOG.md (the single source; [Unreleased] holds only
// the batch overview), one heading per version, rendered to the small subset of HTML the dialog accepts. No `org.jetbrains.changelog` plugin: adding one would need a fresh resolve from the plugin
// portal, which the proxy on this machine blocks (`--offline` builds).
fun latestChangeNotes(): String {
    val versions = 7
    val lines = file("CHANGELOG.md").readLines()
    val start = lines.indexOfFirst { it.startsWith("## [") && !it.startsWith("## [Unreleased]") }
    if (start < 0) return ""
    var seen = 0
    val body = lines.drop(start).takeWhile { !(it.startsWith("## [") && ++seen > versions) }
    fun inline(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
    val html = StringBuilder()
    var inList = false
    var item: StringBuilder? = null
    fun flushItem() { item?.let { html.append("<li>").append(inline(it.toString())).append("</li>") }; item = null }
    fun closeList() { flushItem(); if (inList) { html.append("</ul>"); inList = false } }
    for (raw in body) {
        val line = raw.trim()
        when {
            line.isEmpty() -> {}
            line.startsWith("## [") -> { closeList(); html.append("<h3>").append(inline(line.removePrefix("## ").replace("[", "").replace("]", ""))).append("</h3>") }
            line.startsWith("### ") -> { closeList(); html.append("<p><b>").append(inline(line.removePrefix("### "))).append("</b></p>") }
            line.startsWith("- ") -> { flushItem(); if (!inList) { html.append("<ul>"); inList = true }; item = StringBuilder(line.removePrefix("- ")) }
            // a wrapped line of a list item continues it
            item != null && raw.startsWith(" ") -> item!!.append(' ').append(line)
            else -> { closeList(); html.append("<p>").append(inline(line)).append("</p>") }
        }
    }
    closeList()
    return html.toString()
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        changeNotes = provider { latestChangeNotes() }
        ideaVersion {
            // 2026.1: the platform with the LSP client API under its new names (LspIntegrationProvider) and with the DAP module
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
    // `./gradlew.bat verifyPlugin --offline`: against the installed IDE only (the target of the plugin; nothing is downloaded through the proxy of this machine).
    pluginVerification {
        ides {
            providers.gradleProperty("localIdePath").orNull?.takeIf { file(it).exists() }?.let { local(file(it)) }
        }
        // What fails the build: incompatibilities. Internal and override-only usages of the older code (BuildViewCommandOutput, the
        // wizard, breakpoint types) stay in the report (build/reports/pluginVerifier) as warnings to work off; a new one shows up there.
        failureLevel = listOf(VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS, VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES, VerifyPluginTask.FailureLevel.INVALID_PLUGIN)
    }
}

// --- go-psi library modules (go-psi-core, go-psi-semantic, go-psi-ide; docs/PSI-README.md) ---------------------------------
// Copied from go-psi with the package renamed to io.github.golangsupport; composed into the plugin jar (see dependencies above).
// Their slow gates run with `--no-configuration-cache`: `:go-psi-core:corpusTest`, `benchmark` (testIde tasks).

val psiTestDataDir = layout.projectDirectory.dir("testData").asFile

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
            // The platform bundles Kotlin stdlib 2.3.x; the code must not use newer stdlib APIs.
            compilerOptions {
                apiVersion.set(KotlinVersion.KOTLIN_2_3)
                languageVersion.set(KotlinVersion.KOTLIN_2_3)
            }
        }
    }

    tasks.withType<Test>().configureEach {
        systemProperty("gopsi.testDataPath", psiTestDataDir.absolutePath)
        // Pass-through: ./gradlew test -Dgopsi.updateGoldens=true (or -Pgopsi.updateGoldens=true).
        val updateGoldens = providers.systemProperty("gopsi.updateGoldens")
            .orElse(providers.gradleProperty("gopsi.updateGoldens"))
        updateGoldens.orNull?.let { systemProperty("gopsi.updateGoldens", it) }
        // Pass-through for benchmarks: -Dgopsi.benchmark.update=true, -Dgopsi.benchmark.tolerance=2.0 (or -P).
        listOf("gopsi.benchmark.update", "gopsi.benchmark.tolerance").forEach { key ->
            providers.systemProperty(key).orElse(providers.gradleProperty(key)).orNull?.let { systemProperty(key, it) }
        }
        // The IntelliJ Platform Gradle Plugin attaches the kotlinx-coroutines debug agent to test JVMs. Its class
        // transformer fails on some platform classes and prints dozens of "JPLISAgent.c ... ASSERTION FAILED" lines
        // per run; tests do not need coroutine debug probes, so the agent is dropped (-Pgopsi.coroutinesAgent=true keeps it).
        if (!providers.gradleProperty("gopsi.coroutinesAgent").map(String::toBoolean).getOrElse(false)) {
            doFirst {
                val original = jvmArgumentProviders.toList()
                jvmArgumentProviders.clear()
                original.forEach { provider ->
                    jvmArgumentProviders.add(CommandLineArgumentProvider {
                        provider.asArguments().filterNot { it.startsWith("-javaagent:") && "coroutines-javaagent" in it }
                    })
                }
            }
        }
        // The platform class loader disables CDS for non-system classes and the JVM warns about it on every start.
        jvmArgs("-Xlog:cds=off", "-Xlog:cds+dynamic=off")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Aggregate: runs the benchmark suites of all go-psi modules one after another (they must not compete for CPU).
val benchmarkModules = listOf(":go-psi-core", ":go-psi-semantic", ":go-psi-ide")
tasks.register("benchmark") {
    group = "verification"
    description = "Runs the benchmark suites of go-psi-core, go-psi-semantic and go-psi-ide."
    dependsOn(benchmarkModules.map { "$it:benchmark" })
}
benchmarkModules.zipWithNext().forEach { (a, b) -> project(b).tasks.matching { it.name == "benchmark" }.configureEach { mustRunAfter("$a:benchmark") } }

// Binary compatibility validation of the go-psi modules: Kotlin Gradle plugin built-in ABI validation, <module>/api/<module>.api
// is the committed dump of the public API packages (docs/API.md, "Binary compatibility"). `checkKotlinAbi` runs as part of
// `check`; `./gradlew updateKotlinAbi` rewrites the dumps. @ApiStatus.Internal declarations are excluded.
val abiPackages = listOf(
    "io.github.golangsupport.lang.psi",
    "io.github.golangsupport.lang.stubs",
    "io.github.golangsupport.semantic.api",
    "io.github.golangsupport.semantic.types",
    "io.github.golangsupport.semantic.flow",
    "io.github.golangsupport.project.api",
    "io.github.golangsupport.ide.completion.api",
)
val abiExcludedSubpackages = listOf(
    "io.github.golangsupport.lang.psi.impl",
    "io.github.golangsupport.lang.stubs.index",
)
configure(benchmarkModules.map { project(it) }) {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
        extensions.configure<KotlinJvmProjectExtension> {
            abiValidation {
                // KGP 2.3 keeps the validation off unless enabled explicitly (go-psi on KGP 2.4 had it on by default).
                enabled.set(true)
                filters {
                    // `**` also matches nested classes (`Outer.Nested`, `Companion`), so the non-API subpackages of the API
                    // packages are excluded explicitly. A new subpackage shows up as a dump diff and is reviewed then.
                    include { byNames.addAll(abiPackages.map { "$it.**" }) }
                    exclude {
                        byNames.addAll(abiExcludedSubpackages.map { "$it.**" })
                        annotatedWith.add("org.jetbrains.annotations.ApiStatus.Internal")
                    }
                }
            }
        }
    }
}
