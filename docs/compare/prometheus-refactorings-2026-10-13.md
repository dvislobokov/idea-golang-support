# Рефакторинги: плагин, GoLand и gopls (prometheus, 2026-10-13)

Часть 3 сравнения. Проект — prometheus v3.15.0. Сценарии в основном в `notifier/`, есть `util/`, `model/exemplar`. 36 сценариев —
`tools/compare/refactor/scenarios.json`: якорь, параметры и ожидаемый результат словами.

**Как снято.**
- **Плагин** — стенд `GoRefactorHarnessTest` на своей копии клона. Обработчики и процессоры плагина вызываются без диалогов, как в
  тестах go-psi-ide: Rename — через `RenameProcessor` с автоматическими переименователями, включёнными по умолчанию; Generate — со всеми
  полями. Если плагин сообщил о конфликте, сценарий прогонялся второй раз с игнором конфликтов, как после «Continue» (`forced`).
  Каждый изменённый файл, diff и `go build ./...` + `go vet` затронутых пакетов — `prometheus-refactorings-dumps/plugin/<id>/`, `refactor-build.json`.
- **gopls 0.23.0** — `textDocument/rename` и code actions (`refactor.extract.*`, `refactor.inline.*`, `refactor.rewrite.*`) на тех же
  местах. Результат применён и собран так же — `dumps/gopls/<id>/`, `refactor-gopls-build.json`.
- **GoLand 2026.2.3** — робот на копии клона (`tools/ui-robot/goland/analysis/refactor_probe.js`, `tools/compare/refactor/goland_run.sh`).
  У всех 36 мест снят список Alt+Enter и доступные пункты Refactor This. Без диалогов выполнены Rename, Safe Delete и intentions
  (Convert); после каждого — diff и сборка. Extract / Inline / Change Signature / Move / Generate в GoLand открывают диалог или
  in-place-шаблон, их эталон — ручные шаги `tools/compare/refactor/goland-steps.json`, вживую ещё не снят. Сырые данные —
  `dumps/goland/` (копия `build/compare/out/refactor-goland`).
- **Живой IDEA с плагином** — тот же `refactor_probe.js` в песочнице (`TARGET=plugin`). Он снимает тот же список Alt+Enter / Refactor
  This и выполняет те же Rename / Safe Delete / intentions, что в GoLand. Этот прогон ещё не сделан. Цифры плагина ниже — со стенда.

## Сводка

| | Плагин | gopls | GoLand (что снято) |
|---|---|---|---|
| Сценариев, где есть операция | 34 из 36 (2 не предложены) | 17 из 36 | Refactor This / Alt+Enter — все 36; выполнено скриптом 13 |
| Применено без конфликта | 23 | 15 | 8 (rename ×4, convert ×2, safe delete ×2 — диалог конфликтов) |
| **Ломает сборку без предупреждения** | **1** (rename-conflict) | 1 (inline-variable: объявление остаётся) | 1 (rename-conflict, по скрипту — перепроверить вживую) |
| Ломает сборку после «Continue» на конфликте | 6 | — | не снималось (в GoLand это «Delete anyway» / «Continue») |
| `go vet` не проходит после генерации | 2 (getter копирует мьютекс, `String()` печатает func через `%v`) | — | не снималось |
| Результат не по gofmt | 2 (rename-field: выравнивание полей, move-func-file) | 2 | GoLand переформатирует: в rename-field выравнивание поправлено |
| Отказ там, где gopls справился | 3 (extract-function с `return nil, err`, inline многострочной функции и метода) | — | — |

Плагин против эталона там, где эталон есть (GoLand по скрипту либо gopls): **совпадает 13, другой результат 6 (из них 3 в нашу
пользу), ломает сборку 1, нет в этом месте 4**. У остальных 12 сравнивать не с чем: gopls такого не умеет, GoLand — в ручных шагах.

## Таблица

