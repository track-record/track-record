# Generates the app icon (res/drawable/ic_launcher_*.xml) and icon.svg; see DECISIONS.md D-172.
"""Track Record's app icon: a colourful low-poly music note (after Felix's reference picture).

Writes, in the 108x108 adaptive-icon grid (safe zone: radius 33 around the centre):
  <res>/drawable/ic_launcher_foreground.xml   coloured triangles
  <res>/drawable/ic_launcher_monochrome.xml   the silhouette, for themed icons
  <out>/icon.svg                              the same note for web pages and previews
Usage: icon_gen.py <res-dir> <svg-out> [seed]
"""
import colorsys
import math
import random
import sys

RES, SVG_OUT = sys.argv[1], sys.argv[2]
SEED = int(sys.argv[3]) if len(sys.argv) > 3 else 7
rnd = random.Random(SEED)

# ---- The note: a tilted oval head, a stem, a flag ----
HEAD_C, HEAD_RX, HEAD_RY, HEAD_ROT = (43.5, 73.5), 14.0, 10.4, math.radians(-24)
STEM_X0, STEM_X1, STEM_Y0, STEM_Y1 = 50.8, 57.6, 21.0, 72.0


def bezier(p0, p1, p2, p3, n):
    out = []
    for i in range(n + 1):
        t = i / n
        a, b, c, d = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3
        out.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return out


FLAG_OUTER = bezier((STEM_X1 - 0.5, STEM_Y0), (65, 26), (80, 34), (75.5, 54), 9)
FLAG_INNER = bezier((75.5, 54), (73.5, 45), (65, 38.5), (STEM_X1 - 0.5, 38), 7)
FLAG_POLY = FLAG_OUTER + FLAG_INNER[1:]


def in_head(x, y, margin=0.0):
    dx, dy = x - HEAD_C[0], y - HEAD_C[1]
    c, s = math.cos(-HEAD_ROT), math.sin(-HEAD_ROT)
    u, v = dx * c - dy * s, dx * s + dy * c
    return (u / (HEAD_RX - margin)) ** 2 + (v / (HEAD_RY - margin)) ** 2 <= 1


def in_stem(x, y, margin=0.0):
    return STEM_X0 + margin <= x <= STEM_X1 - margin and STEM_Y0 + margin <= y <= STEM_Y1 - margin


def in_poly(x, y, poly):
    inside = False
    j = len(poly) - 1
    for i in range(len(poly)):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def in_flag(x, y):
    return in_poly(x, y, FLAG_POLY)


def inside(x, y):
    return in_head(x, y) or in_stem(x, y) or in_flag(x, y)


def strictly_inside_other(x, y, part):
    m = 0.4
    return (part != "head" and in_head(x, y, m)) or (part != "stem" and in_stem(x, y, m)) or (part != "flag" and in_poly(x, y, FLAG_POLY) and inside_flag_margin(x, y))


def inside_flag_margin(x, y):
    # A point well inside the flag: its neighbours are inside too.
    return all(in_flag(x + dx, y + dy) for dx, dy in ((0.5, 0), (-0.5, 0), (0, 0.5), (0, -0.5)))


# ---- Points: the outline (where it is the union's edge) and a scatter inside ----
points = []
for i in range(20):
    a = 2 * math.pi * i / 20
    u, v = HEAD_RX * math.cos(a), HEAD_RY * math.sin(a)
    c, s = math.cos(HEAD_ROT), math.sin(HEAD_ROT)
    p = (HEAD_C[0] + u * c - v * s, HEAD_C[1] + u * s + v * c)
    if not strictly_inside_other(*p, "head"):
        points.append(p)
# The stem's sides staggered, with a staggered middle line: diagonal facets instead of stripes.
STEP = (STEM_Y1 - STEM_Y0) / 8
for k in range(9):
    y = STEM_Y0 + k * STEP
    for x, dy in ((STEM_X0, 0), (STEM_X1, STEP / 2), ((STEM_X0 + STEM_X1) / 2 + rnd.uniform(-0.8, 0.8), STEP / 4 + rnd.uniform(-1, 1))):
        if STEM_Y0 <= y + dy <= STEM_Y1 and not strictly_inside_other(x, y + dy, "stem"):
            points.append((x, y + dy))
points.append((STEM_X0, STEM_Y0))
for p in FLAG_POLY:
    if not strictly_inside_other(*p, "flag"):
        points.append(p)

MIN_DIST = 4.7
tries = 0
while tries < 6000:
    tries += 1
    x, y = rnd.uniform(28, 80), rnd.uniform(20, 86)
    if not inside(x, y):
        continue
    if all((x - px) ** 2 + (y - py) ** 2 >= MIN_DIST ** 2 for px, py in points):
        points.append((x, y))

# ---- Delaunay (Bowyer-Watson) ----


def circumcircle(a, b, c):
    ax, ay = a
    bx, by = b
    cx, cy = c
    d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if abs(d) < 1e-12:
        return (0, 0, float("inf"))
    ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
    uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
    return (ux, uy, (ax - ux) ** 2 + (ay - uy) ** 2)


