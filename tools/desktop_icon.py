# The desktop app's icon from web/remote/icon.svg (made by icon_gen.py): the note's triangles drawn with Pillow.
"""Writes, for the Windows app (PLATFORMS.md phase 8):
  desktopApp/icons/TrackRecord.ico                 the installer's and the program's icon, 16 to 256 px
  desktopApp/src/main/resources/desktop/icon.png   the window's icon, 256 px
The note on a transparent square, as Windows draws icons on any taskbar colour.
Usage: desktop_icon.py <project-root>
"""
import io
import os
import re
import sys

from PIL import Image, ImageDraw

ROOT = sys.argv[1]
svg = io.open(os.path.join(ROOT, "web", "remote", "icon.svg"), encoding="utf-8").read()
x0, y0, w, h = (float(v) for v in re.search(r'viewBox="([^"]+)"', svg).group(1).split())

# Drawn large, then scaled down, so the triangles' edges are smooth and meet without gaps.
SIZE = 1024
side = max(w, h) * 1.08
scale = SIZE / side
dx = (side - w) / 2 - x0
dy = (side - h) / 2 - y0
big = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
draw = ImageDraw.Draw(big)
for fill, d in re.findall(r'<path fill="(#[0-9A-Fa-f]{6})"[^>]* d="([^"]+)"', svg):
    points = [((float(x) + dx) * scale, (float(y) + dy) * scale) for x, y in re.findall(r"(-?[\d.]+),(-?[\d.]+)", d)]
    # The SVG strokes each triangle in its own colour (0.3 units) so neighbours overlap; so does this.
    draw.polygon(points, fill=fill, outline=fill, width=max(1, round(0.3 * scale)))

icons = os.path.join(ROOT, "desktopApp", "icons")
os.makedirs(icons, exist_ok=True)
big.save(os.path.join(icons, "TrackRecord.ico"), sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
big.resize((256, 256), Image.LANCZOS).save(os.path.join(ROOT, "desktopApp", "src", "main", "resources", "desktop", "icon.png"))
print("ok")
