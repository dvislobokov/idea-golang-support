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

Docs: [PLAN](docs/PLAN.md), [ANALYSIS](docs/ANALYSIS.md), [agent briefs](docs/AGENT_BRIEFS.md),
[CLAUDE.md](CLAUDE.md), [CHANGELOG](CHANGELOG.md).
