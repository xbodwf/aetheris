"""Draw a comparison sheet for the solution bucket's borrowed art.

The point is to check alignment by eye rather than assert it: the derived liquid region is drawn on top of
vanilla's own bucket, tinted, and if the shape sits wrong inside the bucket it is obvious immediately at this
scale.

Panels, left to right and then down:

    vanilla empty bucket | vanilla water bucket | the derived liquid region
    region marked on the bucket | tinted like copper sulfate | tinted like iron(II) chloride

Rendered at 10x, which is how a 16x16 sprite is meant to be looked at.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from bucket_texture import Image, load_from_jar, write_png  # noqa: E402

EMPTY_ENTRY = "assets/minecraft/textures/item/bucket.png"
FILLED_ENTRY = "assets/minecraft/textures/item/water_bucket.png"
OVERLAY = Path("common/src/main/resources/assets/aetheris/textures/item/solution_bucket_content.png")
OUTPUT = Path("build/bucket_preview.png")

SCALE = 10
GAP = 6
BACKGROUND = (24, 24, 28, 255)

# Two solution colours, so the tint is visibly doing something: copper sulfate blue and iron(II) chloride
# green, which is roughly what the engine's own solutionColor produces for those.
COPPER_SULFATE = (0x1E, 0x6F, 0xD6)
IRON_CHLORIDE = (0x4E, 0x8A, 0x4E)


def liquid_pixels(empty: Image, filled: Image) -> set[tuple[int, int]]:
    found = set()
    for y in range(empty.height):
        for x in range(empty.width):
            if empty.at(x, y) != filled.at(x, y) and filled.at(x, y)[3] > 0:
                found.add((x, y))
    return found


def blank(width: int, height: int) -> Image:
    return Image(width, height, [[BACKGROUND] * width for _ in range(height)])


def paste(canvas: Image, sprite: Image, at_x: int, at_y: int) -> None:
    for y in range(sprite.height):
        for x in range(sprite.width):
            r, g, b, a = sprite.at(x, y)
            if a == 0:
                continue
            for dy in range(SCALE):
                for dx in range(SCALE):
                    canvas.pixels[at_y + y * SCALE + dy][at_x + x * SCALE + dx] = (r, g, b, a)


def mark(empty: Image, liquid: set[tuple[int, int]]) -> Image:
    out = Image(empty.width, empty.height, [list(row) for row in empty.pixels])
    for x, y in liquid:
        out.pixels[y][x] = (230, 60, 60, 255)
    return out


def composite(empty: Image, overlay: Image, tint: tuple[int, int, int]) -> Image:
    out = Image(empty.width, empty.height, [list(row) for row in empty.pixels])
    for y in range(empty.height):
        for x in range(empty.width):
            r, g, b, a = overlay.at(x, y)
            if a == 0:
                continue
            out.pixels[y][x] = (r * tint[0] // 255, g * tint[1] // 255, b * tint[2] // 255, 255)
    return out


def main() -> None:
    empty = load_from_jar(EMPTY_ENTRY)
    filled = load_from_jar(FILLED_ENTRY)
    overlay = Image(0, 0, [])
    from bucket_texture import decode_png  # local import keeps the module's own CLI untouched

    overlay = decode_png(OVERLAY.read_bytes())

    liquid = liquid_pixels(empty, filled)
    panels = [
        empty,
        filled,
        overlay,
        mark(empty, liquid),
        composite(empty, overlay, COPPER_SULFATE),
        composite(empty, overlay, IRON_CHLORIDE),
    ]

    columns = 3
    rows = (len(panels) + columns - 1) // columns
    cell = 16 * SCALE
    width = columns * cell + (columns + 1) * GAP
    height = rows * cell + (rows + 1) * GAP
    canvas = blank(width, height)

    for index, panel in enumerate(panels):
        column = index % columns
        row = index // columns
        paste(canvas, panel, GAP + column * (cell + GAP), GAP + row * (cell + GAP))

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    write_png(canvas, OUTPUT)
    print(f"wrote {OUTPUT} ({width}x{height})")
    print("panels: empty | water | derived region | region marked | tinted CuSO4 | tinted FeCl2")
    print(f"liquid region: {len(liquid)} pixels")


if __name__ == "__main__":
    main()
