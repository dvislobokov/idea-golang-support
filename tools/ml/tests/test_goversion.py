import pytest

from gopsi_ml.corpus import goversion


def test_escape_roundtrip():
    assert goversion.escape("github.com/BurntSushi/toml") == "github.com/!burnt!sushi/toml"
    assert goversion.unescape("github.com/!burnt!sushi/toml") == "github.com/BurntSushi/toml"


@pytest.mark.parametrize("bad", ["a!", "a!B", "!1"])
def test_unescape_rejects_malformed(bad):
    with pytest.raises(ValueError):
        goversion.unescape(bad)


def test_sort_key_follows_semver_precedence():
    ordered = [
        "garbage",
        "v0.0.0-20240101000000-abcdefabcdef",
        "v1.0.0-alpha",
        "v1.0.0-alpha.1",
        "v1.0.0-alpha.beta",
        "v1.0.0-beta.2",
        "v1.0.0-beta.11",
        "v1.0.0-rc.1",
        "v1.0.0",
        "v1.2.0",
        "v1.10.0",
        "v2.0.0+incompatible",
    ]
    assert sorted(reversed(ordered), key=goversion.sort_key) == ordered


def test_build_metadata_is_ignored():
    assert goversion.sort_key("v2.0.0+incompatible") == goversion.sort_key("v2.0.0")
