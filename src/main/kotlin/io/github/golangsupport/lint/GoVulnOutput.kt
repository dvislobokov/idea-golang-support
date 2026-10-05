package io.github.golangsupport.lint

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.golangsupport.mod.GoRequire
import java.io.File
import java.io.StringReader
import java.security.MessageDigest

/**
 * A frame of a govulncheck trace. Frames run from the vulnerable symbol (first) to the entry point of the code (last); the position of
 * frame i > 0 is the call in it that leads to frame i - 1, the position of the first is the declaration of the symbol. [file] is relative
 * to the root of the module of the frame (absolute in older govulncheck); [line] and [column] are one-based, 0 when unknown.
 */
data class GoVulnFrame(val module: String, val version: String?, val pkg: String?, val function: String?, val receiver: String?, val file: String?, val line: Int, val column: Int) {
    /** `language.Parse`, `http.Request.ParseForm`: the package name, the receiver type and the function. */
    val symbol: String? get() = function?.let { f -> listOfNotNull(pkg?.substringAfterLast('/'), receiver?.trimStart('*'), f).joinToString(".") }
}

/** An OSV entry of the report: `GO-2024-2687`, its one-line summary, its CVE / GHSA aliases. */
data class GoVulnOsv(val id: String, val summary: String, val aliases: List<String>)

/** `finding`: a vulnerability reached at the level of its first frame (a module, a package, or a symbol when the frame has a function). */
data class GoVulnFinding(val osv: String, val fixedVersion: String?, val trace: List<GoVulnFrame>)

/** An imported package of a vulnerable module version: what `GoVulnerablePackageImport` marks on the import. */
data class GoVulnPackage(val module: String, val version: String?, val pkg: String, val osv: String, val fixedVersion: String?, val summary: String) {
    val isStdlib: Boolean get() = module == GoVulnOutput.STDLIB
}

/**
 * A call of the module's code on the way to a vulnerable symbol: [file] / [line] / [column] of the call (relative to the module root or
 * absolute), [callee] the name of the called function, [calleeSymbol] its qualified name, [vulnerable] the symbol the trace ends in;
 * [direct] when the callee is the vulnerable symbol itself.
 */
data class GoVulnCallSite(val file: String, val line: Int, val column: Int, val callee: String, val calleeSymbol: String, val vulnerable: String, val osv: String, val direct: Boolean)

/** What the summary of a run says: vulnerabilities called by the code, more in imported packages, more in required modules only. */
data class GoVulnSummary(val called: Int, val imported: Int, val required: Int) {
    val total: Int get() = called + imported + required

    fun text(): String = when {
        total == 0 -> "No known vulnerabilities"
        else -> "$called ${if (called == 1) "vulnerability" else "vulnerabilities"} reachable from the code" +
            (if (imported > 0) ", $imported more in imported packages" else "") + (if (required > 0) ", $required more in required modules" else "")
    }
}

/** A govulncheck `-json` run: the OSV entries it printed and its findings. */
class GoVulnReport(val osvs: Map<String, GoVulnOsv>, val findings: List<GoVulnFinding>, val scannerVersion: String?) {
    fun summaryOf(id: String): String = osvs[id]?.summary.orEmpty()

    /** Every imported package with a vulnerability (package- and symbol-level findings), once per package and OSV entry. */
    fun packages(): List<GoVulnPackage> = findings.mapNotNull { f ->
        val top = f.trace.firstOrNull() ?: return@mapNotNull null
        val pkg = top.pkg ?: return@mapNotNull null
        GoVulnPackage(top.module, top.version, pkg, f.osv, f.fixedVersion, summaryOf(f.osv))
    }.distinctBy { it.pkg to it.osv }

    /** The calls in [modulePath]'s own code nearest to each vulnerable symbol: the first frame of the trace that is in the module and has a position. */
    fun callSites(modulePath: String): List<GoVulnCallSite> = findings.mapNotNull { f ->
        val vulnerable = f.trace.firstOrNull()?.symbol ?: return@mapNotNull null
        val at = (1 until f.trace.size).firstOrNull { i -> f.trace[i].let { it.module == modulePath && !it.file.isNullOrEmpty() && it.line > 0 } } ?: return@mapNotNull null
        val frame = f.trace[at]
        val callee = f.trace[at - 1]
        GoVulnCallSite(frame.file!!, frame.line, frame.column, callee.function ?: return@mapNotNull null, callee.symbol!!, vulnerable, f.osv, at == 1)
    }.distinctBy { listOf(it.file, it.line, it.column, it.osv) }

    fun summary(): GoVulnSummary {
        val levels = findings.groupBy { it.osv }.mapValues { (_, fs) ->
            fs.maxOf { f -> f.trace.firstOrNull().let { when { it?.function != null -> 2; it?.pkg != null -> 1; else -> 0 } } }
        }
        return GoVulnSummary(levels.count { it.value == 2 }, levels.count { it.value == 1 }, levels.count { it.value == 0 })
    }
}

/** The JSON stream of `govulncheck -json` (pretty-printed objects one after another), and the lines the manual run shows in the Build window. */
object GoVulnOutput {
    const val STDLIB = "stdlib"

    fun arguments(buildTags: List<String>): List<String> = buildList {
        add("-json")
        if (buildTags.isNotEmpty()) add("-tags=" + buildTags.joinToString(","))
        add("./...")
    }

