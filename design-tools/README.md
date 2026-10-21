# Bird design tools

Generators for the "bird design" (plain tunnel rebrand) assets. Kit: `<projects>\Plain-Option-6-Kit-Windows\Plain-Option-6-Kit`.

- `svg2vd.py <kit> app/src/main/res`: converts the kit's logo, bird poses, arch/hills and UI icons into VectorDrawables (`drawable/*.xml`). Uses `gear.svg` for `pi_settings` instead of the kit's nut-shaped icon.
- `banner2.py <kit> out.svg`: composes the TV launcher banner (centred arch + bird + wordmark). Render to 640x360 PNG at `app/src/main/res/mipmap-xhdpi/banner_tv.png` (resvg-py).

When the banner changes, give the PNG a new resource name: Google TV's launcher seems to cache banners by resource ID.

## plainVPN design
- `plainvpn_assets.py <Plain-AI-Style-Kit> app/src/main/res`: logo, monogram, shield glyph (with gradients), launcher layers, night ground band, kit system icons (gear kept).
- `plainvpn_banner.py <Plain-AI-Style-Kit> out.png` (needs resvg-py; run plainvpn_assets.py first): TV banner, just plain. VPN centred on the night-sky gradient. Save it under a NEW drawable-xhdpi name each time and check its ID with `aapt2 dump resources`: Google TV's launcher caches banners by resource ID (IDs used so far: 0x7f04003e, 0x7f040008, 0x7f04000a, 0x7f090000, 0x7f090001).
