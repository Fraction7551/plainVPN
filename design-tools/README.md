# plainVPN design tools

Generators for the plainVPN design's assets. Kit: `<projects>\Plain-AI-Style-Kit\Plain-AI-Style-Kit`.
The bird design's generators are archived with it (git tag `bird-design-final`, and `<projects>\plainVPN-archive`).

- `plainvpn_assets.py <Plain-AI-Style-Kit> app/src/main/res`: logo, monogram, launcher icon (p. monogram), night ground band, kit system icons. The settings icon is our own `gear.svg` (the kit's is lopsided).
- `plainvpn_banner.py <Plain-AI-Style-Kit> out.png` (needs resvg-py): TV banner, just plain. VPN centred on the night-sky gradient. Save it under a NEW `drawable-xhdpi` name each time and check its ID with `aapt2 dump resources`: Google TV's launcher caches banners by resource ID (IDs used so far: 0x7f04003e, 0x7f040008, 0x7f04000a, 0x7f040038, 0x7f090000, 0x7f090001).

The `pv_*.svg` files are intermediate sources the scripts write; keep them so the output can be checked or re-rendered.
