# Acceptance memory of the Go plugin — what is stored, where, and how to read it (for the engine side)

Since 0.2.210 the Go plugin keeps a per-project counter of the completion items the user accepted ("learns from me"). This note is for
whoever turns it into a ranker feature of the shared engine (`idea-ml-completion`, `FeatureExtractor`): the key, the storage and the API.

## What is counted
- One increment per **real acceptance**: `LookupListener.itemSelected` of a Go lookup whose item carries `GoLookupInfo` (an item of
  `GoCompletionContributor`; the Fill / mapping items and items of other contributors are not counted). Dismissed popups count nothing.
- Nothing is recorded or read while the setting **Learn from what I accept** (Settings | Go | Editor and Completion,
  `GoCompletionAssistSettings.acceptanceEnabled`, on by default) is off.

## The key
`<kind>|<lookupString>` — `GoAcceptanceMemory.key(kind, lookupString)`.

`kind` is the context kind of the position where the list was shown, `GoAcceptanceMemory.kindOf(GoCompletionContext)`:

| kind   | position                                                                   | engine `ContextKind` |
|--------|----------------------------------------------------------------------------|----------------------|
| `dot`  | after `.` in an expression or a type (`SELECTOR`, `TYPE_SELECTOR`)         | `AFTER_DOT`          |
| `arg`  | inside the argument list of a call (`EXPRESSION` whose reference's parent is `GoArgumentList`) | `ARGUMENT` |
| `stmt` | the start of a statement (`STATEMENT`)                                     | `STATEMENT_START`    |
| `key`  | a struct literal key being typed (`STRUCT_KEY`)                            | `OTHER`              |
| `type` | a type position (`TYPE`, `RECEIVER_TYPE`)                                  | `TYPE_POSITION`      |
| `top`  | between top-level declarations (`TOP_LEVEL`)                               | `OTHER`              |
| `expr` | any other expression position (operand, right side of `=` / `:=`, `return`) | `ASSIGN_RHS` / `OTHER` |

The kind is also written on every Go lookup element: `GoLookupInfo.contextKind` (`GoCompletionWeigher.infoOf(element)`).

`lookupString` is `LookupElement.lookupString` — the plain name for most items, the whole chain for chain items (`u.Profile.Email`), the
qualified name for members of unimported packages. It is the same string `GoCompletionCandidate.lookupString` carries into `GoCompletionRanker.rank`.

## Where it lives
- Service: `io.github.golangsupport.ide.completion.GoAcceptanceMemory` (project level, `go-psi-ide`).
- Storage: `@State(name = "GoCompletionAcceptance", storages = [Storage(StoragePathMacros.CACHE_FILE, roamingType = DISABLED)])` — the
  project's workspace cache under the IDE system directory (`system/workspace/<project>.xml`), **not** `.idea`, not roamed, never in VCS;
  Invalidate Caches drops it.
- State: `counts: Map<String, Int>` (key → count) and `lastDecay: Long` (epoch millis of the last halving).
- Decay: once `DECAY_MILLIS` (30 days) has passed since `lastDecay` every count is halved (integer division; zeros are dropped), once per
  month passed, at most 12 months at a time; checked on every record (`decayIfDue()`). Caps: `MAX_COUNT` 1 000 per key, `MAX_ENTRIES` 5 000
  keys (the rarest are dropped first).

## API (`GoAcceptanceMemory.getInstance(project)`)
- `count(kind, lookupString): Int` — 0 when never accepted.
- `record(kind, lookupString)` — what the listener calls.
- `snapshot(): Map<String, Int>` — a copy of every counter (for an export: write it next to the completion lists of a session, or feed it
  into a feature of the candidate — e.g. `ln(1 + count)` of the exact key, and of the lookup string in any kind).
- `clear()` — the "Reset Memory" button.
- `now: () -> Long` — the clock (tests move it).

## How ranking uses it today
- A ranker with a model (`GoMlCompletionRanker`, ML build): `rankerScore += weight × ln(1 + count)` with `weight = GoCompletionAssistSettings.acceptanceWeight`
  (0.3; "Weight of accepted items" on the Smart Completion page). The scores of `LinearRanker` are softmax logits: three acceptances
  (≈ +0.42) turn a close call, not a clear loss. Declared by `GoCompletionRanker.acceptanceWeight` (> 0: additive).
- The rankers without a model (`GoHeuristicRanker`, `acceptanceWeight` 0): `GoAcceptanceWeigher` (`goAcceptedBefore`, before `goCompletion`)
  orders by the count inside the buckets of the deterministic order (expected-type match, scope level), so a count never beats a
  closer or better-typed candidate; the frequency / recency bonus decides among equal counts.
- Once the engine has the feature, the additive bonus should go (set the weight to 0, or let the ML ranker return its own
  `acceptanceWeight` 0) so the count is not applied twice; the real-list export (`ML_RANKER_EXPORT_TASK.md`) can then write `snapshot()` per project.
