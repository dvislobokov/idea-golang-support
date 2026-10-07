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