def delaunay(pts):
    big = [(-1000, -1000), (1000, -1000), (0, 1000)]
    allp = pts + big
    n = len(pts)
    tris = [(n, n + 1, n + 2)]
    for i, p in enumerate(pts):
        bad = []
        for t in tris:
            ux, uy, r2 = circumcircle(allp[t[0]], allp[t[1]], allp[t[2]])
            if (p[0] - ux) ** 2 + (p[1] - uy) ** 2 < r2:
                bad.append(t)
        edges = {}
        for t in bad:
            for e in ((t[0], t[1]), (t[1], t[2]), (t[2], t[0])):
                k = tuple(sorted(e))
                edges[k] = edges.get(k, 0) + 1
        tris = [t for t in tris if t not in bad]
        for (a, b), count in edges.items():
            if count == 1:
                tris.append((a, b, i))
    return [t for t in tris if max(t) < n]


triangles = []
for t in delaunay(points):
    a, b, c = (points[k] for k in t)
    cx, cy = (a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3
    mids = [((p[0] + q[0]) / 2, (p[1] + q[1]) / 2) for p, q in ((a, b), (b, c), (c, a))]
    # Inside the note, and not bridging the gap between flag and stem.
    if inside(cx, cy) and all(inside(*m) or any(inside(m[0] + dx, m[1] + dy) for dx, dy in ((0.6, 0), (-0.6, 0), (0, 0.6), (0, -0.6))) for m in mids):
        triangles.append((a, b, c))

# ---- Colours: purple-pink head, blue-cyan-green stem, yellow top, orange-to-green flag ----
HUES = [(0.0, 270), (0.12, 315), (0.26, 235), (0.45, 195), (0.62, 150), (0.8, 95), (0.92, 55), (1.05, 32), (1.2, 15), (1.38, 90)]


def hue_at(t):
    for (t0, h0), (t1, h1) in zip(HUES, HUES[1:]):
        if t <= t1:
            f = max(0.0, min(1.0, (t - t0) / (t1 - t0)))
            return h0 + (h1 - h0) * f
    return HUES[-1][1]


def colour(tri):
    cx = sum(p[0] for p in tri) / 3
    cy = sum(p[1] for p in tri) / 3
    if in_flag(cx, cy) and cx > STEM_X1 - 0.2:
        along = max(0.0, min(1.0, (cx - STEM_X1) / 17 * 0.6 + (cy - STEM_Y0) / 30 * 0.4))
        t = 1.0 + 0.38 * along
    else:
        t = max(0.0, min(1.0, (82 - cy) / 59 + (cx - 44) / 120))
    h = (hue_at(t) + rnd.uniform(-14, 14)) % 360
    s = rnd.uniform(0.5, 0.78)
    v = rnd.uniform(0.86, 1.0)
    r, g, b = colorsys.hsv_to_rgb(h / 360, s, v)
    return "#%02X%02X%02X" % (round(r * 255), round(g * 255), round(b * 255))


def fmt(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


# Slightly smaller and towards the centre, so the head stays in the safe zone (radius 33) of any mask.
def place(p):
    return 54 + (p[0] - 54) * 0.95 + 1, 54 + (p[1] - 54) * 0.95 - 0.5


def path_data(tri):
    (ax, ay), (bx, by), (cx, cy) = (place(p) for p in tri)
    return f"M{fmt(ax)},{fmt(ay)}L{fmt(bx)},{fmt(by)}L{fmt(cx)},{fmt(cy)}Z"


coloured = [(tri, colour(tri)) for tri in triangles]

head = """<?xml version="1.0" encoding="utf-8"?>
<!-- {what} Generated by a script from a triangulated note outline (DECISIONS.md D-172). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
"""
with open(f"{RES}/drawable/ic_launcher_foreground.xml", "w", encoding="utf-8", newline="\n") as f:
    f.write(head.format(what="Track Record: a colourful low-poly music note."))
    for tri, c in coloured:
        # The thin stroke in the same colour closes the hairline seams between facets.
        f.write(f'    <path android:fillColor="{c}" android:strokeColor="{c}" android:strokeWidth="0.3" android:pathData="{path_data(tri)}" />\n')
    f.write("</vector>\n")
with open(f"{RES}/drawable/ic_launcher_monochrome.xml", "w", encoding="utf-8", newline="\n") as f:
    f.write(head.format(what="Track Record: the note's silhouette, for themed icons."))
    f.write(f'    <path android:fillColor="#FFFFFF" android:strokeColor="#FFFFFF" android:strokeWidth="0.3" android:pathData="{"".join(path_data(t) for t in triangles)}" />\n')
    f.write("</vector>\n")
with open(SVG_OUT, "w", encoding="utf-8", newline="\n") as f:
    f.write('<svg xmlns="http://www.w3.org/2000/svg" viewBox="24 18 60 72">\n')
    for tri, c in coloured:
        f.write(f'  <path fill="{c}" stroke="{c}" stroke-width="0.3" stroke-linejoin="round" d="{path_data(tri)}"/>\n')
    f.write("</svg>\n")
print(len(points), "points,", len(triangles), "triangles")
