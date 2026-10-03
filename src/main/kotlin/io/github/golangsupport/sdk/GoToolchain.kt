package io.github.golangsupport.sdk

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.SimpleModificationTracker
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.cli.GoEnvironment
import io.github.golangsupport.project.api.GoToolchainInfo
import io.github.golangsupport.project.api.GoToolchainProvider
import io.github.golangsupport.project.impl.DefaultGoToolchainProvider
import io.github.golangsupport.project.impl.GoProjectModelTracker
import io.github.golangsupport.settings.GoCgoMode
import io.github.golangsupport.settings.GoSettings
import java.nio.file.Path

/**
 * The toolchain of the project model, from what the plugin already knows: the `go` of Settings | Go | GOROOT (or the one it found),
 * its `go env -json` ([GoEnvironment], read once in the background), the build tags of the settings and the GOOS / GOARCH the code is
 * analysed for. Replaces `DefaultGoToolchainProvider` of go-psi (the service is overridden in plugin.xml); its pure detection stays the
 * fallback while `go env` has not answered, and when there is no `go` at all. Never runs a process itself.
 */
class GoIgsToolchainProvider : GoToolchainProvider {
    private val tracker = SimpleModificationTracker()
    private val fallback = DefaultGoToolchainProvider()

    /**
     * The last answer with what it was made of. Asked on every import resolve of the model (thousands of times per highlighting pass):
     * the detection walks the PATH and GOROOT on disk (`GoCli.findExecutable`, `detectPure`), which held a read action for 12 s in a
     * freeze (seen live). Recomputed when the settings or `go env` change, and on [invalidate] (Go | Reanalyze), not per call.
     */
    @Volatile private var last: Answer? = null

    private data class Key(val configuredGo: String, val envKnown: Boolean, val tags: List<String>, val goos: String, val goarch: String, val cgo: GoCgoMode, val experiments: String)

    private class Answer(val key: Key, val info: GoToolchainInfo?)

    override val modificationTracker: ModificationTracker get() = tracker

    override fun toolchainFor(project: Project?): GoToolchainInfo? {
        val settings = GoSettings.getInstance()
        val key = Key(settings.goPath, GoEnvironment.isKnown(), settings.tagList(), settings.analysisGoos, settings.analysisGoarch, settings.cgoMode, settings.goExperiments)
        last?.takeIf { it.key == key }?.let { return it.info }
        val info = compute(project, settings)
        last = Answer(key, info)
        tracker.incModificationCount()
        for (p in ProjectManager.getInstance().openProjects) if (!p.isDisposed) GoProjectModelTracker.getInstance(p).incModificationCount()
        return info
    }

    /** Forgets the last answer: the next question looks at the disk again (a `go` installed meanwhile, a changed PATH). */
    fun invalidate() {
        last = null
    }

    private fun compute(project: Project?, settings: GoSettings): GoToolchainInfo? {
        val executable = GoCli.findExecutable() ?: return adjust(fallback.toolchainFor(project), settings)
        if (!GoEnvironment.isKnown()) {
            // the answer of `go env` comes later and changes the key; until then the pure detection, with this go
            GoEnvironment.whenKnown { toolchainFor(project) }
            return adjust(pureFor(executable), settings)
        }
        val env = GoEnvironment.quick().values
        return adjust(DefaultGoToolchainProvider.fromGoEnv(env, Path.of(executable), pureFor(executable)), settings)
    }

    private fun pureFor(executable: String): GoToolchainInfo? = DefaultGoToolchainProvider.detectPure(System.getenv())?.copy(goBinary = Path.of(executable))

    /**
     * The tags of the settings on top of `GOFLAGS`, the GOOS / GOARCH of the settings when they are set, Cgo support forced on or off,
     * and the Experiments of the settings in place of `GOEXPERIMENT` of the environment (what [GoToolchainInfo.buildContext] turns into
     * `goexperiment.X` tags): the build constraints of the analysis follow Settings | Go | Build Tags.
     */
    private fun adjust(info: GoToolchainInfo?, settings: GoSettings): GoToolchainInfo? {
        info ?: return null
        val tags = settings.tagList()
        val goos = settings.analysisGoos
        val goarch = settings.analysisGoarch
        val cgo = settings.cgoMode.forced
        val experiments = settings.goExperiments
        if (tags.isEmpty() && goos.isEmpty() && goarch.isEmpty() && cgo == null && experiments.isEmpty()) return info
        return info.copy(
            buildTags = info.buildTags + tags, goos = goos.ifEmpty { info.goos }, goarch = goarch.ifEmpty { info.goarch }, cgoEnabled = cgo ?: info.cgoEnabled,
            env = if (experiments.isEmpty()) info.env else info.env + ("GOEXPERIMENT" to experiments),
        )
    }
}
