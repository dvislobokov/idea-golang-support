package io.github.golangsupport

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoColorSettingsPage
import io.github.golangsupport.lang.GoColors
import java.io.File

/**
 * The colour page names the keys as GoLand does: every key of the GoLand dump (`docs/goland-analysis/dumps/color-keys-go.txt`, the
 * page `Go` of a live GoLand) is on our page with the same external name and the same group path, so a scheme exported from GoLand
 * reads the same here. The demo uses every tag the page declares and declares every tag it uses.
 */
class GoColorSettingsPageTest : BasePlatformTestCase() {
    private val page = GoColorSettingsPage()

    fun testEveryKeyOfGoLandIsOnThePageUnderTheSameName() {
        val dump = File("docs/goland-analysis/dumps/color-keys-go.txt")
        assertTrue("${dump.absolutePath} is missing", dump.isFile)
        val line = Regex("""^\s+(.+?)\s+=\s+(GO_[A-Z_]+)\s+\(fallback""")
        val goland = dump.readLines().mapNotNull { line.find(it) }.associate { it.groupValues[2] to it.groupValues[1] }
        assertTrue("keys in the dump: ${goland.size}", goland.size >= 64)
        val ours = page.attributeDescriptors.associate { it.key.externalName to it.displayName }
        val problems = goland.mapNotNull { (id, name) ->
            when (ours[id]) {
                null -> "$id ($name) is not on the page"
                name -> null
                else -> "$id is '${ours[id]}' on the page, '$name' in GoLand"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    fun testEveryKeyOfThePaletteIsOnThePage() {
        val onPage = page.attributeDescriptors.map { it.key }.toSet()
        val palette = GoColors::class.java.declaredFields.filter { it.type == TextAttributesKey::class.java }
            .map { it.isAccessible = true; it.get(null) as TextAttributesKey }
        val missing = palette.filter { it !in onPage && it != GoColors.SYNTAX_UPDATE }.map { it.externalName }
        assertEmpty(missing)
        assertEquals("one descriptor per key", onPage.size, page.attributeDescriptors.size)
    }

    fun testTheDemoAndItsTagsAgree() {
        val used = Regex("""<(\w+)>""").findAll(page.demoText).map { it.groupValues[1] }.toSet()
        val declared = page.additionalHighlightingTagToDescriptorMap.keys
        assertEquals(declared.toSortedSet(), used.toSortedSet())
    }
}
