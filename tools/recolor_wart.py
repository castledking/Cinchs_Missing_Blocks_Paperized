#!/usr/bin/env python3
"""Generate the warped nether wart texture set from the vanilla nether wart art.

The vanilla nether wart palette is a red ramp: a shadow tone, a body tone and one
or two highlight tones. A warped crop is the same ramp rotated into the warped
blue/teal band, so we do not recolour per-pixel by a hue rotation (that wrecks the
hand-painted shading). Instead we build one shared source ramp across every
texture, sort it by luminance, and map each rank onto a target ramp of the same
length. Every texture therefore keeps identical relative shading, and the crop
stages stay consistent with the item and with warped_wart_block.

Usage:
    python3 tools/recolor_wart.py --variant warped --out out/
    python3 tools/recolor_wart.py --preview out/preview.png
"""

from __future__ import annotations

import argparse
import colorsys
import json
import pathlib
import sys

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
SOURCE_DIR = HERE.parent / "netherwart" / "source"

# Textures that make up the vanilla crop, in growth order. stage0 is the small sprout
# a freshly planted wart shows (44 pixels in vanilla, not empty).
SOURCES = [
    ("nether_wart.png", "item"),
    ("nether_wart_stage0.png", "stage_0"),
    ("nether_wart_stage1.png", "stage_1"),
    ("nether_wart_stage2.png", "stage_2"),
]

REFERENCE = "warped_wart_block.png"

# Target ramps, darkest to brightest. Each is a full ramp so that the crop reads
# as the warped counterpart of nether wart rather than as a flat recolour.
RAMPS = {
    # Straight from the warped warped-nylium block art: teal leaning green.
    "warped": [
        (0x18, 0x5C, 0x62),
        (0x17, 0x77, 0x7C),
        (0x16, 0x94, 0x93),
        (0x14, 0xB4, 0x9E),
        (0x2A, 0xD2, 0xB4),
    ],
    # Teal leaning blue. Reads as "warped" but clearly not nether.
    "azure": [
        (0x14, 0x52, 0x63),
        (0x13, 0x6E, 0x87),
        (0x12, 0x8C, 0xAE),
        (0x14, 0xAD, 0xD2),
        (0x3C, 0xD2, 0xEE),
    ],
    # The blue the request asked for: saturated sky blue with a cyan core.
    "blue": [
        (0x11, 0x44, 0x66),
        (0x10, 0x5F, 0x94),
        (0x0F, 0x7E, 0xC4),
        (0x18, 0xA1, 0xE6),
        (0x4A, 0xCB, 0xFF),
    ],
    # Cold and pale, for a sickly/icy read.
    "ice": [
        (0x1B, 0x3E, 0x50),
        (0x1D, 0x57, 0x71),
        (0x1F, 0x74, 0x99),
        (0x27, 0x96, 0xC2),
        (0x62, 0xC2, 0xE8),
    ],
    # Deep violet-blue, closest to nether wart's darkness.
    "void": [
        (0x1A, 0x2C, 0x52),
        (0x1E, 0x3B, 0x73),
        (0x22, 0x4F, 0x99),
        (0x2C, 0x6C, 0xC4),
        (0x5E, 0x9C, 0xF0),
    ],
}


def luminance(color: tuple[int, int, int]) -> float:
    r, g, b = (c / 255.0 for c in color)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def saturate(color: tuple[int, int, int], boost: float = 1.0) -> tuple[int, int, int]:
    r, g, b = (c / 255.0 for c in color)
    h, s, v = colorsys.rgb_to_hsv(r, g, b)
    s = min(1.0, s * boost)
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    return (round(r * 255), round(g * 255), round(b * 255))


def load(path: pathlib.Path) -> Image.Image:
    return Image.open(path).convert("RGBA")


def opaque_palette(images: list[Image.Image]) -> list[tuple[int, int, int]]:
    """Every distinct fully-opaque colour across the given images.

    Returned in ascending luminance order, which is the order the ramp is mapped
    in. Near-duplicates are collapsed so that tiny authoring differences between
    the item and the stages do not consume two ramp slots.
    """
    seen: list[tuple[int, int, int]] = []
    for image in images:
        for r, g, b, a in image.get_flattened_data():
            if a != 255:
                continue
            color = (r, g, b)
            if any(abs(color[0] - c[0]) <= 6 and abs(color[1] - c[1]) <= 6 and abs(color[2] - c[2]) <= 6 for c in seen):
                continue
            seen.append(color)
    return sorted(seen, key=luminance)


def build_lut(source_palette: list[tuple[int, int, int]], ramp: list[tuple[int, int, int]],
              boost: float) -> dict[tuple[int, int, int], tuple[int, int, int]]:
    """Map each source colour onto the ramp position with the matching rank.

    The ramp is resampled to the source palette length so that any ramp length
    works, and each target is optionally saturation-boosted.
    """
    lut: dict[tuple[int, int, int], tuple[int, int, int]] = {}
    n = len(source_palette)
    for i, color in enumerate(source_palette):
        # Rank in [0, 1]; centre of the source bucket, so the darkest and
        # brightest colours do not both collapse onto the ramp's endpoints.
        t = 0.0 if n == 1 else (i / (n - 1)) * (len(ramp) - 1)
        lo = int(t)
        hi = min(lo + 1, len(ramp) - 1)
        f = t - lo
        blended = tuple(round(ramp[lo][c] + (ramp[hi][c] - ramp[lo][c]) * f) for c in range(3))
        lut[color] = saturate(blended, boost)
    return lut


