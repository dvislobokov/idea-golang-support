package io.github.golangsupport.sharedindex

import java.nio.file.Path

/**
 * The `dump-shared-index project` run that turns `$GOROOT/src` into a chunk: the IDE's own launcher in headless mode over a throwaway
 * project with an empty go.mod, whose only library is GOROOT (the plugin adds it, `GoRootsProvider`). The second IDE gets its own config,
 * system and log directories (`<PRODUCT>_PROPERTIES`), otherwise the launcher hands the command to the running instance; the plugins
 * directory stays the running IDE's, so the Go indexes are built by this very plugin version.
 */
object GoSharedIndexCommand {
    enum class Os { WINDOWS, MAC, LINUX }

    /** Launchers of [scriptName] (`idea`, `pycharm`, ...) under the IDE home, the preferred first. */
    fun launcherCandidates(home: Path, scriptName: String, os: Os): List<Path> = when (os) {
        Os.WINDOWS -> listOf(home.resolve("bin").resolve("$scriptName.bat"))
        Os.MAC -> listOf(home.resolve("MacOS").resolve(scriptName), home.resolve("bin").resolve("$scriptName.sh"))
        Os.LINUX -> listOf(home.resolve("bin").resolve("$scriptName.sh"), home.resolve("bin").resolve(scriptName))
    }

    /** The arguments after the launcher. `--compression=plain`: the on-disk locator of the platform attaches `*.ijx` as they are. */
    fun arguments(projectDir: Path, outputDir: Path, tempDir: Path, key: GoSharedIndexKey): List<String> = listOf(
        "dump-shared-index", "project",
        "--project-dir=$projectDir",
        "--output=$outputDir",
        "--temp=$tempDir",
        "--project-id=go-stdlib-${key.id}",
        "--compression=plain",
    )

    /** The env variables the launchers read the properties file from; one per product, all set, since only the IDE's own one is read. */
    fun propertiesVariables(scriptName: String): List<String> =
        (listOf(scriptName.uppercase().replace('-', '_')) + listOf("IDEA", "PYCHARM", "WEBIDE", "WEBSTORM", "PHPSTORM", "RIDER", "CLION", "RUSTROVER", "RUBYMINE", "DATAGRIP"))
            .distinct().map { "${it}_PROPERTIES" }

    /** `idea.properties` of the headless run: its own state under [work], the running IDE's [pluginsPath]. Forward slashes: `\` escapes. */
    fun properties(work: Path, pluginsPath: Path): String {
        fun p(path: Path) = path.toString().replace('\\', '/')
        return listOf(
            "idea.config.path=${p(work.resolve("config"))}",
            "idea.system.path=${p(work.resolve("system"))}",
            "idea.log.path=${p(work.resolve("log"))}",
            "idea.plugins.path=${p(pluginsPath)}",
        ).joinToString("\n", postfix = "\n")
    }

    /** The go.mod of the throwaway project: no requirements, so the only library is the standard library. */
    fun goMod(key: GoSharedIndexKey): String = "module gosharedindex\n\ngo ${key.goDirective}\n"

    /** The plugin's settings in the throwaway config, so the headless IDE finds the same `go` (and so the same GOROOT); null when none is set. */
    fun settingsXml(goPath: String?): String? {
        val path = goPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val escaped = path.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        return "<application>\n  <component name=\"GoSupportSettings\">\n    <option name=\"goPath\" value=\"$escaped\" />\n  </component>\n</application>\n"
    }
}
