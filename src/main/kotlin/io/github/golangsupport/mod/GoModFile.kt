package io.github.golangsupport.mod

/** A `require` line; [line] is zero-based. */
data class GoRequire(val path: String, val version: String, val indirect: Boolean, val line: Int)

/** `old [version] => new [version]`; a [newPath] that starts with `.` or `/` (or a drive) is a directory. */
data class GoReplace(val oldPath: String, val oldVersion: String?, val newPath: String, val newVersion: String?, val line: Int) {
    val isLocal: Boolean get() = newVersion == null
}

/** What a go.mod or a go.work says. Parsed by lines, as the format is defined: a directive, or a block of its arguments in parentheses. */
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

        fun parse(text: CharSequence): GoModFile {
            var modulePath: String? = null
            var goVersion: String? = null
            var toolchain: String? = null
            val requires = ArrayList<GoRequire>()
            val replaces = ArrayList<GoReplace>()
            val excludes = ArrayList<Pair<String, String>>()
            val tools = ArrayList<String>()
            val uses = ArrayList<String>()
            var block: String? = null

            text.lineSequence().forEachIndexed { index, raw ->
                val comment = raw.indexOf("//")
                val indirect = comment >= 0 && raw.substring(comment + 2).trim().startsWith("indirect")
                var words = words(if (comment >= 0) raw.substring(0, comment) else raw)
                if (words.isEmpty()) return@forEachIndexed
                if (block != null && words[0] == ")") {
                    block = null
                    return@forEachIndexed
                }
                val directive = block ?: words[0].also { words = words.drop(1) }
                if (block == null && words.firstOrNull() == "(") {
                    block = directive
                    return@forEachIndexed
                }
                when (directive) {
                    "module" -> modulePath = words.firstOrNull() ?: modulePath
                    "go" -> goVersion = words.firstOrNull() ?: goVersion
                    "toolchain" -> toolchain = words.firstOrNull() ?: toolchain
                    "require" -> if (words.size >= 2) requires += GoRequire(words[0], words[1], indirect, index)
                    "exclude" -> if (words.size >= 2) excludes += words[0] to words[1]
                    "tool" -> words.firstOrNull()?.let(tools::add)
                    "use" -> words.firstOrNull()?.let(uses::add)
                    "replace" -> {
                        val arrow = words.indexOf("=>")
                        if (arrow in 1..2 && words.size > arrow + 1) {
                            replaces += GoReplace(words[0], words.getOrNull(1).takeIf { arrow == 2 }, words[arrow + 1], words.getOrNull(arrow + 2), index)
                        }
                    }
                }
            }
            return GoModFile(modulePath, goVersion, toolchain, requires, replaces, excludes, tools, uses)
        }

        /** Splits by whitespace; a quoted or back-quoted string, which a path with unusual characters needs, is one word. */
        private fun words(line: String): List<String> {
            val result = ArrayList<String>()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c.isWhitespace() -> i++
                    c == '"' || c == '`' -> {
                        val close = line.indexOf(c, i + 1).let { if (it < 0) line.length else it }
                        result += line.substring(i + 1, close)
                        i = close + 1
                    }
                    c == '(' || c == ')' -> {
                        result += c.toString()
                        i++
                    }
                    else -> {
                        val start = i
                        while (i < line.length && !line[i].isWhitespace() && line[i] != '(' && line[i] != ')') i++
                        result += line.substring(start, i)
                    }
                }
            }
            return result
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
