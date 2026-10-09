package io.github.golangsupport

import io.github.golangsupport.mod.GoProjectModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoProjectModuleTest {
    @Test fun aDirectoryBasedGoProjectWithoutModulesGetsOne() = assertTrue(GoProjectModule.needsModule(moduleCount = 0, hasGoFiles = true, directoryBased = true, enabled = true))

    @Test fun aProjectWithAModuleIsLeftAlone() = assertFalse(GoProjectModule.needsModule(moduleCount = 1, hasGoFiles = true, directoryBased = true, enabled = true))

    @Test fun aProjectWithoutGoFilesIsLeftAlone() = assertFalse(GoProjectModule.needsModule(moduleCount = 0, hasGoFiles = false, directoryBased = true, enabled = true))

    @Test fun aFileBasedProjectIsLeftAlone() = assertFalse(GoProjectModule.needsModule(moduleCount = 0, hasGoFiles = true, directoryBased = false, enabled = true))

    @Test fun theSettingTurnsItOff() = assertFalse(GoProjectModule.needsModule(moduleCount = 0, hasGoFiles = true, directoryBased = true, enabled = false))

    @Test fun theModuleFileIsWhereTheIdeWritesIts() = assertEquals("C:/p/echo-sample/.idea/echo-sample.iml", GoProjectModule.moduleFilePath("C:/p/echo-sample", "echo-sample"))
}
