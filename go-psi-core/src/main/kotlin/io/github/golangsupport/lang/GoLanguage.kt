package io.github.golangsupport.lang

import com.intellij.lang.Language

object GoLanguage : Language("Go") {
    private fun readResolve(): Any = GoLanguage

    override fun getDisplayName(): String = "Go"

    override fun isCaseSensitive(): Boolean = true
}
