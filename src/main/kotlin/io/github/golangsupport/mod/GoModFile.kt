package io.github.golangsupport.mod

import io.github.golangsupport.project.impl.GoModFileParser

/** A `require` line; [line] is zero-based. */
data class GoRequire(val path: String, val version: String, val indirect: Boolean, val line: Int)

/** `old [version] => new [version]`; a [newPath] that starts with `.` or `/` (or a drive) is a directory. */
data class GoReplace(val oldPath: String, val oldVersion: String?, val newPath: String, val newVersion: String?, val line: Int) {
    val isLocal: Boolean get() = newVersion == null
}

/**
 * What a go.mod or a go.work says, with the line of every require and replace (the tidy banner, the dependency nodes and the quick fixes
 * navigate there). A view over the native project model's parser (`GoModFileParser.directives`): one grammar for the plugin.
 */
class GoModFile(
    val modulePath: String?,
    val goVersion: String?,
    val toolchain: String?,
    val requires: List<GoRequire>,
    val replaces: List<GoReplace>,
    val excludes: List<Pair<String, String>>,
    /** `tool` directives (Go 1.24): packages run by `go tool`. */
    val tools: List<String>,
    /** `use` directives of a go.work: directories of the modules of the workspace. */
    val uses: List<String>,
) {
    val directRequires: List<GoRequire> get() = requires.filter { !it.indirect }
    val indirectRequires: List<GoRequire> get() = requires.filter { it.indirect }

    /** The replacement that applies to [require]: one for its exact version wins over one for any version. */
    fun replacementOf(require: GoRequire): GoReplace? =
        replaces.firstOrNull { it.oldPath == require.path && it.oldVersion == require.version } ?: replaces.firstOrNull { it.oldPath == require.path && it.oldVersion == null }

    companion object {
        val DIRECTIVES = setOf("module", "go", "toolchain", "godebug", "require", "replace", "exclude", "retract", "tool", "ignore", "use")

        /** Malformed directives are skipped, as `GoModFileParser.parseGoMod` skips them; the arity rules are its. */
        fun parse(text: CharSequence): GoModFile {
            var modulePath: String? = null
            var goVersion: String? = null
            var toolchain: String? = null
            val requires = ArrayList<GoRequire>()
            val replaces = ArrayList<GoReplace>()
            val excludes = ArrayList<Pair<String, String>>()
            val tools = ArrayList<String>()
            val uses = ArrayList<String>()
            for (directive in GoModFileParser.directives(text)) {
                val a = directive.args
                val line = directive.line - 1
                when (directive.verb) {
                    "module" -> a.singleOrNull()?.let { modulePath = it }
                    "go" -> a.singleOrNull()?.let { goVersion = it }
                    "toolchain" -> a.singleOrNull()?.let { toolchain = it }
                    "require" -> if (a.size == 2) requires += GoRequire(a[0], a[1], directive.indirect, line)
                    "exclude" -> if (a.size == 2) excludes += a[0] to a[1]
                    "tool" -> a.singleOrNull()?.let(tools::add)
                    "use" -> a.singleOrNull()?.let(uses::add)
                    "replace" -> replace(a, line)?.let(replaces::add)
                }
            }
            return GoModFile(modulePath, goVersion, toolchain, requires, replaces, excludes, tools, uses)
        }

        /** `old [version] => new [version]`, the shape `GoModFileParser.parseGoMod` accepts. */
        private fun replace(a: List<String>, line: Int): GoReplace? {
            val arrow = a.indexOf("=>")
            if (arrow !in 1..2 || a.size - arrow - 1 !in 1..2) return null
            return GoReplace(a[0], a.getOrNull(1).takeIf { arrow == 2 }, a[arrow + 1], a.getOrNull(arrow + 2), line)
        }

        /**
         * The directory of a module in the module cache: `github.com/BurntSushi/toml` `v1.3.2` -> `github.com/!burnt!sushi/toml@v1.3.2`.
         * Upper-case letters are escaped, because the cache has to work on case-insensitive file systems.
         */
        fun cachePath(path: String, version: String): String = escape(path) + "@" + escape(version)

        private fun escape(text: String): String = buildString {
            for (c in text) if (c.isUpperCase()) append('!').append(c.lowercaseChar()) else append(c)
        }
    }
}
