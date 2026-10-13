#!/usr/bin/env python3
"""Generate Android launcher mipmaps from the user-selected A2 top-left crop.

Input is the 212x212 source crop in design/launcher-tile-from-user.webp;
it is supplied directly by the user, not synthesized by an image model.

Run: python3 scripts/build_launcher_icons.py
Requires Pillow. Generated assets are committed so CI needs no Pillow.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"
SOURCE = ROOT / "design/launcher-tile-from-user.webp"
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def render():
    source = Image.open(SOURCE).convert("RGB")
    if source.size != (212, 212):
        raise RuntimeError("Unexpected source crop; check the selected artwork.")
    # An adaptive launcher background is clipped by Android's actual icon mask;
    # do not bake in a second, differently shaped system mask.
    destination = RES / "drawable-nodpi"
    destination.mkdir(parents=True, exist_ok=True)
    large = source.resize((432, 432), Image.Resampling.LANCZOS)
    large.save(destination / "ic_launcher_tile.webp", "WEBP", quality=95, method=6)
    for density, size in SIZES.items():
        folder = RES / f"mipmap-{density}"
        tile = large.resize((size, size), Image.Resampling.LANCZOS)
        tile.save(folder / "ic_launcher.webp", "WEBP", quality=95, method=6)
        # Legacy round launcher icons predate Android adaptive-icon masks.
        alpha = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(alpha).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        alpha = alpha.resize((size, size), Image.Resampling.LANCZOS)
        circled = tile.convert("RGBA")
        circled.putalpha(alpha)
        circled.save(folder / "ic_launcher_round.webp", "WEBP", lossless=True, method=6)
    print("Generated user-selected A2 artwork: adaptive 432px and 5 density mipmaps.")


if __name__ == "__main__":
    render()
