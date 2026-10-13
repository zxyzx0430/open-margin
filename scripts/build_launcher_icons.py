#!/usr/bin/env python3
"""Build launcher assets from the user's A2 reference, with Android adaptive-safe padding.

Android adaptive icons use a 108dp drawable but display a ~72dp mask area.
Painting the 212px reference across the full 108dp background makes the
launcher crop away its original white margins and enlarge the blue panels.
The artwork is now centered within the 72dp mask in a transparent foreground.
A white -> pale blue diagonal gradient fills the full 108dp background for
parallax/cropping, and the original tile keeps its own whitespace.

No generative artwork is used. Requires Pillow only to regenerate checked-in assets.
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"
SOURCE = ROOT / "design/launcher-tile-from-user.webp"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE_SIZE = 432                 # 108dp at 4 pixels/dp
MASK_SIZE = 288                     # 72dp shown within that 108dp canvas
INSET = (ADAPTIVE_SIZE - MASK_SIZE) // 2


def background_gradient(size: int) -> Image.Image:
    """White at top-left, light pastel blue at bottom-right."""
    image = Image.new("RGB", (size, size))
    px = image.load()
    start = (255, 255, 255)
    end = (212, 237, 254)
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = tuple(round(a * (1 - t) + b * t) for a, b in zip(start, end))
    return image


def render() -> None:
    source = Image.open(SOURCE).convert("RGB")
    if source.size != (212, 212):
        raise ValueError("Original A2 crop must be exactly 212x212 pixels")

    destination = RES / "drawable-nodpi"
    destination.mkdir(parents=True, exist_ok=True)
    gradient = background_gradient(ADAPTIVE_SIZE)
    gradient.save(destination / "ic_launcher_bg_gradient.webp", "WEBP", quality=96, method=6)

    fg = Image.new("RGBA", (ADAPTIVE_SIZE, ADAPTIVE_SIZE), (0, 0, 0, 0))
    artwork = source.resize((MASK_SIZE, MASK_SIZE), Image.Resampling.LANCZOS)
    fg.paste(artwork, (INSET, INSET))
    fg.save(destination / "ic_launcher_full_artwork.webp", "WEBP", lossless=True, method=6)

    # Legacy icons are not drawn on the overscan 108dp canvas.
    # Keep the original reference's composition and borders at all five densities.
    for density, size in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        tile = source.resize((size, size), Image.Resampling.LANCZOS)
        tile.save(folder / "ic_launcher.webp", "WEBP", quality=96, method=6)
        alpha = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(alpha).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        alpha = alpha.resize((size, size), Image.Resampling.LANCZOS)
        circled = tile.convert("RGBA")
        circled.putalpha(alpha)
        circled.save(folder / "ic_launcher_round.webp", "WEBP", lossless=True, method=6)

    old_tile = destination / "ic_launcher_tile.webp"
    if old_tile.exists():
        old_tile.unlink()
    print("Restored original A2 margins; generated adaptive-safe artwork + diagonal gradient and 5 density mipmaps.")


if __name__ == "__main__":
    render()
