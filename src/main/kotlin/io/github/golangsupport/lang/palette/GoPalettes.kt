package io.github.golangsupport.lang.palette

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
import com.intellij.ui.ColorUtil
import io.github.golangsupport.lang.GoColors
import io.github.golangsupport.mod.GoModSyntaxHighlighter
import org.jdom.Element
import java.awt.Color
import java.awt.Font

/**
 * A Go palette of a popular theme (`resources/goPalettes/<id>.json`, the sources of the colors are in the file): the attributes of the
 * Go and go.mod keys, a dark and a light variant. Foreground, font style and effect only: a palette never paints a background, it goes on
 * top of whatever scheme is active and leaves everything that is not Go to it. Ported from the C# palettes of idea-dotnet-support.
 */
class GoPalette(val id: String, val name: String, val dark: Map<String, TextAttributes>, val light: Map<String, TextAttributes>) {
    fun variant(dark: Boolean): Map<String, TextAttributes> = if (dark) this.dark else light

    override fun toString(): String = name
}

object GoPalettes {
    /** No palette: the Go keys keep what the scheme gives them (the plugin's GoLand-like colors of `colorSchemes/Go*.xml`). */
    const val DEFAULT_ID = "default"

    /** The order of the popup and of the combo box of Settings | Go | Editor. */
    val IDS = listOf("goland", "vsCode", "nord", "dracula", "one", "solarized", "github")

    /**
     * What every variant of every palette defines: the keys of the lexer, of the semantic highlighter and of go.mod. Punctuation, plain
     * identifiers, bad characters and invalid escapes stay with the scheme: themes paint them in the text color or as errors anyway.
     */
    val REQUIRED_KEYS: List<TextAttributesKey> = with(GoColors) {
        listOf(
            KEYWORD, STRING, NUMBER, LINE_COMMENT, BLOCK_COMMENT, DIRECTIVE, COMMENT_KEYWORD, COMMENT_REFERENCE, BUILD_TAG, BUILD_PAREN, BUILD_OPERATOR,
            VALID_STRING_ESCAPE, FORMAT_VERB,
            BUILTIN_TYPE, BUILTIN_TYPE_REFERENCE, BUILTIN_CONSTANT, BUILTIN_VARIABLE, BUILTIN_FUNCTION, BUILTIN_FUNCTION_CALL,
            FUNCTION_DECLARATION, EXPORTED_FUNCTION, LOCAL_FUNCTION, FUNCTION_CALL, EXPORTED_FUNCTION_CALL, LOCAL_FUNCTION_CALL,
            TYPE_DECLARATION, TYPE_SPECIFICATION, PACKAGE_EXPORTED_STRUCT, PACKAGE_LOCAL_STRUCT, PACKAGE_EXPORTED_INTERFACE, PACKAGE_LOCAL_INTERFACE,
            TYPE_REFERENCE, EXPORTED_STRUCT_REFERENCE, LOCAL_STRUCT_REFERENCE, EXPORTED_INTERFACE_REFERENCE, LOCAL_INTERFACE_REFERENCE,
            PACKAGE, FIELD, STRUCT_EXPORTED_MEMBER, STRUCT_LOCAL_MEMBER, STRUCT_EXPORTED_MEMBER_CALL, STRUCT_LOCAL_MEMBER_CALL,
            CONSTANT, PACKAGE_EXPORTED_CONSTANT, PACKAGE_LOCAL_CONSTANT, LOCAL_CONSTANT,
            PACKAGE_VARIABLE, PACKAGE_EXPORTED_VARIABLE, PACKAGE_LOCAL_VARIABLE, PACKAGE_EXPORTED_VARIABLE_CALL, PACKAGE_LOCAL_VARIABLE_CALL,
            LOCAL_VARIABLE, SCOPE_VARIABLE, LOCAL_VARIABLE_CALL, REASSIGNMENT_IN_SHORT_VAR_DECLARATION, SHADOWING_VARIABLE,
            PARAMETER, FUNCTION_PARAMETER, METHOD_RECEIVER, LABEL, TAG_KEY, TAG_COLON, TAG_VALUE, TAG_TEXT,
        )
    } + listOf(GoModSyntaxHighlighter.DIRECTIVE, GoModSyntaxHighlighter.VERSION, GoModSyntaxHighlighter.STRING, GoModSyntaxHighlighter.COMMENT)

