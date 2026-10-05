package io.github.golangsupport.lang

import io.github.golangsupport.ide.GoIdeOptions
import io.github.golangsupport.ide.GoRenameChoice
import io.github.golangsupport.settings.GoSettings

/**
 * The options of go-psi-ide (auto-import, linked renames) are the plugin's settings ([GoSettings]): replaces `DefaultGoIdeOptions` of
 * go-psi, which keeps them in memory (the service is overridden in plugin.xml, as [GoIgsIdeFeatureGate]).
 */
class GoIgsIdeOptions : GoIdeOptions {
    private val settings get() = GoSettings.getInstance()

    override var importUnambiguousOnTheFly: Boolean
        get() = settings.importUnambiguousOnTheFly
        set(value) { settings.importUnambiguousOnTheFly = value }

    override var importOptimizeOnTheFly: Boolean
        get() = settings.importOptimizeOnTheFly
        set(value) { settings.importOptimizeOnTheFly = value }

    override var importShowPopup: Boolean
        get() = settings.importShowPopup
        set(value) { settings.importShowPopup = value }

    override var importExcluded: List<String>
        get() = settings.importExcluded
        set(value) { settings.importExcluded = value }

    override var renameTestFiles: GoRenameChoice
        get() = settings.renameTestFiles
        set(value) { settings.renameTestFiles = value }

    override var renameStructTags: GoRenameChoice
        get() = settings.renameStructTags
        set(value) { settings.renameStructTags = value }

    override var renameDirectoryPackage: GoRenameChoice
        get() = settings.renameDirectoryPackage
        set(value) { settings.renameDirectoryPackage = value }

    override var renamePackageDirectory: GoRenameChoice
        get() = settings.renamePackageDirectory
        set(value) { settings.renamePackageDirectory = value }
}
