package io.github.golangsupport

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.impl.RunManagerImpl
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.golangsupport.run.GoCommand
import io.github.golangsupport.run.GoLandApplicationConfigurationType
import io.github.golangsupport.run.GoLandRunConfiguration
import io.github.golangsupport.run.GoLandTestConfigurationType

/** GoLand's configurations of a project load as ours, not as "unknown" types that make the platform advertise the GoLand plugin (seen live). */
class GoLandRunConfigurationTypesTest : BasePlatformTestCase() {
    fun testGoLandTypeIdsAreRegistered() {
        assertInstanceOf(ConfigurationTypeUtil.findConfigurationType("GoApplicationRunConfiguration"), GoLandApplicationConfigurationType::class.java)
        assertInstanceOf(ConfigurationTypeUtil.findConfigurationType("GoTestRunConfiguration"), GoLandTestConfigurationType::class.java)
    }

    fun testGoLandConfigurationLoadsAsOurs() {
        val xml = """
            <configuration name="TestTotal in store" type="GoTestRunConfiguration" factoryName="Go Test" nameIsGenerated="true">
              <module name="playground" />
              <working_directory value="/src/store" />
              <kind value="DIRECTORY" />
              <directory value="/src/store" />
              <framework value="gotest" />
              <pattern value="^\QTestTotal\E$" />
              <method v="2" />
            </configuration>
        """.trimIndent()
        val runManager = RunManagerImpl.getInstanceImpl(project)
        val settings = runManager.loadConfiguration(JDOMUtil.load(xml), false)
        try {
            val configuration = settings.configuration as GoLandRunConfiguration
            assertEquals(GoCommand.TEST, configuration.options.command)
            assertEquals("/src/store", configuration.options.target)
            assertTrue(configuration.options.recursive)
            assertEquals("^\\QTestTotal\\E$", configuration.options.testPattern)
        } finally {
            RunManager.getInstance(project).removeConfiguration(settings)
        }
    }
}
