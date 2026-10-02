package io.github.golangsupport.ide

import com.intellij.icons.AllIcons
import com.intellij.psi.PsiElement
import io.github.golangsupport.lang.GoFileType
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
 * Node icons of the IDE layer: the platform `AllIcons.Nodes.*` the root module's `GoDeclarationIcons` uses for the same kinds,
 * the file icon from [GoFileType]. PSI modules ship no icons of their own (icons come from the root module).
 */
object GoIdeIcons {
    @JvmField val FUNCTION: Icon = AllIcons.Nodes.Function
    @JvmField val METHOD: Icon = AllIcons.Nodes.Method
    @JvmField val TYPE: Icon = AllIcons.Nodes.Type
    @JvmField val STRUCT: Icon = AllIcons.Nodes.Class
    @JvmField val INTERFACE: Icon = AllIcons.Nodes.Interface
    @JvmField val VARIABLE: Icon = AllIcons.Nodes.Variable
    @JvmField val CONSTANT: Icon = AllIcons.Nodes.Constant
    @JvmField val FIELD: Icon = AllIcons.Nodes.Field

    /** The node icon of a Go declaration; stub-safe (the kind of a type spec comes from its stubbed type child). */
    @JvmStatic
    fun forElement(element: PsiElement): Icon? = when (element) {
        is GoFile -> GoFileType.icon
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
}
