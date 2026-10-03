#!/usr/bin/env python3
"""Generate the 16x16 placeholder textures for the Aetheris instruments and the solution block.

No third-party dependencies: a minimal PNG (RGBA, no filter) encoder is inlined. These are
deliberately crude stand-ins so the content renders as *something* until real art exists.

Usage: python3 tools/make_textures.py [--output <assets/aetheris/textures dir>]
"""

from __future__ import annotations

import argparse
import math
import struct
import zlib
from pathlib import Path

W = H = 16
TRANSPARENT = (0, 0, 0, 0)


def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def write_png(path: Path, pixels: list[list[tuple[int, int, int, int]]]) -> None:
    raw = b"".join(
        b"\x00" + b"".join(bytes(px) for px in row) for row in pixels
    )
    header = struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0)
    data = (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", header)
        + _chunk(b"IDAT", zlib.compress(raw, 9))
        + _chunk(b"IEND", b"")
    )
    path.write_bytes(data)


class Canvas:
    def __init__(self) -> None:
        self.px = [[TRANSPARENT for _ in range(W)] for _ in range(H)]

    def rect(self, x0: int, y0: int, x1: int, y1: int, color) -> None:
        for y in range(max(0, y0), min(H, y1 + 1)):
            for x in range(max(0, x0), min(W, x1 + 1)):
                self.px[y][x] = color

    def disc(self, cx: int, cy: int, r: int, color) -> None:
        for y in range(H):
            for x in range(W):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    self.px[y][x] = color


GLASS = (198, 226, 240, 190)
GLASS_DARK = (120, 160, 185, 220)
HIGH = (255, 255, 255, 235)
METAL = (150, 152, 158, 255)
METAL_DARK = (92, 94, 100, 255)

# Goggle palette: a leather strap, a dark metal frame, and tinted lenses with a glint.
LEATHER = (120, 84, 52, 255)
FRAME = (74, 78, 86, 255)
LENS = (48, 92, 110, 220)
LENS_GLINT = (170, 220, 235, 235)


def test_tube() -> Canvas:
    c = Canvas()
    c.rect(6, 2, 9, 12, GLASS)
    c.rect(6, 13, 9, 13, GLASS_DARK)
    c.disc(7, 13, 2, GLASS_DARK)
    c.rect(6, 1, 9, 1, HIGH)
    c.rect(6, 2, 6, 12, HIGH)
    return c


def beaker() -> Canvas:
    c = Canvas()
    c.rect(4, 4, 11, 13, GLASS)
    c.rect(4, 13, 11, 13, GLASS_DARK)
    c.rect(4, 4, 11, 4, HIGH)
    c.rect(3, 4, 3, 6, GLASS_DARK)
    c.rect(12, 4, 12, 6, GLASS_DARK)
    return c


def glass_rod() -> Canvas:
    c = Canvas()
    c.rect(7, 1, 8, 14, GLASS)
    c.rect(7, 1, 7, 14, HIGH)
    c.rect(8, 12, 8, 14, GLASS_DARK)
    return c


def chemists_goggles() -> Canvas:
    """Bench goggles: two tinted lenses in a strap.

    Read as an item icon at 16x16, so it is deliberately two dark discs with a lighter frame rather than
    anything with recognisable detail - the silhouette is all that survives at this size.
    """
    c = Canvas()
    # Strap across the middle and around the sides.
    c.rect(1, 6, 14, 8, LEATHER)
    c.rect(1, 5, 2, 9, LEATHER)
    c.rect(13, 5, 14, 9, LEATHER)
    # Frame, then the lenses inside it.
    c.rect(2, 5, 7, 11, FRAME)
    c.rect(8, 5, 13, 11, FRAME)
    c.rect(3, 6, 6, 10, LENS)
    c.rect(9, 6, 12, 10, LENS)
    # A highlight on each lens, which is what stops them reading as flat holes.
    c.rect(3, 6, 4, 7, LENS_GLINT)
    c.rect(9, 6, 10, 7, LENS_GLINT)
    return c


