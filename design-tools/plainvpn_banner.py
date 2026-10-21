"""plainVPN TV launcher banner: centred lockup (shield glyph + plain. wordmark + VPN) on the kit's
dark tile gradient, like the big VPN apps' banners. Renders a 640x360 PNG with resvg-py.

Usage: python plainvpn_banner.py <Plain-AI-Style-Kit folder> <out.png>
"""
import re
import sys
from pathlib import Path

import resvg_py

kit, out = Path(sys.argv[1]), Path(sys.argv[2])
font = kit / "fonts/InterVariable.ttf"


def inner(p):
    s = (kit / p).read_text(encoding="utf-8").strip()
    defs = "".join(re.findall(r"<defs>.*?</defs>", s, re.S))
    body = re.sub(r"^<svg[^>]*>|</svg>$|<defs>.*?</defs>", "", s, flags=re.S)
    return defs, body


shield_defs, shield = inner("assets/apps/tunnel-glyph.svg")   # 1024 artboard, shield spans x 277-747, y 180-830
_, word = inner("assets/logo/plain-dark.svg")                   # 310 x 125

S = 0.54            # shield: 650 * 0.54 = 351 tall
WORD_W = 700        # wordmark width
ws = WORD_W / 310
word_h = 125 * ws
vpn_size = 132
gap = 56
shield_w = 470 * S
vpn_w = 0.62 * vpn_size * 3  # rough Inter semibold advance for "VPN"
total = shield_w + gap + WORD_W + 18 + vpn_w
x0 = (1600 - total) / 2
shield_x = x0 - 277 * S
shield_y = 450 - (180 + 325) * S
word_x = x0 + shield_w + gap
word_y = 450 - word_h * 0.62
baseline = word_y + 96 * ws    # the wordmark's baseline sits at ~96 of its 125 artboard

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 900">
{shield_defs}
<defs><linearGradient id="bg" x2="0.3" y2="1"><stop stop-color="#1A2533"/><stop offset="1" stop-color="#080E15"/></linearGradient></defs>
<rect width="1600" height="900" fill="url(#bg)"/>
<g transform="translate({shield_x:.1f} {shield_y:.1f}) scale({S})">{shield}</g>
<g transform="translate({word_x:.1f} {word_y:.1f}) scale({ws:.4f})">{word}</g>
<text x="{word_x + WORD_W + 18:.1f}" y="{baseline:.1f}" font-family="Inter Variable, Inter" font-weight="600" font-size="{vpn_size}" fill="#B0BDCE">VPN</text>
</svg>'''
png = resvg_py.svg_to_bytes(svg_string=svg, width=640, height=360, font_files=[str(font)], skip_system_fonts=True)
out.write_bytes(bytes(png))
(Path(__file__).parent / "pv_banner.svg").write_text(svg, encoding="utf-8")
