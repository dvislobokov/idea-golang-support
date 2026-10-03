package io.github.golangsupport.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.VirtualConfigurationType
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import io.github.golangsupport.GoIcons
import io.github.golangsupport.mod.GoModFile
import org.jdom.Element
import java.io.File

/**
 * Run configurations GoLand stored in a project (`.idea/workspace.xml`, shared `.run` files): without a type of that id the platform
 * keeps them as "unknown" and advertises the GoLand plugin ("Plugin Go supporting run configuration 'GoApplicationRunConfiguration' is
 * currently not installed", seen live on a project once opened in GoLand). Same ids, so they load as [GoRunConfiguration]s and run with our
 * runner; virtual, so "Add New Configuration" offers only our own type.
 */
abstract class GoLandConfigurationType(id: String, displayName: String, description: String, val command: GoCommand, factoryId: String) :
    ConfigurationTypeBase(id, displayName, description, NotNullLazyValue.createValue { GoIcons.Run }), VirtualConfigurationType {

    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId(): String = factoryId
            override fun createTemplateConfiguration(project: Project): RunConfiguration = GoLandRunConfiguration(project, this, "", command)
            override fun getOptionsClass(): Class<out BaseState> = GoRunConfigurationOptions::class.java
        })
    }
}

/** Type and factory names are GoLand's: the selected configuration of the workspace is remembered as `Go Build.<name>`. */
class GoLandApplicationConfigurationType :
    GoLandConfigurationType(GoLandRunConfigurations.APPLICATION_TYPE, "Go Build", "A Go Build configuration of GoLand, run with go run", GoCommand.RUN, "Go Application")

class GoLandTestConfigurationType :
    GoLandConfigurationType(GoLandRunConfigurations.TEST_TYPE, "Go Test", "A Go Test configuration of GoLand, run with go test", GoCommand.TEST, "Go Test")

/**
 * A GoLand configuration as ours: read from GoLand's elements, written back in them (the fields GoLand does not know stay as our options),
 * so a shared `.run` file still opens in GoLand and is not rewritten when nothing changed.
 */
class GoLandRunConfiguration(project: Project, factory: ConfigurationFactory, name: String, private val typeCommand: GoCommand) :
    GoRunConfiguration(project, factory, name) {

    /** GoLand's elements as they were read: what we do not map is written back verbatim. Never mutated, so a shallow clone may share it. */
    private var original: GoLandRunConfigurations.Stored? = null

    init {
        options.command = typeCommand
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        if (!GoLandRunConfigurations.hasOption(element, "command")) options.command = typeCommand
        original = GoLandRunConfigurations.read(element, options, typeCommand == GoCommand.TEST, GoLandRunConfigurations::modulePathAt, listOfNotNull(project.basePath))
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        GoLandRunConfigurations.write(element, options, typeCommand, original) { directory -> GoLandRunConfigurations.importPathOf(directory, project.basePath) }
    }
}

/** GoLand's XML of `GoApplicationRunConfiguration` / `GoTestRunConfiguration` and our options. Pure, apart from reading `go.mod` files. */
object GoLandRunConfigurations {
    const val APPLICATION_TYPE = "GoApplicationRunConfiguration"
    const val TEST_TYPE = "GoTestRunConfiguration"

    private const val KIND = "kind"
    private const val PACKAGE = "package"
    private const val DIRECTORY = "directory"
    private const val FILE_PATH = "filePath"
    private const val WORKING_DIRECTORY = "working_directory"
    private const val PARAMETERS = "parameters"
    private const val GO_PARAMETERS = "go_parameters"
    private const val ENVS = "envs"
    private const val PASS_PARENT_ENV = "pass_parent_env"
    private const val PATTERN = "pattern"
    private const val FRAMEWORK = "framework"
    private const val AUTO_MANAGED_ID = "auto_managed_config_id"

    /** GoLand's elements this mapping owns; everything else (`module`, `root_directory`, build tags, …) is kept as it was. */
    private val MAPPED = setOf(KIND, PACKAGE, DIRECTORY, FILE_PATH, WORKING_DIRECTORY, PARAMETERS, GO_PARAMETERS, ENVS, PASS_PARENT_ENV, PATTERN, FRAMEWORK)

    /** Our options GoLand's elements carry: not written as `<option>` as well. */
    private val REPRESENTED = setOf("target", "recursive", "testPattern", "benchmark", "programArguments", "goArguments", "workingDirectory", "environment", "passParentEnvironment")

    /** What was read: the elements themselves and the target and recursion they gave, to tell later whether the user changed them. */
    class Stored(val elements: List<Element>, val target: String?, val recursive: Boolean)

    fun hasOption(element: Element, name: String): Boolean = element.getChildren("option").any { it.getAttributeValue("name") == name }

    private fun value(element: Element, name: String): String? = element.getChild(name)?.getAttributeValue("value")

