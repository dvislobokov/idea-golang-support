# Go models of the ML completion engine (rebuilt 2026-10-07 on the training server; idea-ml-completion CHANGELOG e14 / e18)

| file | what | size | use |
|---|---|---|---|
| `e14-b.cml` | n-gram LM, order 5, MKN, 24-bit fingerprints (ppl 4.6, top-1 0.510 on the test fold) | 33 MB | `lm.cml` of the Smart Completion build (`-PmlEnabled=true -Pml.models=<dir with lm.cml, rank.cml>`), ranker feature `lm_logprob` |
| `e14-b-rank.cml` | **proxy** ranker (linear, synthetic lists) | 1.7 KB | debugging only — the real-list ranker e17 (MRR 0.808 vs rules 0.527) was lost with the server disk and has to be re-exported (`tools/psi/` in the engine); do not ship this one |
| `go-16384.bpe` | BPE vocabulary of the Go transformer (retrained 2026-10-07) | 137 KB | loaded together with `go-nn-31m-e2.cml` (added when its evaluation finishes) |

The build expects `lm.cml` / `rank.cml` names in `-Pml.models`; copy or symlink from here. Source of truth:
https://github.com/dvislobokov/idea-ml-completion (`models/`).
