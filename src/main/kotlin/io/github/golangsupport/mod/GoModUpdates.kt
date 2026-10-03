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

    private class Entry(val requires: String, val at: Long, val updates: Map<String, String>?)

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

    private fun ask(project: Project, path: String, key: String, modules: List<String>) {
        try {
            val dir = File(path).parent
            // -e: a module the proxy does not know (private, removed) gets an Error field instead of failing the others
            val output = runCatching { GoCli.execute(GoCli.commandLine(dir, "list", "-m", "-e", "-json", *modules.map { "$it@latest" }.toTypedArray()), 120_000) }.getOrNull()
            val parsed = output?.takeIf { it.exitCode == 0 || it.stdout.isNotBlank() }?.let { GoModuleList.parse(it.stdout) }
            if (parsed == null) GoPluginLog.info("go", "No update check for $path: " + (output?.stderr?.lines()?.firstOrNull { it.isNotBlank() } ?: "go list has failed"))
            val previous = cache[path]?.updates
            val updates = parsed?.mapNotNull { m -> m.version?.let { m.path to it } }?.toMap()
            cache[path] = Entry(key, System.currentTimeMillis(), updates)
            if (updates != null && updates != previous) rehighlight(project, path)
        } finally {
            running.remove(path)
        }
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
        val title = "Go Get $path@$version"
        val commands = GoCli.commandLinesOrNotify(project, title) { listOf(GoCli.commandLine(dir, "get", "$path@$version")) } ?: return
        GoCli.runInBackground(project, title, commands, refresh = listOf(File(dir)))
    }
}