Вердикт — против GoLand, где он снят, иначе против gopls; «—» — сравнивать не с чем, пока не сняты ручные шаги GoLand.

| id | Рефакторинг, место | Плагин: результат / сборка | gopls | GoLand | Вердикт |
|---|---|---|---|---|---|
| rename-local | Rename локальной, `notifier/alert.go:73` | применено / ok | то же | то же (3 места) | совпадает |
| rename-field | Rename поля `GeneratorURL` → `SourceURL` | применено, **json-тег переименован молча** / ok; выравнивание литералов не по gofmt | без тега / ok | спрашивает «Rename the tag `json:"generatorURL"` as well?» (Yes / No); литералы выровнены | **другой результат** |
| rename-iface-method | Rename метода интерфейса с реализациями | применено (реализация и мок в тесте) / ok | то же | по скрипту реализации не переименованы — вживую GoLand задаёт вопрос; перепроверить | совпадает с gopls |
| rename-type | Rename типа `sendLoop` | применено / ok | то же | то же (4 файла) | совпадает |
| rename-package | Rename пакета `kahansum` → `kahan` | каталог перенесён, 4 импортёра / ok (vet — фон клона) | то же | по скрипту каталог не перенесён, тест-файл пакета не переименован — вживую диалог; перепроверить | совпадает с gopls |
| rename-file | Rename файла `util.go` → `openapi.go` | применено, `util_test.go` следом / ok | не умеет | открывает диалог Rename (переименовать тест-файл?) | совпадает по смыслу |
| **rename-conflict** | Rename `lb` → `a`, где `a` занято во внутренней области | **применено без конфликта / сборка сломана** (`a.Reset undefined`, `declared and not used`) | **отказ**: «would cause this reference to become shadowed» | по скрипту — тоже применено и сломано; перепроверить Shift+F6 вживую | **ломает сборку** |
| extract-variable | `s.opts.QueueCapacity`, 2 вхождения | применено / ok | то же (variable-all) | — | совпадает с gopls |
| extract-constant | `"[resolved]"` | константа пакета / ok | локальная константа | — | другой результат (наш ближе к GoLand по описанию) |
| extract-function-multi | 2 результата | метод, 2 результата / ok | то же | — | совпадает с gopls |
| **extract-function-err** | выборка с `return nil, err` | **отказ**: «Not every path of the selection ends in 'return'» | `client, result, err := newFunction(cfg)` / ok | — | **нет в этом месте** |
| extract-method | операторы с получателем | применено / ok | то же | — | совпадает |
| introduce-parameter | строка → параметр | применено, вызовы получили литерал / ok | нет | — | — |
| inline-variable | `addr` используется раз | применено / ok | подставлено, **объявление осталось — сборка сломана** | — | плагин лучше gopls |
| **inline-function-all** | метод `Resolved()` с объявления | **отказ**: «inlining every call of a method is not supported» | на вызове / ok | Refactor This: «Inline Function/Method» | **нет в этом месте** |
| **inline-function-call** | функция из 2 операторов | **отказ**: «only one-statement bodies» | IIFE `func() string {…}()` / ok (не по gofmt) | Refactor This: «Inline Function/Method» | **нет в этом месте** |
| inline-constant | `pathLabel`, 2 использования | применено / ok | нет | Refactor This: «Inline…» | — |
| cs-add-param | Change Signature: + параметр | применено / ok | нет | доступно | — |
| cs-remove-param | удалить используемый параметр | конфликт «Parameter c is used in the body»; после Continue сборка сломана | removeUnusedParam не предложен (верно) | доступно | конфликт верный |
| cs-reorder | поменять местами 2 параметра | конфликт про побочные эффекты (по разу на каждый вызов), после Continue / ok; многострочный список параметров сжат в строку | то же (тоже сжат) | доступно | совпадает с gopls |
| cs-results | + результат | конфликт «9 call sites use the results … not rewritten»; после Continue сборка сломана | нет | доступно | — |
| move-func-file | функция в другой файл пакета | применено / ok (файл не по gofmt) | нет | Refactor This: «Move…» | — |
| move-type-newfile | тип с методом в новый файл | применено (тип + метод) / ok | только тип / ok | — | другой результат (наш полнее) |
| move-func-package | функция в новый пакет | конфликт «unexported, used from notifier»; после Continue **имя не экспортировано, вызовы не переписаны** | нет | — | — |
| move-type-package | тип в новый пакет | конфликты (цикл импортов, неэкспортированные) — верные | нет | — | — |
| safe-delete-test-only | функция, нужная только тесту | конфликт (тест + комментарий); после Continue **остаётся лишний импорт `strings`** | нет | диалог «Conflicts Detected: Usage of function SanitizeFullLabelName that is not safe to delete» (2 конфликта) | конфликт совпадает |
| safe-delete-used | используемая функция | конфликт; после Continue **лишний импорт `fmt`** | нет | «Conflicts Detected: Usage of function postPath…» | конфликт совпадает |
| implement-interface | Implement Interface для `sendLoop` | `url() *url.URL` + импорт / ok | нет | Alt+Enter: «Implement interface…» | — |
| generate-constructor | конструктор `Alert` | `NewAlert(...)` / ok | нет | Alt+Enter: «Generate constructor» | — |
| generate-getters-setters | getter/setter, 9 полей | применено / **vet: getter копирует `sync.RWMutex`** | нет | — | — |
| generate-string | `String()` для `Options` | применено / **vet: `%v` по полю-функции** | нет | — | — |
| introduce-field | выражение → поле | применено / ok | нет | — | — |
| **convert-if-switch** | цепочка `if a.Ts < b.Ts … else if …` | **не предложено** (только `==` по одной переменной) | нет | «Replace 'if' with 'switch'» → `switch { case …}` / ok | **нет в этом месте** |
| convert-switch-if | `switch s.cfg.APIVersion` → if | «Convert 'switch' to 'if'» / ok | нет | не предлагает | у нас есть, у GoLand нет |
| convert-var-to-short | `var dropped int` (без значения) | не предложено | нет | не предлагает | совпадает |
| convert-short-to-var | `lb := …` → var | `var lb *labels.Builder = …` / ok | нет | «Convert to var declaration» / ok | совпадает |