    /** null when the output is not a govulncheck report (no `config` object: the tool has failed or printed something else). */
    fun parse(stdout: String): GoVulnReport? {
        val osvs = LinkedHashMap<String, GoVulnOsv>()
        val findings = ArrayList<GoVulnFinding>()
        var config: JsonObject? = null
        val start = stdout.indexOf('{').takeIf { it >= 0 } ?: return null
        val reader = JsonReader(StringReader(stdout.substring(start))).apply { isLenient = true }
        try {
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                val message = JsonParser.parseReader(reader) as? JsonObject ?: continue
                message.obj("config")?.let { config = it }
                message.obj("osv")?.let { o -> o.string("id")?.let { osvs[it] = GoVulnOsv(it, o.string("summary").orEmpty(), o.array("aliases").mapNotNull { a -> a.asStringOrNull() }) } }
                message.obj("finding")?.let { f -> f.string("osv")?.let { findings += GoVulnFinding(it, f.string("fixed_version")?.ifEmpty { null }, f.array("trace").mapNotNull(::frame)) } }
            }
        } catch (_: Exception) {
            // a truncated stream (the tool was killed): what was read stands
        }
        val found = config ?: return null
        return GoVulnReport(osvs, findings, found.string("scanner_version"))
    }

    private fun frame(element: JsonElement): GoVulnFrame? {
        val o = element as? JsonObject ?: return null
        val position = o.obj("position")
        return GoVulnFrame(o.string("module") ?: return null, o.string("version"), o.string("package"), o.string("function"), o.string("receiver"),
            position?.string("filename"), position?.int("line") ?: 0, position?.int("column") ?: 0)
    }

    /** The message of an imported vulnerable package; stdlib has no `go get` fix, its fixed version is a Go release. */
    fun importMessage(p: GoVulnPackage): String {
        val of = if (p.isStdlib) "of the standard library${p.version?.let { " (Go ${it.removePrefix("v")})" }.orEmpty()}" else "of module ${p.module}${p.version?.let { "@$it" }.orEmpty()}"
        val fixed = if (p.isStdlib && p.fixedVersion != null) " (fixed in Go ${p.fixedVersion.removePrefix("v")})" else ""
        return "Package '${p.pkg}' $of is affected by ${p.osv}" + (if (p.summary.isNotEmpty()) ": ${p.summary}" else "") + fixed
    }

    fun callMessage(c: GoVulnCallSite): String =
        if (c.direct) "Call to vulnerable function ${c.vulnerable} (${c.osv})" else "Call to ${c.calleeSymbol} reaches vulnerable function ${c.vulnerable} (${c.osv})"

    /**
     * The findings of a run as `file:line:col: text` lines (the Build window makes them navigable, relative to the module root): the calls of
     * the code, then the vulnerable modules on their `require` line of go.mod (line 1 for the standard library and unknown modules).
     */
    fun describe(report: GoVulnReport, modulePath: String, requires: List<GoRequire>): List<String> {
        val calls = report.callSites(modulePath).map { c -> "${c.file.replace(File.separatorChar, '/')}:${c.line}:${c.column.coerceAtLeast(1)}: ${callMessage(c)}" }
        val called = report.callSites(modulePath).map { it.osv }.toSet()
        val modules = report.findings.filter { it.osv !in called }.mapNotNull { f -> f.trace.firstOrNull()?.let { it to f } }.distinctBy { (top, f) -> top.module to f.osv }.map { (top, f) ->
            val line = requires.firstOrNull { it.path == top.module }?.line?.plus(1) ?: 1
            val where = if (top.module == STDLIB) "the standard library" else "${top.module}${top.version?.let { "@$it" }.orEmpty()}"
            val fix = f.fixedVersion?.let { if (top.module == STDLIB) "; fixed in Go ${it.removePrefix("v")}" else "; fixed in $it" }.orEmpty()
            "go.mod:$line:1: $where is affected by ${f.osv}" + report.summaryOf(f.osv).let { if (it.isEmpty()) "" else ": $it" } + fix
        }
        return calls + modules
    }

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonObject.string(name: String): String? = get(name)?.asStringOrNull()
    private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    private fun JsonElement.asStringOrNull(): String? = takeIf { it.isJsonPrimitive }?.asString
}

/** The disk cache of a run: the key of go.mod + go.sum it was made for, when, and the output of govulncheck as it was. */
object GoVulnCache {
    private const val MAGIC = "govulncheck-cache 1"

    class Stored(val key: String, val at: Long, val stdout: String)

    /** go.mod and go.sum decide what govulncheck finds (with the database, which is why a result also ages). */
    fun key(goMod: ByteArray?, goSum: ByteArray?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(goMod ?: ByteArray(0))
        digest.update(byteArrayOf(0))
        digest.update(goSum ?: ByteArray(0))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun encode(stored: Stored): String = "$MAGIC\n${stored.key}\n${stored.at}\n${stored.stdout}"

    fun decode(text: String): Stored? {
        val lines = text.split('\n', limit = 4)
        if (lines.size < 4 || lines[0] != MAGIC) return null
        return Stored(lines[1], lines[2].toLongOrNull() ?: return null, lines[3])
    }

    /** Whether a result made at [at] for [key] still stands: the same files, younger than [maxAgeMs]. */
    fun fresh(key: String, at: Long, currentKey: String, now: Long, maxAgeMs: Long): Boolean = key == currentKey && now - at in 0..maxAgeMs

    /** The file name of the cache of the module at [root]. */
    fun fileName(root: String): String = key(root.toByteArray(), null).take(20) + ".json"
}