    val ALL: List<GoPalette> by lazy { IDS.map(::load) }

    fun find(id: String?): GoPalette? = ALL.firstOrNull { it.id == id }

    /** Every key some palette writes: what "IDE default" and a switch of palettes put back, so that nothing of the previous palette stays. */
    val WRITTEN_KEYS: List<TextAttributesKey> by lazy {
        (REQUIRED_KEYS.map { it.externalName } + ALL.flatMap { it.dark.keys + it.light.keys }).distinct().map { TextAttributesKey.find(it) }
    }

    private fun load(id: String): GoPalette {
        val text = GoPalettes::class.java.getResourceAsStream("/goPalettes/$id.json")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("No palette $id")
        return parse(text).also { check(it.id == id) { "$id.json has the id ${it.id}" } }
    }

    fun parse(json: String): GoPalette {
        val root = JsonParser.parseString(json).asJsonObject
        fun variant(name: String): Map<String, TextAttributes> {
            val entries = root.getAsJsonObject(name) ?: JsonObject()
            return entries.entrySet().associate { (key, value) ->
                // only Go keys: whatever else a file says, the plugin does not touch the rest of the scheme
                require(key.startsWith("GO_") && !key.startsWith("GO_ASM_")) { "$key is not a Go key" }
                key to attributes(value.asString)
            }
        }
        return GoPalette(root.get("id").asString, root.get("name").asString, variant("dark"), variant("light"))
    }

    /** `#RRGGBB [bold] [italic] [underline[:#RRGGBB]]`; the underline takes the foreground when it has no color of its own. */
    fun attributes(spec: String): TextAttributes {
        val parts = spec.trim().split(Regex("\\s+"))
        val foreground = color(parts.first())
        var fontType = Font.PLAIN
        var effect: EffectType? = null
        var effectColor: Color? = null
        for (part in parts.drop(1)) {
            when {
                part == "bold" -> fontType = fontType or Font.BOLD
                part == "italic" -> fontType = fontType or Font.ITALIC
                part == "underline" || part.startsWith("underline:") -> {
                    effect = EffectType.LINE_UNDERSCORE
                    effectColor = part.substringAfter(':', "").takeIf { it.isNotEmpty() }?.let(::color) ?: foreground
                }
                else -> throw IllegalArgumentException("Unknown style '$part' in '$spec'")
            }
        }
        return TextAttributes(foreground, null, effectColor, effect, fontType)
    }

    private fun color(text: String): Color {
        require(Regex("#[0-9A-Fa-f]{6}").matches(text)) { "Not a color: $text" }
        return ColorUtil.fromHex(text)
    }
}

/**
 * Writes a palette into a scheme and takes it out again. The scheme is the editable copy the IDE keeps of a bundled scheme
 * (`_@user_Darcula`…, what the Settings dialog edits too), so the colors persist with it. Before the first palette goes in, the Go attributes
 * the scheme had of its own are kept as XML ([backup]); taking the palette out puts exactly those back, and a key the scheme did not define
 * inherits again, so a user's own Go colors survive a round trip through the palettes.
 */
object GoPaletteWriter {
    fun isDark(scheme: EditorColorsScheme): Boolean = ColorUtil.isDark(scheme.defaultBackground)

    fun canWrite(scheme: EditorColorsScheme): Boolean = scheme is AbstractColorsScheme && !scheme.isReadOnly

    /** The palette's variant for this scheme, by the darkness of its background. */
    fun expected(scheme: EditorColorsScheme, palette: GoPalette): Map<String, TextAttributes> = palette.variant(isDark(scheme))

    fun matches(scheme: EditorColorsScheme, palette: GoPalette): Boolean =
        expected(scheme, palette).all { (key, attributes) -> scheme.getAttributes(TextAttributesKey.find(key)) == attributes }

