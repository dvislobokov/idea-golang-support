"""Writes the SVG icons of the plugin into src/main/resources. Not a part of the build: run after changing a shape.

    python tools/icons/generate.py                      # the icons
    python tools/icons/generate.py --preview sheet.html # and a sheet to look at them: 16, 32 and 80px on both themes

The language is the one of the new UI: 16x16, 1px strokes over a tinted fill, a light and a dark variant of every icon. Three families:

    the glyph      the word "go" of two equal bowls; what is under the "o" is the slot of a modifier
                   go (a source file), goTest (check), goGenerated (grey, bolt), goRun (triangle), goNew (plus), gopls (plug)
    the hexagon    a module
                   goMod (cube), goWork (three of them), goSum (check), goPackage (a dependency: the cube with a solid lid),
                   goPackageIndirect (grey), goVendor (in a folder)
    the rest       goTemplate ({{.}}), goConfig (sliders), goBinary (bits), goBenchmark (stopwatch), goFuzz (dice), goExample (// Output)

Colours are the ones of Go (its cyan, its fuchsia for fuzzing) and of the platform (green for run and tests, amber, grey).

Tool window icons are monochrome and come in four colours (classic light / dark, new UI light / dark); for the new UI there is a
drawing of 20x20 as well, its own one: a 16px drawing stretched to 20px has strokes of 1.25px. The mapping for the new UI is
src/main/resources/GoIconMappings.json.
"""
import os
import sys

RESOURCES = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources")

# the stroke and the tinted fill of every colour
LIGHT = dict(c="#007D9C", cf="#E1F5FA", g="#208A3C", gf="#F2FCF3", a="#D68A00", af="#FFF6DE", f="#CE3262", ff="#FDECF2", n="#6C707E", nf="#EBECF0")
DARK = dict(c="#4FC3E0", cf="#15363F", g="#5FAD65", gf="#253627", a="#F2C55C", af="#3D3223", f="#EB6A93", ff="#42212C", n="#CED0D6", nf="#43454A")


def glyph(stroke="{c}", fill="{cf}", width="1.4"):
    """The bowls end 2px above the slot: a mark right under the "o" reads as the tail of a second "g"."""
    return f"""
  <circle cx="4.25" cy="5.75" r="2.75" fill="{fill}" stroke="{stroke}" stroke-width="{width}"/>
  <path d="M7 3v6.5a2.75 2.75 0 0 1-4.52 2.1" fill="none" stroke="{stroke}" stroke-width="{width}" stroke-linecap="round"/>
  <circle cx="11.75" cy="5.75" r="2.75" fill="{fill}" stroke="{stroke}" stroke-width="{width}"/>"""


def plug(colour):
    return f"""
  <path d="M11.5 10.75v1.75M13.5 10.75v1.75" fill="none" stroke="{colour}" stroke-width="1.2"/>
  <path d="M10.25 12.5h4.5v.6a2.25 2.25 0 0 1-4.5 0z" fill="{colour}"/>"""


def hexagon(cx, cy, hw, r):
    """Not a regular one: the half of the width is given apart, for the vertical sides to lie on the pixel grid."""
    return f"M{cx} {cy - r}L{cx + hw} {cy - r / 2}V{cy + r / 2}L{cx} {cy + r}L{cx - hw} {cy + r / 2}V{cy - r / 2}Z"


def brace(spine, direction):
    """A curly brace of 11px; direction 1 is the opening one."""
    d = direction
    return (f"M{spine + 1.5 * d} 2.5c{-1 * d} 0 {-1.5 * d} .6 {-1.5 * d} 1.6v2.3c0 .9 {-.4 * d} 1.6 {-1 * d} 1.6 "
            f"{.6 * d} 0 {1 * d} .7 {1 * d} 1.6v2.3c0 1 {.5 * d} 1.6 {1.5 * d} 1.6")


HEX = hexagon(8, 8, 5.5, 6.5)
CUBE_EDGES = "M8 8V14.5M8 8L2.5 4.75M8 8L13.5 4.75"

