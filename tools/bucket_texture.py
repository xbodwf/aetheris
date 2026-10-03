"""Read a Minecraft item texture and work out where the liquid sits inside a bucket.

Two reasons this exists rather than a hand-drawn overlay:

  * the liquid has to line up with the bucket's interior pixel for pixel, and guessing gets it wrong by one
    or two pixels at 16x16, which is very visible;
  * the game already ships the answer. `item/water_bucket.png` is the same bucket with water in it, so the
    pixels that differ from empty `item/bucket.png` *are* the liquid region. That is exact, and it stays
    exact if the art is ever revised.

The output, `solution_bucket_content.png`, is a near-white shape with a little vertical shading. It is drawn
white on purpose: the item colour provider multiplies the solution's own colour into it, so a bucket of
copper sulfate comes out blue and a bucket of iron(II) chloride comes out green without a texture per
substance.
"""

from __future__ import annotations

import struct
import sys
import zipfile
import zlib
from pathlib import Path

VANILLA_JAR = (
    r".gradle\loom-cache\minecraftMaven\net\minecraft"
    r"\minecraft-merged-b4d93de455\1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2"
    r"\minecraft-merged-b4d93de455-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar"
)
EMPTY_ENTRY = "assets/minecraft/textures/item/bucket.png"
FILLED_ENTRY = "assets/minecraft/textures/item/water_bucket.png"
OUTPUT = Path("common/src/main/resources/assets/aetheris/textures/item/solution_bucket_content.png")


class Image:
    def __init__(self, width: int, height: int, pixels: list[list[tuple[int, int, int, int]]]):
        self.width = width
        self.height = height
        self.pixels = pixels

    def at(self, x: int, y: int) -> tuple[int, int, int, int]:
        return self.pixels[y][x]


