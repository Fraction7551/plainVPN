"""TV banner, VPN-app style: solid pine, centred lockup = arch-with-bird symbol + plain tunnel wordmark."""
import re, sys
from pathlib import Path
kit = Path(sys.argv[1]); out = Path(sys.argv[2])
def inner(p):
    return re.sub(r"^<svg[^>]*>|</svg>\s*$", "", Path(p).read_text(encoding="utf-8").strip())
word = inner(kit / "assets/logo/plain-tunnel-dark.svg")      # 796.8 x 200
bird = inner(kit / "assets/mascot/bird-perched.svg")          # 240 x 200
arch = re.search(r'<path d="M1150 890[^>]*/>', (kit / "assets/artwork/arch-bird-16x9.svg").read_text(encoding="utf-8")).group(0)
A = 0.80                       # arch scale: 200 x 324
aw, ah = 250 * A, 405 * A
bs = 0.55 * A * 1.45           # bird scale
bw, bh = 240 * bs, 200 * bs
W = 1120                        # wordmark width
wh = 200 * W / 796.8
gap = 76
total = aw + gap + W
x0 = (1600 - total) / 2
icon_h = ah + bh * 0.985
y_icon = (900 - icon_h) / 2 + 10
ax, ay = x0, y_icon + bh * 0.985
bx = ax + aw / 2 - 133 * bs      # bird faces the wordmark; feet centre on the arch top
by = ay - 197 * bs
wx = x0 + aw + gap
wy = ay + ah * 0.42 - wh / 2             # wordmark centred on the arch's upper body
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 900">
<rect width="1600" height="900" fill="#173D3B"/>
<g transform="translate({ax:.1f} {ay:.1f}) scale({A}) translate(-1150 -485)">{arch}</g>
<g transform="translate({bx:.1f} {by:.1f}) scale({bs:.3f})">{bird}</g>
<g transform="translate({wx:.1f} {wy:.1f}) scale({W / 796.8:.4f})">{word}</g>
</svg>'''
out.write_text(svg, encoding="utf-8")
