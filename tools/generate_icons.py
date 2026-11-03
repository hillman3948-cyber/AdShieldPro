#!/usr/bin/env python3
"""Generate the AdShield Pro launcher icon, notification icon and brand mark.

One geometry source of truth for both the vector drawables that ship in the APK and the
raster fallbacks used by launchers/Play. Run from the repository root:

    python3 tools/generate_icons.py

Requires Pillow (only for the raster fallbacks and the preview sheet):

    python3 -m pip install pillow
"""

from __future__ import annotations

import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DOCS = os.path.join(ROOT, "docs")

NAVY = "#07101E"
NAVY_DEEP = "#040B14"
EMERALD_LIGHT = "#34D399"
EMERALD = "#10B981"
EMERALD_DARK = "#059669"
OFF_WHITE = "#F0FDF9"

# ---------------------------------------------------------------- geometry (108x108 canvas)
CENTRE = (54.0, 54.0)
SHIELD = [
    ("M", 54.00, 24.24),
    ("L", 77.56, 32.90),
    ("C", 81.28, 34.16, 82.52, 37.26, 82.52, 40.98),
    ("V", 52.76),
    ("C", 82.52, 66.40, 71.36, 77.56, 54.00, 83.76),
    ("C", 36.64, 77.56, 25.48, 66.40, 25.48, 52.76),
    ("V", 40.98),
    ("C", 25.48, 37.26, 26.72, 34.16, 30.44, 32.90),
    ("Z",),
]
INNER_SHIELD = [
    ("M", 54.00, 31.50),
    ("L", 73.40, 38.30),
    ("C", 75.90, 39.20, 76.90, 41.50, 76.90, 44.20),
    ("V", 53.30),
    ("C", 76.90, 63.90, 68.30, 72.50, 54.00, 77.20),
    ("C", 39.70, 72.50, 31.10, 63.90, 31.10, 53.30),
    ("V", 44.20),
    ("C", 31.10, 41.50, 32.10, 39.20, 34.60, 38.30),
    ("Z",),
]
BLOCK_CIRCLE_RADIUS = 12.6
BLOCK_STROKE = 5.0
# Subtle depth rings inside the shield; kept very low contrast so the mark stays legible
# at 24dp. Empty tuple = no rings.
RADAR_RINGS = ()


def path_data(commands) -> str:
    parts: list[str] = []
    for command in commands:
        parts.append(command[0] + " ".join(f"{value:.2f}".rstrip("0").rstrip(".") for value in command[1:]))
    return " ".join(parts)


def circle_path(cx: float, cy: float, radius: float) -> str:
    left, right = cx - radius, cx + radius
    return (
        f"M{left:.2f},{cy:.2f} "
        f"A{radius:.2f},{radius:.2f} 0 1 0 {right:.2f},{cy:.2f} "
        f"A{radius:.2f},{radius:.2f} 0 1 0 {left:.2f},{cy:.2f} Z"
    )


def block_slash() -> tuple[tuple[float, float], tuple[float, float]]:
    offset = BLOCK_CIRCLE_RADIUS * 0.58
    return (
        (CENTRE[0] - offset, CENTRE[1] - offset),
        (CENTRE[0] + offset, CENTRE[1] + offset),
    )


# ------------------------------------------------------------------------------- vector XML
VECTOR_HEADER = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    xmlns:aapt="http://schemas.android.com/aapt"\n'
    '    android:width="108dp"\n'
    '    android:height="108dp"\n'
    '    android:viewportWidth="108"\n'
    '    android:viewportHeight="108">\n'
)


def gradient_path(path: str, start=(30.0, 24.0), end=(84.0, 88.0)) -> str:
    return (
        f'    <path android:pathData="{path}">\n'
        f'        <aapt:attr name="android:fillColor">\n'
        f'            <gradient\n'
        f'                android:type="linear"\n'
        f'                android:startX="{start[0]}"\n'
        f'                android:startY="{start[1]}"\n'
        f'                android:endX="{end[0]}"\n'
        f'                android:endY="{end[1]}">\n'
        f'                <item android:offset="0" android:color="{EMERALD_LIGHT}" />\n'
        f'                <item android:offset="0.55" android:color="{EMERALD}" />\n'
        f'                <item android:offset="1" android:color="{EMERALD_DARK}" />\n'
        f'            </gradient>\n'
        f'        </aapt:attr>\n'
        f'    </path>\n'
    )


