package io.github.golangsupport

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableEP
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ex.ConfigurableExtensionPointUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.settings.GoBuildTagsConfigurable
import io.github.golangsupport.settings.GoDebuggerConfigurable
import io.github.golangsupport.settings.GoEditorConfigurable
import io.github.golangsupport.settings.GoFormattingConfigurable
import io.github.golangsupport.settings.GoImportsConfigurable
import io.github.golangsupport.settings.GoLanguageServerConfigurable
import io.github.golangsupport.settings.GoLintersConfigurable
import io.github.golangsupport.settings.GoPlatformChoices
import io.github.golangsupport.settings.GoSettingsPage
import io.github.golangsupport.settings.GoSettingsTree
import io.github.golangsupport.PluginLanguage

/** Settings | Go: a node of its own at the root of the tree, first, with the pages under it in GoLand's order. */
class GoSettingsTreeTest : BasePlatformTestCase() {
    private fun ours(): List<ConfigurableEP<Configurable>> = Configurable.PROJECT_CONFIGURABLE.getExtensions(project).filter { it.id?.startsWith(GoSettingsTree.ROOT) == true }

    fun testTheRootIsATopLevelNodeAboveAppearance() {
        val root = ours().single { it.id == GoSettingsTree.ROOT }
        assertEquals("root", root.groupId)
        assertNull(root.parentId)
        // Appearance & Behavior is the heaviest group of the platform (70), Keymap 65: a heavier node goes above it
        assertTrue("groupWeight ${root.groupWeight}", root.groupWeight > 70)
        // seen live: without displayName / key the platform throws PluginException; an English displayName stayed English in Russian
        assertEquals("a title without loading the page", emptyList<String>(), ours().filter { it.displayName == null && it.key == null }.map { it.id })
        assertEquals("titles in the plugin's language", emptyList<String>(), ours().filter { it.id != GoSettingsTree.GOPLS && it.bundle != "messages.GoSettingsTitles" }.map { it.id })
        assertEquals("nothing is left under Tools", emptyList<String>(), ours().filter { it.parentId == "tools" || it.groupId == "tools" }.map { it.id })
    }

    fun testThePagesAreUnderGoInTheirOrder() {
        val children = ours().filter { it.parentId == GoSettingsTree.ROOT }.sortedByDescending { it.groupWeight }
        assertEquals(GoSettingsTree.PAGES.map { it.first }, children.map { it.id })
        assertEquals(GoSettingsTree.LANGUAGE_SERVER, ours().single { it.id == GoSettingsTree.GOPLS }.parentId)
        assertEquals("every page has a title in both languages", emptyList<String>(), GoSettingsTree.PAGES.map { it.second }.filter { GoBundle.message("page.$it").startsWith("!") || GoBundle.message("root.page.$it").startsWith("!") })
    }

    fun testTitlesFollowThePluginLanguage() {
        val buildTags = ours().single { it.id == GoSettingsTree.BUILD_TAGS }
        try {
            GoBundle.forced = PluginLanguage.RUSSIAN
            assertEquals(GoBundle.bundle("ru").getString("page.buildTags"), buildTags.displayName())
            GoBundle.forced = PluginLanguage.ENGLISH
            assertEquals("Build Tags", buildTags.displayName())
        } finally {
            GoBundle.forced = null
        }
    }

    private fun ConfigurableEP<*>.displayName(): String = javaClass.getMethod("getDisplayName").invoke(this) as String

    /** What the Settings dialog builds from the extensions: Go first among the top-level nodes, its pages in the order above. */
    fun testTheTreeOfTheDialog() {
        val top = ConfigurableExtensionPointUtil.getConfigurableGroup(project, true).configurables.toList()
        val go = top.first()
        assertEquals(top.map { (it as? SearchableConfigurable)?.id }.toString(), GoSettingsTree.ROOT, (go as SearchableConfigurable).id)
        val pages = (go as Configurable.Composite).configurables.map { (it as SearchableConfigurable).id }
        assertEquals(GoSettingsTree.PAGES.map { it.first }, pages)
    }

    /** The pages that do not ask `go` at reset are built here: a page that throws while it is made is an empty page in the dialog. */
    fun testThePagesAreBuiltAndNotModifiedAfterReset() {
        val pages: List<GoSettingsPage> = listOf(GoBuildTagsConfigurable(project), GoImportsConfigurable(project), GoLintersConfigurable(project), GoFormattingConfigurable(project),
            GoEditorConfigurable(project), GoLanguageServerConfigurable(project), GoDebuggerConfigurable(project))
        for (page in pages) {
            try {
                assertNotNull(page.displayName, page.createComponent())
                page.reset()
                assertFalse(page.displayName, page.isModified)
            } finally {
                page.disposeUIResources()
            }
        }
    }

    fun testTheChoicesOfBuildTagsComeFromTheListOfPlatforms() {
        val systems = GoPlatformChoices.operatingSystems()
        val architectures = GoPlatformChoices.architectures()
        assertTrue(systems.containsAll(listOf("linux", "windows", "darwin", "js", "wasip1")))
        assertTrue(architectures.containsAll(listOf("amd64", "arm64", "wasm", "386")))
        assertEquals(systems.sorted(), systems)
        assertEquals(systems.size, systems.toSet().size)
        assertTrue(GoPlatformChoices.hostOs() in systems)
    }
}
