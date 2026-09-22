import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

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
        testFramework(TestFrameworkType.Platform)
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

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            // 2026.1: the platform with the LSP client API under its new names (LspIntegrationProvider) and with the DAP module
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
}
