"""plainVPN design: convert the Plain AI Style Kit's SVGs into Android VectorDrawables.

Usage: python plainvpn_assets.py <Plain-AI-Style-Kit folder> <app/src/main/res>

Unlike svg2vd.py (bird design) this handles linear gradients (objectBoundingBox, the
kit's default) and fill opacity, which the kit's dimensional product glyphs use. It also
writes the night ridge-and-lake ground band, drawn here in the kit's dark palette rather
than taken from the kit's landscape SVGs (those are full scenes, too busy behind a TV UI).
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = "{http://www.w3.org/2000/svg}"
HERE = Path(__file__).parent


def hexcolor(c, alpha=1.0):
    c = c.strip().lstrip("#")
    if len(c) == 3:
        c = "".join(ch * 2 for ch in c)
    return f"#{round(alpha * 255):02X}{c.upper()}"


def path_points(d):
    """Every endpoint and control point of a path, absolute. Enough for a bounding box."""
    toks = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+(?:e-?\d+)?", d)
    pts, i, cmd, x, y, sx, sy = [], 0, None, 0.0, 0.0, 0.0, 0.0
    sizes = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}
    while i < len(toks):
        if re.match(r"[A-Za-z]", toks[i]):
            cmd = toks[i]
            i += 1
            if cmd in "Zz":
                x, y = sx, sy
                continue
        n = sizes[cmd.upper()]
        a = [float(t) for t in toks[i:i + n]]
        i += n
        rel = cmd.islower()
        if cmd.upper() == "H":
            x = a[0] + (x if rel else 0)
        elif cmd.upper() == "V":
            y = a[0] + (y if rel else 0)
        elif cmd.upper() == "A":
            x, y = a[5] + (x if rel else 0), a[6] + (y if rel else 0)
        else:
            for j in range(0, n, 2):
                px, py = a[j] + (x if rel else 0), a[j + 1] + (y if rel else 0)
                pts.append((px, py))
            x, y = pts[-1]
        if cmd.upper() == "M":
            sx, sy = x, y
            cmd = "l" if rel else "L"  # implicit lineto after moveto
        pts.append((x, y))
    return pts


def gradient_xml(grad, bbox, indent):
    bx0, by0, bx1, by1 = bbox
    def f(k, default):
        v = grad.get(k)
        return float(v.rstrip("%")) / (100 if v and v.endswith("%") else 1) if v else default
    x1, y1, x2, y2 = f("x1", 0), f("y1", 0), f("x2", 1), f("y2", 0)
    w, h = bx1 - bx0, by1 - by0
    items = []
    for s in grad.findall(f"{NS}stop"):
        off = s.get("offset", "0")
        off = float(off.rstrip("%")) / (100 if off.endswith("%") else 1)
        items.append(f'{indent}        <item android:offset="{off:g}" android:color="{hexcolor(s.get("stop-color"), float(s.get("stop-opacity", 1)))}"/>')
    return "\n".join([
        f'{indent}    <aapt:attr name="android:fillColor">',
        f'{indent}      <gradient android:type="linear" android:startX="{bx0 + x1 * w:.1f}" android:startY="{by0 + y1 * h:.1f}" '
        f'android:endX="{bx0 + x2 * w:.1f}" android:endY="{by0 + y2 * h:.1f}">',
        *items,
        f"{indent}      </gradient>",
        f"{indent}    </aapt:attr>",
    ])


def shape_d(el):
    tag = el.tag.replace(NS, "")
    if tag == "path":
        return el.get("d")
    if tag == "circle" or tag == "ellipse":
        cx, cy = float(el.get("cx", 0)), float(el.get("cy", 0))
        rx = float(el.get("r") or el.get("rx"))
        ry = float(el.get("r") or el.get("ry"))
        return f"M{cx - rx:g},{cy:g}a{rx:g},{ry:g} 0 1,0 {2 * rx:g},0a{rx:g},{ry:g} 0 1,0 {-2 * rx:g},0Z"
    if tag == "rect":
        x, y = float(el.get("x", 0)), float(el.get("y", 0))
        w, h = float(el.get("width")), float(el.get("height"))
        r = float(el.get("rx", 0))
        if r:
            return (f"M{x + r:g},{y:g}H{x + w - r:g}A{r:g},{r:g} 0 0 1 {x + w:g},{y + r:g}V{y + h - r:g}"
                    f"A{r:g},{r:g} 0 0 1 {x + w - r:g},{y + h:g}H{x + r:g}A{r:g},{r:g} 0 0 1 {x:g},{y + h - r:g}"
                    f"V{y + r:g}A{r:g},{r:g} 0 0 1 {x + r:g},{y:g}Z")
        return f"M{x:g},{y:g}H{x + w:g}V{y + h:g}H{x:g}Z"
    return None


def transform_groups(t):
    out = []
    for name, args in re.findall(r"(\w+)\(([^)]*)\)", t or ""):
        a = [float(v) for v in re.split(r"[ ,]+", args.strip()) if v]
        if name == "translate":
            out.append(f'android:translateX="{a[0]:g}" android:translateY="{(a[1] if len(a) > 1 else 0):g}"')
        elif name == "scale":
            out.append(f'android:scaleX="{a[0]:g}" android:scaleY="{(a[1] if len(a) > 1 else a[0]):g}"')
    return out


def walk(el, inh, grads, skip, indent="    "):
    lines = []
    inh = dict(inh)
    for k in ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule", "opacity"):
        if k in el.attrib:
            inh[k] = el.attrib[k]
    for ch in el:
        tag = ch.tag.replace(NS, "")
        if tag == "defs" or skip(ch):
            continue
        if tag == "g":
            inner = walk(ch, inh, grads, skip, indent)
            for g in reversed(transform_groups(ch.get("transform"))):
                inner = [f"{indent}<group {g}>"] + ["  " + l for l in inner] + [f"{indent}</group>"]
            lines += inner
            continue
        d = shape_d(ch)
        if not d:
            continue
        a = dict(inh)
        a.update(ch.attrib)
        attrs = [f'android:pathData="{d}"']
        fill = a.get("fill", "#000")
        grad = None
        if fill.startswith("url("):
            grad = grads[fill[5:-1]]
        elif fill != "none":
            attrs.append(f'android:fillColor="{hexcolor(fill) if fill != "currentColor" else "#FFFFFFFF"}"')
        if a.get("opacity") or a.get("fill-opacity"):
            attrs.append(f'android:fillAlpha="{float(a.get("fill-opacity") or a.get("opacity")):g}"')
        if a.get("fill-rule") == "evenodd":
            attrs.append('android:fillType="evenOdd"')
        stroke = a.get("stroke")
        if stroke and stroke != "none":
            attrs.append(f'android:strokeColor="{"#FFFFFFFF" if stroke == "currentColor" else hexcolor(stroke)}"')
            attrs.append(f'android:strokeWidth="{a.get("stroke-width", "1")}"')
            for k, n in (("stroke-linecap", "strokeLineCap"), ("stroke-linejoin", "strokeLineJoin")):
                if k in a:
                    attrs.append(f'android:{n}="{a[k]}"')
        if grad is None:
            lines.append(f"{indent}<path " + " ".join(attrs) + "/>")
        else:
            pts = path_points(d)
            bbox = (min(p[0] for p in pts), min(p[1] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts))
            lines += [f"{indent}<path " + " ".join(attrs) + ">", gradient_xml(grad, bbox, indent), f"{indent}</path>"]
    return lines


def convert(src, dst, width_dp, skip=lambda e: False, viewbox=None, wrap=None):
    """[viewbox] overrides the source viewBox (x, y, w, h); [wrap] adds an outer group (e.g. scale into a safe zone)."""
    root = ET.parse(src).getroot()
    grads = {g.get("id"): g for g in root.iter(f"{NS}linearGradient")}
    x, y, w, h = viewbox or [float(v) for v in root.get("viewBox").split()]
    body = walk(root, {"fill": root.get("fill", "#000"), **{k: v for k, v in root.attrib.items() if k.startswith("stroke")}}, grads, skip)
    if x or y:
        body = [f'    <group android:translateX="{-x:g}" android:translateY="{-y:g}">'] + ["  " + l for l in body] + ["    </group>"]
    if wrap:
        body = [f"    <group {wrap}>"] + ["  " + l for l in body] + ["    </group>"]
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- plainVPN design, generated from {Path(src).name} by design-tools/plainvpn_assets.py; do not hand-edit. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    xmlns:aapt="http://schemas.android.com/aapt"',
        f'    android:width="{width_dp:g}dp" android:height="{width_dp * h / w:.1f}dp"',
        f'    android:viewportWidth="{w:g}" android:viewportHeight="{h:g}">',
        *body,
        "</vector>",
        "",
    ]
    Path(dst).write_text("\n".join(out), encoding="utf-8")


# Night ground band, 1600 x 220: two ridges, a still lake with a pale shoreline, pine stands
# at both ends. Colours step up from the dark canvas (#0B1017) so it reads as distance.
def pines(x0, x1, base, step, hmin, hmax, seed):
    import random
    rnd = random.Random(seed)
    d, x = [], x0
    while x < x1:
        h = rnd.uniform(hmin, hmax)
        w = h * 0.34
        d.append(f"M{x - w:.0f} {base}L{x:.0f} {base - h:.0f}L{x + w:.0f} {base}Z")
        x += step * rnd.uniform(0.7, 1.2)
    return "".join(d)


FAR_RIDGE = "M0 120L90 92L170 108L260 60L330 84L420 46L500 80L600 66L690 98L780 70L880 34L960 70L1040 58L1140 90L1230 52L1320 78L1400 40L1480 72L1600 54V170H0Z"
GROUND = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 220">
<defs>
<linearGradient id="far" x2="0" y2="1"><stop stop-color="#2B3C5C"/><stop offset="1" stop-color="#162236"/></linearGradient>
<linearGradient id="near" x2="0" y2="1"><stop stop-color="#1A2A44"/><stop offset="1" stop-color="#101A29"/></linearGradient>
<linearGradient id="lake" x2="0" y2="1"><stop stop-color="#16264A"/><stop offset="1" stop-color="#0B1017"/></linearGradient>
</defs>
<path fill="url(#far)" d="{FAR_RIDGE}"/>
<path fill="url(#near)" d="M0 150L120 118L210 132L320 104L430 128L540 112L650 136L760 116L880 130L990 106L1100 128L1210 112L1320 132L1430 110L1600 126V172H0Z"/>
<path fill="url(#lake)" d="M0 168H1600V220H0Z"/>
<g transform="translate(0 338) scale(1 -1)"><path fill="#2B3C5C" opacity=".22" d="{FAR_RIDGE}"/></g>
<path fill="#3B5A86" opacity=".7" d="M0 168H1600V169.5H0Z"/>
<path fill="#06090E" d="{pines(-10, 330, 172, 22, 40, 92, 3)}{pines(1290, 1620, 172, 22, 40, 92, 7)}"/>
<path fill="#06090E" d="M0 172H340L300 180H0ZM1600 172H1280L1320 180H1600Z"/>
</svg>'''


if __name__ == "__main__":
    kit, res = Path(sys.argv[1]), Path(sys.argv[2])
    a, d = kit / "assets", res / "drawable"
    convert(a / "logo/plain-dark.svg", d / "pv_wordmark.xml", 124)
    convert(a / "logo/monogram-dark.svg", d / "pv_monogram.xml", 48)
    # Launcher: the kit's p. monogram (the same mark as the drawer rail). Its tile becomes the
    # full-bleed adaptive background; the letter and blue dot are the foreground, scaled into
    # the 108dp icon's safe zone.
    tile = HERE / "pv_tile.svg"
    tile.write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024"><rect width="1024" height="1024" fill="#0B1017"/></svg>', encoding="utf-8")
    convert(tile, d / "pv_launcher_background.xml", 108)
    convert(a / "logo/monogram-dark.svg", d / "pv_launcher_foreground.xml", 108,
            skip=lambda e: e.tag.replace(NS, "") == "rect",
            wrap='android:scaleX="0.72" android:scaleY="0.72" android:pivotX="512" android:pivotY="512"')
    ground = HERE / "pv_ground.svg"
    ground.write_text(GROUND, encoding="utf-8")
    convert(ground, d / "pv_ground.xml", 800)
    # System icons from the kit replace the matching bird-design ones; the kit has no power,
    # clock, tv, location, cast, warning or unlock icon, so those stay (same 24px / 1.8 stroke).
    for kit_name, ours in (("check", "check"), ("chevron-right", "chevron_right"), ("info", "info"),
                           ("lock", "lock"), ("sync", "refresh"), ("globe", "globe"), ("shield", "shield")):
        convert(a / f"system/{kit_name}.svg", d / f"pi_{ours}.xml", 24)
    # The kit's settings icon is lopsided; keep our 8-tooth gear.
    convert(HERE / "gear.svg", d / "pi_settings.xml", 24)
    # Unlock to match the kit lock (rounded body, keyhole stroke, shackle open).
    unlock = HERE / "pv_unlock.svg"
    unlock.write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
                      'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="10" width="14" '
                      'height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 7.5-2m-3.5 10v2"/></svg>', encoding="utf-8")
    convert(unlock, d / "pi_unlock.xml", 24)
