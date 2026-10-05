# ML-completion: указатель

Основной план — общий для Go и C#: `../idea-dotnet-support/ML_COMPLETION_PLAN.md` (2026-10-04, на ревью, ничего не начато).

Коротко, что там про этот репозиторий:
- общий движок `../ide-completion-ml` (модели, обучение, хранение, оценка) попадает сюда через `git subtree` подпроектом `completion-ml/`;
  адаптер Go — пакет `io.github.golangsupport.ml` (лексер go-psi, признаки из `GoCompletionRankingContext` / `GoCompletionCandidate`);
- ранжирование (L1) встаёт в готовый EP `io.github.golangsupport.completionRanker` (`GoCompletionRanker`) как `GoMlCompletionRanker`;
  серый текст (L2, n-gram) — источник внутри `lang/GoInlineIdioms.kt` (`GoInlineIdiomsProvider`), ниже правил;
- корпус для предобучения — существующий `tools/ml` (`gopsi-corpus select/fetch/manifest`), примеры — новая задача `mlDataset`;
- Go идёт первым (фаза M4): ожидаемые типы у go-psi уже есть.

`docs/ML.md` остаётся в силе для имён (ML-2) и подсказок инспекций (ML-3); его части про completion (ML-1, ML-4) заменены основным планом.
