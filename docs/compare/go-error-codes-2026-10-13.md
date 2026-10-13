# Коды ошибок go/types: что плагин ловит (2026-10-13)

Вопрос: ловим ли мы все ошибки из `$GOROOT/src/internal/types/errors/codes.go` (Go 1.27.1). Проверено двумя готовыми наборами самого Go.

1. **Примеры из `codes.go`.** У каждого кода в комментарии есть пример, и `codes_test.go` (`TestErrorCodeExamples`) проверяет,
   что пример даёт именно этот код. Те же правила разбора — `tools/compare/codes/extract.py`: по пакету на пример в
   `build/compare/codes`. Эталон — go/types (`tools/compare/oracle`). У плагина — то, что показывает редактор: checker и
   инспекции по умолчанию, стенд `GoErrorCodesHarnessTest`. Сверка — `tools/compare/codes/compare.py`, таблица по каждому примеру —
   `go-error-codes-dumps/codes-table.md`.
2. **Весь `internal/types/testdata`** (check, fixedbugs, examples, spec) с разметкой `/* ERROR "…" */` — новый корпусный прогон
   `GorootTypesTestdataCorpusTest`. Он читает файлы прямо из GOROOT. Раньше `GoTypesTestdataTest` проверял только 51 скопированный
   файл; он остаётся строгим гейтом.

## После 0.2.224

| | До | После |
|---|---|---|
| Коды из примеров `codes.go`, пойманные ошибкой | 115 из 134 | **129** (+ `package _` и `import _ "C"`, у которых go/types-оракул молчит) |
| Предупреждение вместо ошибки | 2 | 0 |
| Не ловим (примеры) | 19 | 6: `InvalidDeclCycle_2`, `InvalidInstanceCycle_1`, `InvalidUnsafeSlice_4`, `InvalidUntypedConversion_1`, `TypeTooLarge_1/2` |
| testdata go/types: не сообщено | 566 из 2775 | **514** (файлов 329: с длинной константой теперь проверяется) |
| testdata go/types: лишних | 275 | 275 |
| OutOfMemory на `issue78346.go` | да | нет: `constant string too long` |

Все 17 бывших пропусков проверены в живой IDE (WSL, клон примеров в `~/robot/codes`): ERROR на строке go/types, текст go/types.
Гейты: GOROOT — 0 ложных, 350 тест-файлов GOROOT — 0, `GoTypesTestdataTest` (теперь с `check/gotos.go`) — 0 ложных.
Новый тест `GoErrorCodeExamplesTest` гоняет примеры `codes.go` прямо из GOROOT; список непойманных может только сокращаться.

Ниже — исходный замер (до правок).

## 1. По кодам

Всего 145 кодов, у 134 есть пример (148 примеров).

| Итог по коду | Кодов |
|---|---|
| ловим, ошибкой на нужной строке | **115** |
| ловим, но предупреждением (`UnusedImport`, `UnusedLabel`; компилятор и GoLand считают их ошибкой) | 2 |
| не ловим | **17** |

Не ловим (пример → текст go/types):

| Код | Что | Слой |
|---|---|---|
| `JumpIntoBlock`, `JumpOverDecl`, `UndeclaredLabel` | `goto L jumps into block`, `… over variable declaration`, `label L not declared` | метки и goto: в check/gotos.go не поймано ни одно из 40 мест |
| `OutOfScopeResult` | `result parameter i not in scope at return` | затенённый именованный результат и голый `return` |
| `InvalidSelectCase` | `select case must be send or receive` | |
| `MultiValAssignOp` | `assignment operation += requires single-valued expressions` | |
| `InvalidExprSwitch` | `cannot switch on a (… not comparable)` | |
| `InvalidPkgUse` | `use of package fmt not in selector` | |
| `InvalidUntypedConversion` | `cannot convert 1024 (untyped int constant) to type T` | константа в дженерике |
| `InvalidDeclCycle` (пример 2) | `invalid recursive type: T refers to itself` (через `unsafe.Sizeof(T{})`) | |
| `InvalidInstanceCycle` | `instantiation cycle` | |
| `InvalidUnion` | `cannot use interface{m()} in union (… contains methods)` | |
| `BlankIfaceMethod` | `methods must have a unique non-blank name` | |
| `DuplicateFieldAndMethod` | `field and method with the same name m` | |
| `BadDotDotDotSyntax` | `invalid use of [...] array (outside a composite literal)` | |
| `TypeTooLarge` | `… is too large` (массив в 2³¹ элементов) | |
| `InvalidUnsafeSlice` (пример 4) | `length … overflows int` | |
| `BlankPkgName` | `package _` → компилятор: `invalid package name _` | |
| `ImportCRenamed` | `import _ "C"` | нужен cgo, на этой машине нет gcc: go/types не проверен, плагин молчит |

