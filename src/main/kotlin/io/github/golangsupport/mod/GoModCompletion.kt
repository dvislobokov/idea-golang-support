package io.github.golangsupport.mod

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import io.github.golangsupport.GoIcons
import io.github.golangsupport.cli.GoEnvironment
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** What the caret of a go.mod is at: the start of a line (a directive), the path of a module after `require`, or the version after a path. */
sealed class GoModContext {
    object Directive : GoModContext()
    class ModulePath(val directive: String, val typed: String) : GoModContext()
    class Version(val directive: String, val modulePath: String) : GoModContext()
    class GoVersion(val directive: String) : GoModContext()
}

/** By the text of the line before the caret; the directive of a block (`require (`) comes from the lines above. */
object GoModCompletion {
    private val OPEN_BLOCK = Regex("""^(\w+)\s*\($""")

    fun contextAt(text: CharSequence, offset: Int): GoModContext? {
        val at = offset.coerceIn(0, text.length)
        var lineStart = at
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val before = text.subSequence(lineStart, at).toString()
        if ("//" in before) return null
        val words = before.trimStart().split(Regex("\\s+"))
        val endsWithSpace = before.isNotEmpty() && before.last().isWhitespace()
        val block = blockDirective(text, lineStart)
        val directive = if (block != null) block else words.firstOrNull()?.takeIf { it in GoModFile.DIRECTIVES }
        val arguments = if (block != null) words.filter { it.isNotEmpty() } else words.drop(1).filter { it.isNotEmpty() }
        return when {
            block == null && (words.size == 1 && !endsWithSpace) -> GoModContext.Directive
            directive == null -> null
            directive in WITH_GO_VERSION -> if (arguments.isEmpty() || arguments.size == 1 && !endsWithSpace) GoModContext.GoVersion(directive) else null
            directive !in WITH_MODULES -> null
            arguments.isEmpty() || arguments.size == 1 && !endsWithSpace -> GoModContext.ModulePath(directive, arguments.firstOrNull().orEmpty())
            arguments.size == 1 || arguments.size == 2 && !endsWithSpace -> GoModContext.Version(directive, arguments[0])
            else -> null
        }
    }

    /** The directive of an open `name (` block above [lineStart], or null on the top level. */
    private fun blockDirective(text: CharSequence, lineStart: Int): String? {
        var end = lineStart
        while (end > 0) {
            var start = end - 1
            while (start > 0 && text[start - 1] != '\n') start--
            val line = text.subSequence(start, end - 1).toString().trim()
            if (line == ")") return null
            OPEN_BLOCK.matchEntire(line)?.let { return it.groupValues[1] }
            end = start
        }
        return null
    }

    private val WITH_MODULES = setOf("require", "replace", "exclude", "retract", "tool")
    private val WITH_GO_VERSION = setOf("go", "toolchain")
}

/**
 * Where module paths and versions come from without asking the network: the download cache of the module cache has a directory per
 * module and a `@v/list` file with the versions that were ever fetched; the proxy adds the rest, once, with a short timeout.
 */
object GoModSources {
    private val LOG = logger<GoModSources>()
    private val proxyVersions = ConcurrentHashMap<String, List<String>>()
    @Volatile private var cachedPaths: Pair<String, List<String>>? = null

    /** Every module path the download cache holds, unescaped (`!x` is an upper-case letter): the paths a developer has used on this machine. */
    fun modulePaths(): List<String> {
        val cache = GoEnvironment.quick().goModCache ?: return emptyList()
        cachedPaths?.takeIf { it.first == cache }?.let { return it.second }
        val download = File(cache, "cache/download")
        val result = ArrayList<String>()
        fun walk(directory: File, path: String, depth: Int) {
            if (depth > 8) return
            val children = directory.listFiles() ?: return
            if (children.any { it.name == "@v" && it.isDirectory }) result += path
            for (child in children) if (child.isDirectory && child.name != "@v" && !child.name.startsWith("sumdb")) walk(child, if (path.isEmpty()) child.name else "$path/${child.name}", depth + 1)
        }
        walk(download, "", 0)
        val paths = result.map(::unescape).sorted()
        cachedPaths = cache to paths
        return paths
    }