def shield_symbol(monochrome: bool) -> str:
    """Shield + radar rings + blocked-sign, shared by every drawable."""
    shield = path_data(SHIELD)
    inner = path_data(INNER_SHIELD)
    ring_colour = "#FFFFFF" if monochrome else EMERALD_LIGHT
    slash_colour = "#FFFFFF" if monochrome else OFF_WHITE

    body = ""
    if monochrome:
        # Themed (monochrome) icons are tinted by the system from the alpha channel, so the
        # mark is drawn with white strokes only and nothing is punched out with colour.
        body += (
            f'    <path\n'
            f'        android:pathData="{shield}"\n'
            f'        android:strokeColor="#FFFFFF"\n'
            f'        android:strokeWidth="5"\n'
            f'        android:strokeLineJoin="round" />\n'
        )
    else:
        body += gradient_path(shield)
        body += f'    <path android:fillColor="{NAVY}" android:pathData="{inner}" />\n'

    if RADAR_RINGS:
        body += '    <group>\n'
        body += f'        <clip-path android:pathData="{inner}" />\n'
        for radius, width, alpha in RADAR_RINGS:
            colour = "#FFFFFF" if monochrome else ring_colour
            body += (
                f'        <path\n'
                f'            android:pathData="{circle_path(CENTRE[0], CENTRE[1], radius)}"\n'
                f'            android:strokeColor="{colour}"\n'
                f'            android:strokeAlpha="{alpha}"\n'
                f'            android:strokeWidth="{width}"\n'
                f'            android:strokeLineCap="round" />\n'
            )
        body += '    </group>\n'

    (x1, y1), (x2, y2) = block_slash()
    body += (
        f'    <path\n'
        f'        android:pathData="{circle_path(CENTRE[0], CENTRE[1], BLOCK_CIRCLE_RADIUS)}"\n'
        f'        android:strokeColor="{slash_colour}"\n'
        f'        android:strokeWidth="{BLOCK_STROKE}"\n'
        f'        android:strokeLineCap="round" />\n'
    )
    body += (
        f'    <path\n'
        f'        android:pathData="M{x1:.2f},{y1:.2f} L{x2:.2f},{y2:.2f}"\n'
        f'        android:strokeColor="{slash_colour}"\n'
        f'        android:strokeWidth="{BLOCK_STROKE}"\n'
        f'        android:strokeLineCap="round" />\n'
    )
    return body


