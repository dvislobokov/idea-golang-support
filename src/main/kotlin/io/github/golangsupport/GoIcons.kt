package io.github.golangsupport

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object GoIcons {
    @JvmField val File: Icon = load("go")
    @JvmField val TestFile: Icon = load("goTest")

    /** The run configuration. */
    @JvmField val Run: Icon = load("goRun")

    /** go.mod, go.work, go.sum. */
    @JvmField val Module: Icon = load("goMod")

    /** A dependency in the project tree. */
    @JvmField val Package: Icon = load("goPackage")

    private fun load(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", GoIcons::class.java)
}
