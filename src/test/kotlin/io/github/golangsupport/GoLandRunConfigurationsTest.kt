package io.github.golangsupport

import com.intellij.openapi.util.JDOMUtil
import io.github.golangsupport.run.GoCommand
import io.github.golangsupport.run.GoLandRunConfigurations
import io.github.golangsupport.run.GoRunConfigurationOptions
import org.jdom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GoLand's `GoApplicationRunConfiguration` / `GoTestRunConfiguration` XML as our options and back (seen live: `.idea/workspace.xml` of a GoLand project). */
class GoLandRunConfigurationsTest {
    private val modules = mapOf("C:/p" to "github.com/me/sconf")
    private fun modulePathAt(dir: String): String? = modules[dir]
    private fun importPathOf(dir: String): String? = modules.entries.firstOrNull { dir == it.key || dir.startsWith(it.key + "/") }
        ?.let { (root, module) -> module + dir.removePrefix(root) }

    private fun parse(xml: String): Element = JDOMUtil.load(xml)

    private val application = """
        <configuration name="go build github.com/me/sconf/example" type="GoApplicationRunConfiguration" factoryName="Go Application" nameIsGenerated="true">
          <module name="sconf" />
          <working_directory value="C:/p" />
          <kind value="PACKAGE" />
          <package value="github.com/me/sconf/example" />
          <directory value="C:/p" />
          <auto_managed_config_id value="C:/p/example" />
          <parameters value="-v --port 80" />
          <go_parameters value="-race" />
          <envs>
            <env name="A" value="1" />
          </envs>
        </configuration>
    """.trimIndent()

    @Test fun applicationPackageBecomesGoRun() {
        val options = GoRunConfigurationOptions()
        assertTrue(GoLandRunConfigurations.read(parse(application), options, test = false, ::modulePathAt) != null)
        assertEquals("C:/p/example", options.target)
        assertEquals("C:/p", options.workingDirectory)
        assertEquals("-v --port 80", options.programArguments)
        assertEquals("-race", options.goArguments)
        assertEquals(mapOf("A" to "1"), options.environment)
        assertFalse(options.recursive)
    }

    @Test fun packageWithoutModuleFallsBackToFileOrManagedId() {
        val options = GoRunConfigurationOptions()
        val xml = """<configuration><kind value="PACKAGE" /><package value="other/cmd" /><directory value="C:/q" /><filePath value="C:/q/cmd/main.go" /></configuration>"""
        GoLandRunConfigurations.read(parse(xml), options, test = false, ::modulePathAt)
        assertEquals("C:/q/cmd", options.target)
    }

    @Test fun testKinds() {
        val pkg = GoRunConfigurationOptions()
        GoLandRunConfigurations.read(parse("""<configuration><kind value="PACKAGE" /><package value="github.com/me/sconf/store" /><directory value="C:/p" /><framework value="gotest" /><pattern value="^\QTestTotal\E$" /></configuration>"""), pkg, test = true, ::modulePathAt)
        assertEquals("C:/p/store", pkg.target)
        assertEquals("^\\QTestTotal\\E$", pkg.testPattern)
        assertFalse(pkg.recursive)

        val dir = GoRunConfigurationOptions()
        GoLandRunConfigurations.read(parse("""<configuration><kind value="DIRECTORY" /><directory value="C:/p/store" /><framework value="gobench" /></configuration>"""), dir, test = true, ::modulePathAt)
        assertEquals("C:/p/store", dir.target)
        assertTrue(dir.recursive)
        assertTrue(dir.benchmark)

        val file = GoRunConfigurationOptions()
        GoLandRunConfigurations.read(parse("""<configuration><kind value="FILE" /><filePath value="C:/p/a_test.go" /></configuration>"""), file, test = true, ::modulePathAt)
        assertEquals("C:/p/a_test.go", file.target)
    }

    @Test fun ourOwnConfigurationIsNotGoLands() {
        val xml = """<configuration><option name="target" value="C:/p" /></configuration>"""
        assertNull(GoLandRunConfigurations.read(parse(xml), GoRunConfigurationOptions(), test = false, ::modulePathAt))
    }

    @Test fun unchangedConfigurationIsWrittenBackAsGoLandWroteIt() {
        val options = GoRunConfigurationOptions().apply { command = GoCommand.RUN }
        val source = parse(application)
        val stored = GoLandRunConfigurations.read(source, options, test = false, ::modulePathAt)
        val written = Element("configuration")
        GoLandRunConfigurations.write(written, options, GoCommand.RUN, stored, ::importPathOf)
        assertEquals(source.children.map { JDOMUtil.write(it) }, written.children.map { JDOMUtil.write(it) })
    }

    @Test fun changedTargetIsWrittenInGoLandTermsAndExtrasStayOurs() {
        val options = GoRunConfigurationOptions()
        val stored = GoLandRunConfigurations.read(parse(application), options, test = false, ::modulePathAt)
        options.target = "C:/p/cmd/api"
        options.race = true
        val written = Element("configuration")
        written.addContent(Element("option").setAttribute("name", "target").setAttribute("value", "C:/p/cmd/api"))
        written.addContent(Element("option").setAttribute("name", "race").setAttribute("value", "true"))
        GoLandRunConfigurations.write(written, options, GoCommand.RUN, stored, ::importPathOf)
        assertEquals("PACKAGE", written.getChild("kind").getAttributeValue("value"))
        assertEquals("github.com/me/sconf/cmd/api", written.getChild("package").getAttributeValue("value"))
        assertEquals("sconf", written.getChild("module").getAttributeValue("name"))
        assertEquals(listOf("race"), written.getChildren("option").map { it.getAttributeValue("name") })

        // and it reads back to the same target
        val again = GoRunConfigurationOptions()
        GoLandRunConfigurations.read(written, again, test = false, ::modulePathAt)
        assertEquals("C:/p/cmd/api", again.target)
    }

    @Test fun recursiveTestIsWrittenAsDirectory() {
        val options = GoRunConfigurationOptions().apply { command = GoCommand.TEST; target = "C:/p/store"; recursive = true; testPattern = "^TestX$" }
        val written = Element("configuration")
        GoLandRunConfigurations.write(written, options, GoCommand.TEST, null, ::importPathOf)
        assertEquals("DIRECTORY", written.getChild("kind").getAttributeValue("value"))
        assertEquals("gotest", written.getChild("framework").getAttributeValue("value"))
        assertEquals("^TestX$", written.getChild("pattern").getAttributeValue("value"))
        val again = GoRunConfigurationOptions()
        GoLandRunConfigurations.read(written, again, test = true, ::modulePathAt)
        assertEquals("C:/p/store", again.target)
        assertTrue(again.recursive)
    }

    @Test fun packageDirectoryFromModule() {
        assertEquals("C:/p", GoLandRunConfigurations.packageDirectory("github.com/me/sconf", listOf("C:/p"), ::modulePathAt))
        assertEquals("C:/p/a/b", GoLandRunConfigurations.packageDirectory("github.com/me/sconf/a/b", listOf("C:/x", "C:/p"), ::modulePathAt))
        assertNull(GoLandRunConfigurations.packageDirectory("github.com/me/sconfx", listOf("C:/p"), ::modulePathAt))
    }
}
