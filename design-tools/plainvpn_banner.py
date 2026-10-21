"""plainVPN TV launcher banner: just "plain. VPN", centred and as large as the safe area allows,
on the app's night-sky gradient. Renders a 640x360 PNG with resvg-py.

Usage: python plainvpn_banner.py <Plain-AI-Style-Kit folder> <out.png>
"""
import re
import sys
from pathlib import Path

import resvg_py

HERE = Path(__file__).parent
kit, out = Path(sys.argv[1]), Path(sys.argv[2])
font = kit / "fonts/InterVariable.ttf"

word = re.sub(r"^\s*<svg[^>]*>|</svg>\s*$", "", (kit / "assets/logo/plain-dark.svg").read_text(encoding="utf-8"))  # 310 x 125

# Lockup width = wordmark + gap + "VPN" (Inter semibold advance ~2.12 em for these three letters).
# Keep ~8.5% side margins: the launcher rounds the tile's corners and scales it on focus.
SAFE_W = 1600 * 0.83
VPN_EM = 2.12
# "VPN" cap height matches the wordmark's x-height region: font size = 0.52 x wordmark height.
# Solve WORD_W + gap + VPN_EM * size = SAFE_W with height = WORD_W * 125/310, size = 0.52 * height.
k = 125 / 310
WORD_W = SAFE_W / (1 + 0.025 + VPN_EM * 0.52 * k)
ws = WORD_W / 310
word_h = 125 * ws
gap = WORD_W * 0.025
vpn_size = 0.52 * word_h
total = WORD_W + gap + VPN_EM * vpn_size
word_x = (1600 - total) / 2
# Centre the letters' visual band (top of the l/i ~10 to baseline ~96 of 125), not the artboard.
word_y = 450 - (10 + 96) / 2 * ws - 16  # optical: the p descender makes it read low
baseline = word_y + 96 * ws

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 900">
<defs><linearGradient id="sky" x2="0" y2="1"><stop stop-color="#0B1017"/><stop offset="1" stop-color="#121D2E"/></linearGradient></defs>
<rect width="1600" height="900" fill="url(#sky)"/>
<g transform="translate({word_x:.1f} {word_y:.1f}) scale({ws:.4f})">{word}</g>
<text x="{word_x + WORD_W + gap:.1f}" y="{baseline:.1f}" font-family="Inter Variable, Inter" font-weight="600" font-size="{vpn_size:.1f}" fill="#B0BDCE">VPN</text>
</svg>'''
png = resvg_py.svg_to_bytes(svg_string=svg, width=640, height=360, font_files=[str(font)], skip_system_fonts=True)
out.write_bytes(bytes(png))
(HERE / "pv_banner.svg").write_text(svg, encoding="utf-8")
