package io.github.golangsupport

import io.github.golangsupport.lint.config.GolangciConfig
import io.github.golangsupport.lint.config.GolangciConfigResult
import io.github.golangsupport.lint.config.GolangciConfigs
import io.github.golangsupport.lint.config.GolangciConfigs.Format
import io.github.golangsupport.lint.config.GolangciDefault
import io.github.golangsupport.lint.config.GolangciExclusions
import io.github.golangsupport.lint.config.GolangciGenerated
import io.github.golangsupport.lint.config.GolangciLinters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The golangci-lint configuration reader without a process: both formats, presets, exclusions, settings of the ported linters, bad files. */
class GolangciConfigTest {
    private fun parse(text: String, format: Format = Format.YAML): GolangciConfig {
        val result = GolangciConfigs.parse(text.trimIndent(), format)
        assertTrue("expected parsed, got $result", result is GolangciConfigResult.Parsed)
        return result.configOrNull!!
    }

    private val v1 = """
        run:
          timeout: 5m
          tests: true
          build-tags: [integration, e2e]
          skip-dirs: [gen]
        linters:
          disable-all: true
          enable:
            - errcheck
            - govet
            - gosimple
            - staticcheck
            - revive
            - gocyclo
            - gomnd
        linters-settings:
          errcheck:
            check-type-assertions: true
            exclude-functions:
              - io/ioutil.ReadFile
              - (io.Closer).Close
          gocyclo:
            min-complexity: 15
          govet:
            enable: [shadow]
            disable: [printf]
            settings:
              shadow:
                strict: true
          revive:
            rules:
              - name: var-naming
                arguments: [["ID"], ["VM"]]
              - name: line-length-limit
                severity: warning
                arguments: [120]
              - name: exported
                disabled: true
        issues:
          exclude-use-default: true
          include: [EXC0002, EXC0012]
          exclude:
            - "should check returned error before deferring"
          exclude-rules:
            - path: _test\.go
              linters: [gocyclo, errcheck]
            - path: internal/legacy/
              text: "SA1019"
            - linters: [lll]
              source: "^//go:generate "
          exclude-files:
            - ".*\\.pb\\.go$"
        severity:
          default-severity: error
          rules:
            - linters: [revive]
              severity: info
    """

    private val v2 = """
        version: "2"
        run:
          tests: false
          build-tags: [integration]
        linters:
          default: none
          enable: [errcheck, govet, staticcheck, unused, gocritic, gosec]
          disable: [unused]
          settings:
            staticcheck:
              checks: ["all", "-ST1000", "-SA1019", "-QF*"]
            gocritic:
              enabled-checks: [hugeParam, rangeValCopy]
              disabled-checks: [ifElseChain]
              settings:
                hugeParam:
                  sizeThreshold: 120
            gosec:
              excludes: [G104, G304]
          exclusions:
            generated: strict
            presets: [comments, std-error-handling]
            rules:
              - path: _test\.go
                linters: [gosec]
              - path-except: ^cmd/
                text: "fmt.Println"
            paths:
              - third_party$
              - ^gen/
            paths-except:
              - ^gen/keep/
        formatters:
          enable: [gofumpt, goimports]
          settings:
            gofumpt:
              extra-rules: true
    """

    @Test fun versionOneIsReadAndNormalized() {
        val c = parse(v1)
        assertEquals(1, c.version)
        assertEquals(GolangciDefault.NONE, c.default)
        assertEquals(listOf("integration", "e2e"), c.buildTags)
        assertTrue(c.tests)
        assertEquals(15, (c.settingsOf("gocyclo")["min-complexity"] as Number).toInt())
        assertEquals("error", c.defaultSeverity)
        assertEquals("info", GolangciExclusions.severity(c, "a.go", "revive", "x"))
        assertEquals("error", GolangciExclusions.severity(c, "a.go", "errcheck", "x"))
        assertTrue(c.warnings.toString(), c.warnings.isEmpty())
    }

    @Test fun versionTwoIsRead() {
        val c = parse(v2)
        assertEquals(2, c.version)
        assertEquals(GolangciDefault.NONE, c.default)
        assertFalse(c.tests)
        assertEquals(GolangciGenerated.STRICT, c.generated)
        assertEquals(listOf("gofumpt", "goimports"), c.formatters)
        assertEquals(true, c.formatterSettings["gofumpt"]?.get("extra-rules"))
        assertEquals(listOf("comments", "std-error-handling"), c.exclusionPresets)
    }

