# go-psi

Native Go language support for the IntelliJ Platform: lexer, parser, PSI, stubs, indices,
project model, type system and resolve, implemented on PSI with no LSP and no gopls. Also
usable as a library (`go-psi-core`, `go-psi-semantic`) by other plugins and tools.
Requires JDK 21; targets IntelliJ IDEA 2026.1+ (build 261, no upper bound).

    ./gradlew build                      # compile, fast tests, plugin ZIP in plugin/build/distributions
    ./gradlew :plugin:runIde             # sandbox IDE with PsiViewer
    ./gradlew test                       # fast tests; -Dgopsi.updateGoldens=true rewrites goldens
    ./gradlew :go-psi-core:corpusTest    # slow gates over GOROOT/src (-Pgopsi.goroot=...)
    ./gradlew :plugin:verifyPlugin       # JetBrains Plugin Verifier

Docs: [PLAN](PSI-PLAN.md), [ANALYSIS](ANALYSIS.md), [agent briefs](AGENT_BRIEFS.md),
[CLAUDE.md](../CLAUDE.md), [CHANGELOG](../CHANGELOG.md) (section "go-psi").

The go-psi documents in this folder (moved from `docs/psi` in migration step 10; the plugin's own plans are
[ROADMAP](../ROADMAP.md), [PLAN](../PLAN.md) and [MIGRATION](../MIGRATION.md)):

- [PSI-PLAN](PSI-PLAN.md) — decisions, module layout, phases of go-psi
- [ANALYSIS](ANALYSIS.md) — how existing Go IDE support is built
- [API](API.md) — go-psi API for library consumers, binary compatibility
- [GRAMMAR](GRAMMAR.md) — grammar ambiguities and how `GoParserUtil` resolves them
- [PROJECT-MODEL](PROJECT-MODEL.md) — project model
- [SEMANTIC](SEMANTIC.md) — types, scopes, resolve, inference, diagnostics
- [IDE-FEATURES](IDE-FEATURES.md) — IDE features of go-psi-ide
- [FORMATTER](FORMATTER.md) — the gofmt-compatible formatter
- [FEATURES](FEATURES.md) — feature roadmap (what is not done yet)
- [PERF-BACKLOG](PERF-BACKLOG.md) — performance backlog
- [LIBRARY-SUMMARIES](LIBRARY-SUMMARIES.md) — persisted type information for libraries (design)
- [RULES](RULES.md) — declarative lint rules (assessment and plan)
- [ML](ML.md) — ML assistance (assessment and plan)
- [TESTING](TESTING.md) — testing and live verification
- [AGENT_BRIEFS](AGENT_BRIEFS.md) — briefs for implementation agents
- [PSI-MIGRATION-history](PSI-MIGRATION-history.md) — the transplant analysis of 2026-10-01 (inventory, extension points, risks);
  the plan that was followed is the root `MIGRATION.md`
