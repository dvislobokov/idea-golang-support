// The editor colour scheme in use, its parents, and what it answers for the keys of the plugin.
importClass(com.intellij.openapi.editor.colors.EditorColorsManager)
importClass(com.intellij.openapi.editor.colors.TextAttributesKey)
const scheme = EditorColorsManager.getInstance().getGlobalScheme()
var out = "scheme: " + scheme.getName() + " (" + scheme.getClass().getSimpleName() + ")"
var parent = scheme
for (var i = 0; i < 5; i++) {
    try { parent = parent.getParentScheme() } catch (e) { parent = null }
    if (parent == null) break
    out += " <- " + parent.getName()
}
out += "\n"
const names = ["GO_PACKAGE", "GO_TYPE_REFERENCE", "GO_FUNCTION_CALL", "GO_FUNCTION_DECLARATION", "GO_FIELD", "GO_CONSTANT", "GO_KEYWORD"]
for (var n = 0; n < names.length; n++) {
    const attributes = scheme.getAttributes(TextAttributesKey.find(names[n]))
    const colour = attributes == null || attributes.getForegroundColor() == null ? "none" : java.lang.Integer.toHexString(attributes.getForegroundColor().getRGB() & 0xffffff)
    out += "  " + names[n] + " = " + colour + "\n"
}
out
