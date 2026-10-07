# Задание: серый текст (inline completion) от нашей нейросети в Go-плагине

Написано 2026-10-07. Подробная версия того же задания (с эскизом кода, критериями приёмки и подводными камнями) —
`ML_INLINE_TASK.md` в `../idea-dotnet-support`; здесь только отличия для Go.

**Сделано 2026-10-07 (0.2.199–0.2.200, см. CHANGELOG и ROADMAP):** пункты 1–5 ниже выполнены и проверены роботом на ML-сборке (серый текст, принятие, передача идиомам). Движок, модель и ядра готовы, в плагине
нейросеть пока никто не вызывает: `ml-core` используется только ранкером списка (`go-psi-ide/.../ml/`).

## Что есть
- `ml-core/` (копия движка, обновлять `tools/ml/sync-ml-core.sh ../idea-ml-completion`, не править здесь) — в jar
  плагина вместе с нативными ядрами `native/libcmlkernels-*`; API — `io.github.completionml.core.nn.NnCompletion`
  (`docs/NN-COMPLETION-API.md` движка): `complete(path, before, after, session)` → `text`, `confProd`, `show`.
- Модель: `ml-models/go/go-nn-31m-e2.cml` + `ml-models/go/go-16384.bpe` (31 MB + 137 KB; ppl 2.99, остаток строки точно
  63.6 % позиций, при `confProd ≥ 0.8` показывается 36 % позиций и 97 % из них верны).
- Каркас: `GoMlModels` (APP-сервис, фоновая загрузка), `GoMlSettings` (страница Settings | Go | Smart Completion),
  `src/ml/resources/META-INF/go-ml.xml`, блок `mlEnabled` в `build.gradle.kts` (модели копируются в `ml/go/` при
  `-PmlEnabled=true`, zip получает classifier `-ml`).

## Что сделать
1. `build.gradle.kts`, блок `mlEnabled`: дополнительно класть `go-nn-31m-e2.cml` и `go-16384.bpe` из `ml-models/go/` в `ml/go/`
   (сейчас копируются только `lm.cml` и `rank.cml` из `-Pml.models`); не падать, если ранкера нет — real-list ранкер e17
   потерян, proxy `e14-b-rank.cml` в прод не класть.
2. `GoMlModels`: вторая пара «нейросеть + словарь» — `NnFormat.read` (из ресурса во временный файл, mmap), `BpeTokenizer`,
   `NnModel(nThreads = min(8, cores))`, `NnCompletion(Options(showThreshold = 0.8))`, прогрев одним вызовом, в лог —
   `NativeLib.status` (какие ядра загрузились). `NnModel` не реентерабелен — один поток на модель.
3. `GoNnInlineCompletionProvider : com.intellij.codeInsight.inline.completion.InlineCompletionProvider`, регистрация в
   `go-ml.xml`: `<inline.completion.provider implementation="…"/>` (EP `com.intellij.inline.completion.provider`).
   `isEnabled`: Go-файл, настройка включена, событие печати или явный вызов. `getSuggestion` (suspend): под read action взять
   хвост ≤ 40 KB до каретки, остаток строки + ≤ 16 KB после, путь относительно `project.basePath`; на фоновом потоке
   модели — `complete`; при `r.show` вернуть один `InlineCompletionGrayTextElement(r.textString)`. `NnSession` — на редактор
   (закрывать в `editorReleased`).
4. `GoMlSettings`: вкл/выкл серого текста, порог (0.8), показывать ли одиночные закрыватели (по умолчанию нет), строка
   состояния «модель / ядра».
5. Тесты: провайдер с моделью-заглушкой (show → текст, иначе пусто); в ML-сборке — загрузка из ресурсов и непустой
   результат на фиксированном Go-фрагменте; `:ml-core:test` не трогать.

## Подводные камни
Те же, что в C#-версии: ничего на EDT и под read action, один `NnModel` на приложение (~100 MB), первый вызов 0.3–1 с
(прогревать при открытии первого Go-файла), Windows-антивирус и распаковка DLL во `%TEMP%` (при неудаче движок сам
уходит в scalar), ничего не требовать от пользователя (vmoptions, флаги). IDE уже стартует с
`--enable-native-access=ALL-UNNAMED`.

