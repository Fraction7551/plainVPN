# Bird design tools

Generators for the "bird design" (plain tunnel rebrand) assets. Kit: `<projects>\Plain-Option-6-Kit-Windows\Plain-Option-6-Kit`.

- `svg2vd.py <kit> app/src/main/res`: converts the kit's logo, bird poses, arch/hills and UI icons into VectorDrawables (`drawable/*.xml`). Uses `gear.svg` for `pi_settings` instead of the kit's nut-shaped icon.
- `banner2.py <kit> out.svg`: composes the TV launcher banner (centred arch + bird + wordmark). Render to 640x360 PNG at `app/src/main/res/mipmap-xhdpi/banner_tv.png` (resvg-py).

When the banner changes, give the PNG a new resource name: Google TV's launcher seems to cache banners by resource ID.
