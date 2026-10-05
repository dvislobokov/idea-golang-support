"""Tests of compare.py over small fixtures: `uvx pytest tools/vet -q`."""

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import compare  # noqa: E402

ROOT = "C:/work/app"

VET = """# example.com/app
# [example.com/app]
{
\t"example.com/app": {
\t\t"printf": [
\t\t\t{
\t\t\t\t"posn": "C:\\\\work\\\\app\\\\main.go:10:2",
\t\t\t\t"message": "fmt.Printf format %d has arg s of wrong type string"
\t\t\t}
\t\t],
\t\t"unreachable": [
\t\t\t{"posn": "C:\\\\work\\\\app\\\\main.go:20:2", "message": "unreachable code"}
\t\t]
\t}
}
# example.com/app/sub
{
\t"example.com/app/sub": {
\t\t"nilfunc": [{"posn": "C:\\\\work\\\\app\\\\sub\\\\s.go:5:6", "message": "comparison of function f == nil is always false"}],
\t\t"asmdecl": [{"posn": "C:\\\\work\\\\app\\\\sub\\\\s.go:7:1", "message": "bad frame"}],
\t\t"copylocks": {"error": "analysis failed"}
\t}
}
"""


def sarif(results, root=ROOT):
    return json.dumps({
        "version": "2.1.0",
        "runs": [{
            "originalUriBaseIds": {"%SRCROOT%": {"uri": "file:///" + root + "/"}},
            "results": [{
                "ruleId": rule,
                "message": {"text": text},
                "locations": [{"physicalLocation": {"artifactLocation": {"uri": uri, "uriBaseId": "%SRCROOT%"},
                                                    "region": {"startLine": line, "startColumn": 3}}}],
            } for rule, uri, line, text in results],
        }],
    })


def test_parse_vet_skips_headers_and_errors():
    found = compare.parse_vet(VET)
    assert [(f.source, f.line) for f in found] == [("printf", 10), ("unreachable", 20), ("nilfunc", 5), ("asmdecl", 7)]
    assert found[0].path == "C:\\work\\app\\main.go"


def test_parse_vet_with_noise_between_objects():
    text = "vet: some warning\n" + VET + "\ntrailing text {not json\n"
    assert len(compare.parse_vet(text)) == 4


def test_split_posn_windows_and_unix():
    assert compare.split_posn("C:\\a\\b.go:3:4") == ("C:\\a\\b.go", 3)
    assert compare.split_posn("/a/b.go:12") == ("/a/b.go", 12)
    assert compare.split_posn("garbage") is None


def test_parse_sarif_rules_engine_prefix_and_root():
    found, root = compare.parse_sarif(sarif([("GoRules", "sub/s.go", 5, "[govet:nilfunc] comparison of function f == nil is always false"),
                                             ("GoPrintFunctions", "main.go", 10, "bad verb")]))
    assert root == ROOT + "/"
    assert [(f.source, f.path, f.line) for f in found] == [("GoRules[govet:nilfunc]", "sub/s.go", 5), ("GoPrintFunctions", "main.go", 10)]


def test_everything_covered():
    ours, root = compare.parse_sarif(sarif([
        ("GoPrintFunctions", "main.go", 10, "x"),
        ("GoUnreachableCode", "main.go", 20, "Unreachable code"),
        ("GoRules", "sub/s.go", 5, "[govet:nilfunc] comparison"),
    ]))
    report = compare.compare(compare.parse_vet(VET), ours, root)
    assert not report.failed
    assert report.vet_only == []
    assert [f.source for f in report.not_covered] == ["asmdecl"]
    assert report.counts["printf"] == (1, 1, 0)
    assert report.counts["asmdecl"] == (1, 0, 0)
    assert "OK:" in compare.render(report)


def test_missing_finding_fails_and_column_is_ignored():
    ours, root = compare.parse_sarif(sarif([
        ("GoPrintFunctions", "main.go", 10, "different text"),
        ("GoUnreachableCode", "main.go", 21, "Unreachable code"),  # another line: no match
        ("GoUnusedVariable", "main.go", 5, "unmapped inspection: not listed"),
    ]))
    report = compare.compare(compare.parse_vet(VET), ours, root)
    assert report.failed
    assert [(f.source, f.path, f.line) for f in report.vet_only] == [("unreachable", "main.go", 20), ("nilfunc", "sub/s.go", 5)]
    assert [(f.source, f.line) for f in report.ours_only] == [("GoUnreachableCode", 21)]
    text = compare.render(report)
    assert "FAIL" in text and "main.go:20  unreachable" in text


def test_wrong_inspection_on_the_line_does_not_count():
    ours, root = compare.parse_sarif(sarif([("GoStructTag", "main.go", 10, "x")]))
    report = compare.compare(compare.parse_vet(VET), ours, root)
    assert ("printf", 10) in [(f.source, f.line) for f in report.vet_only]


def test_allow_list():
    allowed = compare.parse_allow("# exceptions\nmain.go:20 unreachable  dead code after log.Fatal is vet's only\nsub/s.go:5 nilfunc reason\n")
    ours, root = compare.parse_sarif(sarif([("GoPrintFunctions", "main.go", 10, "x")]))
    report = compare.compare(compare.parse_vet(VET), ours, root, allowed)
    assert not report.failed
    assert [f.source for f in report.allowed] == ["unreachable", "nilfunc"]


def test_bad_allow_line():
    try:
        compare.parse_allow("main.go unreachable")
    except ValueError:
        return
    raise AssertionError("expected ValueError")


def test_main_exit_codes(tmp_path):
    vet = tmp_path / "vet.txt"
    vet.write_text(VET, encoding="utf-8")
    ok = tmp_path / "ok.sarif"
    ok.write_text(sarif([("GoPrintFunctions", "main.go", 10, "x"), ("GoUnreachableCode", "main.go", 20, "x"),
                         ("GoRules", "sub/s.go", 5, "[govet:nilfunc] x")]), encoding="utf-8")
    bad = tmp_path / "bad.sarif"
    bad.write_text(sarif([]), encoding="utf-8")
    assert compare.main(["--vet-json", str(vet), "--sarif", str(ok)]) == 0
    assert compare.main(["--vet-json", str(vet), "--sarif", str(bad)]) == 1
    assert compare.main(["--vet-json", str(vet), "--sarif", str(tmp_path / "missing.sarif")]) == 2


def test_mapping_ids_are_known():
    for analyzer, ids in compare.MAPPING.items():
        assert analyzer not in compare.NOT_COVERED
        for i in ids:
            assert i.startswith("Go"), (analyzer, i)


def test_mapping_ids_exist_in_the_plugin():
    repo = Path(__file__).resolve().parents[2]
    xml = "".join(p.read_text(encoding="utf-8") for d in ("go-psi-ide/src/main/resources/META-INF", "src/main/resources/META-INF")
                  for p in (repo / d).glob("*.xml"))
    short_names = set(re.findall(r'shortName="(\w+)"', xml))
    rule_ids = set()
    for p in (repo / "go-psi-ide/src/main/kotlin").rglob("*.kt"):
        rule_ids.update(re.findall(r'id: String get\(\) = "([\w:.-]+)"', p.read_text(encoding="utf-8")))
    for analyzer, ids in compare.MAPPING.items():
        for i in ids:
            m = re.match(r"^GoRules\[(.+)]$", i)
            assert (m.group(1) in rule_ids) if m else (i in short_names), (analyzer, i)