    @Test fun effectiveLintersOfVersionOne() {
        val c = parse(v1)
        assertEquals(setOf("errcheck", "govet", "gosimple", "staticcheck", "revive", "gocyclo", "mnd"), GolangciLinters.effectiveLinters(c))
        assertEquals(GolangciLinters.DEFAULT_V1, GolangciLinters.effectiveLinters(parse("run:\n  tests: true")))
        val presets = GolangciLinters.effectiveLinters(parse("linters:\n  presets: [sql]\n  disable: [errcheck]"))
        assertTrue("rowserrcheck" in presets && "sqlclosecheck" in presets && "govet" in presets && "errcheck" !in presets)
        val fast = GolangciLinters.effectiveLinters(parse("linters:\n  enable-all: true\n  fast: true"))
        assertTrue("misspell" in fast && "gocyclo" in fast && "staticcheck" !in fast)
    }

    @Test fun effectiveLintersOfVersionTwo() {
        assertEquals(setOf("errcheck", "govet", "staticcheck", "gocritic", "gosec"), GolangciLinters.effectiveLinters(parse(v2)))
        assertEquals(GolangciLinters.DEFAULT_V2, GolangciLinters.effectiveLinters(parse("version: \"2\"")))
        val all = GolangciLinters.effectiveLinters(parse("version: \"2\"\nlinters:\n  default: all\n  disable: [wsl]"))
        assertTrue("revive" in all && "wsl" !in all && "gosimple" !in all && "gofmt" !in all)
        val fast = GolangciLinters.effectiveLinters(parse("version: \"2\"\nlinters:\n  default: fast"))
        assertTrue("misspell" in fast && "errcheck" !in fast)
        // gosimple of a v1 habit lands on staticcheck in v2
        assertTrue("staticcheck" in GolangciLinters.effectiveLinters(parse("version: 2\nlinters:\n  default: none\n  enable: [gosimple]")))
    }

    @Test fun staticcheckChecksPerVersion() {
        val c1 = parse(v1)
        val on1 = GolangciLinters.effectiveLinters(c1)
        assertTrue(GolangciLinters.isStaticcheckEnabled(c1, on1, "SA4006"))
        assertTrue(GolangciLinters.isStaticcheckEnabled(c1, on1, "S1000"))
        assertFalse("stylecheck is off in this v1 config", GolangciLinters.isStaticcheckEnabled(c1, on1, "ST1005"))
        assertFalse(GolangciLinters.isStaticcheckEnabled(c1, on1, "QF1001"))
        val c2 = parse(v2)
        val on2 = GolangciLinters.effectiveLinters(c2)
        assertTrue(GolangciLinters.isStaticcheckEnabled(c2, on2, "SA4006"))
        assertFalse(GolangciLinters.isStaticcheckEnabled(c2, on2, "SA1019"))
        assertFalse(GolangciLinters.isStaticcheckEnabled(c2, on2, "QF1003"))
        assertTrue(GolangciLinters.isStaticcheckEnabled(c2, on2, "ST1005"))
        assertFalse(GolangciLinters.isStaticcheckEnabled(c2, on2, "ST1000"))
        val defaults = parse("version: \"2\"")
        assertFalse("ST1003 is off by staticcheck's defaults", GolangciLinters.isStaticcheckEnabled(defaults, GolangciLinters.DEFAULT_V2, "ST1003"))
        assertTrue(GolangciLinters.checkSelected(listOf("inherit", "ST1003"), "ST1003"))
    }

    @Test fun errcheckSettings() {
        val e = GolangciLinters.errcheck(parse(v1))
        assertEquals(listOf("io/ioutil.ReadFile", "(io.Closer).Close"), e.excludeFunctions)
        assertTrue(e.checkTypeAssertions)
        assertFalse(e.checkBlank)
    }

