# Поведение по клавишам: плагин против GoLand (2026-10-13)

Часть 4 сравнения. 80 сценариев, код по образцу prometheus (структуры с тегами, методы, ошибки, замыкания, каналы, дженерики) —
`tools/compare/typing/scenarios.txt`. Формат описан в заголовке файла. `to_json.py` переводит его в JSON, общий для обеих сторон.

**Как снято.**
- **GoLand 2026.2.3** — робот (`tools/ui-robot/goland/analysis/typing_batch.js` + `typing_batch.sh`). Каждый сценарий — во временном
  пакете `typingcmp/<id>/` открытого проекта; набор идёт через `TypedAction`, как с клавиатуры; клавиши — через действия редактора.
- **Плагин** — стенд `GoTypingHarnessTest` (`myFixture.type` / `performEditorAction`), форматтер GOFMT, format on save включён (по
  умолчанию у плагина), gopls выключен. Файлы сценариев лежат на диске в `build/compare/typing-work`: gofmt запускается в каталоге файла.
- **Живая IDEA с плагином** — тот же `typing_batch.js` с `TARGET=plugin` (порт 8083, вывод в `build/compare/out/typing-sandbox/`).
  Ещё не прогнан. Он сверит стенд с настоящим редактором: автопопап, отрисовка подсказок, фокус.
- Сравнение — `tools/compare/typing/compare.py`: текст с `<caret>` и `<selection>` дословно; для навигации — цели перехода; для
  подсказок — текст. Таблица — `prometheus-typing-dumps/table.md`, diff по каждому расхождению — `details.md`. Сырые данные —
  `typing-plugin.json`, `goland/*.json`.
- Площадка для проверки глазами (попапы, цвета, отрисовка инлеев) — `playground/compare/typing_manual.go`, 16 меток `MARK:` с `EXPECT:`.

## Сводка

| Категория | Сценариев | ok | иначе | нет |
|---|---|---|---|---|
| Enter | 9 | 7 | 2 | |
| Complete Statement | 5 | 4 | 1 (у нас лучше) | |
| Автопары | 10 | 9 | 1 | |
| Отступ при вставке и `}` | 6 | 5 | 1 | |
| Форматирование (Ctrl+Alt+L, при сохранении) | 5 | 4 | 1 (GoLand ≠ gofmt, мы = gofmt) | |
| Ctrl+W / Ctrl+Shift+W | 4 | 2 | 2 | |
| Комментарии | 4 | 2 | 2 | |
| Строки (дублировать, переместить) | 4 | 3 | 1 | |
| Optimize Imports | 3 | 3 | | |
| Surround With | 3 | 0 | 3 | |
| Generate (списки) | 3 | 0 | 3 | |
| Навигация | 14 | 10 | 3 | Ctrl+U — GoLand-скрипт упал |
| Подсказки | 10 | 2 | 7 | 1 (Ctrl+Shift+P) |
| **Всего** | **80** | **51** | **27** | **1** (+1 не снято) |

Из 27 «иначе» одна настоящая поломка кода (Enter в строке) и 7 заметных расхождений с GoLand. Остальное — стиль, тексты и списки
меню (раздел «Расхождения»).

## Расхождения

### Ломает код или заметно мешает

