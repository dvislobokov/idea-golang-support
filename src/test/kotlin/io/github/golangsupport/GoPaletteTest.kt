package io.github.golangsupport

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.lang.GoColorSettingsPage
import io.github.golangsupport.lang.GoColors
import io.github.golangsupport.lang.palette.GoPaletteChoice
import io.github.golangsupport.lang.palette.GoPaletteSchemeListener
import io.github.golangsupport.lang.palette.GoPaletteService
import io.github.golangsupport.lang.palette.GoPaletteWriter
import io.github.golangsupport.lang.palette.GoPalettes
import java.awt.Color
import java.awt.Font

/** The Go palettes (lang/palette): their files, their contrast, and what writing them into a scheme touches. */
class GoPaletteTest : BasePlatformTestCase() {
    private val manager get() = EditorColorsManager.getInstance()

    override fun tearDown() {
        try {
            GoPaletteService.getInstance().choose(GoPalettes.DEFAULT_ID)
            GoPaletteService.getInstance().promptDismissed = false
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** An editable copy of a bundled scheme, as the IDE keeps one for each (`_@user_Darcula`): what the palette is written into. */
    private fun editableCopy(base: EditorColorsScheme, name: String): EditorColorsScheme =
        ((base as AbstractColorsScheme).clone() as AbstractColorsScheme).apply { setName(name) }

    /** IntelliJ's new UI schemes "Dark" and "Light" (parents Darcula and Default) from the platform's resources. */
    private fun newUiScheme(dark: Boolean): EditorColorsScheme {
        val path = if (dark) "/themes/expUI/expUI_darkScheme.xml" else "/themes/expUI/expUI_lightScheme.xml"
        val element = checkNotNull(EditorColorsManager::class.java.getResourceAsStream(path)) { path }.use { JDOMUtil.load(it) }
        return EditorColorsSchemeImpl(null).apply { readExternal(element) }
    }

    private fun bases(): List<Pair<String, EditorColorsScheme>> = listOf(
        "Darcula" to editableCopy(manager.getScheme("Darcula")!!, "_@user_Darcula palette test"),
        "Default" to editableCopy(manager.getScheme("Default")!!, "_@user_Default palette test"),
        "Dark" to newUiScheme(dark = true),
        "Light" to newUiScheme(dark = false),
    )

    /** The keys of the Color Scheme page a palette leaves to the scheme: punctuation, plain identifiers and errors. */
    private val leftToTheScheme = with(GoColors) {
        setOf(BRACES, PARENTHESES, BRACKETS, BRACKET, SEMICOLON, COMMA, DOT, OPERATOR, COLON, IDENTIFIER, BAD_CHARACTER, BAD_TOKEN, INVALID_STRING_ESCAPE)
    }.map { it.externalName }.toSet()

    fun testThePaletteFilesDefineEveryGoKeyAndNothingElse() {
        assertEquals(GoPalettes.IDS, GoPalettes.ALL.map { it.id })
        val required = GoPalettes.REQUIRED_KEYS.map { it.externalName }.toSet()
        val page = GoColorSettingsPage.DESCRIPTORS.map { it.key.externalName }.toSet()
        assertEquals("every key of the Color Scheme page is either painted or left to the scheme", emptySet<String>(), page - required - leftToTheScheme)
        assertTrue("go.mod too", "GO_MOD_DIRECTIVE" in required)
        for (palette in GoPalettes.ALL) {
            for (dark in listOf(true, false)) {
                val variant = palette.variant(dark)
                assertEquals("${palette.id} dark=$dark misses", emptySet<String>(), required - variant.keys)
                assertEquals("${palette.id} dark=$dark: nothing but the required keys", emptySet<String>(), variant.keys - required)
                for ((key, attributes) in variant) {
                    assertTrue(key, key.startsWith("GO_"))
                    assertNull("${palette.id} $key: a palette never paints a background", attributes.backgroundColor)
                    assertNotNull("${palette.id} $key", attributes.foregroundColor)
                }
            }
        }
        assertEquals(listOf("IDE default", "GoLand", "VS Code", "Nord", "Dracula", "One Dark / One Light", "Solarized", "GitHub"),
            GoPaletteChoice.all().map { it.name })
    }

    fun testTheFilesCiteTheirSources() {
        for (id in GoPalettes.IDS) {
            val text = checkNotNull(GoPalettes::class.java.getResourceAsStream("/goPalettes/$id.json")).use { it.readBytes().toString(Charsets.UTF_8) }
            val sources = com.google.gson.JsonParser.parseString(text).asJsonObject.getAsJsonArray("sources")
            assertTrue(id, sources != null && sources.size() > 0)
        }
    }

    fun testTheSpecOfAnAttribute() {
        assertEquals(TextAttributes(Color(0xC7, 0x7D, 0xBB), null, null, null, Font.ITALIC), GoPalettes.attributes("#C77DBB italic"))
        assertEquals(TextAttributes(Color(0xBC, 0xBE, 0xC4), null, Color(0x84, 0x86, 0x8C), EffectType.LINE_UNDERSCORE, Font.PLAIN),
            GoPalettes.attributes("#BCBEC4 underline:#84868C"))
        assertEquals(Font.BOLD or Font.ITALIC, GoPalettes.attributes("#000000 italic bold").fontType)
        // runCatching, not assertThrows: a lambda of a test method compiles to `test…$lambda$0`, which JUnit 3 takes for a test
        assertTrue(runCatching { GoPalettes.attributes("#12345 bold") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue("only Go keys", runCatching { GoPalettes.parse("""{"id":"x","name":"X","dark":{"DEFAULT_KEYWORD":"#FFFFFF"}}""") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue("not Go assembly", runCatching { GoPalettes.parse("""{"id":"x","name":"X","dark":{"GO_ASM_STRING":"#FFFFFF"}}""") }.exceptionOrNull() is IllegalArgumentException)
    }

    fun testEveryColorReadsOnTheBackgroundsOfIntelliJ() {
        fun luminance(color: Color): Double {
            fun channel(value: Int): Double = (value / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
        }
        fun contrast(a: Color, b: Color): Double = listOf(luminance(a), luminance(b)).sortedDescending().let { (it[0] + 0.05) / (it[1] + 0.05) }

        val low = mutableListOf<String>()
        for ((name, scheme) in bases()) {
            val dark = GoPaletteWriter.isDark(scheme)
            assertEquals(name, name == "Darcula" || name == "Dark", dark)
            for (palette in GoPalettes.ALL) {
                for ((key, attributes) in palette.variant(dark)) {
                    // comments and the free text of a struct tag are meant to recede
                    val needed = if ("COMMENT" in key || key == "GO_TAG_TEXT") 2.0 else 3.0
                    val ratio = contrast(attributes.foregroundColor, scheme.defaultBackground)
                    if (ratio < needed) low += "${palette.id} on $name: $key %.2f".format(ratio)
                }
            }
        }
        assertEquals(emptyList<String>(), low)
    }

    /** `ship(order, qty, Express)`: the arguments and the locals tell apart from the types, the calls and the keywords in every palette. */
    fun testParametersAndLocalsStandApartFromTypesCallsAndKeywords() {
        fun distance(a: Color, b: Color): Double =
            Math.sqrt(((a.red - b.red) * (a.red - b.red) + (a.green - b.green) * (a.green - b.green) + (a.blue - b.blue) * (a.blue - b.blue)).toDouble())
        val identifiers = with(GoColors) { listOf(PARAMETER, FUNCTION_PARAMETER, LOCAL_VARIABLE, SCOPE_VARIABLE) }
        val others = with(GoColors) {
            listOf(TYPE_REFERENCE, EXPORTED_STRUCT_REFERENCE, LOCAL_STRUCT_REFERENCE, EXPORTED_INTERFACE_REFERENCE, BUILTIN_TYPE_REFERENCE,
                FUNCTION_CALL, EXPORTED_FUNCTION_CALL, LOCAL_FUNCTION_CALL, KEYWORD)
        }
        val close = mutableListOf<String>()
        for (palette in GoPalettes.ALL) {
            for (dark in listOf(true, false)) {
                val variant = palette.variant(dark)
                for (identifier in identifiers) for (other in others) {
                    val a = variant.getValue(identifier.externalName).foregroundColor
                    val b = variant.getValue(other.externalName).foregroundColor
                    if (distance(a, b) < 60) close += "${palette.id} dark=$dark ${identifier.externalName} ~ ${other.externalName} (%.0f)".format(distance(a, b))
                }
            }
        }
        assertEquals(emptyList<String>(), close)
    }

    /** What a palette must not touch: the background, the selection, the caret row, the keys of the other languages and of Go's punctuation. */
    private fun untouched(scheme: EditorColorsScheme): List<Any?> = listOf(
        scheme.defaultBackground, scheme.defaultForeground, scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR), scheme.getColor(EditorColors.CARET_ROW_COLOR),
        scheme.getColor(EditorColors.CARET_COLOR), scheme.getAttributes(HighlighterColors.TEXT), scheme.getAttributes(DefaultLanguageHighlighterColors.KEYWORD),
        scheme.getAttributes(DefaultLanguageHighlighterColors.CLASS_NAME), scheme.getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT),
        scheme.getAttributes(DefaultLanguageHighlighterColors.STRING), scheme.getAttributes(DefaultLanguageHighlighterColors.FUNCTION_CALL),
        scheme.getAttributes(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES), scheme.getAttributes(TextAttributesKey.find("JAVA_KEYWORD")),
        scheme.getAttributes(TextAttributesKey.find("XML_TAG")), scheme.getAttributes(TextAttributesKey.find("JAVA_STRING")),
        scheme.getAttributes(GoColors.BRACES), scheme.getAttributes(GoColors.IDENTIFIER), scheme.getAttributes(GoColors.SYNTAX_UPDATE),
        scheme.getAttributes(TextAttributesKey.find("GO_ASM_STRING")), scheme.getAttributes(TextAttributesKey.find("GO_MOD_ARROW")),
    )

    private fun go(scheme: EditorColorsScheme): Map<String, TextAttributes?> = GoPalettes.WRITTEN_KEYS.associate { it.externalName to scheme.getAttributes(it) }

    fun testWritingAndTakingOutTouchesOnlyGo() {
        for ((name, scheme) in bases()) {
            val before = untouched(scheme)
            val plain = go(scheme)
            val backup = GoPaletteWriter.backup(scheme)
            for (palette in GoPalettes.ALL) {
                GoPaletteWriter.write(scheme, palette, backup)
                assertTrue("${palette.id} on $name", GoPaletteWriter.matches(scheme, palette))
                val expected = palette.variant(GoPaletteWriter.isDark(scheme))
                assertEquals(expected.getValue("GO_TYPE_REFERENCE"), scheme.getAttributes(GoColors.TYPE_REFERENCE))
                assertEquals(expected.getValue("GO_KEYWORD"), scheme.getAttributes(GoColors.KEYWORD))
                assertEquals("${palette.id} on $name", before, untouched(scheme))
            }
            GoPaletteWriter.restore(scheme, backup)
            assertEquals("back to what $name had", plain, go(scheme))
            assertEquals(before, untouched(scheme))
            // the copies of Darcula and Default carry the plugin's colors; Dark and Light here stand for the bundled schemes themselves
            if (name == "Darcula" || name == "Default") assertFalse("$name colors Go with the colors of the IDE only", GoPaletteWriter.definesOwnGoColors(scheme))
        }
    }

    /** The new UI Light of the platform colors Go itself (GoLand's light colors): its editable copy has no Go colors of a user. */
    fun testTheColorsOfTheBundledSchemesAreNotTheUsers() {
        for (dark in listOf(true, false)) {
            val bundled = newUiScheme(dark) as AbstractColorsScheme
            val copy = EditorColorsSchemeImpl(bundled).apply { setName("_@user_${bundled.name} copy test") }
            for ((key, attributes) in bundled.directlyDefinedAttributes) {
                if (attributes !== AbstractColorsScheme.INHERITED_ATTRS_MARKER) copy.setAttributes(TextAttributesKey.find(key), attributes.clone())
            }
            assertFalse("dark=$dark", GoPaletteWriter.definesOwnGoColors(copy))
            copy.setAttributes(GoColors.LOCAL_VARIABLE, TextAttributes(Color.ORANGE, null, null, null, Font.PLAIN))
            assertTrue("dark=$dark", GoPaletteWriter.definesOwnGoColors(copy))
        }
    }

    fun testOwnGoColorsOfTheSchemeComeBack() {
        val scheme = editableCopy(manager.getScheme("Darcula")!!, "_@user_Darcula own colors")
        val own = TextAttributes(Color.ORANGE, null, null, null, Font.BOLD)
        val pluginCall = scheme.getAttributes(GoColors.FUNCTION_CALL)
        scheme.setAttributes(GoColors.KEYWORD, own)
        assertTrue(GoPaletteWriter.definesOwnGoColors(scheme))
        val backup = GoPaletteWriter.backup(scheme)
        GoPaletteWriter.write(scheme, GoPalettes.find("nord")!!, backup)
        GoPaletteWriter.write(scheme, GoPalettes.find("dracula")!!, backup)
        assertEquals(GoPalettes.find("dracula")!!.dark.getValue("GO_KEYWORD"), scheme.getAttributes(GoColors.KEYWORD))
        GoPaletteWriter.restore(scheme, backup)
        assertEquals(own, scheme.getAttributes(GoColors.KEYWORD))
        assertEquals("the plugin's GoLand-like colors come back too", pluginCall, scheme.getAttributes(GoColors.FUNCTION_CALL))
        assertEquals(scheme.getAttributes(DefaultLanguageHighlighterColors.STRING), scheme.getAttributes(GoColors.STRING))
    }

    fun testTheVariantFollowsTheBackgroundOfTheScheme() {
        val service = GoPaletteService.getInstance()
        service.choose("vsCode")
        val dark = editableCopy(manager.getScheme("Darcula")!!, "_@user_Darcula switch test")
        val light = editableCopy(manager.getScheme("Default")!!, "_@user_Default switch test")
        val darkBefore = untouched(dark)
        val lightBefore = untouched(light)
        val vsCode = GoPalettes.find("vsCode")!!
        service.ensureApplied(dark)
        service.ensureApplied(light)
        assertEquals(vsCode.dark.getValue("GO_EXPORTED_FUNCTION_CALL"), dark.getAttributes(GoColors.EXPORTED_FUNCTION_CALL))
        assertEquals(vsCode.light.getValue("GO_EXPORTED_FUNCTION_CALL"), light.getAttributes(GoColors.EXPORTED_FUNCTION_CALL))
        assertEquals(darkBefore, untouched(dark))
        assertEquals(lightBefore, untouched(light))
    }

    fun testChoosingPreviewingAndIdeDefaultOnTheGlobalScheme() {
        val service = GoPaletteService.getInstance()
        val global = manager.globalScheme
        assertTrue("the global scheme of the tests is an editable copy: ${global.name}", GoPaletteWriter.canWrite(global))
        val before = untouched(global)
        val plain = go(global)
        var events = 0
        ApplicationManager.getApplication().messageBus.connect(testRootDisposable).subscribe(EditorColorsManager.TOPIC, EditorColorsListener { events++ })

        service.choose("goland")
        assertTrue(GoPaletteWriter.matches(global, GoPalettes.find("goland")!!))
        assertTrue("the editors are told to repaint", events > 0)

        service.preview("solarized")
        assertTrue(GoPaletteWriter.matches(global, GoPalettes.find("solarized")!!))
        assertEquals("goland", service.paletteId)
        // the popup closed with Esc
        service.endPreview()
        assertTrue(GoPaletteWriter.matches(global, GoPalettes.find("goland")!!))

        service.choose("one")
        assertTrue(GoPaletteWriter.matches(global, GoPalettes.find("one")!!))
        // a switch of the scheme: the listener writes the palette again where it is missing
        GoPaletteWriter.restore(global, GoPaletteWriter.backup(editableCopy(manager.getScheme("Default")!!, "x")))
        GoPaletteSchemeListener().globalSchemeChange(global)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(GoPaletteWriter.matches(global, GoPalettes.find("one")!!))
        assertEquals(before, untouched(global))

        service.choose(GoPalettes.DEFAULT_ID)
        assertEquals("nothing of the palettes stays", plain, go(global))
        assertEquals(before, untouched(global))
    }
}
