# Ошибки на сломанном коде: плагин против компилятора (prometheus, 2026-10-13)

Часть 1 сравнения с GoLand. Проект — **prometheus/prometheus v3.15.0** (коммит `5241a27fe3c6983549fccc32f6e65917408c63cd`, `go.work` из пяти
модулей), клон в `build/compare/prometheus`, `go mod download` выполнен, `go build ./...` проходит. Go 1.27.1, windows/amd64, `CGO_ENABLED=0`
(как `go env` на этой машине — так же видит проект и IDE). Плагин — рабочее дерево ветки `migration` (0.2.221), режим Built-in, gopls выключен,
включены все Go-инспекции, которые включены по умолчанию. Клон после работы чистый (`git status --short` пуст).

## Сводка

| Мера | Значение |
|---|---|
| Поломок (по одной исходной ошибке) | **55** (54 ошибки компилятора + 1 «недостижимый код», который видит только `go vet`) |
| Поймано на том же месте с тем же смыслом (ok) | **43** |
| Поймано, но не тот текст | 2 |
| Поймано, но не то место (другая колонка или строка) | 6 |
| Показано, но как предупреждение, а компилятор — ошибка | 2 (неиспользуемый импорт) |
| Пропущено | **2** (тип перестал реализовывать интерфейс) |
| **Поймано M из N** (ok + не тот текст + не то место) | **51 из 55**; со всеми видами показа — 53 из 55 |
| Вторичные ошибки после настоящей | **0** |
| **Extra на сломанном коде** | **0** |
| **Extra на чистом клоне** (229 файлов, 12 пакетов) | **12 ошибок**: 8 — настоящие ложные, 4 — в файлах вне сборки (build tags / GOOS) |

Главное: на сломанном коде плагин точен — ни одной лишней или каскадной ошибки, все категории ловятся. Портят картину три причины:
кэш реализаций интерфейса после правки, методы из `_test.go` и файлы вне сборки.

## Как мерили

- **Набор поломок** — `docs/compare/prometheus-broken-dumps/breakages.txt`, формат `путь|было|стало` (`## категория`, `#` — комментарий; `\n`,
  `\t`, `\|`). 55 строк, 9 категорий, 24 пакета. Каждая поломка проверена: оракул даёт ровно одну ошибку.
- **Эталон** — `tools/compare/broken_oracle.py`: кладёт поломку на диск, запускает `go build -gcflags=-e` на пакете, `go vet` на пакете и
  оракул go/types (`tools/compare/oracle`), возвращает файл байт в байт. Оракул: go/packages + go/types, печатает файл, строку, колонку,
  **код ошибки** (`UndeclaredName`, `WrongArgCount`, …) и текст. Запуск — `tools/compare/README.md`.
- **Плагин** — стенд `GoBrokenHarnessTest` (`src/test/kotlin/io/github/golangsupport/compare/`): клон — content root light-проекта, toolchain
  локальный, правка только в документе (на диск ничего не пишется), подсветка (`doHighlighting`) всех файлов пакета, кроме тестов и
  исключённых сборкой, потом откат. Включается только `GO_HARNESS=1`, в обычном прогоне тестов молчит.
- **Сверка** — `tools/compare/broken_compare.py`. Ключ — (файл, строка, колонка, нормализованный текст), а не строка: пять ошибок в разных
  аргументах одного вызова считаются за пять. Эталонная позиция — и go/types, и gc: они иногда ставят одну ошибку в разные колонки (у
  плагина колонка чаще совпадает с gc). Из найденного вычитается то, что те же файлы показывают на чистом клоне. Вердикты: ok / не тот
  текст / не то место / не та серьёзность / пропуск; остаток — вторичные (тот же файл, ±40 строк) и extra.
- Сырые данные: `prometheus-broken-dumps/` — `oracle.jsonl` (gc, vet, go/types по каждой поломке), `broken.json` и `broken-baseline.json`
  (стенд), `verdicts.json`, `table.md` (полная таблица), `clean.json` и `clean-vet.txt` (фон).

## Таблица по поломкам

Коротко; полная таблица с текстами эталона и плагина — `prometheus-broken-dumps/table.md`.

