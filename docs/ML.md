# ML assistance on top of go-psi: assessment and plan

Status: planning only. Nothing in this document is scheduled before Phase 6d (completion) is
stable. Decided on 2026-10-01 from a proposal to add small, CPU-only models to the plugin.

## 1. Assessment of the proposal

The proposal: use small models (gradient boosting / logistic regression, later a 10-30M
parameter quantised transformer via ONNX Runtime) for (1) completion ranking, (2) name
suggestion, (3) inline next-fragment prediction, (4) inspection classifiers; keep all of it in
a separable module behind extension points, with no network and no data collection.

What holds up:

- **Ranking is the right first target.** Candidates come from PSI and resolve, the model only
  orders them. Features are cheap and already available from go-psi: element kind, expected
  type match (`typeOf` + assignability from `semantic.types`), declaration distance, name
  frequency in file/package (stub indices), prefix match, recency. A 20-50 feature boosted
  tree is kilobytes and microseconds per candidate. JetBrains ships exactly this
  (`intellij.go.completionMlRanking` in GoLand, see ANALYSIS.md 1.5). The platform already has
  the `com.intellij.completion.ml.ranking` infrastructure; a custom weigher is the fallback.
- **Name suggestion** is mostly a table with back-off (`type -> name`), optionally a tiny
  char n-gram model. go-psi gives the expression type and the callee; the destination plugin
  already has `GoExpectedTypes`-style helpers to reuse.
- **Separability** is correct and cheap: a content module `go-ml` with `loading="optional"`,
  EPs in the main plugin with empty default behaviour, lazy background model loading, a kill
  switch, graceful degradation on any load error.
- **Scope-constrained inline prediction** (model chooses among identifiers PSI says are
  visible) is the one idea that makes a small model useful instead of a hallucination source.

What is weaker or unverified:

- **Inline prediction (3)** is a separate product. Training a Go-specific model, tokenizer,
  quantisation, latency work and UI (inlay-style ghost text through
  `InlineCompletionProvider`) are each bigger than any single go-psi phase. The expected
  quality gap to cloud models is large; the value is idioms (`if err != nil { return err }`,
  `for i := range`, closing braces), which a rule-based snippet engine on PSI context already
  covers to a large degree. Build the rules first, measure what is left.
- **ONNX Runtime packaging**: the Java artifact with natives is ~30-60 MB per platform set,
  loads native code inside the plugin classloader, and its behaviour in split mode (Remote
  Dev) is unverified. This must be prototyped on the build before any model work.
- **Training data and licensing**: GOROOT and golang.org/x are BSD-licensed and fine for
  offline training of ranking/name models; a code-generation model trained on broader corpora
  needs a licensing review. Logging user sessions for training is off the table by default.
- **Evaluation**: without an offline metric per feature (MRR for ranking, top-k accuracy for
  names, exact-match for idioms) nothing can be accepted; the corpus-gate style of this
  project applies here too.

Verdict: do (1), (2) and (4) as planned, through EPs in a separate optional module, after
Phase 6d; treat (3) as an experiment with its own go/no-go after the rule-based snippets exist.

## 2. Architecture

```
plugin (main)            go-ml (optional content module, loading="optional")
  ext points:              implementations:
  GoCompletionRanker  <--  GoMlCompletionRanker   (boosted trees, features from PSI/semantic)
  GoNameSuggester     <--  GoMlNameSuggester      (type -> name table + n-gram back-off)
  GoInspectionHint    <--  GoMlInspectionHints    (small classifiers)
  GoInlineProposer    <--  GoOnnxInlineProposer   (experiment; ONNX Runtime, scope-constrained)
```

- EP interfaces live in `go-psi-ide`, default behaviour when no implementation is registered:
  current ordering, no suggestions. `GoNameSuggester`, `GoInspectionHint` and `GoInlineProposer`
  will go to `io.github.golangsupport.ide.ml.api`; the ranking EP exists since Phase 6d:

### `GoCompletionRanker` (implemented EP, Phase 6d)

```kotlin
package io.github.golangsupport.ide.completion.api

interface GoCompletionRanker {
    /** Scores in the order of [candidates] (higher is better), or null to abstain. */
    fun rank(context: GoCompletionRankingContext, candidates: List<GoCompletionCandidate>): List<Double>?

    companion object {
        val EP_NAME = ExtensionPointName<GoCompletionRanker>("io.github.golangsupport.completionRanker")
    }
}

data class GoCompletionRankingContext(
    val file: PsiFile, val offset: Int,
    val positionKind: String,   // GoCompletionContext.Kind: STATEMENT, EXPRESSION, TYPE, SELECTOR, ...
    val prefix: String,
    val expectedType: String?,  // rendered expected type at the caret, null if none
)

data class GoCompletionCandidate(
    val lookupString: String,
    val kind: String,            // GoCandidateKind: LOCAL, PARAMETER, FUNCTION, METHOD, FIELD, KEYWORD, ...
    val scopeLevel: Int,         // 0 local, 1 parameter, 2 keyword/snippet, 3 package, 4 imported, 5 universe, 6 unimported
    val expectedTypeMatch: Int,  // 2 identical, 1 assignable, 0 none
    val element: PsiElement?,    // the declaration; never load other files' AST from it
)
```

