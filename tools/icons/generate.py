"""Writes the SVG icons of the plugin into src/main/resources/icons. Not a part of the build: run after changing a shape.

    python tools/icons/generate.py

The style and the purposes follow the icons of ../idea-dotnet-support: 16x16, outlined shapes with 1px strokes, one accent colour per
kind of thing. What maps to what:

    dotnet            here           used for
    csharp            go             a Go source file (filled: the one icon seen most)
    csharpType        goNew          the New | Go File dialog
    project           goMod          go.mod: the unit that builds
    solution          goWork         go.work: what holds the units together
    nuget             goPackage      a dependency of a module
    assembly          goBinary       what the toolchain produces: *.exe, *.test, __debug_bin*
    config            goConfig       configuration of the tools: .golangci.yml, .goreleaser.yaml, .air.toml
    msbuild           goGenerated    code written by a generator: *.pb.go, *_gen.go, zz_generated.*
    settingsJson      goTemplate     text/template and html/template files: *.tmpl, *.gotmpl, *.gohtml

Tool window icons are monochrome and come in four colours (classic light / dark, new UI light / dark) and, for the new UI, in 20x20
as well; the mapping for the new UI is src/main/resources/GoIconMappings.json.
"""
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "icons")

GO = "#00ADD8"      # the colour of Go: everything that is Go itself
LIGHT = "#5DC9E2"   # a lighter Go: tests
ORANGE = "#E8A33D"  # as in the dotnet icons: generated and templated text
GREY = "#7F8B91"    # as in the dotnet icons: neutral things, tools
GREEN = "#4B9E5F"   # as in the dotnet icons: "new", "passed"

# the word "go" in white strokes, for a filled background
GO_WHITE = """
  <path d="M7.1 6.6A2 2 0 1 0 7.3 9.2V8.1H5.9" fill="none" stroke="#FFFFFF" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="11" cy="8" r="1.9" fill="none" stroke="#FFFFFF" stroke-width="1.2"/>"""


def go_outline(colour, width="1.2"):
    return f"""
  <path d="M7.1 6.6A2 2 0 1 0 7.3 9.2V8.1H5.9" fill="none" stroke="{colour}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="11" cy="8" r="1.9" fill="none" stroke="{colour}" stroke-width="{width}"/>"""


COLOURED = {
    # files
    "go": f"""
  <rect x="1" y="3" width="14" height="10" rx="3" fill="{GO}"/>{GO_WHITE}""",
    "goTest": f"""
  <rect x="1" y="3" width="14" height="10" rx="3" fill="{LIGHT}"/>{GO_WHITE}
  <path d="M9.5 12.2l2 2 3.3-3.6" fill="none" stroke="#59A869" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>""",
    "goGenerated": f"""
  <rect x="1.5" y="1.5" width="13" height="13" rx="2" fill="none" stroke="{ORANGE}"/>
  <path d="M6.2 5.5 3.7 8l2.5 2.5M9.8 5.5 12.3 8l-2.5 2.5" fill="none" stroke="{ORANGE}" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>""",
    "goTemplate": f"""
  <path d="M6 2.5c-1.6 0-1.7.9-1.7 2v1.6C4.3 7.2 3.8 8 2.6 8c1.2 0 1.7.8 1.7 1.9v1.6c0 1.1.1 2 1.7 2M10 2.5c1.6 0 1.7.9 1.7 2v1.6c0 1.1.5 1.9 1.7 1.9-1.2 0-1.7.8-1.7 1.9v1.6c0 1.1-.1 2-1.7 2" fill="none" stroke="{ORANGE}" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="8" cy="8" r="1.4" fill="{GO}"/>""",
    "goConfig": f"""
  <circle cx="8" cy="8" r="5" fill="none" stroke="{GREY}" stroke-width="2.2" stroke-dasharray="1.9635 1.9635"/>
  <circle cx="8" cy="8" r="3.2" fill="none" stroke="{GREY}" stroke-width="1.8"/>
  <circle cx="8" cy="8" r="1.3" fill="{GO}"/>""",
    "goBinary": f"""
  <rect x="3.5" y="3.5" width="9" height="9" rx="1" fill="none" stroke="{GREY}"/>
  <rect x="6" y="6" width="4" height="4" fill="{GO}"/>
  <path d="M6 1.5v2M10 1.5v2M6 12.5v2M10 12.5v2M1.5 6h2M1.5 10h2M12.5 6h2M12.5 10h2" fill="none" stroke="{GREY}"/>""",
    # modules
    "goMod": f"""
  <rect x="1.5" y="1.5" width="13" height="13" rx="2" fill="none" stroke="{GO}"/>{go_outline(GO, "1.1")}""",
    "goWork": f"""
  <rect x="1.5" y="2.5" width="13" height="11" rx="1.5" fill="none" stroke="{GO}"/>
  <rect x="1.5" y="2.5" width="13" height="3" rx="1.5" fill="{GO}"/>
  <rect x="3.5" y="7.5" width="3.5" height="3.5" rx="0.8" fill="none" stroke="{GO}"/>
  <rect x="9" y="7.5" width="3.5" height="3.5" rx="0.8" fill="none" stroke="{GO}"/>""",
    "goSum": f"""
  <path d="M8 1.5l5.5 2v4.2c0 3.2-2.3 5.6-5.5 6.8-3.2-1.2-5.5-3.6-5.5-6.8V3.5z" fill="none" stroke="{GO}" stroke-linejoin="round"/>
  <path d="M5.4 8l1.9 1.9 3.4-3.7" fill="none" stroke="{GO}" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>""",
    "goPackage": f"""
  <circle cx="3.2" cy="3.2" r="1.7" fill="{GO}"/>
  <rect x="4.5" y="4.5" width="10" height="10" rx="3" fill="{GO}"/>
  <circle cx="7.8" cy="7.8" r="1.3" fill="#FFFFFF"/>
  <circle cx="10.9" cy="11" r="2" fill="#FFFFFF"/>""",
    "goPackageIndirect": f"""
  <circle cx="3.2" cy="3.2" r="1.7" fill="{GREY}"/>
  <rect x="4.5" y="4.5" width="10" height="10" rx="3" fill="none" stroke="{GREY}" stroke-width="1.2"/>
  <circle cx="7.8" cy="7.8" r="1.1" fill="{GREY}"/>
  <circle cx="10.9" cy="11" r="1.7" fill="{GREY}"/>""",
    "goVendor": f"""
  <path d="M1.5 4.5a1 1 0 0 1 1-1h3.3l1.4 1.5h6.3a1 1 0 0 1 1 1v6.5a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1z" fill="none" stroke="{GREY}"/>
  <circle cx="6.3" cy="7.6" r="0.9" fill="{GO}"/>
  <rect x="7" y="8.2" width="4.6" height="4.2" rx="1.3" fill="{GO}"/>""",
    # run and tests
    "goRun": f"""
  <rect x="1" y="3" width="14" height="10" rx="3" fill="{GO}"/>
  <path d="M6.2 5.4v5.2L10.8 8z" fill="#FFFFFF"/>""",
    "goNew": go_outline(GREEN, "1.5"),
    "goBenchmark": f"""
  <circle cx="8" cy="9" r="5.5" fill="none" stroke="{GO}" stroke-width="1.3"/>
  <path d="M8 9l2.6-3M6.5 1.7h3" fill="none" stroke="{GO}" stroke-width="1.3" stroke-linecap="round"/>""",
    "goFuzz": f"""
  <path d="M9.2 1.5L3.5 9h3.8l-.9 5.5L12.5 7H8.6z" fill="none" stroke="{GO}" stroke-width="1.2" stroke-linejoin="round"/>""",
    # the language server: the Go badge with a plug, for the status bar widget and the menu
    "gopls": f"""
  <rect x="1" y="4" width="10" height="8" rx="2.5" fill="{GO}"/>
  <path d="M4.6 7.1A1.4 1.4 0 1 0 4.7 8.9V8.2H3.8" fill="none" stroke="white" stroke-width="1.1" stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="7.6" cy="8" r="1.3" fill="none" stroke="white" stroke-width="1.1"/>
  <path d="M11 8H12.5M13.5 6V10M12.5 6.5H14.5M12.5 9.5H14.5" fill="none" stroke="{GO}" stroke-width="1.2" stroke-linecap="round"/>""",
    "goExample": f"""
  <rect x="2" y="2.5" width="12" height="11" rx="1.5" fill="none" stroke="{GO}"/>
  <path d="M4.5 6h7M4.5 8.5h7M4.5 11h4" fill="none" stroke="{GO}" stroke-linecap="round"/>""",
}

