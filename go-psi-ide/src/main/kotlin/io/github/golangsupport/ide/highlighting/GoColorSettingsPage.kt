package io.github.golangsupport.ide.highlighting

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import io.github.golangsupport.lang.GoIcons
import javax.swing.Icon

/** Settings | Editor | Color Scheme | Go. */
class GoColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = "Go"

    override fun getIcon(): Icon = GoIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = GoSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = DEMO_TEXT

    private companion object {
        val DESCRIPTORS = arrayOf(
            AttributesDescriptor("Keyword", GoHighlightingColors.KEYWORD),
            AttributesDescriptor("Identifier", GoHighlightingColors.IDENTIFIER),
            AttributesDescriptor("Literals//String", GoHighlightingColors.STRING),
            AttributesDescriptor("Literals//Rune", GoHighlightingColors.RUNE),
            AttributesDescriptor("Literals//Number", GoHighlightingColors.NUMBER),
            AttributesDescriptor("Comments//Line comment", GoHighlightingColors.LINE_COMMENT),
            AttributesDescriptor("Comments//Block comment", GoHighlightingColors.BLOCK_COMMENT),
            AttributesDescriptor("Operators and punctuation//Operator", GoHighlightingColors.OPERATOR),
            AttributesDescriptor("Operators and punctuation//Braces", GoHighlightingColors.BRACES),
            AttributesDescriptor("Operators and punctuation//Brackets", GoHighlightingColors.BRACKETS),
            AttributesDescriptor("Operators and punctuation//Parentheses", GoHighlightingColors.PARENTHESES),
            AttributesDescriptor("Operators and punctuation//Comma", GoHighlightingColors.COMMA),
            AttributesDescriptor("Operators and punctuation//Semicolon", GoHighlightingColors.SEMICOLON),
            AttributesDescriptor("Operators and punctuation//Dot", GoHighlightingColors.DOT),
            AttributesDescriptor("Bad character", GoHighlightingColors.BAD_CHARACTER),
            AttributesDescriptor("Identifiers//Package", GoHighlightingColors.PACKAGE),
            AttributesDescriptor("Identifiers//Type", GoHighlightingColors.TYPE),
            AttributesDescriptor("Identifiers//Type parameter", GoHighlightingColors.TYPE_PARAMETER),
            AttributesDescriptor("Identifiers//Function declaration", GoHighlightingColors.FUNCTION_DECLARATION),
            AttributesDescriptor("Identifiers//Function call", GoHighlightingColors.FUNCTION_CALL),
            AttributesDescriptor("Identifiers//Method declaration", GoHighlightingColors.METHOD_DECLARATION),
            AttributesDescriptor("Identifiers//Method call", GoHighlightingColors.METHOD_CALL),
            AttributesDescriptor("Identifiers//Field", GoHighlightingColors.FIELD),
            AttributesDescriptor("Identifiers//Parameter", GoHighlightingColors.PARAMETER),
            AttributesDescriptor("Identifiers//Local variable", GoHighlightingColors.LOCAL_VARIABLE),
            AttributesDescriptor("Identifiers//Package variable", GoHighlightingColors.PACKAGE_VARIABLE),
            AttributesDescriptor("Identifiers//Constant", GoHighlightingColors.CONSTANT),
            AttributesDescriptor("Identifiers//Label", GoHighlightingColors.LABEL),
            AttributesDescriptor("Builtins//Type", GoHighlightingColors.BUILTIN_TYPE),
            AttributesDescriptor("Builtins//Function", GoHighlightingColors.BUILTIN_FUNCTION),
            AttributesDescriptor("Builtins//Constant", GoHighlightingColors.BUILTIN_CONSTANT),
        )

        val TAGS: Map<String, TextAttributesKey> = mapOf(
            "pkg" to GoHighlightingColors.PACKAGE,
            "type" to GoHighlightingColors.TYPE,
            "tparam" to GoHighlightingColors.TYPE_PARAMETER,
            "fdecl" to GoHighlightingColors.FUNCTION_DECLARATION,
            "fcall" to GoHighlightingColors.FUNCTION_CALL,
            "mdecl" to GoHighlightingColors.METHOD_DECLARATION,
            "mcall" to GoHighlightingColors.METHOD_CALL,
            "field" to GoHighlightingColors.FIELD,
            "param" to GoHighlightingColors.PARAMETER,
            "local" to GoHighlightingColors.LOCAL_VARIABLE,
            "global" to GoHighlightingColors.PACKAGE_VARIABLE,
            "const" to GoHighlightingColors.CONSTANT,
            "label" to GoHighlightingColors.LABEL,
            "btype" to GoHighlightingColors.BUILTIN_TYPE,
            "bfunc" to GoHighlightingColors.BUILTIN_FUNCTION,
            "bconst" to GoHighlightingColors.BUILTIN_CONSTANT,
        )

        val DEMO_TEXT = """
            // Package demo shows Go syntax highlighting.
            package <pkg>demo</pkg>

            import (
                "fmt"
                "strings"
            )

            /* Point is a 2D point. */
            type <type>Point</type>[<tparam>T</tparam> ~<btype>int</btype> | ~<btype>float64</btype>] struct {
                <field>X</field>, <field>Y</field> <tparam>T</tparam>
            }

            const <const>mask</const> = 0x7F &^ 0b1010_0101
            var <global>ratio</global>, <global>phase</global> = 1.5e-3, 2i

            func (<param>p</param> *<type>Point</type>[<tparam>T</tparam>]) <mdecl>String</mdecl>() <btype>string</btype> {
                return <pkg>fmt</pkg>.<fcall>Sprintf</fcall>("(%v, %v)\n", <param>p</param>.<field>X</field>, <param>p</param>.<field>Y</field>)
            }

            func <fdecl>main</fdecl>() {
                <local>ch</local> := <bfunc>make</bfunc>(chan <btype>rune</btype>, 1)
                <local>ch</local> <- 'λ'
                <local>b</local> := <pkg>strings</pkg>.<type>Builder</type>{}
            <label>Loop</label>:
                for <local>i</local> := 0; <local>i</local> < 3; <local>i</local>++ {
                    if <local>s</local> := <pkg>strings</pkg>.<fcall>Repeat</fcall>(`raw`, <local>i</local>); <bfunc>len</bfunc>(<local>s</local>) > 0 && <bconst>true</bconst> {
                        <local>b</local>.<mcall>WriteString</mcall>(<local>s</local>)
                        <pkg>fmt</pkg>.<fcall>Println</fcall>(<local>s</local>, <-<local>ch</local>, <bconst>nil</bconst>)
                        break <label>Loop</label>
                    }
                }
                x := 1 # 2
            }
        """.trimIndent()
    }
}
