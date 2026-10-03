import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform.module)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":go-psi-core"))
    implementation(project(":go-psi-semantic"))
    intellijPlatform {
        // Like the root project: the installed IDE from localIdePath when it exists (nothing is downloaded),
        // otherwise IntelliJ IDEA of platformVersion.
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null && file(localIde).exists()) {
            local(localIde)
        } else {
            intellijIdea(providers.gradleProperty("platformVersion"))
        }
        // ide.spelling: the spellchecker is a product module (com.intellij.modules.spellchecker), not on the core classpath;
        // the host declares it as an optional dependency (go-psi-ide-spelling.xml)
        bundledModule("intellij.spellchecker")
        // injection: the JSON language injected into Go strings (go-psi-ide-injection-json.xml; an optional dependency of the host)
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
}

// --- Tests ------------------------------------------------------------------------------------

val benchmarkPattern = "*Benchmark"

sourceSets {
    test {
        // Shared harness: test classes of one module are not visible to the others.
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("tools/benchmark"))
    }
}

val corpusTestPattern = "*CorpusTest"

tasks.test {
    filter {
        excludeTestsMatching(corpusTestPattern)
        excludeTestsMatching(benchmarkPattern)
    }
}

/** Lazily resolves GOROOT: -Dgopsi.goroot / -Pgopsi.goroot, then `go env GOROOT`, then the default install path. */
val goroot: Provider<String> = providers.systemProperty("gopsi.goroot")
    .orElse(providers.gradleProperty("gopsi.goroot"))
    .orElse(
        provider {
            runCatching {
                providers.exec {
                    commandLine("go", "env", "GOROOT")
                    isIgnoreExitValue = true
                }.standardOutput.asText.get().trim()
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: """C:\Program Files\Go"""
        },
    )

val gomodcache: Provider<String> = providers.systemProperty("gopsi.gomodcache")
    .orElse(providers.gradleProperty("gopsi.gomodcache"))
    .orElse(providers.systemProperty("user.home").map { "$it\\go\\pkg\\mod" })

// Navigation/documentation tests resolve into the real GOROOT (fmt.Println -> $GOROOT/src/fmt/print.go).
tasks.test {
    // Local copies: a lambda that reads the script-level properties captures the script object, which the
    // configuration cache cannot serialize.
    val gorootValue = goroot
    val gomodcacheValue = gomodcache
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dgopsi.goroot=${gorootValue.get()}", "-Dgopsi.gomodcache=${gomodcacheValue.get()}")
        },
    )
}

/** Extra corpus options passed through to the test JVM (`-Pgopsi.formatter.*`). */
val formatterCorpusOptions: Provider<List<String>> = provider {
    listOf("gopsi.formatter.corpus.mode", "gopsi.formatter.corpus.includeCmd", "gopsi.formatter.corpus.filter", "gopsi.formatter.corpus.report").mapNotNull { key ->
        (providers.gradleProperty(key).orNull ?: providers.systemProperty(key).orNull)?.let { "-D$key=$it" }
    }
}

intellijPlatformTesting {
    testIde {
        register("corpusTest") {
            // The installed IDE from localIdePath when it exists (nothing is downloaded), like the compile platform.
            val localIde = providers.gradleProperty("localIdePath").orNull?.takeIf { file(it).exists() }
            if (localIde != null) {
                localPath = file(localIde)
            } else {
                type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea
                version = libs.versions.intellijPlatform
            }

            task {
                description = "Runs slow corpus gates (*CorpusTest): gofmt parity over GOROOT/src."
                group = "verification"
                val testSourceSet = sourceSets.test.get()
                testClassesDirs = testSourceSet.output.classesDirs
                classpath += tasks.test.get().classpath
                useJUnit()
                isScanForTestClasses = false
                include("**/*CorpusTest.class")
                filter {
                    includeTestsMatching(corpusTestPattern)
                }
                shouldRunAfter(tasks.test)
                outputs.upToDateWhen { false }
                maxHeapSize = "3g"
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf("-Dgopsi.goroot=${goroot.get()}") + formatterCorpusOptions.get()
                    },
                )
                testLogging {
                    showStandardStreams = true
                }
            }
        }

        register("benchmark") {
            // The installed IDE from localIdePath when it exists (nothing is downloaded), like the compile platform.
            val localIde = providers.gradleProperty("localIdePath").orNull?.takeIf { file(it).exists() }
            if (localIde != null) {
                localPath = file(localIde)
            } else {
                type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea
                version = libs.versions.intellijPlatform
            }

            task {
                description = "Runs benchmarks (*Benchmark) and compares medians with testData/benchmark/thresholds.json."
                group = "verification"
                val testSourceSet = sourceSets.test.get()
                testClassesDirs = testSourceSet.output.classesDirs
                classpath += tasks.test.get().classpath
                useJUnit()
                isScanForTestClasses = false
                include("**/*Benchmark.class")
                filter {
                    includeTestsMatching(benchmarkPattern)
                }
                shouldRunAfter(tasks.test)
                outputs.upToDateWhen { false }
                maxHeapSize = "3g"
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf(
                            "-Dgopsi.goroot=${goroot.get()}",
                            "-Dgopsi.gomodcache=${gomodcache.get()}",
                            // GOROOT has generated files above the platform's default 2.5 MB PSI limit.
                            "-Didea.max.intellisense.filesize=20000",
                        ) +
                            // -Pgopsi.benchmark.jvmArgs="..." (space-separated), e.g. a JFR recording for profiling
                            (providers.gradleProperty("gopsi.benchmark.jvmArgs").orNull?.split(' ')?.filter { it.isNotBlank() } ?: emptyList())
                    },
                )
                testLogging {
                    showStandardStreams = true
                }
            }
        }
    }
}
