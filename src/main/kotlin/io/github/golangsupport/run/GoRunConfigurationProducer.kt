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
import io.github.golangsupport.lang.GoDeclaration
import io.github.golangsupport.lang.GoDeclarationKind
import io.github.golangsupport.lang.GoFile
import io.github.golangsupport.lang.GoStructure
import io.github.golangsupport.lang.GoTokenTypes
import io.github.golangsupport.testing.GoTestKind
import io.github.golangsupport.testing.GoTests

/** What a place of the code runs: a main package, the tests of a package, or one test function. */
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

/** Gutter ▶ at `func main()` of a main package and at the test, benchmark, fuzz and example functions of a `_test.go` file. */
class GoRunLineMarkerContributor : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.elementType != GoTokenTypes.IDENTIFIER) return null
        val declaration = element.parent as? GoDeclaration ?: return null
        val info = declaration.info?.takeIf { it.kind == GoDeclarationKind.FUNCTION && it.nameRange == element.textRange } ?: return null
        val file = element.containingFile as? GoFile ?: return null
        val actions = ExecutorAction.getActions(0)
        return when {
            GoTests.kindOf(info, file.name) != null -> Info(AllIcons.RunConfigurations.TestState.Run, actions) { "Run ${info.name}" }
            !file.isTestFile && info.name == "main" && GoStructure.of(file).isMainPackage -> Info(AllIcons.RunConfigurations.TestState.Run, actions) { "Run the program" }
            else -> null
        }
    }
}