    /** The versions of [modulePath] the cache has, newest last as the file lists them, then the ones the proxy names. */
    fun versions(modulePath: String, askProxy: Boolean): List<String> {
        val local = GoEnvironment.quick().goModCache?.let { File(it, "cache/download/${escape(modulePath)}/@v/list") }
            ?.takeIf { it.isFile }?.readLines()?.map(String::trim)?.filter { it.startsWith("v") }.orEmpty()
        val remote = if (askProxy) proxyVersions.getOrPut(modulePath) { fromProxy(modulePath) } else proxyVersions[modulePath].orEmpty()
        return (remote + local).distinct().sortedWith(::compareVersions).asReversed()
    }

    private fun fromProxy(modulePath: String): List<String> {
        val proxy = GoEnvironment.quick().values["GOPROXY"]?.split(',', '|')?.map(String::trim)?.firstOrNull { it.startsWith("http") } ?: "https://proxy.golang.org"
        return try {
            val request = HttpRequest.newBuilder(URI.create("${proxy.trimEnd('/')}/${escape(modulePath)}/@v/list")).timeout(Duration.ofSeconds(3)).GET().build()
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) response.body().lines().map(String::trim).filter { it.startsWith("v") } else emptyList()
        } catch (e: Exception) {
            LOG.info("versions of $modulePath from $proxy: $e")
            emptyList()
        }
    }

    fun escape(text: String): String = buildString { for (c in text) if (c.isUpperCase()) append('!').append(c.lowercaseChar()) else append(c) }
    fun unescape(text: String): String = Regex("!([a-z])").replace(text) { it.groupValues[1].uppercase() }

    /** `v1.10.0` after `v1.9.0`, a pre-release before its release. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.removePrefix("v").substringBefore('+').split('-', limit = 2).let { it[0].split('.').map { n -> n.toIntOrNull() ?: 0 } to it.getOrNull(1) }
        val (na, pa) = parts(a)
        val (nb, pb) = parts(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val d = (na.getOrNull(i) ?: 0).compareTo(nb.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return when {
            pa == null && pb == null -> 0
            pa == null -> 1
            pb == null -> -1
            else -> pa.compareTo(pb)
        }
    }
}

/** Completion in go.mod and go.work: directives, the module paths of the cache after `require` and the like, versions after a path, Go versions after `go`. */
class GoModCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(GoModPsiFile::class.java)), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val text = parameters.editor.document.immutableCharSequence
                when (val at = GoModCompletion.contextAt(text, parameters.offset)) {
                    null -> Unit
                    GoModContext.Directive -> GoModFile.DIRECTIVES.forEach { result.addElement(LookupElementBuilder.create(it).bold()) }
                    is GoModContext.ModulePath -> {
                        val prefix = at.typed
                        val set = result.withPrefixMatcher(prefix)
                        for (path in GoModSources.modulePaths()) set.addElement(LookupElementBuilder.create(path).withIcon(GoIcons.Package))
                    }
                    is GoModContext.Version -> {
                        // the version being typed is the prefix; the proxy is asked once per module, not per keystroke
                        val typed = text.subSequence(0, parameters.offset).toString().substringAfterLast(' ').substringAfterLast('\t')
                        val set = result.withPrefixMatcher(typed)
                        GoModSources.versions(at.modulePath, askProxy = !parameters.isAutoPopup).forEachIndexed { index, version ->
                            set.addElement(com.intellij.codeInsight.completion.PrioritizedLookupElement.withPriority(LookupElementBuilder.create(version).withTypeText(at.modulePath, true), -index.toDouble()))
                        }
                    }
                    is GoModContext.GoVersion -> {
                        val installed = GoEnvironment.quick().goVersion ?: return
                        val versions = if (at.directive == "toolchain") listOf("go$installed") else listOf(installed.substringBeforeLast('.').takeIf { it.count { c -> c == '.' } == 1 } ?: installed, installed).distinct()
                        versions.forEach { result.addElement(LookupElementBuilder.create(it).withTypeText("installed", true)) }
                    }
                }
                result.stopHere()
            }
        })
    }
}
