package io.github.golangsupport.lang.palette

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleListCellRenderer
import io.github.golangsupport.GoBundle
import io.github.golangsupport.cli.GoCli
import io.github.golangsupport.lang.GoFileType

/** An entry of the combo box and of the popup: a palette, or "IDE default" ([palette] null). */
data class GoPaletteChoice(val id: String, val palette: GoPalette?) {
    val name: String get() = palette?.name ?: GoBundle.message("palette.default")

    override fun toString(): String = name

    companion object {
        fun all(): List<GoPaletteChoice> = listOf(GoPaletteChoice(GoPalettes.DEFAULT_ID, null)) + GoPalettes.ALL.map { GoPaletteChoice(it.id, it) }

        fun of(id: String): GoPaletteChoice = all().firstOrNull { it.id == id } ?: all().first()
    }
}

/**
 * The Go palette chosen for the machine (Settings | Go | Editor, Go → Go Color Palette…), kept in the current color scheme of the IDE: written
 * into it when chosen, written again in the matching variant when the scheme or the theme changes ([GoPaletteSchemeListener]), taken out of
 * every scheme it went into when "IDE default" is chosen. What each scheme had before is kept in [State.backups] (see [GoPaletteWriter]).
 */
@Service(Service.Level.APP)
@State(name = "GoPalette", storages = [Storage("golang-support.xml")])
class GoPaletteService : SimplePersistentStateComponent<GoPaletteService.State>(State()) {
    class State : BaseState() {
        var palette by string(GoPalettes.DEFAULT_ID)
        /** "Don't Show Again" (or a palette chosen from the suggestion): the suggestion of palettes is not shown any more. */
        var promptDismissed by property(false)
        /** Name of a scheme a palette went into -> the Go attributes it had of its own before, see [GoPaletteWriter.backup]. */
        var backups by map<String, String>()
    }

    /** What the popup shows while the selection moves through its list; Esc puts [paletteId] back. */
    private var previewId: String? = null

    var paletteId: String
        get() = state.palette?.takeIf { it == GoPalettes.DEFAULT_ID || GoPalettes.find(it) != null } ?: GoPalettes.DEFAULT_ID
        private set(value) { state.palette = value }

    val effectiveId: String get() = previewId ?: paletteId

    var promptDismissed: Boolean
        get() = state.promptDismissed
        set(value) { state.promptDismissed = value }

    /** The choice of Settings and of the popup: kept, and the current scheme recolored at once. */
    fun choose(id: String) {
        if (id == paletteId && previewId == null) return ensureApplied()
        paletteId = id
        previewId = null
        if (id == GoPalettes.DEFAULT_ID) restoreAll() else applyTo(globalScheme())
    }

    fun preview(id: String) {
        previewId = id
        applyTo(globalScheme())
    }

    fun endPreview() {
        if (previewId == null) return
        previewId = null
        applyTo(globalScheme())
    }

    /** After a switch of the scheme or of the theme: the scheme gets the palette in the variant of its background, if it does not have it yet. */
    fun ensureApplied(scheme: EditorColorsScheme = globalScheme()) {
        val palette = GoPalettes.find(effectiveId) ?: return
        if (!GoPaletteWriter.canWrite(scheme) || GoPaletteWriter.matches(scheme, palette)) return
        applyTo(scheme)
    }

    /** [effectiveId] into [scheme]; "IDE default" takes the palette out of it. */
    fun applyTo(scheme: EditorColorsScheme) {
        if (!GoPaletteWriter.canWrite(scheme)) return
        val palette = GoPalettes.find(effectiveId)
        val backup = state.backups[scheme.name]
        if (palette == null) {
            if (backup == null) return
            GoPaletteWriter.restore(scheme, backup)
            setBackup(scheme.name, null)
        } else {
            val kept = backup ?: GoPaletteWriter.backup(scheme).also { setBackup(scheme.name, it) }
            GoPaletteWriter.write(scheme, palette, kept)
        }
        refresh(scheme)
    }

    /** "IDE default": every scheme a palette went into gets back what it had. */
    private fun restoreAll() {
        val manager = EditorColorsManager.getInstance()
        val global = globalScheme()
        for ((name, backup) in state.backups.toMap()) {
            val scheme = if (global.name == name) global else manager.getScheme(name)
            if (scheme != null && GoPaletteWriter.canWrite(scheme)) GoPaletteWriter.restore(scheme, backup)
            setBackup(name, null)
        }
        refresh(global)
    }