## Alt+Enter в тех же местах

Полные списки — `dumps/refactor.json` (`intentions`) и `dumps/goland/<id>.json`.

- **Есть у GoLand, нет у нас:** «Generate constructor» и «Generate method» на типе (у нас — только Alt+Insert), «Replace 'if' with 'switch'»
  и «Invert if» на цепочке `if`, «Merge declaration up», «Add parens to declaration», «Add comment» на экспортированном объявлении
  без комментария, «Generate struct fields from JSON» / «Generate type from JSON», «Export» на методе интерфейса.
- **Есть у нас, нет у GoLand:** «Generate test» — почти в каждом месте; GoLand держит его в Alt+Insert, в Alt+Enter это шум.
  Ещё «Copy JSON sample to clipboard», «Fill switch», «Go to Implementations» / «Go to Interfaces» (навигация в Alt+Enter) и
  «Convert 'switch' to 'if'».
- **Другие тексты для того же действия:** «Convert to 'var' declaration» против «Convert to var declaration», «Convert to raw string
  literal» против «Convert double-quoted string to raw string», «Flip '+' (changes semantics)» против «Flip '+'», «Implement interface...»
  (три точки) против «Implement interface…» (многоточие).

## Что чинить (сначала то, что ломает сборку)

1. ✅ **Сделано в 0.2.227** (проверено в IDE: 5 конфликтов на `lb → a`). **Rename не видит затенения** (rename-conflict). Переименование внешней переменной в имя, занятое во внутренней области, проходит
   молча, и код перестаёт собираться: использования `lb` внутри цикла начинают ссылаться на внутреннюю `a`. Нужен конфликт, как у gopls:
   для каждой ссылки на переименуемый объект проверить, что новое имя в её области не разрешается в другой объект, и что новое
   объявление не перекрывает чужие ссылки. Пример для регрессионного теста — `notifier/alert.go:72`, `lb` → `a`. GoLand по скрипту
   ведёт себя так же; если подтвердится вживую, это место, где можно обогнать GoLand.