def recolor(image: Image.Image, lut: dict[tuple[int, int, int], tuple[int, int, int]]) -> Image.Image:
    out = Image.new("RGBA", image.size)
    pixels = []
    for r, g, b, a in image.get_flattened_data():
        if a == 0:
            pixels.append((0, 0, 0, 0))
        elif a != 255:
            # Partial coverage: keep the coverage, use the nearest opaque ramp
            # colour so antialiased edges keep their original tint.
            base = min(lut, key=lambda c: abs(c[0] - r) + abs(c[1] - g) + abs(c[2] - b))
            nr, ng, nb = lut[base]
            pixels.append((nr, ng, nb, a))
        else:
            nr, ng, nb = lut[(r, g, b)]
            pixels.append((nr, ng, nb, a))
    out.putdata(pixels)
    return out


def scale(image: Image.Image, factor: int) -> Image.Image:
    return image.resize((image.width * factor, image.height * factor), Image.NEAREST)


def preview(groups: list[tuple[str, list[tuple[str, Image.Image]]]], out: pathlib.Path,
            factor: int = 6) -> None:
    """Lay the generated set out as a labelled contact sheet.

    ``groups`` is a list of (heading, [(label, image), ...]) so that each variant
    gets one row and the textures within it are shown side by side.
    """
    from PIL import ImageDraw

    gap, label_h, head_h = 6, 10, 14
    scaled = [[(label, scale(img, factor)) for label, img in items] for _, items in groups]
    cols = max(len(row) for row in scaled) or 1
    cell_w = max((t.width for row in scaled for _, t in row), default=16) + gap
    cell_h = max((t.height for row in scaled for _, t in row), default=16) + gap + label_h

    width = cols * cell_w + gap
    height = sum(head_h + cell_h for _ in groups) + gap
    sheet = Image.new("RGBA", (width, height), (28, 28, 32, 255))
    draw = ImageDraw.Draw(sheet)

    y = gap
    for (heading, _), row in zip(groups, scaled):
        draw.text((gap, y), heading, fill=(240, 240, 245, 255))
        y += head_h
        for col, (label, tile) in enumerate(row):
            x = gap + col * cell_w
            sheet.alpha_composite(tile, (x, y + label_h))
            draw.rectangle([x - 1, y + label_h - 1, x + tile.width, y + label_h + tile.height],
                           outline=(72, 72, 84, 255))
            draw.text((x, y), label.replace("warped_nether_wart", "wart").replace(".png", ""),
                      fill=(198, 198, 206, 255))
        y += cell_h

    out.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)
    print(f"preview -> {out}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--variant", choices=sorted(RAMPS), default="warped",
                        help="colour ramp to use; 'warped' matches warped_wart_block.png")
    parser.add_argument("--boost", type=float, default=1.0, help="saturation multiplier for target colours")
    parser.add_argument("--out", type=pathlib.Path, default=HERE.parent / "build" / "textures")
    parser.add_argument("--preview", type=pathlib.Path, help="also write a contact sheet here")
    parser.add_argument("--preview-all", type=pathlib.Path,
                        help="write one contact sheet comparing every ramp variant")
    parser.add_argument("--dump-palette", type=pathlib.Path, help="write the source/target palette as JSON")
    args = parser.parse_args()

    missing = [n for n, _ in SOURCES if not (SOURCE_DIR / n).is_file()]
    if missing:
        print(f"missing source textures in {SOURCE_DIR}: {', '.join(missing)}", file=sys.stderr)
        return 1

    images = [(name, role, load(SOURCE_DIR / name)) for name, role in SOURCES]
    palette = opaque_palette([img for _, _, img in images])
    reference = load(SOURCE_DIR / REFERENCE) if (SOURCE_DIR / REFERENCE).is_file() else None

    def build(variant: str) -> tuple[dict, list[tuple[str, Image.Image]]]:
        lut = build_lut(palette, RAMPS[variant], args.boost)
        produced = []
        for name, role, image in images:
            out_name = name.replace("nether_wart", "warped_nether_wart")
            result = recolor(image, lut)
            produced.append((role, result))
        empty = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        produced.append(("stage_0", empty))
        if reference is not None:
            produced.append(("reference", reference))
        return lut, produced

    if args.preview_all:
        groups = []
        for variant in RAMPS:
            _, produced = build(variant)
            groups.append((variant, produced))
        preview(groups, args.preview_all)
        return 0

    lut = build_lut(palette, RAMPS[args.variant], args.boost)
    args.out.mkdir(parents=True, exist_ok=True)

    for name, role, image in images:
        out_name = name.replace("nether_wart", "warped_nether_wart")
        recolor(image, lut).save(args.out / out_name)
        print(f"{out_name:<34} {role:<10} {len(palette)} ramp stops")

    if args.dump_palette:
        args.dump_palette.parent.mkdir(parents=True, exist_ok=True)
        args.dump_palette.write_text(json.dumps({
            "variant": args.variant,
            "source_palette": [list(c) for c in palette],
            "target_ramp": [list(c) for c in RAMPS[args.variant]],
            "lut": {",".join(map(str, k)): list(v) for k, v in lut.items()},
        }, indent=2) + "\n")
        print(f"palette -> {args.dump_palette}")

    if args.preview:
        _, produced = build(args.variant)
        preview([(args.variant, produced)], args.preview)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
