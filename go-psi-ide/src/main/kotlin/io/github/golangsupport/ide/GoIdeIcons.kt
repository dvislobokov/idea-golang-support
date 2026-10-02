package io.github.golangsupport.ide

import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.GoIcons
import io.github.golangsupport.lang.psi.GoAnonymousFieldDefinition
import io.github.golangsupport.lang.psi.GoConstDefinition
import io.github.golangsupport.lang.psi.GoFieldDefinition
import io.github.golangsupport.lang.psi.GoFile
import io.github.golangsupport.lang.psi.GoFunctionDeclaration
import io.github.golangsupport.lang.psi.GoFunctionLit
import io.github.golangsupport.lang.psi.GoInterfaceType
import io.github.golangsupport.lang.psi.GoMethodDeclaration
import io.github.golangsupport.lang.psi.GoMethodSpec
import io.github.golangsupport.lang.psi.GoParamDefinition
import io.github.golangsupport.lang.psi.GoReceiver
import io.github.golangsupport.lang.psi.GoStructType
import io.github.golangsupport.lang.psi.GoTypeParamDefinition
import io.github.golangsupport.lang.psi.GoTypeSpec
import io.github.golangsupport.lang.psi.GoVarDefinition
import javax.swing.Icon

/**
 * Placeholder node icons of the IDE layer. The destination plugin (idea-golang-support) supplies
 * the real icon set at the transplant; only [forElement] is expected to survive.
 */
object GoIdeIcons {
    @JvmField val FUNCTION: Icon = load("function")
    @JvmField val METHOD: Icon = load("method")
    @JvmField val TYPE: Icon = load("type")
    @JvmField val STRUCT: Icon = load("struct")
    @JvmField val INTERFACE: Icon = load("interface")
    @JvmField val VARIABLE: Icon = load("variable")
    @JvmField val CONSTANT: Icon = load("constant")
    @JvmField val FIELD: Icon = load("field")

    /** The node icon of a Go declaration; stub-safe (the kind of a type spec comes from its stubbed type child). */
    @JvmStatic
    fun forElement(element: PsiElement): Icon? = when (element) {
        is GoFile -> GoIcons.FILE
        is GoFunctionDeclaration, is GoFunctionLit -> FUNCTION
        is GoMethodDeclaration, is GoMethodSpec -> METHOD
        is GoTypeSpec -> when (element.type) {
            is GoStructType -> STRUCT
            is GoInterfaceType -> INTERFACE
            else -> TYPE
        }
        is GoTypeParamDefinition -> TYPE
        is GoFieldDefinition, is GoAnonymousFieldDefinition -> FIELD
        is GoConstDefinition -> CONSTANT
        is GoVarDefinition, is GoParamDefinition, is GoReceiver -> VARIABLE
        else -> null
    }

    private fun load(name: String): Icon = IconLoader.getIcon("/icons/ide/$name.svg", GoIdeIcons::class.java)
}
