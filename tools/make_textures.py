#!/usr/bin/env python3
"""Generate simple 16x16 placeholder textures for the Aetheris instruments.

No third-party dependencies: a minimal PNG (RGBA, no filter) encoder is inlined. These are
deliberately crude stand-ins so the items render as *something* until real art exists.

Usage: python3 tools/make_textures.py [--output <assets/aetheris/textures dir>]
"""

from __future__ import annotations

import argparse
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


def conduit() -> Canvas:
    c = Canvas()
    c.rect(0, 0, 15, 15, METAL)
    c.rect(0, 0, 15, 2, METAL_DARK)
    c.rect(0, 13, 15, 15, METAL_DARK)
    c.rect(4, 4, 11, 11, METAL_DARK)
    c.rect(6, 6, 9, 9, TRANSPARENT)
    return c


def main() -> int:
    here = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=here / "common/src/main/resources/assets/aetheris/textures/item",
    )
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    for name, canvas in {
        "test_tube": test_tube(),
        "beaker": beaker(),
        "glass_rod": glass_rod(),
        "conduit": conduit(),
    }.items():
        path = args.output / f"{name}.png"
        write_png(path, canvas.px)
        print("wrote", path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