def decode_png(data: bytes) -> Image:
    """Decode 8-bit RGB/RGBA or 4/8-bit palette PNGs, without external libraries."""
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("not a PNG")

    pos = 8
    idat = bytearray()
    palette: list[tuple[int, int, int]] = []
    alpha_table: bytes | None = None
    width = height = depth = colour_type = 0

    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos : pos + 4])
        tag = data[pos + 4 : pos + 8]
        body = data[pos + 8 : pos + 8 + length]
        pos += 12 + length

        if tag == b"IHDR":
            width, height, depth, colour_type, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if interlace:
                raise ValueError("interlaced PNGs are not supported")
        elif tag == b"PLTE":
            palette = [tuple(body[i : i + 3]) for i in range(0, len(body), 3)]
        elif tag == b"tRNS":
            alpha_table = body
        elif tag == b"IDAT":
            idat += body
        elif tag == b"IEND":
            break

    raw = zlib.decompress(bytes(idat))

    if colour_type == 6:
        channels, palette_bits = 4, 0
    elif colour_type == 2:
        channels, palette_bits = 3, 0
    elif colour_type == 3:
        channels, palette_bits = 0, depth
    else:
        raise ValueError(f"unsupported colour type {colour_type}")

    if palette_bits:
        bits_per_pixel = palette_bits
    else:
        bits_per_pixel = channels * depth

    stride = (width * bits_per_pixel + 7) // 8
    filter_bpp = max(1, bits_per_pixel // 8)

    rows: list[bytearray] = []
    previous = bytearray(stride)
    p = 0
    for _ in range(height):
        filter_type = raw[p]
        p += 1
        line = bytearray(raw[p : p + stride])
        p += stride

        for i in range(stride):
            left = line[i - filter_bpp] if i >= filter_bpp else 0
            up = previous[i]
            up_left = previous[i - filter_bpp] if i >= filter_bpp else 0
            if filter_type == 1:
                line[i] = (line[i] + left) & 0xFF
            elif filter_type == 2:
                line[i] = (line[i] + up) & 0xFF
            elif filter_type == 3:
                line[i] = (line[i] + ((left + up) >> 1)) & 0xFF
            elif filter_type == 4:
                estimate = left + up - up_left
                da, db, dc = abs(estimate - left), abs(estimate - up), abs(estimate - up_left)
                nearest = left if (da <= db and da <= dc) else (up if db <= dc else up_left)
                line[i] = (line[i] + nearest) & 0xFF
        rows.append(line)
        previous = line

    pixels = []
    for y in range(height):
        row = []
        for x in range(width):
            if palette_bits:
                if palette_bits == 8:
                    index = rows[y][x]
                elif palette_bits == 4:
                    index = (rows[y][x // 2] >> (4 if x % 2 == 0 else 0)) & 0x0F
                else:
                    raise ValueError(f"unsupported palette depth {palette_bits}")
                if index >= len(palette):
                    row.append((0, 0, 0, 0))
                    continue
                r, g, b = palette[index]
                a = alpha_table[index] if alpha_table and index < len(alpha_table) else 255
                row.append((r, g, b, a))
            else:
                offset = x * channels
                if channels == 4:
                    r, g, b, a = rows[y][offset : offset + 4]
                else:
                    r, g, b = rows[y][offset : offset + 3]
                    a = 255
                row.append((r, g, b, a))
        pixels.append(row)

    return Image(width, height, pixels)


def load_from_jar(entry: str) -> Image:
    with zipfile.ZipFile(VANILLA_JAR) as archive:
        return decode_png(archive.read(entry))


def load_from_path(path: str) -> Image:
    return decode_png(Path(path).read_bytes())


def render_map(image: Image, liquid: set[tuple[int, int]]) -> str:
    lines = []
    for y in range(image.height):
        line = ""
        for x in range(image.width):
            r, g, b, a = image.at(x, y)
            if a == 0:
                line += "."
            elif (x, y) in liquid:
                line += "~"
            else:
                line += "#" if (r + g + b) < 340 else "+"
        lines.append(f"{y:2d} {line}")
    return "\n".join(lines)


def main() -> None:
    empty = load_from_jar(EMPTY_ENTRY)
    filled = load_from_jar(FILLED_ENTRY)
    if (empty.width, empty.height) != (filled.width, filled.height):
        raise SystemExit("bucket and water bucket disagree on size")

    # The liquid is whatever the filled texture has that the empty one does not: the bucket shape is
    # identical, so the difference is exactly the water.
    liquid = set()
    for y in range(empty.height):
        for x in range(empty.width):
            if empty.at(x, y) == filled.at(x, y):
                continue
            if filled.at(x, y)[3] > 0:
                liquid.add((x, y))

    if not liquid:
        raise SystemExit("found no liquid pixels; the comparison assumption is wrong")

    xs = [x for x, _ in liquid]
    ys = [y for _, y in liquid]
    print("empty bucket:")
    print(render_map(empty, set()))
    print()
    print("water bucket, '~' marking the liquid region:")
    print(render_map(filled, liquid))
    print()
    print(f"liquid region: x {min(xs)}..{max(xs)}  y {min(ys)}..{max(ys)}  ({len(liquid)} pixels)")

    # Optional: a downloaded reference image, so the derived region can be eyeballed against the real art.
    if len(sys.argv) > 1:
        for path in sys.argv[1:]:
            other = load_from_path(path)
            print()
            print(f"{path}: {other.width}x{other.height}")
            print(render_map(other, set()))

    top, bottom = min(ys), max(ys)
    span = max(1, bottom - top)
    out = Image(empty.width, empty.height, [[(0, 0, 0, 0)] * empty.width for _ in range(empty.height)])
    for y in range(empty.height):
        for x in range(empty.width):
            if (x, y) not in liquid:
                continue
            # Near-white with a little vertical shading: bright at the surface, deeper below. The colour
            # provider multiplies the solution's colour through this, so the shading has to live in the
            # brightness, not the hue.
            depth = (y - top) / span
            level = int(236 - 62 * depth)
            out.pixels[y][x] = (level, level, level, 255)

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    write_png(out, OUTPUT)
    print(f"\nwrote {OUTPUT}")


def write_png(image: Image, path: Path) -> None:
    raw = bytearray()
    for y in range(image.height):
        raw.append(0)
        for x in range(image.width):
            raw.extend(image.pixels[y][x])

    def chunk(tag: bytes, body: bytes) -> bytes:
        return (
            struct.pack(">I", len(body))
            + tag
            + body
            + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", image.width, image.height, 8, 6, 0, 0, 0)
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


if __name__ == "__main__":
    main()