| id | Что | GoLand | Плагин |
|---|---|---|---|
| **E05** | Enter внутри строки `"scrape pool reload failed"` | разрывает строку конкатенацией: `"scrape pool " +⏎"reload failed"` | **перевод строки внутри литерала** — код не компилируется |
| **P03** | `"` внутри тега в обратных кавычках `` `yaml:` `` | `` `yaml:"scrape_interval"` `` | **кавычка удваивается**: `` `yaml:""scrape_interval"`" `` |
| **H09** | Ctrl+Shift+P (тип выражения) | `lset["job"] -> []string` | **ничего**: провайдер зарегистрирован под тегом, которого платформа не знает (`expressionTypeProvider` / `lang.expressionTypeProvider` вместо `codeInsight.typeInfo`); тест `GoDocumentationTest` создаёт класс напрямую и этого не видит |
| **E04** | Enter внутри `//`-комментария | новая строка **без** `//` (`typing-assists.txt` #8 подтверждает) | продолжает комментарий `// ` |
| **I01** | вставка в тело функции | вставлено как есть, импорт не трогается | плагин сам добавил `import "errors"`; по отчёту стенда в I01–I02 строка каретки после вставки теряет отступ (у GoLand в I02 так же — совпало) |
| **L04** | Move Statement Up из `if`-блока | оператор поднимается внутри блока | оператор **выходит из блока** и встаёт над `if` |
| **S02**, **S03** | Ctrl+W в строке и в теге | слово → содержимое строки → строка с кавычками → `(…)` → вызов…; в теге: содержимое тега → тег → поле | шаг «содержимое без кавычек» пропущен; в теге **сразу вся `struct {…}`** |
| **H01**, H07 | Ctrl+P / Ctrl+Q: типы из других пакетов | `start time.Time, duration time.Duration` | `start Time, duration Duration` — без имени пакета |

### Иначе, но не хуже

- **C03** Complete Statement на `func f(` без `)`: GoLand оставляет `sync(targets []*Target {` без `)`, плагин дописывает `)` — у нас лучше.
- **F01** Reformat: GoLand сводит `total int, limit int` в `total, limit int` (это не gofmt), плагин даёт ровно gofmt. Так и оставить.
- **K01**, **K02** Ctrl+/: GoLand пишет `//n += 2` у отступа, плагин — `// n += 2` (как пишут руками в Go). Можно оставить. Если
  нужно как в GoLand — опция Code Style «пробел после //».
- **W01** Surround → if: `if <caret>{` у GoLand, `if <caret> {` у нас. **W02**: пункт «(expression)» у GoLand и «(expr)» у нас.
  **W03**: без выделения у нас 7 пунктов (есть `func(){}()`, `go func(){}()`, `defer func(){}()`), у GoLand 4.
- **G01–G03** Alt+Insert: у нас «Copyright», «Constructor...», «Method...», «Getters...», «Setters...»; у GoLand «Constructor», «Getter and
  Setter», «Struct Fields from JSON», «Empty test file». Внутри функции у нас «Test…» и «Test» дважды (платформа и плагин), у GoLand
  «Test for function / file / package». Выровнять названия и убрать дубль.
- **N10** Ctrl+Alt+B на вызове метода интерфейса: GoLand показывает и сам метод интерфейса, и обе реализации; мы — только реализации
  (как в IDEA для Java).
- **N15** Ctrl+Shift+F7 на `return`: GoLand подсвечивает ещё ключевое слово `func`, мы — только точки выхода.
- **N16** Ctrl+F12: у GoLand методы под типом без получателя, поля `имя: тип`, дженерик `pool`. У нас `(Target) Health()`, `mtx sync.RWMutex`,
  `pool[T any]`; константы стоят в порядке файла, у GoLand — после типов.
- **H03** Ctrl+P на дженерике: у нас в начале ещё `[T any, U any]`. Полезно, у GoLand нет.
- **H06–H08** Ctrl+Q: у GoLand первая строка «Package: strings», у нас её нет.
- **H11**, **H12** инлеи: имена параметров совпадают. У нас ещё типы переменных (`: *scrapeLoop`, `: int`) и размер структуры
  (`16 bytes`). Значение константы (`= 1024`) GoLand ставит после имени, мы — в конце строки. У GoLand над объявлениями code vision
  (usages), у нас — в другом месте (не сравнивалось).

## Что чинить (по причинам)

1. **Ввод внутри литералов** (E05, P03): typed handler для `Enter` в интерпретируемой строке должен разрывать литерал (`" +⏎"`), а не
   вставлять перевод строки. Автопара `"` внутри raw-строки (тег) не должна срабатывать: обратные кавычки — уже строка. Обе — поломка
   кода одной клавишей.
2. **Регистрация провайдера типа выражения** (H09): `codeInsight.typeInfo`, а не `expressionTypeProvider`. Нужен тест через
   `ExpressionTypeProvider` EP (`LanguageExpressionTypes.INSTANCE.forLanguage(GoLanguage)`), а не через конструктор класса.
3. **Ctrl+W** (S02, S03): добавить уровни «содержимое строки без кавычек» и «содержимое тега → тег → поле»; не перескакивать к
   объемлющему `struct {}`.
4. **Enter в `//`-комментарии** (E04): не продолжать комментарий (GoLand и gofmt-стиль). Если продолжение нужно — опция, по
   умолчанию выключена.
5. **Вставка** (I01): каретка после вставленного блока должна остаться на отступе блока; автоимпорт при вставке
   (I01) — проверить, включён ли он в GoLand («Optimize imports on the fly» / Paste); у GoLand импорт не добавился.
6. **Move Statement** (L04): не выносить оператор из блока, пока он не первый в блоке (как в GoLand).
7. **Имена пакетов в Ctrl+P / Ctrl+Q** (H01, H07): печатать `time.Duration`, а не `Duration`, для типов чужого пакета.
8. Списки Alt+Insert (G01–G03) и Surround (W02, W03): названия как в GoLand, без дубля «Test».
9. Format on save: у плагина включён по умолчанию, у GoLand выключен (Actions on Save). Решить, оставлять ли: у пользователя GoLand
   файл при сохранении не меняется.

## Как повторить

```sh
GO_HARNESS=1 ./gradlew.bat :test --tests "io.github.golangsupport.compare.GoTypingHarnessTest" --offline --rerun   # плагин, стенд
# GoLand: start-goland.ps1, GOLAND_PID; PG_DIR — открытый проект (по умолчанию площадка goland-playground)
GOLAND_PID=<pid> PG_DIR=<проект> bash tools/ui-robot/goland/analysis/typing_batch.sh
# живая IDEA с плагином: runIdeForUiTests (порт 8083) с открытым проектом
TARGET=plugin GOLAND_PID=<pid песочницы> PG_DIR=<проект> bash tools/ui-robot/goland/analysis/typing_batch.sh
python tools/compare/typing/compare.py build/compare/out/typing.json build/compare/out/typing-goland --details
```
