package io.github.golangsupport.project.impl

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
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

    override fun incModificationCount() {
        super.incModificationCount()
        GoRootsProvider.scheduleRootsUpdate(project)
    }

    companion object {
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
        override fun after(events: List<VFileEvent>) {
            if (project.isDisposed) return
            if (events.any(::affectsModel)) getInstance(project).incModificationCount()
        }

        private fun affectsModel(event: VFileEvent): Boolean {
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
