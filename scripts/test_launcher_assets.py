#!/usr/bin/env python3
"""Pixel-level simulation of Android's 108dp -> 72dp adaptive launcher mask.

Checks both crop behavior and the original reference, without a device/emulator.
Install app on Android later to verify OEM-specific shape and launcher caching.
"""
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageStat

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"
SOURCE = Image.open(ROOT / "design/launcher-tile-from-user.webp").convert("RGB")
BACKGROUND = Image.open(RES / "drawable-nodpi/ic_launcher_bg_gradient.webp").convert("RGB")
FOREGROUND = Image.open(RES / "drawable-nodpi/ic_launcher_full_artwork.webp").convert("RGBA")

assert SOURCE.size == (212, 212)
assert BACKGROUND.size == FOREGROUND.size == (432, 432)
assert FOREGROUND.getchannel("A").getbbox() == (72, 72, 360, 360)
assert BACKGROUND.getpixel((0, 0))[0] > 250
br = BACKGROUND.getpixel((431, 431))
assert br[2] - br[0] >= 25, f"Bottom-right should be pale blue, got {br}"

# Emulator-independent mask simulation: Android's adaptive drawable has 18dp
# overscan on each side; the central 72dp is the guaranteed visible area.
adaptive = Image.alpha_composite(BACKGROUND.convert("RGBA"), FOREGROUND)
visible = adaptive.crop((72, 72, 360, 360)).convert("RGB")
sample = visible.resize((212, 212), Image.Resampling.LANCZOS)
difference = ImageChops.difference(sample, SOURCE)
mae = sum(ImageStat.Stat(difference).mean) / 3
assert mae < 5.0, f"Adaptive crop altered original A2 geometry: mean error={mae:.2f}"

# Each OEM mask clips corners, but must not clip the blue panes.
for shape in ("circle", "rounded-square"):
    mask = Image.new("L", (288, 288), 0)
    draw = ImageDraw.Draw(mask)
    if shape == "circle":
        draw.ellipse((0, 0, 287, 287), fill=255)
    else:
        draw.rounded_rectangle((0, 0, 287, 287), radius=78, fill=255)
    # Blue pane pixels should remain well inside both masks.
    blue = []
    for y in range(288):
        for x in range(288):
            r, g, b = visible.getpixel((x, y))
            if b > r + 40 and b > g + 10 and r < 140:
                blue.append((x, y))
                assert mask.getpixel((x, y)) != 0, f"Blue panel clipped by {shape} at {(x,y)}"
    assert len(blue) > 200, "Expected prominent blue artwork missing"
    print(f"PASS {shape} blue-pane pixels={len(blue)}")

for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    path = RES / f"mipmap-{density}"
    full = Image.open(path / "ic_launcher.webp")
    round_icon = Image.open(path / "ic_launcher_round.webp").convert("RGBA")
    assert full.size == round_icon.size == (size, size)
    assert round_icon.getpixel((0, 0))[3] == 0
    print(f"PASS {density} {size}px")
print(f"PASS Android adaptive crop simulated: full margins maintained; MAE={mae:.2f}, gradient and density resources valid")
