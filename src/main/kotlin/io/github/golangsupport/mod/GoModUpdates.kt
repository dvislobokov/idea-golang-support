package io.github.golangsupport.mod

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoPluginLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The newer versions the module proxy knows for the requires of a go.mod, from `go list -m -e -json path@latest...` run in the background: what
 * GoLand (and gopls, now off by default) shows in the editor. Asked when the file is highlighted, again when its requires change and at
 * most once an [MAX_AGE_MS]; a failed run (no network, a private module) is not repeated for that time either. Not `go list -m -u all`:
 * that one walks the whole graph and fails as soon as go.sum lacks the version typed into go.mod by hand (seen live).
 */
object GoModUpdates {
    private const val MAX_AGE_MS = 60 * 60 * 1000L
    private const val MIN_GAP_MS = 30 * 1000L

    private class Entry(val requires: String, val at: Long, val updates: Map<String, String>?, val issues: GoModIssues = GoModIssues.NONE)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** The require with a newer version available, and that version: only where the file still has another version. Pure, for the tests. */
    fun outdated(requires: List<GoRequire>, updates: Map<String, String>): List<Pair<GoRequire, String>> =
        requires.mapNotNull { require -> updates[require.path]?.takeIf { newer(it, require.version) }?.let { require to it } }

    /**
     * Whether semantic version [a] is above [b]: `latest` is never a downgrade offer, also not for a pseudo-version or a pre-release
     * newer than the last release. Unparsable versions are not compared.
     */
    fun newer(a: String, b: String): Boolean {
        val x = parse(a) ?: return false
        val y = parse(b) ?: return false
        for (i in 0..2) if (x.first[i] != y.first[i]) return x.first[i] > y.first[i]
        return when {
            x.second == y.second -> false
            x.second == null -> true
            y.second == null -> false
            else -> x.second!! > y.second!!
        }
    }