# name -> body of a 16x16 icon; {c}, {cf}, ... are the colours of the theme
COLOURED = {
    # Go sources
    "go": glyph(),
    "goTest": glyph() + """
  <path d="M9.8 13.2l1.8 1.8 3.6-4" fill="none" stroke="{g}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>""",
    "goGenerated": glyph("{n}", "{nf}") + """
  <path d="M13.1 10.6l-2.5 3h1.9l-.6 2.2 2.8-3.2h-2z" fill="{a}" stroke="{a}" stroke-width=".6" stroke-linejoin="round"/>""",
    "goRun": glyph() + """
  <path d="M10.8 11v4.4l3.9-2.2z" fill="{g}" stroke="{g}" stroke-linejoin="round"/>""",
    "goNew": glyph() + """
  <path d="M12.5 11v4.5M10.25 13.25h4.5" fill="none" stroke="{g}" stroke-width="1.4" stroke-linecap="round"/>""",
    "gopls": glyph() + plug("{n}"),
    # modules
    "goMod": f"""
  <path d="{HEX}" fill="{{cf}}" stroke="{{c}}" stroke-linejoin="round"/>
  <path d="{CUBE_EDGES}" fill="none" stroke="{{c}}" stroke-linejoin="round"/>""",
    "goWork": f"""
  <path d="{hexagon(4, 4.5, 3, 3.5)}" fill="{{cf}}" stroke="{{c}}" stroke-linejoin="round"/>
  <path d="{hexagon(12, 4.5, 3, 3.5)}" fill="{{cf}}" stroke="{{c}}" stroke-linejoin="round"/>
  <path d="{hexagon(8, 11.5, 3, 3.5)}" fill="{{c}}" stroke="{{c}}" stroke-linejoin="round"/>""",
    "goSum": f"""
  <path d="{HEX}" fill="{{cf}}" stroke="{{c}}" stroke-linejoin="round"/>
  <path d="M5.4 8.1l1.9 1.9 3.4-3.7" fill="none" stroke="{{c}}" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>""",
    "goPackage": f"""
  <path d="{HEX}" fill="{{cf}}" stroke="{{c}}" stroke-linejoin="round"/>
  <path d="M8 1.5L13.5 4.75L8 8L2.5 4.75Z" fill="{{c}}" stroke="{{c}}" stroke-linejoin="round"/>
  <path d="M8 8V14.5" fill="none" stroke="{{c}}"/>""",
    "goPackageIndirect": f"""
  <path d="{HEX}" fill="{{nf}}" stroke="{{n}}" stroke-linejoin="round"/>
  <path d="{CUBE_EDGES}" fill="none" stroke="{{n}}" stroke-linejoin="round"/>""",
    "goVendor": f"""
  <path d="M1.5 4.5a1 1 0 0 1 1-1h3.2a1 1 0 0 1 .7.3l1.2 1.2h5.9a1 1 0 0 1 1 1v6.5a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1z" fill="{{nf}}" stroke="{{n}}"/>
  <path d="{hexagon(8, 9.25, 2.5, 3)}" fill="{{cf}}" stroke="{{c}}" stroke-linejoin="round"/>""",
    # files around the sources
    "goTemplate": f"""
  <path d="{brace(2, 1)}{brace(5, 1)}{brace(14, -1)}{brace(11, -1)}" fill="none" stroke="{{a}}" stroke-linecap="round" stroke-linejoin="round"/>
  <circle cx="8" cy="8" r="1.2" fill="{{c}}"/>""",
    "goConfig": """
  <path d="M1.5 5.5h6M13.5 5.5h1M1.5 10.5h1M8.5 10.5h6" fill="none" stroke="{n}" stroke-linecap="round"/>
  <circle cx="10.5" cy="5.5" r="2" fill="{cf}" stroke="{c}"/>
  <circle cx="5.5" cy="10.5" r="2" fill="{cf}" stroke="{c}"/>""",
    "goBinary": """
  <rect x="2.5" y="2.5" width="11" height="11" rx="2.5" fill="{nf}" stroke="{n}"/>
  <path d="M6 4.75v2.5M10 8.75v2.5" fill="none" stroke="{c}" stroke-width="1.2" stroke-linecap="round"/>
  <ellipse cx="10" cy="6" rx="1.1" ry="1.4" fill="none" stroke="{c}" stroke-width="1.1"/>
  <ellipse cx="6" cy="10" rx="1.1" ry="1.4" fill="none" stroke="{c}" stroke-width="1.1"/>""",
    # kinds of tests
    "goBenchmark": """
  <circle cx="8" cy="9" r="5.5" fill="{cf}" stroke="{c}"/>
  <path d="M6.5 1.5h3M8 1.5v2M8 9l2.4-2.7" fill="none" stroke="{c}" stroke-linecap="round"/>""",
    "goFuzz": """
  <rect x="2.5" y="2.5" width="11" height="11" rx="2.5" fill="{ff}" stroke="{f}"/>
  <circle cx="5.6" cy="10.4" r="1.05" fill="{f}"/>
  <circle cx="8" cy="8" r="1.05" fill="{f}"/>
  <circle cx="10.4" cy="5.6" r="1.05" fill="{f}"/>""",
    "goExample": """
  <rect x="1.5" y="2.5" width="13" height="11" rx="2" fill="{cf}" stroke="{c}"/>
  <path d="M4.5 5.5h7M4.25 11l1.3-3M6.75 11l1.3-3M10 9.5h1.5" fill="none" stroke="{c}" stroke-linecap="round"/>""",
}