Без примера в `codes.go` (не проверено): `MismatchedPkgName`, `BadImportPath`, `InvalidTypeCycle`, `InvalidMainDecl`,
`UnexportedLitField`, `InvalidMethodTypeParams`, `TooNew`. Служебные и устаревшие: `Test`, `BadDecl`, `UnsupportedFeature`,
`InvalidInitSig`.

Текст у пойманных обычно совпадает с go/types. Отличаются: позиция, вынесенная в текст (`declared at …`), у нас её нет;
`BadRecv` и `BadTypeKeyword` у нас выдаются как синтаксические ошибки парсера.

## 2. Весь testdata go/types

| | Значение |
|---|---|
| Файлов проверено | 328 из 365 одиночных (37 пропущены: флаги `-lang` / `-goexperiment` в первой строке, 2 — регулярка разметки не разобрана тестом, 1 — см. ниже) |
| Мест `ERROR` | 2775 |
| Поймано с нужным текстом | **2209 (79,6 %)** |
| На нужной строке, но с другим текстом | ~170 |
| Не сообщено совсем | ~396 (14 %) |
| Диагностик на строках без `ERROR` | 94 — это верхняя граница ложных срабатываний: файлы и так полны ошибок, часть из них — каскад |

Больше всего пропусков: `check/gotos.go` (0 из 40, метки и goto), `check/init2.go` (2 из 26, циклы инициализации через методы и
замыкания), `check/cycles6.go` (0 из 13), `spec/range*.go` (range по функциям и целым: другой текст), `examples/inference2.go`
(вывод типов: 13 из 14 мест — другой текст плюс 13 ложных), `fixedbugs/issue70417.go`.

**Найдена поломка с зависанием: `fixedbugs/issue78346.go` уводит checker в OutOfMemory.** Это строковая константа, которая
удваивается 31 раз. go/types останавливается на `constant string too long`, а наш checker строит строку целиком. Открытый в
редакторе такой файл съест память IDE. Чинить: ограничить длину строковой константы, как в go/types.

## Что чинить

1. OutOfMemory на длинной строковой константе: поломка IDE одним файлом.
2. Метки и `goto`: `JumpIntoBlock`, `JumpOverDecl`, `UndeclaredLabel` и 40 мест gotos.go. Целый класс ошибок не проверяется.
3. `UnusedImport` и `UnusedLabel` — уровнем ERROR (п. 17 общего списка).
4. Остальные 12 кодов из таблицы: каждый — небольшая проверка в `GoChecker`, пример из `codes.go` и есть регрессионный тест.
5. Тексты: ~170 мест с правильной строкой и другим текстом — сверять по `build/reports/goroot-types-testdata.txt`.

## Как повторить

```sh
python tools/compare/codes/extract.py                                   # build/compare/codes
(cd tools/compare/oracle && go build -o /tmp/oracle.exe .) && (cd build/compare/codes && /tmp/oracle.exe -dir . ./... > oracle.jsonl)
GO_HARNESS=1 GO_HARNESS_ROOT=build/compare/codes ./gradlew.bat :test --tests "io.github.golangsupport.compare.GoErrorCodesHarnessTest" --offline --rerun
python tools/compare/codes/compare.py build/compare/codes build/compare/out/codes-plugin.json > build/compare/out/codes-table.md
./gradlew.bat :go-psi-semantic:corpusTest --tests "io.github.golangsupport.semantic.GorootTypesTestdataCorpusTest" --offline --no-configuration-cache
```
