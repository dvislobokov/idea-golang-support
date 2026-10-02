# Briefs for implementation agents

This project is built by an orchestrator (Fable) that delegates well-specified implementation
work to subagents (Opus/Sonnet). A subagent does not see the orchestrator's conversation, so a
brief must be self-contained. `CLAUDE.md` is loaded automatically and its hard rules apply.

## Brief template

```
Task: <one sentence>
Context: read CLAUDE.md, docs/PLAN.md section <N>, and <files>. Do not read generated sources.
Deliverables: <files to create or change, with package names>
Constraints:
  - Do not touch: <files/areas>
  - Public API: <none | listed signatures>
  - Stub/index versions: <unchanged | bump X>
Done when:
  - <command> passes (paste the tail of its output in the report)
  - <golden/metric> updated or unchanged
Report: files changed, commands run with results, open questions, anything you could not do.
```

## Rules for implementation agents

1. Work only inside the deliverables listed in the brief. If something else must change to make
   it compile, change the minimum and list it in the report.
2. Never edit `CLAUDE.md`, `docs/PLAN.md`, `Go.bnf` ambiguity rules in `GoParserUtil`, public API
   packages, or stub/index versions unless the brief says so explicitly.
3. Never commit, push, or create branches. Never delete files outside the deliverables.
4. Run the exact commands from "Done when". A task is not done if they fail. Do not weaken or
   delete a failing test to make it pass; report it.
5. Code and comments in English. Kotlin unless the brief says Java. Follow existing style in the
   module. No TODO without an issue-like note in the report.
6. Generated sources (`build/generated/**`) are never edited or committed.
7. Do not add dependencies beyond those in `gradle/libs.versions.toml` without stating why in
   the report.
8. Golden test files: when the brief allows regenerating, regenerate with
   `-Dgopsi.updateGoldens=true`, then re-run without the flag; list every golden that changed.
9. Prefer small, readable code over clever code. The orchestrator will review the diff.
10. If a platform API is unclear, check the SDK jar in the Gradle cache or the docs at
    plugins.jetbrains.com/docs/intellij; do not guess signatures.

## Report format

```
Changed: <list>
Commands:
  ./gradlew ... -> BUILD SUCCESSFUL (N tests)
Goldens changed: <list or none>
Deviations from brief: <list or none>
Open questions: <list or none>
```

## Acceptance checklist (orchestrator)

- Diff reviewed line by line; no edits outside deliverables.
- Affected tests run by the orchestrator, not only reported by the agent.
- Corpus gate for the touched layer run when grammar, stubs or resolve changed.
- `CHANGELOG.md` updated.