    /**
     * Fills [options] from GoLand's elements of [element]; null when there are none (a configuration of ours stored under this type).
     * [modulePathAt]: the module path of the `go.mod` in a directory, for a configuration of kind `PACKAGE` that names an import path.
     */
    fun read(element: Element, options: GoRunConfigurationOptions, test: Boolean, modulePathAt: (String) -> String?, extraRoots: List<String> = emptyList()): Stored? {
        val children = element.children.filter { it.name != "option" && it.name != "method" }
        if (children.none { it.name in MAPPED }) return null
        val kind = value(element, KIND)?.uppercase() ?: "PACKAGE"
        val directory = value(element, DIRECTORY)
        val filePath = value(element, FILE_PATH)
        when (kind) {
            "FILE" -> options.target = filePath ?: directory
            "DIRECTORY" -> {
                options.target = directory
                // a directory of a test configuration is go test ./... there; of an application, the package in it
                options.recursive = test
            }
            else -> options.target = value(element, PACKAGE)?.let { packageDirectory(it, listOfNotNull(directory, value(element, WORKING_DIRECTORY)) + extraRoots, modulePathAt) }
                ?: filePath?.takeIf { it.endsWith(".go") }?.let { File(it).parent?.replace('\\', '/') }
                ?: value(element, AUTO_MANAGED_ID) ?: directory
        }
        value(element, WORKING_DIRECTORY)?.let { options.workingDirectory = it }
        value(element, PARAMETERS)?.let { options.programArguments = it }
        value(element, GO_PARAMETERS)?.let { options.goArguments = it }
        value(element, PASS_PARENT_ENV)?.let { options.passParentEnvironment = it.toBoolean() }
        element.getChild(ENVS)?.getChildren("env")?.let { envs ->
            options.environment = envs.mapNotNull { env -> env.getAttributeValue("name")?.takeIf { it.isNotEmpty() }?.let { it to env.getAttributeValue("value").orEmpty() } }.toMap(LinkedHashMap())
        }
        if (test) {
            value(element, PATTERN)?.takeIf { it.isNotBlank() }?.let { options.testPattern = it }
            if (value(element, FRAMEWORK) == "gobench") options.benchmark = true
        }
        return Stored(children.map { it.clone() }, options.target, options.recursive)
    }

    /** The directory of the package [importPath]: below the `go.mod` of one of [roots]; null when none of them is its module. */
    fun packageDirectory(importPath: String, roots: List<String>, modulePathAt: (String) -> String?): String? {
        for (root in roots.distinct()) {
            val module = modulePathAt(root) ?: continue
            if (importPath == module) return root
            if (importPath.startsWith("$module/")) return root.trimEnd('/', '\\') + "/" + importPath.removePrefix("$module/")
        }
        return null
    }

    /** Writes GoLand's elements for [options] into [element] in place of our options they carry. */
    fun write(element: Element, options: GoRunConfigurationOptions, typeCommand: GoCommand, stored: Stored?, importPathOf: (String) -> String?) {
        val test = typeCommand == GoCommand.TEST
        element.getChildren("option").filter { val name = it.getAttributeValue("name"); name in REPRESENTED || name == "command" && options.command == typeCommand }
            .forEach { element.removeContent(it) }
        val fields = LinkedHashMap<String, Element>()
        fun field(name: String, value: String?) { if (!value.isNullOrEmpty()) fields[name] = Element(name).setAttribute("value", value) }

        field(WORKING_DIRECTORY, options.workingDirectory)
        val target = options.target?.takeIf { it.isNotBlank() }
        if (stored != null && stored.target == target && stored.recursive == options.recursive) {
            // the target is as GoLand wrote it: its own kind, package and paths, untouched
            for (name in listOf(KIND, PACKAGE, DIRECTORY, FILE_PATH)) stored.elements.firstOrNull { it.name == name }?.let { fields[name] = it.clone() }
        } else if (target != null) {
            val path = target.replace('\\', '/')
            when {
                path.endsWith(".go") -> { field(KIND, "FILE"); field(DIRECTORY, path.substringBeforeLast('/')); field(FILE_PATH, path) }
                else -> {
                    val importPath = if (test && options.recursive) null else importPathOf(path)
                    // DIRECTORY of a test is ./... there, so a single package without a module path stays PACKAGE (read back from `directory`)
                    val kind = if (test) (if (options.recursive) "DIRECTORY" else "PACKAGE") else if (importPath == null) "DIRECTORY" else "PACKAGE"
                    field(KIND, kind); field(PACKAGE, importPath); field(DIRECTORY, path)
                }
            }
        }
        field(PARAMETERS, options.programArguments)
        field(GO_PARAMETERS, options.goArguments)
        if (!options.passParentEnvironment) field(PASS_PARENT_ENV, "false")
        if (options.environment.isNotEmpty()) fields[ENVS] = Element(ENVS).apply {
            for ((name, value) in options.environment) addContent(Element("env").setAttribute("name", name).setAttribute("value", value))
        }
        if (test) {
            field(FRAMEWORK, if (options.benchmark) "gobench" else stored?.elements?.firstOrNull { it.name == FRAMEWORK }?.getAttributeValue("value") ?: "gotest")
            field(PATTERN, options.testPattern)
        }

        // GoLand's order where it had the element, the new ones after
        for (old in stored?.elements.orEmpty()) {
            if (old.name !in MAPPED) element.addContent(old.clone()) else fields.remove(old.name)?.let { element.addContent(it) }
        }
        fields.values.forEach { element.addContent(it) }
    }

    /** The module path of the `go.mod` in [directory], if there is one. */
    fun modulePathAt(directory: String): String? = readModulePath(File(directory, "go.mod"))

    /** The import path of the package in [directory]: the nearest `go.mod` above it, not above [projectDirectory]. */
    fun importPathOf(directory: String, projectDirectory: String?): String? {
        val stop = projectDirectory?.let { File(it).absoluteFile }
        var dir: File? = File(directory).absoluteFile
        val segments = ArrayDeque<String>()
        while (dir != null) {
            readModulePath(File(dir, "go.mod"))?.let { module -> return (listOf(module) + segments).joinToString("/") }
            if (dir == stop) return null
            segments.addFirst(dir.name)
            dir = dir.parentFile
        }
        return null
    }

    private fun readModulePath(goMod: File): String? = runCatching { if (goMod.isFile) GoModFile.parse(goMod.readText()).modulePath else null }.getOrNull()
}
