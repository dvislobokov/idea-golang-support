package io.github.golangsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoProjectPresence
import io.github.golangsupport.mod.GoDependenciesToolWindowFactory
import io.github.golangsupport.monitor.GoMonitorToolWindowFactory
import io.github.golangsupport.settings.GoPlatformWidgetFactory
import io.github.golangsupport.testing.GoTestExplorerToolWindowFactory
import org.junit.Assert
import org.junit.Test

/** A project without Go files has no menu Go, no Go tool windows and no Go widget; the first Go file brings them back. */
class GoProjectPresenceTest : BasePlatformTestCase() {
    private val presence get() = GoProjectPresence.getInstance(project)

    private fun visible(id: String): Boolean {
        val action = ActionManager.getInstance().getAction(id)
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.update(event)
        return event.presentation.isVisible
    }

    private fun goUi(): List<Boolean> = listOf(
        visible("Go.MainMenu"), visible("Go.ProjectViewPopup"),
        GoTestExplorerToolWindowFactory().shouldBeAvailable(project), GoDependenciesToolWindowFactory().shouldBeAvailable(project),
        GoMonitorToolWindowFactory().shouldBeAvailable(project),
    )

    fun testNoGoFilesHideTheUiAndAGoFileBringsItBack() {
        myFixture.addFileToProject("README.md", "# not Go")
        assertFalse(presence.recompute())
        assertEquals(List(5) { false }, goUi())
        assertFalse(GoPlatformWidgetFactory().isAvailable(project))

        myFixture.addFileToProject("cmd/app/main.go", "package main\n\nfunc main() {}\n")
        assertTrue(presence.recompute())
        assertEquals(List(5) { true }, goUi())
    }

    fun testGoModAloneIsGo() {
        assertFalse(presence.recompute())
        myFixture.addFileToProject("service/go.mod", "module example.com/service\n\ngo 1.22\n")
        assertTrue(presence.recompute())
    }

    fun testTheChangeIsPublishedOnce() {
        assertFalse(presence.recompute())
        val heard = mutableListOf<Boolean>()
        project.messageBus.connect(testRootDisposable).subscribe(GoProjectPresence.TOPIC, GoProjectPresence.Listener { heard += it })
        presence.recompute()
        myFixture.addFileToProject("a.go", "package a\n")
        presence.recompute()
        presence.recompute()
        assertEquals(listOf(true), heard)
    }

    /** No explicit recompute: the file listener notices the new file and the service computes the answer again in the background. */
    fun testTheFileListenerNoticesANewGoFile() {
        assertFalse(presence.recompute())
        myFixture.addFileToProject("pkg/x.go", "package pkg\n")
        PlatformTestUtil.waitWithEventsDispatching("the presence of Go files is not noticed", { presence.hasGoFiles }, 20)
        assertTrue(visible("Go.MainMenu"))
    }

    fun testTheGuessLooksOneLevelDown() {
        val file = myFixture.addFileToProject("p/sub/x.go", "package sub\n").virtualFile
        val p = file.parent.parent
        assertTrue(GoProjectPresence.guessFromDirectory(p))
        assertFalse(GoProjectPresence.guessFromDirectory(p.parent))
        assertFalse(GoProjectPresence.guessFromDirectory(null))
    }
}

class GoProjectPresenceNamesTest {
    @Test
    fun goNames() {
        Assert.assertTrue(GoProjectPresence.isGoName("main.go"))
        Assert.assertTrue(GoProjectPresence.isGoName("go.mod"))
        Assert.assertTrue(GoProjectPresence.isGoName("go.work"))
        Assert.assertFalse(GoProjectPresence.isGoName("go.sum"))
        Assert.assertFalse(GoProjectPresence.isGoName("main.golang"))
        Assert.assertFalse(GoProjectPresence.isGoName(null))
    }

    @Test
    fun skippedDirectories() {
        Assert.assertTrue(GoProjectPresence.skippedDirectory(".git"))
        Assert.assertTrue(GoProjectPresence.skippedDirectory("node_modules"))
        Assert.assertFalse(GoProjectPresence.skippedDirectory("cmd"))
        Assert.assertFalse(GoProjectPresence.skippedDirectory("internal"))
    }
}