2. **Safe Delete после «Continue» оставляет неиспользуемые импорты** (`strings`, `fmt`), и сборка ломается на этом. Удаление объявления
   должно чистить импорты, ставшие лишними (как Optimize Imports по затронутым файлам).
3. **Move в другой пакет после «Continue» не экспортирует имя и не переписывает вызовы** (`openapi.labelsToOpenAPILabelSet` не
   определён). Либо предлагать «Export» как часть Move (GoLand при переносе неэкспортированного имени переименовывает его в
   экспортированное), либо не давать Continue.
4. **Генераторы дают код, который не проходит `go vet`:** getter поля `sync.Mutex` / `sync.RWMutex` копирует замок (такие поля пропускать
   или возвращать указатель); `String()` форматирует поле-функцию через `%v` (пропускать func-поля).
5. **Rename поля молча переименовывает json-тег.** Это меняет формат данных на проводе. GoLand спрашивает; у нас галочка «struct tags»
   включена по умолчанию, а в стенде диалога нет. Сделать по умолчанию «спросить» или выключить.
6. **Результат рефакторинга не по gofmt** (rename-field: выравнивание `ключ: значение` и полей структуры после смены длины имени;
   move-func-file). Переформатировать затронутые места, как GoLand.
7. **Отказы там, где gopls справляется:** Extract Function с `return nil, err` внутри (gopls возвращает `(…, result, err)` и проверяет
   на месте вызова), Inline Function для тела из нескольких операторов и для всех вызовов метода с объявления, Convert if → switch для
   цепочки сравнений (у GoLand есть «Replace 'if' with 'switch'» с `switch { case … }`).
8. **Alt+Enter:** добавить «Generate constructor» / «Generate method» на типе, убрать «Generate test» из Alt+Enter (оставить в Alt+Insert),
   выровнять тексты с GoLand (п. «Другие тексты»).
9. Change Signature, reorder: конфликт «side effects» выводится по разу на каждый вызов — схлопнуть; многострочный список параметров
   сжимается в одну строку (gopls так же, GoLand — проверить вживую).

## Что ещё не снято

- GoLand вживую: Extract / Inline / Change Signature / Move / Generate / Introduce (шаги — `tools/compare/refactor/goland-steps.json`).
  Rename метода интерфейса, пакета и rename-conflict — перепроверить Shift+F6 вживую: скрипт обходит in-place-переименование и его
  вопросы, поэтому неполный результат GoLand там может быть артефактом скрипта.
- Плагин в живой IDEA (`refactor_probe.js` с `TARGET=plugin`).

## Как повторить

```sh
# плагин (стенд) — на своей копии клона: move / rename file / rename package пишут на диск
git clone --no-hardlinks build/compare/prometheus build/compare/prometheus-stand
GO_HARNESS=1 GO_HARNESS_ROOT=build/compare/prometheus-stand ./gradlew.bat :test --tests "*GoRefactorHarnessTest*" --offline --rerun
python tools/compare/refactor/gopls_refactor.py --root build/compare/prometheus-stand --out build/compare/out
git clone --no-hardlinks build/compare/prometheus build/compare/prometheus-refactor
python tools/compare/refactor/apply_and_build.py --clone build/compare/prometheus-refactor --out build/compare/out
python tools/compare/refactor/compare.py --out build/compare/out
# GoLand (копия клона открыта в GoLand) или живая IDEA с плагином (TARGET=plugin, копия открыта в песочнице):
GOLAND_PID=<pid> CLONE=build/compare/prometheus-goland bash tools/compare/refactor/goland_run.sh [id…]
```
