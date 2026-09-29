package io.github.golangsupport

import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.run.GoConfigurationType

/** plugin.xml as the platform reads it: what is registered there is there, before the sandbox says so with an error in its log. */
class GoPluginTest : BasePlatformTestCase() {
    fun testTheMenuGoAndItsActions() {
        val actions = ActionManager.getInstance()
        val menu = actions.getAction("Go.MainMenu") as? ActionGroup ?: error("Go.MainMenu is not a group")
        val ids = HashSet<String>()
        fun collect(group: ActionGroup) {
            for (action in group.getChildren(null)) {
                actions.getId(action)?.let(ids::add)
                if (action is ActionGroup) collect(action)
            }
        }
        collect(menu)
        val expected = listOf(
            "Go.Build", "Go.Vet", "Go.Generate", "Go.ModTidy", "Go.ModDownload", "Go.ModVendor", "Go.NewModule", "Go.AnalyzeStackTrace", "Go.Environment",
            "Go.DisablePlugins", "Go.Monitor", "Go.HelpPage", "Go.Debugger.ShowLogs", "Go.Debugger.TraceProtocol",
        )
        assertEquals(emptyList<String>(), expected.filter { it !in ids })
        for (id in listOf("Go.NewFile", "Go.Generate.Constructor", "Go.Generate.Test", "Go.ProjectViewPopup")) assertNotNull(id, actions.getAction(id))
        assertTrue(actions.getAction("Go.NewFile") is AnAction)
    }

    fun testFileTypesAndLanguages() {
        val fileTypes = FileTypeManager.getInstance()
        assertEquals("Go", fileTypes.getFileTypeByFileName("main.go").name)
        assertEquals("Go Module", fileTypes.getFileTypeByFileName("go.mod").name)
        assertEquals("Go Module", fileTypes.getFileTypeByFileName("go.work").name)
        assertNotNull(Language.findLanguageByID("Go"))
        assertNotNull(Language.findLanguageByID("GoModule"))
    }

    fun testTheRunConfigurationType() {
        val type = ConfigurationTypeUtil.findConfigurationType(GoConfigurationType::class.java)
        assertEquals("Go", type.displayName)
        assertEquals(1, type.configurationFactories.size)
    }
}
