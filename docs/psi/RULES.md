# Declarative lint rules: assessment and plan

Status: planning only. Scheduled after the semantic performance work (per-package trackers,
lazy reparse, per-function inference; see the performance notes in `docs/SEMANTIC.md`), because
hundreds of rules multiply the cost of every semantic query. Decided on 2026-10-01.

## 1. Goal

Let rules for linting be described as data (YAML, validated by a JSON Schema) instead of Kotlin
code, so that:

- go-psi can ship a large built-in rule set cheaply;
- users and teams add their own rules per project without writing a plugin;
- existing community rule sets (ruleguard / go-critic) can be imported.

## 2. Assessment

Why it fits go-psi:

- Precedents prove the model: Semgrep and ast-grep (YAML patterns), ruleguard and go-critic
  (gogrep-style patterns with type filters), IntelliJ Structural Search inspections.
- go-psi has a full parser and a semantic API (`GoSemanticService.typeOf/resolve/implements`,
  method sets, constants). Rules can therefore filter on types, not only on syntax, which is
  what separates useful rules from noisy ones and puts this above text-based tools.
- Declarative rules are safe: user rules are data, no third-party code runs in the IDE.

Limits to accept up front:

- Data-flow rules (resource leaks, nil after check, results lost through several assignments)
  do not fit a declarative pattern well. They stay Kotlin inspections or wait for a data-flow
  module.
- Rule quality matters more than count. A hundred rules with 5% false positives are worse than
  twenty precise ones; every bundled rule must pass the corpus gate (section 6).

## 3. Rule format

One rule per YAML document; files under `.go-psi/rules/*.yaml` in the project and
`rules/*.yaml` bundled in the plugin. Schema in `go-psi-ide/src/main/resources/schema/go-rules.schema.json`,
registered through the platform's JSON Schema support so that completion and validation work in
the editor.

```yaml
id: error-compare-with-eq
title: Comparing errors with ==
pattern: $err == $target
where:
  $err: { implements: error }
  $target: { not: nil }
message: "Use errors.Is($err, $target) instead of =="
severity: warning            # error | warning | weak_warning | info
category: errors
since: "1.13"                # minimum Go language version
fix: errors.Is($err, $target)
imports: [errors]            # added by the fix when missing
examples:
  bad:  ["if err == io.EOF {}"]
  good: ["if err == nil {}", "if errors.Is(err, io.EOF) {}"]
```

Fields:

| Field | Meaning |
|---|---|
| `id` | stable identifier, used for suppression (`//noinspection` + id) and settings |
| `pattern` / `patterns` | one or more Go code templates with metavariables (alternatives) |
| `where` | constraints per metavariable (section 4) |
| `inside` / `not-inside` | context patterns (e.g. only inside `func ... error`) |
| `message` | text with `$var` substitution, rendered with the matched source text |
| `severity`, `category`, `since`, `tags` | presentation, Go version gating, filtering |
| `fix` | replacement template; the result is formatted with the go-psi gofmt formatter |
| `imports` | packages the fix needs; added with the existing import inserter |
| `examples.bad/good` | self-tests, mandatory for bundled rules |

## 4. Pattern language and constraints

Patterns use gogrep syntax and are parsed by the go-psi parser into PSI with metavariables:

- `$x` matches one expression, type or statement; the same name must match equal subtrees.
- `$_` matches anything without binding; `$*args` matches zero or more list elements.
- Patterns are compared structurally on the PSI (ignoring whitespace, comments and redundant
  parentheses), never as text.

Constraints are evaluated only through the public semantic API:

| Constraint | Example |
|---|---|
| `type` | `{ type: "[]byte" }`, `{ type: "*$T" }` |
| `implements`, `assignableTo`, `convertibleTo` | `{ implements: error }` |
| `kind` | `{ kind: [slice, map, chan, pointer, interface] }` |
| `const`, `value` | `{ const: true }`, `{ value: 0 }` |
| `pure` | no calls, receives or assignments in the subtree |
| `resolvesTo` | `{ resolvesTo: "fmt.Errorf" }` (package path + name) |
| `text` | regular expression on the source text (escape hatch) |
| `not`, `any`, `all` | boolean combinations |

## 5. Engine and integration

- **Matcher:** compiles each pattern into a matcher tree once; rules are indexed by the root
  element kind and, for calls, by the callee name, so one walk of the file dispatches to the
  few candidate rules per node.
- **Caching:** results per file in a `CachedValue` on the Go trackers, like the existing
  diagnostics cache; constraint evaluation reuses the `typeOf` and resolve caches.
- **Inspection:** one inspection "Go: custom rules" whose options list every loaded rule with its
  own toggle and severity; suppression with `//noinspection <rule-id>`; bundled and project rules
  shown separately.
- **Fixes:** the replacement template is instantiated with the matched subtrees, inserted as
  text, then the edited range is reformatted; imports from `imports` are added.
- **Loading:** project rule files are watched through VFS; parse errors are reported in the
  rule file itself (annotator on YAML with the schema), never as exceptions.
- **Extension point:** `io.github.golangsupport.ruleProvider` so other plugins can contribute
  rule sets.

## 6. Quality gates

- Every rule's `examples.bad` must match and `examples.good` must not: a generated test per rule
  file (`GoRulesSelfTest`), also runnable by users through an action "Test rules".
- Bundled rules run over GOROOT/src and golang.org/x in a `*CorpusTest`; findings are reviewed,
  and a bundled rule ships only when its findings on these corpora are true positives.
- Performance budget: the full bundled set adds at most 20% to the check time per file
  (benchmarked with the existing benchmark harness).

## 7. Rule sources

1. Hand-written bundled set: simple, high-precision checks in the spirit of staticcheck
   S1xxx/SA4xxx and gopls simplifications, each with examples.
2. ruleguard importer: ruleguard rule files are Go code calling a matcher DSL; go-psi parses
   them with its own PSI and converts `m.Match(...)`, `.Where(m["x"].Type.Is(...))`,
   `.Report(...)`, `.Suggest(...)` into the YAML model. This makes go-critic rule sets available.
   Unsupported constructs are reported per rule and skipped.
3. Project and team rules written by users.

## 8. Plan

| Step | Content | Exit criterion |
|---|---|---|
| R-0 | Pattern parser with metavariables, structural matcher, unit tests | 50 pattern/match cases incl. lists and repeated variables |
| R-1 | Constraints on the semantic API, YAML loader, JSON Schema, single inspection with options | rules from `.go-psi/rules` highlight in the sandbox IDE |
| R-2 | Fix templates, imports, suppression, rule self-test harness | self-tests green for all example rules |
| R-3 | Bundled set of 30-50 rules with corpus gate and performance budget | 0 unreviewed findings on GOROOT/x, < 20% check overhead |
| R-4 | ruleguard importer, `ruleProvider` EP | go-critic rules imported with a coverage report |

## 9. Open questions

- Whether to also expose rules through the platform's Structural Search UI (needs a
  `StructuralSearchProfile` for Go) or keep a go-psi-specific engine only.
- Whether rule files should support `include`/`extends` for shared team configurations.
- Naming and versioning of the schema (`version: 1` field in each file).
