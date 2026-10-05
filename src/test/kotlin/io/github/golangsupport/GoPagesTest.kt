package io.github.golangsupport

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.help.GoPages

/** The pages of the plugin: packed into it, self-contained, the one about the plugin shown once per version. */
class GoPagesTest : BasePlatformTestCase() {
    private fun page(page: GoPages.Page, dark: Boolean = true): String =
        GoPages.html(page, dark) ?: error("${page.resource} is packed from docs/ by the build")

    fun testThePagesArePackedAndStandAlone() {
        for (which in listOf(GoPages.WELCOME, GoPages.GUIDE)) {
            val page = page(which)
            assertTrue(which.resource, page.contains("""<html lang="ru" data-host="ide" data-theme="dark">"""))
            assertTrue(which.resource, page(which, dark = false).contains("""data-theme="light""""))
            // inside the IDE there is no network: nothing is loaded from outside
            val external = Regex("""(?:src|href)\s*=\s*"(https?:)?//[^"]*"""").findAll(page).map { it.value }.toList()
            assertEquals(which.resource, emptyList<String>(), external)
            assertFalse("no @import of fonts in ${which.resource}", page.contains("@import"))
            // links into the repository and between the files of docs/ are hidden there
            for (link in Regex("""<a [^>]*href="(?!#)[^"]*"[^>]*>""").findAll(page)) assertTrue(link.value, link.value.contains("class=\"repo\""))
            for (anchor in Regex("""href="#([\w-]+)"""").findAll(page).map { it.groupValues[1] }.toSet()) {
                assertTrue("no element with id $anchor in ${which.resource}", page.contains("id=\"$anchor\""))
            }
        }
    }

    fun testTheKeysOfTheGuideAreTheOnesOfTheKeymap() {
        val page = """<html lang="ru"><body><kbd data-action="GotoDeclaration">Ctrl+B</kbd> <kbd data-action="Unbound">F20</kbd> <kbd>Tab</kbd> <kbd data-action="Odd">X</kbd></body></html>"""
        val shown = GoPages.forIde(page, dark = false) { id -> mapOf("GotoDeclaration" to "⌘B", "Unbound" to "", "Odd" to "<&>")[id] }
        assertTrue(shown, shown.contains("""<kbd data-action="GotoDeclaration">⌘B</kbd>"""))
        // an action without a key in this keymap, and one the IDE does not have: the key of the page stays
        assertTrue(shown, shown.contains("""<kbd data-action="Unbound">F20</kbd>"""))
        assertEquals(page.replace("<html lang=\"ru\">", "<html lang=\"ru\" data-host=\"ide\" data-theme=\"light\">"), GoPages.forIde(page, dark = false))
        // a key that is not of an action is left alone, and the text of a key cannot break the markup
        assertTrue(shown, shown.contains("<kbd>Tab</kbd>"))
        assertTrue(shown, shown.contains("""<kbd data-action="Odd">&lt;&amp;&gt;</kbd>"""))
    }

    fun testEveryKeyOfTheGuideIsOfAnActionOfTheIde() {
        val actions = ActionManager.getInstance()
        val ids = Regex("""<kbd data-action="([\w.$]+)">""").findAll(page(GoPages.GUIDE)).map { it.groupValues[1] }.toSet()
        assertTrue("the guide names its keys by actions", ids.size > 10)
        assertEquals(emptyList<String>(), ids.filter { actions.getAction(it) == null })
    }

    fun testTheGuideNamesTheActionsAndTheSettingsThatAreThere() {
        val guide = page(GoPages.GUIDE)
        val actions = ActionManager.getInstance()
        // the items of the menu, by the names they have in it
        fun names(group: ActionGroup): List<String> = group.getChildren(null).flatMap { action ->
            if (action is ActionGroup) names(action) else listOfNotNull(action.templatePresentation.text?.takeIf { it.isNotBlank() })
        }
        val menu = names(actions.getAction("Go.MainMenu") as ActionGroup)
        assertTrue(menu.toString(), menu.size > 15)
        assertEquals(emptyList<String>(), menu.map { it.removeSuffix("...") }.filter { !guide.contains(it) })
    }

    fun testTheGuideOpensAtTheGoFixSection() {
        val guide = page(GoPages.GUIDE)
        assertTrue("the What's New lens of Go fix opens the guide there", guide.contains("id=\"go-fix\""))
        assertFalse(guide.contains("scrollIntoView()"))
        val atGoFix = GoPages.html(GoPages.GUIDE, dark = true, anchor = "go-fix")!!
        assertTrue(atGoFix.contains("getElementById('go-fix')"))
        assertNotNull(ActionManager.getInstance().getAction("Go.HelpPage.GoFix"))
    }

    fun testShownOncePerVersion() {
        assertTrue("a new installation", GoPages.isNewFor(null, "0.1.0"))
        assertTrue("an update", GoPages.isNewFor("0.1.0", "0.2.0"))
        assertFalse(GoPages.isNewFor("0.1.0", "0.1.0"))
        assertFalse("the version is not known: nothing to record", GoPages.isNewFor(null, null))
    }

    fun testTheActionsAreInTheMenu() {
        val actions = ActionManager.getInstance()
        val menu = actions.getAction("Go.MainMenu") as ActionGroup
        val ids = menu.getChildren(null).mapNotNull { actions.getId(it) }
        assertTrue(ids.toString(), "Go.Welcome" in ids && "Go.HelpPage" in ids)
    }
}