## Порог после точки (2026-10-07, измерено на 3000 позициях)
После `.` модель угадывает не хуже, но менее уверена: при пороге 0.7 показывается Go 37 % / C# 20 % позиций (точность 96 / 98 %), при 0.5 — Go 51 % / C# 30 % (92 / 96 %).
В провайдере: `showThreshold = 0.5`, если байт перед кареткой — `.` (в C# также `?.`, `::`, `->`), иначе 0.7 — через `Options.copy(showThreshold = …)` на вызов.

## Статус 2026-10-08 (0.2.207): четыре шага ниже сделаны
1. `GoMlPreloadActivity` (`src/main/kotlin/.../settings/`, регистрация в `go-ml.xml`): после smart mode спрашивает `GoProjectPresence.recompute()`
   (индекс типов файлов) и только в проекте с Go-файлами вызывает `GoMlModels.preload()` — сеть (JIT, нативные ядра) и ранкер грузятся в фоне.
   Тест `GoMlPreloadActivityTest` (корневой модуль: проект без Go-файлов — 0 загрузок, с `main.go` — 1).
2. `GoNnFileOpenListener` (`FileEditorManagerListener.fileOpened`, projectListeners в `go-ml.xml`): снимок документа + каретка → `GoMlModels.prefill(editor)`
   на потоке модели (`GoNnInline.prefill` строит тот же промпт, что построит `complete`, с той же лечёной границей); пропускается, пока сеть не
   загружена или уже ждёт completion. Первая подсказка в файле: 6 мс вместо холодного prefill (`GoNnModelTest.prefillOfTheCaretIsReusedByTheFirstCompletion`,
   `GoNnModelSwitchTest` — prefill не блокирует вызывающего).
3. `-Pml.big=true` кладёт `go-nn-50m-e3-lr2e3.cml`; настройка «Big model (50 M)» (`GoMlSettings.inlineBigModel`), `GoMlModels.loadNn(dir, options, model)`
   по имени файла, ключ загрузки — каталог + имя; `reset()` теперь сразу перезагружает сеть в фоне и закрывает сессии редакторов. Без файла в сборке
   флажок выключен (каталог моделей с файлом включает). Тест `GoNnModelSwitchTest` (31 M → 50 M → 31 M через сервис).
4. Порог после точки: настройка «Confidence threshold after a dot» (0.5), `GoNnInline.gate(before, main, dot, empty)`; вариант `NnCompletion`
   на порог хранится в `Nn.completionFor` (не пересобирается на каждый вызов). Тест `GoNnInlineTest.gateIsLowerAfterADotAndOnABlankLine`.
5. Ранкер: `-PmlEnabled=true` без `-Pml.models` берёт `e14-b.cml` → `lm.cml` и `e17b-rank.cml` → `rank.cml` из `ml-models/go/` (processResources с
   rename); `GoMlCompletionRankerTest` проходит на этой паре (схема e17b совпадает с `GoMlFeatures`).

ML-сборка: `./gradlew buildPlugin -PmlEnabled=true [-Pml.big=true]` → `build/distributions/idea-golang-support-<version>-ml.zip` (на сервере
`buildPlugin` не собирается из-за Ultimate-классов в `GoCommitCheck`/`GoSharedIndexFinder` — zip собирает пользователь на Windows).
Не сделано: приоритет потока модели не понижается (prefill уступает только ожидающему completion); при префиксе длиннее `maxPrefix` (1024 токена)
окно промпта движка сдвигается с каждым токеном и KV-кэш не переиспользуется — это вопрос к движку (`InlinePrompt.tail`), не к плагину.

## Следующие шаги для серого текста (2026-10-08, от пользователя)
1. **Предзагрузка при открытии проекта**: `ProjectActivity` в фоне после индексации — если в проекте есть `.go`-файлы (проверка через индекс
   типов файлов), запустить загрузку `GoMlModels` + прогрев (JIT, нативная библиотека) с низким приоритетом. Не грузить при старте приложения.
   Проекты без Go-файлов не должны платить ~100 MB.
2. **Фоновый prefill при открытии файла**: при открытии Go-редактора посчитать prefill `NnSession` для префикса файла на потоке модели
   (низкий приоритет, отменяемо, пропускается, пока модель не загружена) — первая подсказка в файле переиспользует KV-кэш вместо холодного
   prefill (~150 мс). Сессия — одна на редактор, EDT не блокировать.
3. **Переключатель 31 M / 50 M**: вторая модель `ml-models/go/go-nn-50m-e3-lr2e3.cml` в ML-сборке за свойством (`-Pml.big=true`), настройка
   «большая модель» в `GoMlSettings`, `GoMlModels.loadNn` по имени файла; 50 M даёт 65.7 % против 63.6 % при ~1.7× латентности.
4. **Ранкер**: `ml-models/go/e17b-rank.cml` как `rank.cml` ML-сборки (MRR 0.799 против правил 0.513) и `e14-b.cml` как `lm.cml`.