# name -> body of a 16x16 icon; {c} is the stroke / fill colour
TOOL_WINDOWS = {
    "goTestsToolWindow": """
  <path d="M6.5 1.5V6L2.7 12.6C2.2 13.4 2.8 14.5 3.8 14.5H12.2C13.2 14.5 13.8 13.4 13.3 12.6L9.5 6V1.5" stroke="{c}" stroke-linejoin="round"/>
  <path d="M5 1.5H11" stroke="{c}" stroke-linecap="round"/>
  <path d="M4.6 9.5H11.4" stroke="{c}"/>
  <circle cx="7" cy="12" r="0.9" fill="{c}"/>
  <circle cx="9.6" cy="11.2" r="0.6" fill="{c}"/>""",
    "goplsToolWindow": """
  <rect x="1.5" y="3.5" width="9" height="9" rx="2" stroke="{c}"/>
  <path d="M4 7.5H6M4 9.5H7.5" stroke="{c}" stroke-linecap="round"/>
  <path d="M10.5 8H12M13 6V10M12 6.5H14.5M12 9.5H14.5" stroke="{c}" stroke-linecap="round"/>""",
    "goMonitorToolWindow": """
  <rect x="1.5" y="2.5" width="13" height="11" rx="2" stroke="{c}"/>
  <path d="M3.5 9H5.5L7 5.5L9 11L10.5 8H12.5" stroke="{c}" stroke-linecap="round" stroke-linejoin="round"/>""",
}


def svg(size, body, fill_none=False):
    attrs = ' fill="none"' if fill_none else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 16 16"{attrs}>{body}\n</svg>\n'


def write(relative, text):
    path = os.path.join(OUT, relative)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


# what is not generated any more must not stay around
for root, _, files in os.walk(OUT):
    for name in files:
        if name.endswith(".svg"):
            os.remove(os.path.join(root, name))

for name, body in COLOURED.items():
    write(f"{name}.svg", svg(16, body))

for name, body in TOOL_WINDOWS.items():
    for suffix, colour in (("", "#6E6E6E"), ("_dark", "#AFB1B3")):
        write(f"{name}{suffix}.svg", svg(16, body.format(c=colour), True))
    for suffix, colour in (("", "#6C707E"), ("_dark", "#CED0D6")):
        write(f"expui/{name}{suffix}.svg", svg(16, body.format(c=colour), True))
        write(f"expui/{name}@20x20{suffix}.svg", svg(20, body.format(c=colour), True))

print(len(COLOURED), "icons and", len(TOOL_WINDOWS), "tool window icons written to", os.path.normpath(OUT))