    /** The Go attributes the scheme defines itself (not its parent): `<key name="…">` with a `<value>`, or `inherited="true"`. */
    fun backup(scheme: EditorColorsScheme): String {
        val own = (scheme as? AbstractColorsScheme)?.directlyDefinedAttributes.orEmpty()
        val root = Element("goColors")
        for (key in GoPalettes.WRITTEN_KEYS) {
            val attributes = own[key.externalName] ?: continue
            val element = Element("key").setAttribute("name", key.externalName)
            if (attributes === AbstractColorsScheme.INHERITED_ATTRS_MARKER) element.setAttribute("inherited", "true")
            else element.addContent(Element("value").also { attributes.writeExternal(it) })
            root.addContent(element)
        }
        return JDOMUtil.write(root)
    }

    /** Puts the palette in; [backup] first takes out what a previous palette wrote, so that nothing of it stays. */
    fun write(scheme: EditorColorsScheme, palette: GoPalette, backup: String) {
        restore(scheme, backup)
        for ((key, attributes) in expected(scheme, palette)) scheme.setAttributes(TextAttributesKey.find(key), attributes.clone())
        (scheme as? AbstractColorsScheme)?.setSaveNeeded(true)
    }

    /** Back to [backup]: a key the scheme had defines it again, any other inherits (from the parent scheme, else from its fallback key). */
    fun restore(scheme: EditorColorsScheme, backup: String) {
        val saved = runCatching { JDOMUtil.load(backup) }.getOrNull()?.getChildren("key").orEmpty().associateBy { it.getAttributeValue("name") }
        val parent = (scheme as? AbstractColorsScheme)?.parentScheme as? AbstractColorsScheme
        for (key in GoPalettes.WRITTEN_KEYS) {
            val element = saved[key.externalName]
            val attributes = when {
                element == null -> parent?.getDirectlyDefinedAttributes(key)?.takeUnless { it === AbstractColorsScheme.INHERITED_ATTRS_MARKER }?.clone()
                element.getAttributeValue("inherited") == "true" -> null
                else -> element.getChild("value")?.let { TextAttributes(it) }
            }
            scheme.setAttributes(key, attributes ?: AbstractColorsScheme.INHERITED_ATTRS_MARKER)
        }
        (scheme as? AbstractColorsScheme)?.setSaveNeeded(true)
    }

    /**
     * Has the scheme Go colors a user gave it (on the Color Scheme page, or a scheme of a user / imported from GoLand)? Then there is
     * nothing to suggest. Unlike C#, every scheme of the IDE colors Go already: the plugin puts its GoLand-like colors into Darcula and
     * Default (`colorSchemes/Go*.xml`), the platform's new UI Light carries GoLand's light Go colors itself, and an editable copy carries
     * what its bundled parent has — none of that counts.
     */
    fun definesOwnGoColors(scheme: EditorColorsScheme): Boolean {
        val colors = scheme as? AbstractColorsScheme ?: return true
        if (colors.isReadOnly) return false
        val parent = colors.parentScheme
        val plugin = pluginColors
        return GoPalettes.REQUIRED_KEYS.any { key ->
            val own = colors.getDirectlyDefinedAttributes(key)
            own != null && own !== AbstractColorsScheme.INHERITED_ATTRS_MARKER && own !in plugin[key.externalName].orEmpty() && own != parent?.getAttributes(key)
        }
    }

    /** The colors the plugin itself adds to Darcula and Default (`additionalTextAttributes` of plugin.xml), by key. */
    private val pluginColors: Map<String, List<TextAttributes>> by lazy {
        listOf("/colorSchemes/GoDarcula.xml", "/colorSchemes/GoDefault.xml").flatMap { path ->
            val root = GoPaletteWriter::class.java.getResourceAsStream(path)?.use { JDOMUtil.load(it) } ?: return@flatMap emptyList()
            root.getChildren("option").mapNotNull { option -> option.getChild("value")?.let { option.getAttributeValue("name") to TextAttributes(it) } }
        }.groupBy({ it.first }, { it.second })
    }
}
