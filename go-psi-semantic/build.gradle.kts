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
    intellijPlatform {
        // Like the root project: the installed IDE from localIdePath when it exists (nothing is downloaded),
        // otherwise IntelliJ IDEA of platformVersion.
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null && file(localIde).exists()) {
            local(localIde)
        } else {
            intellijIdea(providers.gradleProperty("platformVersion"))
        }
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
                description = "Runs slow corpus gates (*CorpusTest) of the project model over GOROOT/src."
                group = "verification"
                val testSourceSet = sourceSets.test.get()
                testClassesDirs = testSourceSet.output.classesDirs
                // The standard test task carries the platform test framework (BasePlatformTestCase etc.).
                classpath += tasks.test.get().classpath
                useJUnit()
                // Classes extending platform test bases cannot be detected by hierarchy scanning here.
                isScanForTestClasses = false
                include("**/*CorpusTest.class")
                filter {
                    includeTestsMatching(corpusTestPattern)
                }
                shouldRunAfter(tasks.test)
                outputs.upToDateWhen { false }
                jvmArgumentProviders.add(
                    CommandLineArgumentProvider {
                        listOf(
                            "-Dgopsi.goroot=${goroot.get()}",
                            "-Dgopsi.gomodcache=${gomodcache.get()}",
                            // GOROOT has generated files above the platform's default 2.5 MB PSI limit (cmd/compile/internal/ssa/opGen.go).
                            "-Didea.max.intellisense.filesize=20000",
                        ) +
                            // GorootSlowFilesCheckCorpusTest: -Dgopsi.check.files=a.go,b.go (relative to GOROOT/src)
                            listOfNotNull(providers.systemProperty("gopsi.check.files").orNull?.let { "-Dgopsi.check.files=$it" }) +
                            // -Pgopsi.corpus.jvmArgs="..." (space-separated), e.g. a JFR recording for profiling
                            (providers.gradleProperty("gopsi.corpus.jvmArgs").orNull?.split(' ')?.filter { it.isNotBlank() } ?: emptyList())
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
                        )
                    },
                )
                testLogging {
                    showStandardStreams = true
                }
            }
        }
    }
}
