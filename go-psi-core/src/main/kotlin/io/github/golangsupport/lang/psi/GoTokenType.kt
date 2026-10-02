package io.github.golangsupport.lang.psi

import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.GoLanguage
import org.jetbrains.annotations.NonNls

/** Leaf token type; instances are created by the Grammar-Kit generated [GoTypes] holder. */
class GoTokenType(@NonNls debugName: String) : IElementType(debugName, GoLanguage)
