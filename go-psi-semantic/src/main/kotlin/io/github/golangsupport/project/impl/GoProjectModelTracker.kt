package io.github.golangsupport.project.impl

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.util.messages.Topic
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import org.jetbrains.annotations.ApiStatus

/**
 * Modification tracker of the Go project model: bumped when a go.mod, go.work, go.sum,
 * go.work.sum or vendor/modules.txt file is created, changed, moved, renamed or deleted (and when
 * the toolchain or a `go list` fallback result changes). Module graph and package caches depend on it.
 */
@ApiStatus.Internal
@Service(Service.Level.PROJECT)
class GoProjectModelTracker(private val project: Project) : SimpleModificationTracker() {

    private val bumps = java.util.concurrent.atomic.AtomicInteger()

    /** Every bump drops the caches of the model (module graphs, packages, resolve) and recomputes the roots: callers say why, for the journal. */
    fun bump(reason: String) {
        super.incModificationCount()
        journal(project, "project model bumped #${bumps.incrementAndGet()} ($reason)")
        GoRootsProvider.scheduleRootsUpdate(project)
    }

    override fun incModificationCount() = bump("unspecified")

    /**
     * What the project model did that costs time at project open (bumps, `go list` served from disk), for the journal of the plugin:
     * go-psi does not know the plugin's log, the plugin subscribes to this topic.
     */
    fun interface Journal {
        fun log(project: Project, message: String)
    }

    companion object {
        private val LOG = logger<GoProjectModelTracker>()

        @JvmField
        @Topic.ProjectLevel
        val JOURNAL: Topic<Journal> = Topic(Journal::class.java, Topic.BroadcastDirection.NONE)

        /** A line for [JOURNAL] (and the IDE log). */
        @JvmStatic
        fun journal(project: Project, message: String) {
            LOG.info("go-psi: $message")
            if (!project.isDisposed) project.messageBus.syncPublisher(JOURNAL).log(project, message)
        }

        private val MODEL_FILES = setOf("go.mod", "go.work", "go.sum", "go.work.sum")

        @JvmStatic
        fun getInstance(project: Project): GoProjectModelTracker = project.service()

        /** Whether [name] (with its parent directory name) is a project model input. */
        @JvmStatic
        fun isModelFile(name: String, parentName: String?): Boolean =
            name in MODEL_FILES || (name == "modules.txt" && parentName == "vendor") || name == "vendor"
    }

    /** Project-level VFS listener registered in go-psi-semantic.xml. */
    class Listener(private val project: Project) : BulkFileListener {
        private val IDE_DIRECTORIES = listOf(com.intellij.openapi.application.PathManager.getPluginsPath(), com.intellij.openapi.application.PathManager.getSystemPath(),
            com.intellij.openapi.application.PathManager.getConfigPath()).map { com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(it).trimEnd('/') + "/" }

        override fun after(events: List<VFileEvent>) {
            if (project.isDisposed) return
            val event = events.firstOrNull(::affectsModel) ?: return
            getInstance(project).bump("model file changed: ${event.path} (${event.javaClass.simpleName})")
        }

        private fun affectsModel(event: VFileEvent): Boolean {
            // the IDE's own directories are no project's model: a plugin carries Go sources with a go.mod (seen live: the bundled delve
            // of the plugin, rewritten on every update, bumped the model of every open project)
            if (IDE_DIRECTORIES.any { event.path.startsWith(it) }) return false
            val name = when (event) {
                is VFileCreateEvent -> event.childName
                is VFilePropertyChangeEvent -> if (event.propertyName == com.intellij.openapi.vfs.VirtualFile.PROP_NAME) {
                    return isModelFile(event.oldValue as? String ?: "", null) || isModelFile(event.newValue as? String ?: "", null)
                } else return false
                is VFileContentChangeEvent, is VFileDeleteEvent, is VFileMoveEvent -> event.file?.name ?: return false
                else -> return false
            }
            val parentName = when (event) {
                is VFileCreateEvent -> event.parent.name
                else -> event.file?.parent?.name
            }
            return isModelFile(name, parentName)
        }
    }
}
