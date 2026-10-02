package io.github.golangsupport.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import io.github.golangsupport.lang.GoDeclarationPsi
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.GoStructure
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoTypes
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import io.github.golangsupport.testing.GoSubtests
import io.github.golangsupport.testing.GoTestKind
import io.github.golangsupport.testing.GoTestStatus
import io.github.golangsupport.testing.GoTestStatuses
import io.github.golangsupport.testing.GoTests
import javax.swing.Icon

/** What a place of the code runs: a main package, the tests of a package, one test function, or one subtest of it. */
data class GoRunTarget(val command: GoCommand, val target: String, val name: String, val testPattern: String? = null, val benchmark: Boolean = false, val recursive: Boolean = false)

object GoRunTargets {
    fun of(context: ConfigurationContext): GoRunTarget? {
        val element = context.psiLocation ?: return null
        if (element is PsiDirectory) {
            val directory = element.virtualFile
            return GoRunTarget(GoCommand.TEST, directory.path, "go test ${directory.name}/...", recursive = true).takeIf { containsGo(directory) }
        }
        val file = element.containingFile as? GoFile ?: return null
        val directory = file.virtualFile?.parent ?: return null
        val structure = GoStructure.of(file)
        if (file.isTestFile) {
            val offset = element.textRange.startOffset
            val function = GoTests.find(structure, file.name).firstOrNull { offset in it.first.range }
                ?: return GoRunTarget(GoCommand.TEST, directory.path, "go test ${directory.name}")
            val (declaration, kind) = function
            // the caret on the line of `t.Run("empty", ...)` or of a case of the table: that one subtest
            val document = file.viewProvider.document
            val subtest = document?.let { doc -> GoSubtests.ofFile(file).values.firstOrNull { it.function === declaration && doc.getLineNumber(it.nameRange.startOffset) == doc.getLineNumber(offset) } }
            if (subtest != null) return GoRunTarget(GoCommand.TEST, directory.path, subtest.fullName, GoTests.pattern(listOf(subtest.fullName)))
            return GoRunTarget(GoCommand.TEST, directory.path, declaration.name, GoTests.pattern(listOf(declaration.name)), benchmark = kind == GoTestKind.BENCHMARK)
        }
        return if (structure.mainFunction != null) GoRunTarget(GoCommand.RUN, directory.path, "go run ${directory.name}") else null
    }

    private fun containsGo(directory: com.intellij.openapi.vfs.VirtualFile): Boolean =
        directory.findChild("go.mod") != null || directory.children.any { it.extension == "go" || it.isDirectory && it.children.any { child -> child.extension == "go" } }
}

class GoRunConfigurationProducer : LazyRunConfigurationProducer<GoRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = GoConfigurationType.instance.factory

    override fun setupConfigurationFromContext(configuration: GoRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
        val target = GoRunTargets.of(context) ?: return false
        configuration.options.apply {
            command = target.command
            this.target = target.target
            testPattern = target.testPattern
            benchmark = target.benchmark
            recursive = target.recursive
        }
        configuration.name = target.name
        return true
    }

    override fun isConfigurationFromContext(configuration: GoRunConfiguration, context: ConfigurationContext): Boolean {
        val target = GoRunTargets.of(context) ?: return false
        val options = configuration.options
        return options.command == target.command && options.target == target.target && options.testPattern.orEmpty() == target.testPattern.orEmpty() &&
            options.benchmark == target.benchmark && options.recursive == target.recursive
    }
}

/**
 * Gutter ▶ at `func main()` of a main package and at the test, benchmark, fuzz and example functions of a `_test.go` file; a test that has
 * run shows how it went last time (green, red, or the yellow of a skip), whoever ran it (see [GoTestStatuses]).
 */
class GoRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        val file = element.containingFile as? GoFile ?: return null
        val type = element.elementType
        if (type == GoTypes.STRING || type == GoTypes.RAW_STRING) return subtestInfo(file, element)
        val declaration = GoDeclarationPsi.ofName(element) as? GoFunctionDeclaration ?: return null
        val info = GoDeclarationPsi.infoOf(declaration)?.takeIf { it.kind == GoDeclarationKind.FUNCTION } ?: return null
        val actions = ExecutorAction.getActions(0)
        return when (GoTests.kindOf(info, file.name)) {
            null -> if (!file.isTestFile && info.name == "main" && GoStructure.of(file).isMainPackage) Info(AllIcons.RunConfigurations.TestState.Run, actions) { "Run the program" } else null
            // a fuzz function is a test by `-run`; the fuzzing itself is its own way to start
            GoTestKind.FUZZ -> Info(statusIcon(file, info.name), actions + GoFuzzAction(info.name)) { "Run ${info.name}" }
            else -> Info(statusIcon(file, info.name), actions) { "Run ${info.name}" }
        }
    }

    /** ▶ at `t.Run("name", ...)` and at `{name: "case", ...}` of a table: one subtest, as GoLand runs them. */
    private fun subtestInfo(file: GoFile, element: PsiElement): Info? {
        if (!file.isTestFile) return null
        val subtest = GoSubtests.ofFile(file)[element.textRange.startOffset] ?: return null
        val directory = file.virtualFile?.parent?.path
        val icon = if (directory != null && file.project.service<GoTestStatuses>().of(directory, subtest.fullName) == GoTestStatus.FAILED) AllIcons.RunConfigurations.TestState.Red2 else AllIcons.RunConfigurations.TestState.Run
        return Info(icon, ExecutorAction.getActions(0)) { "Run ${subtest.fullName}" }
    }

    private fun statusIcon(file: GoFile, test: String): Icon {
        val directory = file.virtualFile?.parent?.path ?: return AllIcons.RunConfigurations.TestState.Run
        return when (file.project.service<GoTestStatuses>().of(directory, test)) {
            GoTestStatus.PASSED -> AllIcons.RunConfigurations.TestState.Green2
            GoTestStatus.FAILED -> AllIcons.RunConfigurations.TestState.Red2
            GoTestStatus.SKIPPED -> AllIcons.RunConfigurations.TestState.Yellow2
            null -> AllIcons.RunConfigurations.TestState.Run
        }
    }
}

/** In the gutter of `FuzzXxx`: `go test -run ^$ -fuzz ^FuzzXxx$`, which runs until it finds a failing input or is stopped. */
class GoFuzzAction(private val function: String) : AnAction("Run Fuzzing '$function'", null, AllIcons.Actions.Lightning), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = e.getData(CommonDataKeys.VIRTUAL_FILE)?.parent?.path ?: return
        GoRunLauncher.runTests(project, directory, "fuzz $function", GoTests.pattern(listOf(function)), benchmark = false, fuzz = true)
    }
}