| # | Категория | Код go/types | Вердикт | Что не так |
|---|---|---|---|---|
| 1–3 | undefined-name: необъявленное имя | UndeclaredName | ok ×3 | |
| 4–8 | undefined-name: опечатка в поле / методе | MissingFieldOrMethod | ok ×5 | в т.ч. на дженерик-типе `searchResultStreamer[T]` |
| 9, 10 | undefined-name: неэкспортированное имя чужого пакета | UndeclaredImportedName | не тот текст ×2 | gc: `undefined: chunks.newCRC32`; плагин: `name newCRC32 not exported by package chunks` (текст go/types с исходниками — понятнее, но не как у компилятора) |
| 11–19 | type-mismatch: присваивание, return, аргумент | IncompatibleAssign / InvalidIfaceAssign | ok ×9 | int↔string, указатель↔значение, именованный↔базовый |
| 20 | type-mismatch: сравнение string с 0 | MismatchedTypes | не то место | gc/go/types — колонка оператора `==` (34), плагин — начало выражения (6) |
| 21–23, 25, 26 | arity: аргументы, присваивание, missing return | WrongArgCount / WrongAssignCount / MissingReturn | ok ×5 | |
| 24 | arity: мало результатов в return | WrongResultCount | не то место | gc — первый результат (кол. 15), плагин — `return` (кол. 10) |
| 27, 28 | interface: значение вместо указателя | InvalidIfaceAssign | ok ×2 | |
| **29, 30** | interface: метод переименован, `var _ I = &T{}` | InvalidIfaceAssign | **пропуск ×2** | после правки имени метода ошибка не появляется; на свежем файле с тем же текстом — появляется (проверено отдельной пробой) |
| 31, 34–36 | unused / redeclared / `:=` без новых | UnusedVar / DuplicateDecl / NoNewVar | ok ×4 | |
| **32, 33** | неиспользуемый импорт | UnusedImport | **предупреждение вместо ошибки** | у компилятора ошибка, у GoLand — красная подсветка (`ERRORS_ATTRIBUTES «Unused import»`, дамп `highlight-internal-probeerr-broken.txt`), у плагина — WARNING |
| 37–39 | break / continue вне цикла | MisplacedBreak / MisplacedContinue | ok ×3 | |
| 40 | недостижимый код (только vet) | — | ok | WARNING, как у GoLand |
| 41, 43, 44 | дженерики: число аргументов типа, ограничение | WrongTypeArgCount / CannotInferTypeArgs | ok ×3 | |
| 42 | дженерики: невыводимый тип `clearIfNotNil(nil)` | CannotInferTypeArgs | не то место | go/types — имя функции (22), gc — скобка (35), плагин — аргумент `nil` (39) |
| 45, 47–49 | каналы: отправка в `<-chan`, range по неитерируемому | InvalidSend / InvalidRangeExpr | ok ×4 | |
| 46 | каналы: приём из `chan<-` | InvalidReceive | не то место | gc — операнд (4), плагин — оператор `<-` (2) |
| 50–53 | литерал: неизвестное поле | MissingLitField | ok ×4 | |
| 54, 55 | литерал: смешение `поле: значение` и позиционных | MixedStructLit | не то место | компилятор — на позиционном элементе, плагин — на `{` литерала (на 3 строки выше) |

Вторичных ошибок плагина после настоящей нет ни в одной поломке. Подсветка пакета целиком после правки — медиана 3,4 с, максимум 21 с
(`tsdb`, `storage`: 30–50 файлов; на один файл — доли секунды).

## Фон чистого клона

Пакеты: `promql`, `promql/parser`, `tsdb`, `tsdb/chunkenc`, `storage/remote`, `scrape`, `rules`, `discovery/kubernetes`, `model/labels`,
`util/annotations`, `web/api/v1`, `cmd/promtool` — 229 файлов вместе с тестами. Клон собирается, `go vet ./...` даёт 52 находки
(`clean-vet.txt`: 25 в сгенерированном лексере, остальное — `stdmethods` на `Seek`), оракул — 0 ошибок.

| Ошибка плагина | Сколько | Вид | Причина |
|---|---|---|---|
| `cannot use &Discovery{} … as hasSynced value: … missing method hasSynced` (`discovery/kubernetes/kubernetes_test.go:237–243`) | 7 | **extra** | метод `hasSynced` объявлен в `_test.go` на типе из основного файла — плагин его не видит. Воспроизведено на трёх строках в light-фикстуре |
| `tc.matcher.hasCaseInsensitivePrefix undefined` (`model/labels/matcher_test.go:199`) | 1 | **extra** | та же причина: метод объявлен в `matcher_test.go:30` |
| `cannot use ls (… struct type Labels) as Labels value` ×2, `EmptyLabels() … as Labels` (`model/labels/labels_dedupelabels.go`, `labels_slicelabels.go`) | 3 | вне сборки | файлы с `//go:build dedupelabels` / `slicelabels` проверяются в контексте сборки по умолчанию, где `Labels` — другой тип из `labels_stringlabels.go` |
| `undefined: syscall.Mkfifo` (`cmd/promtool/tsdb_posix_test.go:43`) | 1 | вне сборки | файл `//go:build !windows`, проверяется под windows |

