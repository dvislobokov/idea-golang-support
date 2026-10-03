package io.github.golangsupport.settings

import io.github.golangsupport.lang.GoProjectPresence
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import io.github.golangsupport.mod.GoModulesService
import io.github.golangsupport.project.api.GoToolchainProvider

/** The words and the lists of the platform widget; pure, for the tests. */
object GoPlatformChoices {
    /** What `go tool dist list` prints (Go 1.24); a pair outside it is not a target `go build` accepts. */
    val ALL_PAIRS: List<String> = listOf(
        "aix/ppc64", "android/386", "android/amd64", "android/arm", "android/arm64", "darwin/amd64", "darwin/arm64", "dragonfly/amd64",
        "freebsd/386", "freebsd/amd64", "freebsd/arm", "freebsd/arm64", "freebsd/riscv64", "illumos/amd64", "ios/amd64", "ios/arm64",
        "js/wasm", "linux/386", "linux/amd64", "linux/arm", "linux/arm64", "linux/loong64", "linux/mips", "linux/mips64", "linux/mips64le",
        "linux/mipsle", "linux/ppc64", "linux/ppc64le", "linux/riscv64", "linux/s390x", "netbsd/386", "netbsd/amd64", "netbsd/arm", "netbsd/arm64",
        "openbsd/386", "openbsd/amd64", "openbsd/arm", "openbsd/arm64", "openbsd/ppc64", "openbsd/riscv64", "plan9/386", "plan9/amd64", "plan9/arm",
        "solaris/amd64", "wasip1/wasm", "windows/386", "windows/amd64", "windows/arm", "windows/arm64",
    )

    val COMMON_PAIRS: List<String> = listOf("linux/amd64", "linux/arm64", "darwin/arm64", "windows/amd64", "js/wasm", "wasip1/wasm")

    /** `linux/amd64`, with ` · tag1,tag2` when tags are set and ` · cgo off` when Cgo support is disabled. */
    fun text(goos: String, goarch: String, tags: List<String>, cgoOff: Boolean = false): String =
        "$goos/$goarch" + (if (tags.isEmpty()) "" else " · " + tags.joinToString(",")) + if (cgoOff) " · cgo off" else ""

    /** The (GOOS, GOARCH) of a `os/arch` string; null when there is no slash. */
    fun split(pair: String): Pair<String, String>? = pair.substringBefore('/', "").takeIf { it.isNotEmpty() }?.let { it to pair.substringAfter('/') }

    /** Common pairs first, then the rest in the order of the platform list. */
    fun ordered(): List<String> = COMMON_PAIRS + (ALL_PAIRS - COMMON_PAIRS.toSet())

    /** Every GOOS / GOARCH of [ALL_PAIRS], sorted: the choices of Settings | Go | Build Tags. */
    fun operatingSystems(): List<String> = ALL_PAIRS.mapNotNull { split(it)?.first }.distinct().sorted()
    fun architectures(): List<String> = ALL_PAIRS.mapNotNull { split(it)?.second }.distinct().sorted()

    /** GOOS / GOARCH of this machine, what an empty setting means when the toolchain does not say otherwise. */
    fun hostOs(): String = System.getProperty("os.name").lowercase().let { if ("win" in it) "windows" else if ("mac" in it) "darwin" else "linux" }
    fun hostArch(): String = System.getProperty("os.arch").lowercase().let { if (it == "aarch64") "arm64" else if (it == "x86_64" || it == "amd64") "amd64" else it }
}

/** GOOS / GOARCH / build tags of the analysis in the status bar, for projects with Go code; a click changes them. Affects the built-in analysis (go-psi), not gopls. */
class GoPlatformWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID
    override fun getDisplayName(): String = "Go Build Target (GOOS/GOARCH)"
    override fun isAvailable(project: Project): Boolean = GoProjectPresence.hasGoFiles(project) && GoModulesService.getInstance(project).let { it.modules().isNotEmpty() || it.workspace() != null }
    override fun createWidget(project: Project): StatusBarWidget = GoPlatformWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val ID = "Go.Platform.Status"

        fun refresh(project: Project) = ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
        }, ModalityState.any())

        /**
         * After GOOS / GOARCH / tags changed: the toolchain provider keys its answer on the settings, so asking it (off the UI thread, it
         * may look at the disk) bumps the model trackers; then the highlighting restarts and the widgets show the new target.
         */
        fun reanalyze() {
            ApplicationManager.getApplication().executeOnPooledThread {
                for (p in ProjectManager.getInstance().openProjects) if (!p.isDisposed) runCatching { GoToolchainProvider.getInstance().toolchainFor(p) }
                ApplicationManager.getApplication().invokeLater({
                    for (p in ProjectManager.getInstance().openProjects) if (!p.isDisposed) { DaemonCodeAnalyzer.getInstance(p).restart(); refresh(p) }
                }, ModalityState.any())
            }
        }
    }
}

class GoPlatformWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.MultipleTextValuesPresentation {
    private val settings get() = GoSettings.getInstance()

    override fun ID(): String = GoPlatformWidgetFactory.ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun install(statusBar: StatusBar) = Unit
    override fun dispose() = Unit

    /** The effective target: the settings when set, else what the toolchain (the host, `go env`) answers. */
    private fun effective(): Pair<String, String> {
        val info = runCatching { GoToolchainProvider.getInstance().toolchainFor(project) }.getOrNull()
        return (settings.analysisGoos.ifEmpty { info?.goos ?: hostOs() }) to (settings.analysisGoarch.ifEmpty { info?.goarch ?: hostArch() })
    }

    private fun hostOs(): String = GoPlatformChoices.hostOs()
    private fun hostArch(): String = GoPlatformChoices.hostArch()

    override fun getSelectedValue(): String = effective().let { GoPlatformChoices.text(it.first, it.second, settings.tagList(), settings.cgoMode == GoCgoMode.DISABLED) }
    override fun getTooltipText(): String =
        "Go analysis target: GOOS/GOARCH" + (if (settings.analysisGoos.isEmpty() && settings.analysisGoarch.isEmpty()) " (host default)" else "") + ". Click to change"

    override fun getPopup(): JBPopup {
        val items = listOf(HOST) + GoPlatformChoices.ordered() + TAGS
        val allPlatforms = GoPlatformChoices.ordered()[GoPlatformChoices.COMMON_PAIRS.size]
        val step = object : BaseListPopupStep<String>("Go Build Target", items) {
            override fun isSpeedSearchEnabled(): Boolean = true
            override fun getSeparatorAbove(value: String): ListSeparator? = when (value) {
                GoPlatformChoices.COMMON_PAIRS.first(), TAGS -> ListSeparator()
                allPlatforms -> ListSeparator("All platforms")
                else -> null
            }

            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                ApplicationManager.getApplication().invokeLater({ if (selectedValue == TAGS) editTags() else choose(GoPlatformChoices.split(selectedValue)) }, ModalityState.any())
                return FINAL_CHOICE
            }
        }
        return JBPopupFactory.getInstance().createListPopup(step)
    }

    /** The tags, and the target as combos, are on Settings | Go | Build Tags: one place to edit them. */
    private fun editTags() = ShowSettingsUtil.getInstance().showSettingsDialog(project, GoBuildTagsConfigurable::class.java)

    private fun choose(pair: Pair<String, String>?) {
        settings.analysisGoos = pair?.first.orEmpty()
        settings.analysisGoarch = pair?.second.orEmpty()
        reanalyze()
    }

    private fun reanalyze() = GoPlatformWidgetFactory.reanalyze()

    private companion object {
        const val HOST = "Host default"
        const val TAGS = "Edit build tags…"
    }
}
