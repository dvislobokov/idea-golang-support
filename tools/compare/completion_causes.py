"""docs/compare part 2: why the plugin's right answer is not first. For every attempt where the plugin has the identifier at rank 2..5,
the items above it are classified (duplicate, keyword/builtin where it is not wanted, a word that is only a prefix of the identifier,
a package name in a type place, a longer name starting with the identifier, other); the attempt is credited to the cause that would
lift the identifier to the top if fixed (all items above it fall into that cause). Prints the table and examples.

    python tools/compare/completion_causes.py --plugin a.json b.json ... --sites sites.json
"""
import argparse, collections, json

KEYWORDS = set("break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var".split())
BUILTINS = set("append cap clear close complex copy delete imag len make max min new panic print println real recover any bool byte comparable complex128 complex64 error float32 float64 int int16 int32 int64 int8 rune string uint uint16 uint32 uint64 uint8 uintptr true false iota nil".split())

ap = argparse.ArgumentParser()
ap.add_argument("--plugin", nargs="+", required=True)
ap.add_argument("--packages", default="")
a = ap.parse_args()
attempts = []
for p in a.plugin:
    attempts += json.load(open(p, encoding="utf-8"))["attempts"]
attempts = [x for x in attempts if x["prefix"] >= 0]
n = len(attempts)
causes = collections.Counter()
examples = collections.defaultdict(list)
for x in attempts:
    if not 2 <= x["rank"] <= 5:
        continue
    above = x["top"][: x["rank"] - 1]
    ident = x["ident"]
    kinds = set()
    seen = set()
    for i, item in enumerate(above):
        if item in seen or item in x["top"][:i]:
            kinds.add("дубликат")
        elif item in KEYWORDS and x["kind"] in ("struct-literal-field", "case", "expression", "after-dot") or (item in KEYWORDS and x["kind"] == "type" and item not in ("chan", "map", "func", "interface", "struct")):
            kinds.add("ключевое слово не к месту")
        elif item in KEYWORDS or item in BUILTINS:
            kinds.add("ключевое слово / встроенное выше имени")
        elif ident.startswith(item) and item != ident:
            kinds.add("слово-префикс (не символ)")
        elif item.startswith(ident):
            kinds.add("длинное имя с тем же началом выше точного")
        elif x["kind"] == "type" and item.islower() and "." not in item:
            kinds.add("имя пакета выше типа")
        else:
            kinds.add("другое ранжирование")
        seen.add(item)
    cause = next(iter(kinds)) if len(kinds) == 1 else "несколько причин: " + " + ".join(sorted(kinds))
    causes[cause] += 1
    if len(examples[cause]) < 4:
        examples[cause].append(f"{x['kind']}/{x['prefix']} `{ident}` → ранг {x['rank']}, выше: {', '.join(above)}")
print(f"Попыток: {n}; на 2–5 месте: {sum(causes.values())}\n")
print("| Причина | Попыток | + top-1, п.п. | Примеры |")
print("|---|---|---|---|")
for c, k in causes.most_common():
    ex = "<br>".join(e.replace("|", "\|") for e in examples[c][:3])
    print(f"| {c} | {k} | +{100.0 * k / n:.1f} | {ex} |")
