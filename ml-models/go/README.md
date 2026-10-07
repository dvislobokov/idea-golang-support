# Go models of the ML completion engine (rebuilt 2026-10-07 on the training server; idea-ml-completion CHANGELOG e14 / e18)

| file | what | size | use |
|---|---|---|---|
| `e14-b.cml` | n-gram LM, order 5, MKN, 24-bit fingerprints (ppl 4.6, top-1 0.510 on the test fold) | 33 MB | `lm.cml` of the Smart Completion build (`-PmlEnabled=true -Pml.models=<dir with lm.cml, rank.cml>`), ranker feature `lm_logprob` |
| `e14-b-rank.cml` | **proxy** ranker (linear, synthetic lists) | 1.7 KB | debugging only — the real-list ranker e17 (MRR 0.808 vs rules 0.527) was lost with the server disk and has to be re-exported (`tools/psi/` in the engine); do not ship this one |
| `go-nn-31m-e2.cml` | own transformer go31m-e2 (30.9 M params, int8, BPE 16k, SPM/PSM FIM; scrubbed corpus 6.85 G tokens, lr 2e-3) — ppl 2.99, rest of line exact 63.6 % (≤ 8 tokens 74.8 %), at confProd ≥ 0.8: 36 % shown / 97 % right | 31 MB | **inline (grey-text) completion** via `NnCompletion` — see `ML_INLINE_TASK.md` |
| `go-nn-50m-e3-lr2e3.cml` | the big Go transformer (d640 × 10, 49.8 M params, int8), same data/recipe — ppl 2.83, rest of line exact 65.7 % (31 M: 63.6 %), fresh repos 59.2 % (56.6 %), at confProd ≥ 0.7: 45 % shown / 96 % right | 50 MB | optional (user switch): ~1.7× the latency of the 31 M model |
| `go-16384.bpe` | BPE vocabulary of that transformer (retrained 2026-10-07; the .cml's tokenizerSha256 must match) | 137 KB | loaded together with `go-nn-31m-e2.cml` |

The build expects `lm.cml` / `rank.cml` names in `-Pml.models`; copy or symlink from here. Source of truth:
https://github.com/dvislobokov/idea-ml-completion (`models/`).