    @Test fun reviveRulesWithArguments() {
        val r = GolangciLinters.revive(parse(v1))
        assertEquals(listOf(listOf("ID"), listOf("VM")), r.rules.getValue("var-naming").arguments)
        assertEquals(120, (r.rules.getValue("line-length-limit").arguments.single() as Number).toInt())
        assertEquals("warning", r.rules.getValue("line-length-limit").severity)
        assertTrue(r.isEnabled("var-naming"))
        assertFalse(r.isEnabled("exported"))
        assertFalse("a listed set replaces revive's defaults", r.isEnabled("error-strings"))
        val defaults = GolangciLinters.revive(parse("linters:\n  enable: [revive]"))
        assertTrue(defaults.isEnabled("error-strings") && !defaults.isEnabled("line-length-limit"))
        val all = GolangciLinters.revive(parse("linters-settings:\n  revive:\n    enable-all-rules: true\n    rules:\n      - name: add-constant\n        disabled: true"))
        assertTrue(all.isEnabled("line-length-limit") && !all.isEnabled("add-constant"))
    }

    @Test fun govetGocriticGosecSelections() {
        val c1 = parse(v1)
        assertTrue(GolangciLinters.isGovetAnalyzerEnabled(c1, "shadow"))
        assertFalse(GolangciLinters.isGovetAnalyzerEnabled(c1, "printf"))
        assertTrue(GolangciLinters.isGovetAnalyzerEnabled(c1, "copylocks"))
        assertFalse(GolangciLinters.isGovetAnalyzerEnabled(c1, "fieldalignment"))
        assertEquals(true, GolangciLinters.govetAnalyzerSettings(c1, "shadow")["strict"])
        val c2 = parse(v2)
        assertTrue(GolangciLinters.isGocriticEnabled(c2, "hugeParam"))
        assertFalse(GolangciLinters.isGocriticEnabled(c2, "ifElseChain"))
        assertTrue(GolangciLinters.isGocriticEnabled(c2, "dupSubExpr"))
        assertFalse(GolangciLinters.isGocriticEnabled(c2, "whyNoLint"))
        assertEquals(120, (GolangciLinters.gocriticSettings(c2, "hugeparam")["sizeThreshold"] as Number).toInt())
        assertFalse(GolangciLinters.isGosecRuleEnabled(c2, "G104"))
        assertTrue(GolangciLinters.isGosecRuleEnabled(c2, "G101"))
    }

    @Test fun exclusionsOfVersionOne() {
        val c = parse(v1)
        assertTrue(GolangciConfigs.isExcluded(c, "store/order_test.go", "gocyclo", "cyclomatic complexity 30"))
        assertFalse(GolangciConfigs.isExcluded(c, "store/order_test.go", "govet", "printf: wrong verb"))
        assertTrue(GolangciConfigs.isExcluded(c, "internal/legacy/a.go", "staticcheck", "SA1019: io/ioutil is deprecated"))
        assertTrue("text is case-insensitive", GolangciConfigs.isExcluded(c, "internal/legacy/a.go", "staticcheck", "sa1019: x"))
        assertFalse(GolangciConfigs.isExcluded(c, "internal/fresh/a.go", "staticcheck", "SA1019: io/ioutil is deprecated"))
        assertTrue(GolangciConfigs.isExcluded(c, "gen.go", "lll", "line is 200 characters", "//go:generate stringer -type=Kind"))
        assertFalse(GolangciConfigs.isExcluded(c, "gen.go", "lll", "line is 200 characters", "var x = 1"))
        assertTrue(GolangciConfigs.isExcluded(c, "a.go", "staticcheck", "SA5001: should check returned error before deferring f.Close()"))
        assertTrue(GolangciConfigs.isExcluded(c, "api/v1/a.pb.go", "errcheck", "x"))
        assertTrue("skip-dirs", GolangciConfigs.isExcluded(c, "gen/a.go", "errcheck", "x"))
        assertTrue("default dirs", GolangciConfigs.isExcluded(c, "third_party/lib/a.go", "errcheck", "x"))
        assertTrue("EXC0001 by default", GolangciConfigs.isExcluded(c, "a.go", "errcheck", "Error return value of `f.Close` is not checked"))
        assertFalse(GolangciConfigs.isExcluded(c, "a.go", "errcheck", "Error return value of `json.Unmarshal` is not checked"))
        assertFalse("EXC0002 and EXC0012 are in issues.include", GolangciConfigs.isExcluded(c, "a.go", "revive", "exported function Foo should have comment or be unexported"))
        assertTrue(GolangciConfigs.isExcluded(c, "a.go", "revive", "should have a package comment"))
        assertTrue("go list skips testdata", GolangciConfigs.isExcluded(c, "pkg\\testdata\\a.go", "errcheck", "x"))
        assertFalse(GolangciConfigs.isExcluded(parse("issues:\n  exclude-use-default: false"), "a.go", "errcheck", "Error return value of `f.Close` is not checked"))
    }