    private val SEMVER = Regex("""^v(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

    private fun parse(version: String): Pair<List<Long>, String?>? {
        val m = SEMVER.matchEntire(version) ?: return null
        return listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3]).map { it.toLongOrNull() ?: return null } to m.groupValues[4].ifEmpty { null }
    }

    /** The updates known for the go.mod at [path] whose requires are [requires]; asks for them in the background when there are none or they are old. */
    fun updates(project: Project, path: String, requires: List<GoRequire>): Map<String, String>? {
        val key = requires.joinToString(",") { "${it.path}@${it.version}" }
        val entry = cache[path]
        val now = System.currentTimeMillis()
        val stale = entry == null || now - entry.at > MAX_AGE_MS || entry.requires != key && now - entry.at > MIN_GAP_MS
        if (stale && running.add(path)) ApplicationManager.getApplication().executeOnPooledThread { ask(project, path, key, requires.map { it.path }.distinct()) }
        return entry?.updates
    }

    /** Deprecated modules and retracted required versions of the go.mod at [path], from the last background check; null before it. */
    fun issues(project: Project, path: String, requires: List<GoRequire>): GoModIssues? {
        updates(project, path, requires)
        return cache[path]?.takeIf { it.updates != null }?.issues
    }

    private fun ask(project: Project, path: String, key: String, modules: List<String>) {
        try {
            val dir = File(path).parent
            // -e: a module the proxy does not know (private, removed) gets an Error field instead of failing the others;
            // -mod=readonly: with a vendor/ directory go defaults to -mod=vendor and refuses every query (seen live); readonly writes nothing;
            // -u: fills Deprecated (the comment of the latest go.mod) without changing the version a @latest query answers
            val arguments = arrayOf("list", "-m", "-e", "-u", "-mod=readonly", "-json") + modules.filter(::isModuleArgument).map { "$it@latest" }
            val output = runCatching { GoCli.execute(GoCli.commandLine(dir, *arguments), 120_000) }.getOrNull()
            val parsed = output?.takeIf { it.exitCode == 0 || it.stdout.isNotBlank() }?.let { GoModuleList.parse(it.stdout) }
            if (parsed == null) GoPluginLog.info("go", "No update check for $path: " + (output?.stderr?.lines()?.firstOrNull { it.isNotBlank() } ?: "go list has failed"))
            val previous = cache[path]
            val updates = parsed?.mapNotNull { m -> m.version?.let { m.path to it } }?.toMap()
            val issues = if (parsed == null) GoModIssues.NONE else GoModIssues(GoModIssues.deprecated(parsed), retracted(dir, key))
            cache[path] = Entry(key, System.currentTimeMillis(), updates, issues)
            if (updates != null && (updates != previous?.updates || issues != previous.issues)) rehighlight(project, path)
        } finally {
            running.remove(path)
        }
    }

    /** `path@version` -> rationales of the required versions that are retracted: `go list -m -retracted` on the exact versions. */
    private fun retracted(dir: String, key: String): Map<String, List<String>> {
        val targets = key.split(',').filter { '@' in it && !it.endsWith("@") && isModuleArgument(it) }.distinct()
        if (targets.isEmpty()) return emptyMap()
        val arguments = arrayOf("list", "-m", "-e", "-retracted", "-mod=readonly", "-json") + targets
        val output = runCatching { GoCli.execute(GoCli.commandLine(dir, *arguments), 120_000) }.getOrNull() ?: return emptyMap()
        return GoModIssues.retracted(GoModuleList.parse(output.stdout))
    }

    /** A go.mod path (with an optional `@version`) safe to pass to `go` as a positional argument: never flag-like, no whitespace. */
    fun isModuleArgument(target: String): Boolean = target.isNotEmpty() && !target.startsWith("-") && target.none { it.isWhitespace() }

    /** `go get targets...` in the background, then `go mod vendor` when the module vendors: a stale vendor/ breaks the build. */
    fun goGet(project: Project, dir: String, title: String, targets: List<String>) {
        if (targets.any { !isModuleArgument(it) }) return GoPluginLog.warn("go", "go get skipped: flag-like target in ${targets.filterNot(::isModuleArgument)}")
        if (targets.isEmpty()) return
        val vendored = File(dir, "vendor/modules.txt").isFile
        val commands = GoCli.commandLinesOrNotify(project, title) {
            listOfNotNull(GoCli.commandLine(dir, "get", *targets.toTypedArray()), if (vendored) GoCli.commandLine(dir, "mod", "vendor") else null)
        } ?: return
        GoCli.runInBackground(project, title, commands, refresh = listOf(File(dir)))
    }

    private fun rehighlight(project: Project, path: String) {
        if (project.isDisposed) return
        val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        ReadAction.run<RuntimeException> {
            if (!project.isDisposed) PsiManager.getInstance(project).findFile(file)?.let { DaemonCodeAnalyzer.getInstance(project).restart(it, "go.mod updates") }
        }
    }
}

/** A require of go.mod with a newer version in the module proxy: a weak warning on the version, Alt+Enter upgrades it with `go get`. */
class GoModUpdatesInspection : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is GoModPsiFile || file.name != GoModFileType.GO_MOD || !isOnTheFly) return null
        val path = file.virtualFile?.path ?: return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val requires = GoModFile.parse(document.immutableCharSequence).requires
        if (requires.isEmpty()) return null
        val updates = GoModUpdates.updates(file.project, path, requires) ?: return null
        return GoModUpdates.outdated(requires, updates).mapNotNull { (require, version) ->
            if (require.line >= document.lineCount) return@mapNotNull null
            val start = document.getLineStartOffset(require.line)
            val line = document.getText(TextRange(start, document.getLineEndOffset(require.line)))
            val at = line.indexOf(require.version).takeIf { it >= 0 } ?: return@mapNotNull null
            val range = TextRange(start + at, start + at + require.version.length)
            manager.createProblemDescriptor(file, range, "Newer version is available: $version", ProblemHighlightType.WEAK_WARNING, true,
                UpgradeRequireFix(require.path, version))
        }.toTypedArray()
    }
}

class UpgradeRequireFix(private val path: String, private val version: String) : LocalQuickFix {
    override fun getFamilyName(): String = "Upgrade dependency"
    override fun getName(): String = "Upgrade to $version"
    override fun startInWriteAction(): Boolean = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val dir = descriptor.psiElement?.containingFile?.virtualFile?.parent?.path ?: return
        GoModUpdates.goGet(project, dir, "Go Get $path@$version", listOf("$path@$version"))
    }
}

/** What `go list -m -u` / `-retracted` says about the requires of a go.mod beyond newer versions. */
data class GoModIssues(val deprecated: Map<String, String>, val retracted: Map<String, List<String>>) {
    companion object {
        val NONE = GoModIssues(emptyMap(), emptyMap())

        /** Module path -> deprecation comment. Pure, for the tests. */
        fun deprecated(modules: List<GoModuleInfo>): Map<String, String> =
            modules.mapNotNull { m -> m.deprecated?.trim()?.takeIf { it.isNotEmpty() }?.let { m.path to it } }.toMap()

        /** `path@version` -> rationales, for the retracted versions only. Pure, for the tests. */
        fun retracted(modules: List<GoModuleInfo>): Map<String, List<String>> =
            modules.mapNotNull { m -> m.version?.takeIf { m.retracted.isNotEmpty() }?.let { "${m.path}@$it" to m.retracted } }.toMap()

        /** The problems of [requires]: (require, true for the path / false for the version, message). Pure, for the tests. */
        fun problems(requires: List<GoRequire>, issues: GoModIssues): List<Triple<GoRequire, Boolean, String>> = requires.flatMap { r ->
            listOfNotNull(
                issues.deprecated[r.path]?.let { Triple(r, true, "Module '${r.path}' is deprecated: $it") },
                issues.retracted["${r.path}@${r.version}"]?.let { Triple(r, false, "Version ${r.version} of '${r.path}' is retracted: ${it.joinToString("; ")}") },
            )
        }
    }
}

/**
 * Base of GoLand's `go list -m -u` dependency inspections over [GoModIssues]: the path ([onPath]) or the version of the require, from the
 * background check of [GoModUpdates] (nothing until it has answered, nothing offline).
 */
abstract class GoModIssuesInspectionBase(private val onPath: Boolean) : LocalInspectionTool() {
    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is GoModPsiFile || file.name != GoModFileType.GO_MOD || !isOnTheFly) return null
        val path = file.virtualFile?.path ?: return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val requires = GoModFile.parse(document.immutableCharSequence).requires
        if (requires.isEmpty()) return null
        val issues = GoModUpdates.issues(file.project, path, requires) ?: return null
        val updates = GoModUpdates.updates(file.project, path, requires).orEmpty()
        return GoModIssues.problems(requires, issues).filter { it.second == onPath }.mapNotNull { (require, _, message) ->
            if (require.line >= document.lineCount) return@mapNotNull null
            val start = document.getLineStartOffset(require.line)
            val line = document.getText(TextRange(start, document.getLineEndOffset(require.line)))
            val needle = if (onPath) require.path else require.version
            val at = line.indexOf(needle).takeIf { it >= 0 } ?: return@mapNotNull null
            val fixes = updates[require.path]?.takeIf { !onPath && GoModUpdates.newer(it, require.version) }?.let { arrayOf<LocalQuickFix>(UpgradeRequireFix(require.path, it)) }
                ?: LocalQuickFix.EMPTY_ARRAY
            manager.createProblemDescriptor(file, TextRange(start + at, start + at + needle.length), message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, true, *fixes)
        }.toTypedArray()
    }
}

/** GoLand's `VgoDependencyDeprecated`: a require of a module whose latest go.mod carries a `// Deprecated:` comment. */
class GoModDeprecatedDependencyInspection : GoModIssuesInspectionBase(onPath = true)

/** GoLand's `VgoDependencyVersionRetracted`: the required version is retracted by its module; Alt+Enter upgrades when a newer version is known. */
class GoModRetractedVersionInspection : GoModIssuesInspectionBase(onPath = false)