    private fun setBackup(name: String, backup: String?) {
        // a new map: that is how BaseState notices the change
        state.backups = state.backups.toMutableMap().apply { if (backup == null) remove(name) else put(name, backup) }
    }

    /** As the Color Scheme page after Apply: the editors repaint with the new attributes. */
    private fun refresh(scheme: EditorColorsScheme) {
        ApplicationManager.getApplication().messageBus.syncPublisher(EditorColorsManager.TOPIC).globalSchemeChange(scheme)
    }

    private fun globalScheme(): EditorColorsScheme = EditorColorsManager.getInstance().globalScheme

    companion object {
        fun getInstance(): GoPaletteService = service()
    }
}

/** A switch of the color scheme or of the theme of the IDE: the palette follows in the variant of the new background. */
class GoPaletteSchemeListener : EditorColorsListener {
    override fun globalSchemeChange(scheme: EditorColorsScheme?) {
        val service = GoPaletteService.getInstance()
        if (service.effectiveId == GoPalettes.DEFAULT_ID) return
        // after the switch has settled: LafManager updates the UI later, and our own refresh comes back here (a no-op then)
        ApplicationManager.getApplication().invokeLater({ service.ensureApplied() }, ModalityState.any())
    }
}

/** Go → Go Color Palette…: the list of palettes, each previewed in the open editors while the selection moves; Esc puts the previous back. */
class GoPaletteAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) = GoPalettePopup.show(e.project)
}

object GoPalettePopup {
    fun show(project: Project?) {
        val service = GoPaletteService.getInstance()
        val choices = GoPaletteChoice.all()
        val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle("Go Color Palette")
            .setRenderer(SimpleListCellRenderer.create("") { it.name })
            .setSelectedValue(GoPaletteChoice.of(service.paletteId), true)
            .setItemSelectedCallback { it?.let { choice -> service.preview(choice.id) } }
            .setItemChosenCallback { service.choose(it.id) }
            .setNamerForFiltering { it.name }
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (!event.isOk) service.endPreview()
                }
            })
            .createPopup()
        if (project != null) popup.showCenteredInCurrentWindow(project) else popup.showInFocusCenter()
    }
}

/**
 * The first Go file opened while Go has only the colors of the IDE (palette "IDE default", a scheme of the IDE without Go colors of a user):
 * a suggestion of the palettes, once a session until one is chosen or it is dismissed. Also where a palette lost from the scheme (its file
 * reset) is written again.
 */
class GoPalettePrompt(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (file.fileType != GoFileType || project.isDisposed) return
        val service = GoPaletteService.getInstance()
        service.ensureApplied()
        if (ApplicationManager.getApplication().isUnitTestMode || shown || service.promptDismissed || service.paletteId != GoPalettes.DEFAULT_ID) return
        val scheme = EditorColorsManager.getInstance().globalScheme
        if (!isIdeScheme(scheme) || GoPaletteWriter.definesOwnGoColors(scheme)) return
        shown = true
        NotificationGroupManager.getInstance().getNotificationGroup(GoCli.NOTIFICATION_GROUP)
            .createNotification("Make Go colors like GoLand, VS Code, Dracula…?",
                "A palette colors only Go and go.mod on top of the color scheme: the background and the other languages stay.",
                NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Choose Palette…") {
                service.promptDismissed = true
                GoPalettePopup.show(project)
            })
            .addAction(NotificationAction.createSimpleExpiring("Don't Show Again") { service.promptDismissed = true })
            .notify(project)
    }

    companion object {
        private var shown = false

        /** What EditorColorsManagerImpl (internal API) prefixes the editable copy of a bundled scheme with. */
        private const val EDITABLE_COPY_PREFIX = "_@user_"

        /** A scheme of the IDE (its editable copy `_@user_…`, or the bundled one itself), not one of a user. */
        fun isIdeScheme(scheme: EditorColorsScheme): Boolean = scheme.isReadOnly || scheme.name.startsWith(EDITABLE_COPY_PREFIX)
    }
}