В тех же исключённых файлах — ещё 22 предупреждения «Unused function / global variable / constant» (`Equal`, `FromStrings`, `NewScratchBuilder`…):
используемость считается по сборке по умолчанию, где этих объявлений нет. Артефактов стенда (не та версия модуля, vendor) не найдено:
все ошибки воспроизводятся в IDE на тех же файлах.

## Что чинить (по причинам)

1. ✅ **Сделано в 0.2.222** (проверено в IDE на клоне: 8 ложных ошибок ушли; гейт `GorootTestFilesCheckCorpusTest`: на 350 тест-файлах GOROOT 1452 → 0, включая `export_test.go` внешних тестов). **Методы, объявленные в `_test.go`, не входят в набор методов типа** — 8 ложных ошибок на чистом клоне в двух пакетах из двенадцати,
   поэтому это самое частое и самое заметное. Тест-файлы пакета (`package x`, не `x_test`) — часть того же пакета при `go test`: их
   методы должны быть видны в тест-файлах (и только в них). Корпусный гейт `GorootCheckCorpusTest` этого не ловит: он пропускает `_test.go`.
   В корпус: пример `k/a.go` (`type T struct{}`) + `k/b_test.go` (`func (t *T) hasSynced() bool`, `var _ synced = &T{}`, `x.hasSynced()`),
   и включить `_test.go` в проверочный корпус (хотя бы выборку GOROOT `*_test.go` внутреннего пакета).
2. ✅ **Сделано в 0.2.225** (проверено в IDE на клоне: поломки 29 и 30 ловятся). **Реализация интерфейса не пересчитывается после правки метода** — 2 пропуска из 4 в категории interface. На свежем файле
   `var _ I = &T{}` с переименованным методом даёт ошибку, после такой же правки в открытом документе — нет. Кэш набора методов / проверки
   присваиваемости не сбрасывается от правки сигнатуры метода (вне тела функции). Опасно: пользователь переименовывает метод и не видит,
   что сломал реализацию. Регрессионный тест: `configureByText` → правка имени метода в документе → `doHighlighting` ждёт `missing method`.
3. **Файлы вне сборки проверяются как обычные** (build tags, GOOS) — 4 ошибки и 22 предупреждения на чистом клоне. GoLand для такого файла
   показывает плашку «файл не входит в сборку» и проверяет его в своём контексте (по тегам файла). Варианты: проверять в контексте,
   где файл собирается (теги из `//go:build`, GOOS из имени/условия), или не показывать ошибки типов и «unused» в исключённых файлах.
   Отличить от GoLand живой съёмкой: `labels_dedupelabels.go` открыть в GoLand и снять подсветку.
4. **Неиспользуемый импорт — предупреждение, а не ошибка.** Компилятор и GoLand считают ошибкой. Поднять уровень `GoUnusedImport` до ERROR
   (у неиспользуемой переменной уже ERROR).
5. **Позиции ошибок расходятся с компилятором** (6 случаев): бинарная операция — на начале выражения вместо оператора; `not enough return
   values` — на `return` вместо первого результата; невыводимый тип — на аргументе; приём из send-only — на `<-`; смешанный литерал — на
   `{` вместо позиционного элемента. Текст верный, пользователь видит место; править по одной функции checker'а, сверяя с gc
   (`cmd/compile` даёт позицию, которую видит человек в терминале).
6. **Текст для неэкспортированного имени чужого пакета** (2): плагин пишет текст go/types с исходниками (`name x not exported by package p`),
   компилятор — `undefined: p.x`. Можно оставить (текст понятнее); если цель — как у `go build`, поменять одну строку.

Порядок, если чинить: 1 → 2 → 3 → 4 (ложные ошибки и пропуск, который прячет поломку), затем 5–6.

## Как повторить

```sh
# эталон (пишет файлы клона на диск по одному и возвращает их)
python tools/compare/broken_oracle.py --root build/compare/prometheus --breaks docs/compare/prometheus-broken-dumps/breakages.txt --out docs/compare/prometheus-broken-dumps/oracle.jsonl
# плагин (стенд; клон на диске не трогает)
GO_HARNESS=1 ./gradlew.bat :test --tests "*GoBrokenHarnessTest*" --offline     # testClean + testBroken -> build/compare/out/*.json
# сверка
python tools/compare/broken_compare.py --oracle …/oracle.jsonl --plugin …/broken.json --baseline …/broken-baseline.json --out …/verdicts.json --md …/table.md
```
