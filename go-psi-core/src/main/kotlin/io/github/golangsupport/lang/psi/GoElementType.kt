package io.github.golangsupport.lang.psi

import com.intellij.psi.tree.IElementType
import io.github.golangsupport.lang.GoLanguage
import org.jetbrains.annotations.NonNls

/** Composite element type; instances are created by the Grammar-Kit generated [GoTypes] holder. */
class GoElementType(@NonNls debugName: String) : IElementType(debugName, GoLanguage)