    @Test fun exclusionsOfVersionTwo() {
        val c = parse(v2)
        assertTrue("run.tests: false", GolangciConfigs.isExcluded(c, "a_test.go", "errcheck", "x"))
        assertTrue(GolangciConfigs.isExcluded(c, "gen/a.go", "errcheck", "x"))
        assertFalse("paths-except", GolangciConfigs.isExcluded(c, "gen/keep/a.go", "errcheck", "x"))
        assertTrue(GolangciConfigs.isExcluded(c, "svc/a.go", "gocritic", "avoid fmt.Println here"))
        assertFalse("path-except keeps cmd/", GolangciConfigs.isExcluded(c, "cmd/a.go", "gocritic", "avoid fmt.Println here"))
        assertTrue("std-error-handling preset", GolangciConfigs.isExcluded(c, "a.go", "errcheck", "Error return value of `w.Flush` is not checked"))
        assertTrue("comments preset, stylecheck reported as staticcheck", GolangciConfigs.isExcluded(c, "a.go", "stylecheck", "ST1020: comment on exported method"))
        assertFalse("legacy preset is not on", GolangciConfigs.isExcluded(c, "a.go", "staticcheck", "SA4011: ineffective break statement"))
        assertFalse(GolangciConfigs.isExcluded(c, "a.go", "errcheck", "Error return value of `json.Unmarshal` is not checked"))
    }

    @Test fun jsonConfiguration() {
        val c = parse("""{"version": "2", "linters": {"default": "all", "settings": {"gocyclo": {"min-complexity": 20}}, "exclusions": {"rules": [{"path": "_test\\.go", "linters": ["gocyclo"]}]}}}""", Format.JSON)
        assertEquals(GolangciDefault.ALL, c.default)
        assertEquals(20, (c.settingsOf("gocyclo")["min-complexity"] as Number).toInt())
        assertTrue(GolangciConfigs.isExcluded(c, "x_test.go", "gocyclo", "too complex"))
    }

    @Test fun malformedAndUnsupportedFilesAreResults() {
        assertTrue(GolangciConfigs.parse("linters:\n  enable: [errcheck\n  disable: x: y", Format.YAML) is GolangciConfigResult.Failed)
        assertTrue(GolangciConfigs.parse("- a\n- b", Format.YAML) is GolangciConfigResult.Failed)
        assertTrue(GolangciConfigs.parse("{\"linters\": ", Format.JSON) is GolangciConfigResult.Failed)
        assertTrue(GolangciConfigs.parse("[linters]\nenable = [\"errcheck\"]", Format.TOML) is GolangciConfigResult.Unsupported)
        assertEquals(1, parse("").version)
        val bad = parse("issues:\n  exclude-rules:\n    - text: \"([a-z\"\n      linters: [errcheck]\n    - {}")
        assertTrue(bad.exclusionRules.none { it.preset == null })
        assertEquals(2, bad.warnings.size)
        val wrongTypes = parse("linters:\n  enable: errcheck\nlinters-settings:\n  errcheck: 5")
        assertEquals(listOf("errcheck"), wrongTypes.enable)
        assertTrue(wrongTypes.settingsOf("errcheck").isEmpty())
    }

    @Test fun findWalksUpToTheStop() {
        val root = Files.createTempDirectory("golangci").toFile()
        try {
            val module = File(root, "repo/svc").apply { mkdirs() }
            val pkg = File(module, "store/order").apply { mkdirs() }
            assertNull(GolangciConfigs.find(pkg, module))
            File(root, "repo/.golangci.json").writeText("{}")
            assertNull("above the stop", GolangciConfigs.find(pkg, module))
            assertEquals(File(root, "repo/.golangci.json").canonicalFile, GolangciConfigs.find(pkg, File(root, "repo"))?.canonicalFile)
            File(module, ".golangci.yaml").writeText("version: \"2\"")
            File(module, ".golangci.yml").writeText("version: \"2\"\nlinters:\n  default: none")
            val found = GolangciConfigs.find(pkg, module)!!
            assertEquals(".golangci.yml", found.name)
            assertEquals(GolangciDefault.NONE, GolangciConfigs.load(found).configOrNull?.default)
            assertEquals(found.path, GolangciConfigs.load(found).file)
        } finally {
            root.deleteRecursively()
        }
    }
}