# name -> bodies of the 16x16 and of the 20x20 icon; {c} is the stroke / fill colour
TOOL_WINDOWS = {
    "goTestsToolWindow": ("""
  <path d="M6.5 1.5V6L2.7 12.6C2.2 13.4 2.8 14.5 3.8 14.5H12.2C13.2 14.5 13.8 13.4 13.3 12.6L9.5 6V1.5" stroke="{c}" stroke-linejoin="round"/>
  <path d="M5 1.5H11" stroke="{c}" stroke-linecap="round"/>
  <path d="M4.6 9.5H11.4" stroke="{c}"/>
  <circle cx="7" cy="12" r="0.9" fill="{c}"/>
  <circle cx="9.6" cy="11.2" r="0.6" fill="{c}"/>""", """
  <path d="M7.5 2.5V7.5L3 15.4C2.3 16.7 3.2 18.5 4.8 18.5H15.2C16.8 18.5 17.7 16.7 17 15.4L12.5 7.5V2.5" stroke="{c}" stroke-linejoin="round"/>
  <path d="M6 2.5H14" stroke="{c}" stroke-linecap="round"/>
  <path d="M4.7 12.5H15.3" stroke="{c}"/>
  <circle cx="8.5" cy="15.5" r="1.1" fill="{c}"/>
  <circle cx="12" cy="14.8" r="0.8" fill="{c}"/>"""),
    "goplsToolWindow": (glyph("{c}", "none", "1") + plug("{c}"), """
  <circle cx="5" cy="7" r="3.5" stroke="{c}"/>
  <path d="M8.5 3.5v8a3.5 3.5 0 0 1-5.75 2.68" stroke="{c}" stroke-linecap="round"/>
  <circle cx="14" cy="7" r="3.5" stroke="{c}"/>
  <path d="M13 13v2M16 13v2" stroke="{c}" stroke-width="1.2"/>
  <path d="M11.75 15h5.5v.6a2.75 2.75 0 0 1-5.5 0z" fill="{c}"/>"""),
    "goMonitorToolWindow": ("""
  <rect x="1.5" y="2.5" width="13" height="11" rx="2" stroke="{c}"/>
  <path d="M3.5 9H5.5L7 5.5L9 11L10.5 8H12.5" stroke="{c}" stroke-linecap="round" stroke-linejoin="round"/>""", """
  <rect x="2.5" y="3.5" width="15" height="13" rx="2.5" stroke="{c}"/>
  <path d="M5 11H7.2L9 6.8L11.4 13.6L13.2 10H15" stroke="{c}" stroke-linecap="round" stroke-linejoin="round"/>"""),
    "goDependenciesToolWindow": (f"""
  <path d="{HEX}" stroke="{{c}}" stroke-linejoin="round"/>
  <path d="{CUBE_EDGES}" stroke="{{c}}" stroke-linejoin="round"/>""", f"""
  <path d="{hexagon(10, 10, 6.5, 7.5)}" stroke="{{c}}" stroke-linejoin="round"/>
  <path d="M10 10V17.5M10 10L3.5 6.25M10 10L16.5 6.25" stroke="{{c}}" stroke-linejoin="round"/>"""),
}