Registration (in the optional module's descriptor):

```xml
<extensions defaultExtensionNs="io.github.golangsupport">
  <completionRanker implementation="...GoMlCompletionRanker"/>
</extensions>
```

How it plugs in: `GoCompletionContributor` builds the lookup elements of one provider call,
then calls the registered rankers in order with the whole batch (background read action, the
completion thread; budget < 1 ms per 100 candidates; exceptions are logged and ignored); the
first non-null list of the right size wins and each score is stored on the element's
`GoLookupInfo`. `GoCompletionWeigher` (registered after the platform's `priority` and before
`prefix`) orders by expected-type match, then the ranker score, then the deterministic scope
distance; with no ranker the score is 0 for every candidate and the order is unchanged. The
platform's start-vs-middle-match classifier still runs before all weighers. Further features
for a model (declaration distance, usage frequency in file/package from stub indices, recency)
can be computed by the implementation from `element` and `context.file`.
- `go-ml` depends on `go-psi-core`, `go-psi-semantic`, `go-psi-ide` public API only.
- Models are resources inside `go-ml` (`models/*.bin` for trees/tables, `.onnx` for the
  experiment), loaded lazily on a pooled thread, versioned, with a checksum; any failure logs
  once and disables the feature for the session.
- Settings: one flag per feature in the destination plugin's settings page (not in go-psi).
- No network calls, no telemetry, no training-data collection in the plugin.

## 3. Plan

### ML-0: prerequisites (after Phase 6d)
- Completion contributor with deterministic ranking and a `CompletionWeigher`; snippet/idiom
  engine on PSI context (`err != nil`, `for range`, `switch` cases, struct literal fields).
- Define the three EPs and their feature records (`GoCompletionCandidateFeatures`,
  `GoNameContext`), implement feature extraction in `go-psi-ide` with unit tests.
- Build prototype: add `onnxruntime` to a throwaway module, load a trivial model inside the
  sandbox IDE and in `runIde --split-mode`; record size, startup cost, classloader issues.

### ML-1: completion ranking

Status 2026-10-05 (0.2.179): the shared engine lives in `ml/` (subtree of idea-ml-completion), the Go feature adapter
`io.github.golangsupport.ml.GoMlFeatures` and the headless dataset export `:go-psi-ide:mlDataset` exist, and so does the
IDE side: `GoMlCompletionRanker` (the `completionRanker` EP), `GoMlModels` (bundled or user-chosen `lm.cml` + `rank.cml`,
loaded in the background on the first completion) and Settings | Go | Smart Completion (`GoMlSettings`). All of it is
opt-in at build time: `./gradlew.bat buildPlugin -PmlEnabled=true -Pml.models=<dir>` (or `MLENABLED=true`) copies
`META-INF/go-ml.xml` and the models into the plugin; a plain build has neither the ranker nor the page. Measured offline
(ml/docs/REPORT-GO-RU.md): MRR 0.783 vs 0.534 for the deterministic order on held-out repositories.
- Offline dataset from GOROOT + golang.org/x: for each identifier/selector position, the
  candidate list our completion would produce (headless, via a `*CorpusTest`-style exporter)
  and the actual token; export as a feature table.
- Train gradient boosting (Python, offline); export to a compact tree format read by Kotlin
  (no runtime dependency); unit-test the evaluator against the Python predictions.
- Gate: offline MRR / top-1 against the deterministic ranking on a held-out corpus; ship only
  if clearly better; latency budget < 1 ms per 100 candidates.

### ML-2: name suggestion
- Dataset: (declared type, callee, context) -> chosen name from the corpora; table with
  back-off, optional char n-gram model; used by `:=` completion, extract variable, rename.
- Gate: top-3 accuracy on held-out code.

### ML-3: inspection hints
- Small classifiers on PSI features for: test helper detection, ignorable error results,
  warning priority. Each needs a labelled set; start with heuristics and measure.

### ML-4: inline prediction experiment
- Only after ML-0 prototype and the rule-based snippet engine are in. Separate repository for
  training (tokenizer over Go tokens + identifiers, 10-30M parameters, int8). Integration via
  `InlineCompletionProvider`, constrained decoding over identifiers visible from PSI scope.
- Go/no-go on: latency (< 100 ms for 20 tokens on a laptop CPU), exact-match on idiom
  benchmark, memory, packaging size.

## 4. Open questions to verify on the build
- ONNX Runtime native loading in the plugin classloader and in split mode.
- Whether the platform's built-in ML completion ranking API can host our ranker directly
  (less code) or a weigher is needed.
- Size budget for the plugin ZIP with models.
