package io.github.golangsupport.cli

import io.github.golangsupport.settings.GoVendoring
import java.io.File

/**
 * What Settings | Go | Go Modules adds to the environment of the go commands of the plugin: the variables of its Environment field
 * (GOPROXY, GOPRIVATE...) and the `-mod` flag of the vendoring choice. The flag goes through GOFLAGS, not the arguments: go applies a
 * flag of GOFLAGS only to the commands that know it, so `go mod tidy` or `go env` are not broken by it. Pure, for the tests.
 */
object GoModulesEnvironment {
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** `GOPROXY=https://proxy.example;GOPRIVATE=example.com`: pairs split by `;` or new lines; the value is everything after the first `=`. */
    fun parse(text: String): Map<String, String> = entries(text).mapNotNull { entry ->
        val equals = entry.indexOf('=')
        val name = if (equals > 0) entry.substring(0, equals).trim() else return@mapNotNull null
        if (NAME.matches(name)) name to entry.substring(equals + 1).trim() else null
    }.toMap()

    /** The first entry that is not `NAME=value`, for the validation of the field; null when all are. */
    fun invalidEntry(text: String): String? = entries(text).firstOrNull { entry ->
        val equals = entry.indexOf('=')
        equals <= 0 || !NAME.matches(entry.substring(0, equals).trim())
    }

    private fun entries(text: String) = text.split(';', '\n').map(String::trim).filter(String::isNotEmpty)

    /** GOFLAGS with [modFlag] added; null when nothing is to change: no flag, or a `-mod` of the user's own is there already and wins. */
    fun goflags(current: String?, modFlag: String?): String? {
        if (modFlag == null) return null
        val flags = current.orEmpty().trim()
        if (flags.split(Regex("\\s+")).any { it.trimStart('-').startsWith("mod=") }) return null
        return if (flags.isEmpty()) modFlag else "$flags $modFlag"
    }

    /** Whether the module of [directory] (the nearest directory up with a go.mod) is vendored: has `vendor/modules.txt`. */
    fun hasVendor(directory: File?): Boolean {
        var current = directory
        while (current != null) {
            if (File(current, "go.mod").isFile) return File(current, "vendor/modules.txt").isFile
            current = current.parentFile
        }
        return false
    }

    /** The variables for a go command run in [workDirectory]; [systemGoflags] is the GOFLAGS the IDE was started with, kept under the added flag. */
    fun of(text: String, vendoring: GoVendoring, workDirectory: String?, systemGoflags: String?): Map<String, String> {
        val environment = parse(text).toMutableMap()
        val flag = if (vendoring == GoVendoring.AUTO) null else vendoring.modFlag(hasVendor(workDirectory?.let(::File)))
        goflags(environment["GOFLAGS"] ?: systemGoflags, flag)?.let { environment["GOFLAGS"] = it }
        return environment
    }
}