def svg(size, body, view=16, fill_none=False):
    attrs = ' fill="none"' if fill_none else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {view} {view}"{attrs}>{body}\n</svg>\n'


def write(relative, text):
    path = os.path.join(RESOURCES, relative)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def preview(target):
    """The icons as the IDE shows them, and enlarged: what is wrong with a shape is seen at 80px, whether it reads - at 16px."""
    def panel(theme, icons):
        return f'<td class="{theme}">{"".join(icons)}</td>'

    rows = []
    for name, body in COLOURED.items():
        cells = [panel(theme, [svg(size, body.format(**colours)) for size in (16, 32, 80)]) for theme, colours in (("light", LIGHT), ("dark", DARK))]
        rows.append(f"<tr><th>{name}</th>{''.join(cells)}</tr>")
    for name, (small, large) in TOOL_WINDOWS.items():
        cells = [panel(theme, [svg(16, small.format(c=colour), fill_none=True), svg(20, large.format(c=colour), 20, True), svg(80, large.format(c=colour), 20, True)])
                 for theme, colour in (("light", "#6C707E"), ("dark", "#CED0D6"))]
        rows.append(f"<tr><th>{name}</th>{''.join(cells)}</tr>")
    page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Icons of the Go plugin</title>
<style>
  body {{ margin: 32px 40px; font: 13px "Segoe UI", system-ui, sans-serif; background: #eef0f3; color: #1f2329; }}
  table {{ border-collapse: separate; border-spacing: 6px 4px; }}
  th {{ text-align: left; font: 13px Consolas, monospace; padding-right: 16px; }}
  td {{ padding: 6px 18px; border-radius: 6px; }}
  td svg {{ vertical-align: middle; margin-right: 18px; }}
  .light {{ background: #f7f8fa; }}
  .dark {{ background: #2b2d30; }}
</style></head><body>
<table>{"".join(rows)}</table>
</body></html>
"""
    with open(target, "w", encoding="utf-8", newline="\n") as f:
        f.write(page)
    print("the preview is", os.path.normpath(target))


# what is not generated any more must not stay around
for root, _, files in os.walk(os.path.join(RESOURCES, "icons")):
    for name in files:
        if name.endswith(".svg"):
            os.remove(os.path.join(root, name))

for name, body in COLOURED.items():
    write(f"icons/{name}.svg", svg(16, body.format(**LIGHT)))
    write(f"icons/{name}_dark.svg", svg(16, body.format(**DARK)))

for name, (small, large) in TOOL_WINDOWS.items():
    for suffix, colour in (("", "#6E6E6E"), ("_dark", "#AFB1B3")):
        write(f"icons/{name}{suffix}.svg", svg(16, small.format(c=colour), fill_none=True))
    for suffix, colour in (("", "#6C707E"), ("_dark", "#CED0D6")):
        write(f"icons/expui/{name}{suffix}.svg", svg(16, small.format(c=colour), fill_none=True))
        write(f"icons/expui/{name}@20x20{suffix}.svg", svg(20, large.format(c=colour), 20, True))

# the plugin itself in the list of plugins: 40x40
write("META-INF/pluginIcon.svg", svg(40, glyph().format(**LIGHT)))
write("META-INF/pluginIcon_dark.svg", svg(40, glyph().format(**DARK)))

print(len(COLOURED), "icons and", len(TOOL_WINDOWS), "tool window icons written to", os.path.normpath(RESOURCES))
if "--preview" in sys.argv:
    preview(sys.argv[sys.argv.index("--preview") + 1])
