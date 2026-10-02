import pytest

from gopsi_ml.corpus import licenses

MIT = """MIT License

Copyright (c) 2020 Someone

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal"""

BSD3 = """Copyright (c) 2009 The Go Authors. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
   * Neither the name of Google LLC nor the names of its
contributors may be used to endorse or promote products"""

APACHE = """                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/"""

MPL = """Mozilla Public License Version 2.0
1.12. "Secondary License" means either the GNU General Public License, Version 2.0, the GNU
Lesser General Public License, Version 2.1, the GNU Affero General Public License, Version 3.0"""

GPL3 = """GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007
13. Use with the GNU Affero General Public License."""

POSTGRES = """Permission to use, copy, modify, and distribute this software and its
documentation for any purpose, without fee, and without a written agreement is hereby granted"""

GPL_AND_MIT = "Dual licensed: GNU General Public License v3 or " + MIT


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        (MIT, "MIT"),
        (BSD3, "BSD-3-Clause"),
        (APACHE, "Apache-2.0"),
        (GPL_AND_MIT, "GPL"),
        (MPL, "MPL-2.0"),
        (GPL3, "GPL"),
        (POSTGRES, "PostgreSQL"),
        ("whatever", None),
    ],
)
def test_classify_text(text, expected):
    assert licenses.classify_text(text) == expected


def test_module_needs_every_license_allowed(tmp_path):
    (tmp_path / "LICENSE").write_text(MIT)
    assert licenses.ids_allowed(licenses.classify_module(tmp_path))
    (tmp_path / "COPYING.txt").write_text("custom terms")
    assert not licenses.ids_allowed(licenses.classify_module(tmp_path))


def test_module_without_license_is_not_allowed(tmp_path):
    assert not licenses.ids_allowed(licenses.classify_module(tmp_path))


@pytest.mark.parametrize(
    ("expr", "allowed"),
    [
        ("MIT", True),
        ("Apache-2.0 OR GPL-2.0-only", True),
        ("MIT AND GPL-3.0-only", False),
        ("(MIT OR GPL-3.0-only) AND BSD-3-Clause", True),
        ("Apache-2.0 WITH LLVM-exception", True),
        ("Apache-2.0+", True),
        ("LicenseRef-scancode-unknown", False),
        ("non-standard", False),
        ("MIT OR", False),
        ("", False),
    ],
)
def test_spdx_allowed(expr, allowed):
    assert licenses.spdx_allowed(expr) is allowed


def test_spdx_list_requires_all():
    assert licenses.spdx_list_allowed(["MIT", "BSD-3-Clause"])
    assert not licenses.spdx_list_allowed(["MIT", "GPL-3.0-only"])
    assert not licenses.spdx_list_allowed([])
