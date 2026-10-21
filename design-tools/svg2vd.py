"""Convert the Plain kit's simple path-only SVGs into Android VectorDrawables.

Handles <path>, <circle>, <rect>, nested <g transform> (translate/scale/rotate),
fill, fill-rule, stroke, stroke-width and stroke-linecap/linejoin. currentColor
strokes become white so Compose can tint them.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

NS = "{http://www.w3.org/2000/svg}"


def color(c):
    if c in (None, "none"):
        return None
    if c == "currentColor":
        return "#FFFFFFFF"
    c = c.lstrip("#")
    if len(c) == 3:
        c = "".join(ch * 2 for ch in c)
    return "#FF" + c.upper()


def transform_groups(t):
    out = []
    for name, args in re.findall(r"(\w+)\(([^)]*)\)", t or ""):
        a = [float(x) for x in re.split(r"[ ,]+", args.strip()) if x]
        if name == "translate":
            out.append(f'translateX="{a[0]:g}" android:translateY="{(a[1] if len(a) > 1 else 0):g}"')
        elif name == "scale":
            out.append(f'scaleX="{a[0]:g}" android:scaleY="{(a[1] if len(a) > 1 else a[0]):g}"')
        elif name == "rotate":
            piv = f' android:pivotX="{a[1]:g}" android:pivotY="{a[2]:g}"' if len(a) == 3 else ""
            out.append(f'rotation="{a[0]:g}"{piv}')
    return out


def shape(el, inherited):
    tag = el.tag.replace(NS, "")
    if tag == "path":
        d = el.get("d")
    elif tag == "circle":
        cx, cy, r = (float(el.get(k)) for k in ("cx", "cy", "r"))
        d = f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1,0 {2 * r:g},0a{r:g},{r:g} 0 1,0 {-2 * r:g},0Z"
    elif tag == "rect":
        x, y = float(el.get("x", 0)), float(el.get("y", 0))
        w, h = float(el.get("width")), float(el.get("height"))
        d = f"M{x:g},{y:g}H{x + w:g}V{y + h:g}H{x:g}Z"
    else:
        return None
    attrs = dict(inherited)
    attrs.update({k: v for k, v in el.attrib.items()})
    fill = attrs.get("fill", "#000000")
    a = [f'android:pathData="{d}"']
    if color(fill):
        a.append(f'android:fillColor="{color(fill)}"')
    if attrs.get("fill-rule") == "evenodd":
        a.append('android:fillType="evenOdd"')
    stroke = color(attrs.get("stroke"))
    if stroke:
        a.append(f'android:strokeColor="{stroke}"')
        a.append(f'android:strokeWidth="{attrs.get("stroke-width", "1")}"')
        if "stroke-linecap" in attrs:
            a.append(f'android:strokeLineCap="{attrs["stroke-linecap"]}"')
        if "stroke-linejoin" in attrs:
            a.append(f'android:strokeLineJoin="{attrs["stroke-linejoin"]}"')
    return "<path " + " ".join(a) + "/>"


def walk(el, inherited, skip, depth=1):
    lines = []
    inh = dict(inherited)
    for k in ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule"):
        if k in el.attrib and el.tag.replace(NS, "") in ("g", "svg"):
            inh[k] = el.attrib[k]
    for child in el:
        if skip(child):
            continue
        tag = child.tag.replace(NS, "")
        if tag == "g":
            groups = transform_groups(child.get("transform"))
            inner = walk(child, inh, skip, depth)
            for g in reversed(groups):
                inner = [f"<group android:{g}>"] + inner + ["</group>"]
            lines += inner
        else:
            s = shape(child, inh)
            if s:
                lines.append(s)
    return lines


def convert(src, dst, width_dp, skip=lambda e: False, crop=None):
    root = ET.parse(src).getroot()
    vb = [float(x) for x in root.get("viewBox").split()]
    w, h = vb[2], vb[3]
    body = walk(root, {"color": root.get("color")}, skip)
    if crop:
        x, y, w, h = crop
        body = [f'<group android:translateX="{-x:g}" android:translateY="{-y:g}">', *("    " + l for l in body), "</group>"]
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- Generated from Plain kit v1.3.0 {Path(src).name}; do not hand-edit. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{width_dp:g}dp" android:height="{width_dp * h / w:.1f}dp"',
        f'    android:viewportWidth="{w:g}" android:viewportHeight="{h:g}">',
        *("    " + l for l in body),
        "</vector>",
        "",
    ]
    Path(dst).write_text("\n".join(out), encoding="utf-8")


if __name__ == "__main__":
    kit, res = Path(sys.argv[1]), Path(sys.argv[2])
    a = kit / "assets"
    d = res / "drawable"
    convert(a / "logo/plain-dark.svg", d / "plain_wordmark.xml", 120)
    convert(a / "logo/plain-tunnel-dark.svg", d / "plain_tunnel_wordmark.xml", 200)
    for pose in ("perched", "happy-hop", "sleepy", "concerned", "resting", "curious", "happy", "waving"):
        convert(a / f"mascot/bird-{pose}.svg", d / f"bird_{pose.replace('-', '_')}.xml", 120)
    # Home: the two hills as a full-width ground band, and the arch on its own so it keeps its proportions.
    hills_only = lambda e: e.tag.replace(NS, "") in ("rect", "g") or "a125" in (e.get("d") or "")
    convert(a / "artwork/arch-bird-16x9.svg", d / "hills_band.xml", 800, skip=hills_only, crop=(0, 640, 1600, 260))
    arch_only = lambda e: e.tag.replace(NS, "") in ("rect", "g") or "a125" not in (e.get("d") or "")
    convert(a / "artwork/arch-bird-16x9.svg", d / "arch.xml", 100, skip=arch_only, crop=(1150, 485, 250, 405))
    for icon in (a / "ui-icons").glob("*.svg"):
        convert(icon, d / f"pi_{icon.stem.replace('-', '_')}.xml", 24)
    # The kit's settings icon reads as a nut on a TV; use our 8-tooth gear (gear.svg, same 1.8 stroke).
    convert(Path(__file__).with_name("gear.svg"), d / "pi_settings.xml", 24)
