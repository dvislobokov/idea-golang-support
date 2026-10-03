package io.github.golangsupport.semantic

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.golangsupport.project.impl.GoProjectModelTracker
import java.nio.file.Files
import java.nio.file.Path

/** Which go.mod changes move the project model (seen live: the bundled delve of the plugin bumped it on every start). */
class GoModelListenerTest : GoSemanticTestBase() {
    override val group: String get() = "types"

    private val count get() = GoProjectModelTracker.getInstance(project).modificationCount

    fun testGoModOfTheProjectBumpsTheModel() {
        val before = count
        myFixture.addFileToProject("m/go.mod", "module example.com/m\n")
        assertTrue(count > before)
    }

    fun testGoModInTheIdeDirectoriesDoesNot() {
        val dir = Files.createDirectories(Path.of(PathManager.getPluginsPath(), "some-plugin", "delve"))
        try {
            val parent = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
            val before = count
            WriteAction.run<Throwable> { parent.createChildData(this, "go.mod").setBinaryContent("module github.com/go-delve/delve\n".toByteArray()) }
            assertEquals(before, count)
        } finally {
            WriteAction.run<Throwable> { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.parent)?.delete(this) }
        }
    }
}