def write(name: str, body: str, directory: str = "drawable", header: str = VECTOR_HEADER) -> None:
    path = os.path.join(RES, directory, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(header + body + "</vector>\n")


def emit_vectors() -> None:
    write("ic_launcher_foreground.xml", shield_symbol(monochrome=False))
    write("ic_launcher_monochrome.xml", shield_symbol(monochrome=True))
    write("ic_adshield_mark.xml", shield_symbol(monochrome=False))
    write(
        "ic_notification_shield.xml",
        shield_symbol(monochrome=True),
        header=VECTOR_HEADER.replace('android:width="108dp"', 'android:width="24dp"')
        .replace('android:height="108dp"', 'android:height="24dp"'),
    )

    background = (
        f'    <path android:pathData="M0,0h108v108h-108z">\n'
        f'        <aapt:attr name="android:fillColor">\n'
        f'            <gradient\n'
        f'                android:type="linear"\n'
        f'                android:startX="10"\n'
        f'                android:startY="6"\n'
        f'                android:endX="96"\n'
        f'                android:endY="104">\n'
        f'                <item android:offset="0" android:color="{NAVY_DEEP}" />\n'
        f'                <item android:offset="1" android:color="{NAVY}" />\n'
        f'            </gradient>\n'
        f'        </aapt:attr>\n'
        f'    </path>\n'
    )
    write("ic_launcher_background.xml", background)

    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    adaptive_mono = adaptive.replace(
        "</adaptive-icon>",
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>',
    )
    for directory, body in (("mipmap-anydpi-v26", adaptive), ("mipmap-anydpi-v33", adaptive_mono)):
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            path = os.path.join(RES, directory, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(body)


# ---------------------------------------------------------------------------- raster fallback
def cubic(p0, p1, p2, p3, steps=40):
    points = []
    for index in range(steps + 1):
        t = index / steps
        u = 1 - t
        x = u**3 * p0[0] + 3 * u**2 * t * p1[0] + 3 * u * t**2 * p2[0] + t**3 * p3[0]
        y = u**3 * p0[1] + 3 * u**2 * t * p1[1] + 3 * u * t**2 * p2[1] + t**3 * p3[1]
        points.append((x, y))
    return points


def shield_polygon(commands) -> list[tuple[float, float]]:
    points: list[tuple[float, float]] = []
    current = (0.0, 0.0)
    for command in commands:
        if command[0] == "M":
            current = (command[1], command[2])
            points.append(current)
        elif command[0] == "L":
            current = (command[1], command[2])
            points.append(current)
        elif command[0] == "V":
            current = (current[0], command[1])
            points.append(current)
        elif command[0] == "C":
            control1 = (command[1], command[2])
            control2 = (command[3], command[4])
            end = (command[5], command[6])
            points.extend(cubic(current, control1, control2, end)[1:])
            current = end
        elif command[0] == "Z":
            points.append(points[0])
    return points


def vertical_gradient(size: int, top: str, bottom: str) -> Image.Image:
    top_rgb = tuple(int(top[index : index + 2], 16) for index in (1, 3, 5))
    bottom_rgb = tuple(int(bottom[index : index + 2], 16) for index in (1, 3, 5))
    gradient = Image.new("RGB", (1, size))
    pixels = gradient.load()
    for y in range(size):
        ratio = y / max(size - 1, 1)
        pixels[0, y] = tuple(
            round(top_rgb[channel] + (bottom_rgb[channel] - top_rgb[channel]) * ratio) for channel in range(3)
        )
    return gradient.resize((size, size), Image.Resampling.NEAREST)


def render(size: int, round_mask: bool) -> Image.Image:
    scale = 4
    canvas = size * scale
    unit = canvas / 108.0

    image = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    background = vertical_gradient(canvas, NAVY_DEEP, NAVY).convert("RGBA")

    shield_mask = Image.new("L", (canvas, canvas), 0)
    ImageDraw.Draw(shield_mask).polygon(
        [(x * unit, y * unit) for x, y in shield_polygon(SHIELD)], fill=255
    )

    shield_fill = vertical_gradient(canvas, EMERALD_LIGHT, EMERALD_DARK).convert("RGBA")
    image.paste(background, (0, 0))
    image.paste(shield_fill, (0, 0), shield_mask)

    draw = ImageDraw.Draw(image)
    inner = [(x * unit, y * unit) for x, y in shield_polygon(INNER_SHIELD)]
    draw.polygon(inner, fill=NAVY)

    cx, cy = CENTRE[0] * unit, CENTRE[1] * unit
    for radius, width, alpha in RADAR_RINGS:
        colour = EMERALD_LIGHT + f"{int(alpha * 255):02X}"
        draw.ellipse(
            [cx - radius * unit, cy - radius * unit, cx + radius * unit, cy + radius * unit],
            outline=colour,
            width=max(1, int(width * unit)),
        )

    stroke = max(2, int(BLOCK_STROKE * unit))
    radius = BLOCK_CIRCLE_RADIUS * unit
    draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], outline=OFF_WHITE, width=stroke)
    (x1, y1), (x2, y2) = block_slash()
    draw.line([x1 * unit, y1 * unit, x2 * unit, y2 * unit], fill=OFF_WHITE, width=stroke)

    if round_mask:
        circle = Image.new("L", (canvas, canvas), 0)
        ImageDraw.Draw(circle).ellipse([0, 0, canvas - 1, canvas - 1], fill=255)
        image.putalpha(circle)
    else:
        corner = int(canvas * 0.22)
        rounded = Image.new("L", (canvas, canvas), 0)
        ImageDraw.Draw(rounded).rounded_rectangle([0, 0, canvas - 1, canvas - 1], radius=corner, fill=255)
        image.putalpha(rounded)

    return image.resize((size, size), Image.Resampling.LANCZOS)


def emit_rasters() -> None:
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, size in densities.items():
        directory = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(directory, exist_ok=True)
        render(size, round_mask=False).save(os.path.join(directory, "ic_launcher.png"))
        render(size, round_mask=True).save(os.path.join(directory, "ic_launcher_round.png"))

    os.makedirs(DOCS, exist_ok=True)
    render(512, round_mask=False).save(os.path.join(DOCS, "app-icon-512.png"))
    sheet = Image.new("RGBA", (512 + 192 + 48 + 40, 512), (11, 18, 32, 255))
    sheet.paste(render(512, False), (0, 0), render(512, False))
    sheet.paste(render(192, True), (532, 0), render(192, True))
    sheet.paste(render(48, True), (532 + 192 + 20, 200), render(48, True))
    sheet.save(os.path.join(DOCS, "app-icon-preview.png"))


def main() -> None:
    emit_vectors()
    emit_rasters()
    print("icons written to", RES)


if __name__ == "__main__":
    main()