def conduit() -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, METAL)
    c.rect(0, 0, 15, 2, METAL_DARK)
    c.rect(0, 13, 15, 15, METAL_DARK)
    c.rect(4, 4, 11, 11, METAL_DARK)
    c.rect(6, 6, 9, 9, TRANSPARENT)
    return c


def solution() -> Canvas:
    """The solution surface: near-white, semi-transparent, faintly rippled.

    Deliberately colourless. At render time this texture is multiplied by the live solution colour
    (`SolutionTint` reads it out of the block entity), so any hue baked in here would fight the
    chemistry - a blue solution would come out muddy. The alpha below 255 is what lets the
    translucent render layer show depth instead of a flat painted cube, and the ripples only give
    the surface something to catch light on.
    """
    c = Canvas()
    for y in range(H):
        for x in range(W):
            ripple = math.sin((x + y * 0.7) * 0.85) + math.sin((x * 0.45 - y) * 1.25)
            shade = int(round(4.0 * ripple))
            c.px[y][x] = (
                clamp(246 + shade),
                clamp(248 + shade),
                clamp(252 + shade),
                SURFACE_ALPHA,
            )
    return c


# Alpha for the fluid textures. Vanilla water's own textures are translucent and the fluid renderer blends
# them over whatever is behind, so an opaque pair would look like painted glass - but the first pair here was
# translucent enough to read as frosted glass, which is worse: a colourless solution came out as an opaque
# white block rather than as liquid. Roughly half opacity leaves the background visible through a full cell
# while still showing the liquid's own surface.
SURFACE_ALPHA = 120

FLUID_ALPHA = 118


def _fluid_shade(x: int, y: int, angle: float, period: float, amplitude: float) -> int:
    """A shallow wave running at [angle] radians, tileable across the 16x16 texture.

    Sinusoids whose period divides the texture size wrap seamlessly, which matters for a fluid: the
    renderer tiles these across a whole surface, and a seam would show as a grid.
    """
    along = x * math.cos(angle) + y * math.sin(angle)
    across = -x * math.sin(angle) + y * math.cos(angle)
    wave = math.sin(along * 2.0 * math.pi / period) * math.cos(across * 2.0 * math.pi / (period * 2))
    return int(round(amplitude * wave))


def solution_still() -> Canvas:
    """Still fluid texture: the surface of a solution that is not moving."""
    c = Canvas()
    for y in range(H):
        for x in range(W):
            shade = _fluid_shade(x, y, 0.5, 16.0, 5.0) + _fluid_shade(x, y, 1.9, 8.0, 3.0)
            c.px[y][x] = (clamp(244 + shade), clamp(247 + shade), clamp(252 + shade), FLUID_ALPHA)
    return c


def solution_flowing() -> Canvas:
    """Flowing fluid texture: streaks along the direction of travel, as vanilla's flowing texture has."""
    c = Canvas()
    for y in range(H):
        for x in range(W):
            streak = _fluid_shade(x, y, 0.28, 16.0, 9.0) + _fluid_shade(x, y, 1.4, 5.33, 3.0)
            c.px[y][x] = (clamp(240 + streak), clamp(244 + streak), clamp(250 + streak), FLUID_ALPHA)
    return c


def clamp(value: int) -> int:
    return max(0, min(255, value))


def main() -> int:
    here = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=here / "common/src/main/resources/assets/aetheris/textures",
        help="the assets/aetheris/textures directory; 'item' and 'block' are written beneath it",
    )
    args = parser.parse_args()

    targets = {
        "item": {
            "test_tube": test_tube(),
            "beaker": beaker(),
            "glass_rod": glass_rod(),
            "conduit": conduit(),
            "chemists_goggles": chemists_goggles(),
        },
        "block": {
            "solution": solution(),
            "solution_still": solution_still(),
            "solution_flowing": solution_flowing(),
        },
    }

    for folder, canvases in targets.items():
        out = args.output / folder
        out.mkdir(parents=True, exist_ok=True)
        for name, canvas in canvases.items():
            path = out / f"{name}.png"
            write_png(path, canvas.px)
            print("wrote", path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
