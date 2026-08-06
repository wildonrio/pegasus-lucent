#!/usr/bin/env python3
"""Find fake 16:9 canvases containing blurred padding or embedded cover art."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps


def background_titles(metadata: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for path in metadata.glob("*.metadata.pegasus.txt"):
        title = ""
        background = ""
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines() + ["game: __end__"]:
            if line.startswith("game: "):
                if background:
                    result[background] = title
                title = line.removeprefix("game: ").strip()
                background = ""
            elif line.startswith("assets.background: "):
                background = line.removeprefix("assets.background: ").strip()
    return result


def score(path: Path) -> tuple[float, float, int, int, int, float, float, str] | None:
    try:
        with Image.open(path) as image:
            pixels = np.asarray(image.convert("L").resize((160, 90)), dtype=np.float32)
    except Exception:
        return None
    horizontal = np.abs(np.diff(pixels, axis=1)).mean(axis=1)
    vertical = np.abs(np.diff(pixels, axis=0)).mean(axis=1)
    energy = horizontal.copy()
    energy[:-1] += vertical
    top = float(energy[3:20].mean())
    middle = float(energy[33:57].mean())
    bottom = float(energy[70:87].mean())
    detail_ratio = (top + bottom) / (2 * max(middle, 0.01))
    seams = np.abs(np.diff(pixels, axis=0)).mean(axis=1)
    top_seam = 12 + int(np.argmax(seams[12:38]))
    bottom_seam = 52 + int(np.argmax(seams[52:78]))
    symmetry = abs((top_seam + bottom_seam) - 89)
    seam_strength = float(
        (seams[top_seam] + seams[bottom_seam]) /
        (2 * max(float(seams.mean()), 0.01))
    )
    top_coverage = float(np.mean(np.abs(np.diff(pixels, axis=0))[top_seam] > 10))
    bottom_coverage = float(np.mean(np.abs(np.diff(pixels, axis=0))[bottom_seam] > 10))
    padded_canvas = middle > 4.5 and detail_ratio < 0.20
    paired_seams = detail_ratio < 0.55 and seam_strength > 4.0 and symmetry < 7
    kind = "embedded-or-padded-canvas" if padded_canvas else (
        "paired-horizontal-seams" if paired_seams else "")
    return (detail_ratio, seam_strength, symmetry, top_seam, bottom_seam,
            top_coverage, bottom_coverage, kind)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--art-root", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--contact-sheets", type=Path, required=True)
    parser.add_argument("--minimum-seam-coverage", type=float, default=0.0)
    args = parser.parse_args()
    titles = background_titles(args.metadata)
    rows: list[dict[str, str]] = []
    for path in args.art_root.rglob("*"):
        if not path.is_file():
            continue
        metrics = score(path)
        if not metrics:
            continue
        (detail_ratio, seam_strength, symmetry, top_seam, bottom_seam,
         top_coverage, bottom_coverage, kind) = metrics
        if not kind:
            continue
        if kind == "paired-horizontal-seams" and \
                min(top_coverage, bottom_coverage) < args.minimum_seam_coverage:
            continue
        relative = path.relative_to(args.art_root)
        device = "/storage/6432-6661/PegasusMedia/game-wallpapers/" + str(relative)
        rows.append({
            "title": titles.get(device, ""),
            "relative_path": str(relative),
            "local_path": str(path),
            "device_path": device,
            "problem": kind,
            "detail_ratio": f"{detail_ratio:.5f}",
            "seam_strength": f"{seam_strength:.5f}",
            "symmetry": str(symmetry),
            "top_seam": str(top_seam),
            "bottom_seam": str(bottom_seam),
            "top_seam_coverage": f"{top_coverage:.5f}",
            "bottom_seam_coverage": f"{bottom_coverage:.5f}",
        })
    rows.sort(key=lambda row: (float(row["detail_ratio"]), -float(row["seam_strength"])))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0].keys() if rows else ("title",),
                                delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)

    args.contact_sheets.mkdir(parents=True, exist_ok=True)
    font_path = Path("/System/Library/Fonts/Supplemental/Arial Bold.ttf")
    font = ImageFont.truetype(str(font_path), 20) if font_path.exists() else ImageFont.load_default()
    for offset in range(0, len(rows), 16):
        page = rows[offset:offset + 16]
        sheet = Image.new("RGB", (1920, 1080), "#090b0f")
        draw = ImageDraw.Draw(sheet)
        for local_index, row in enumerate(page):
            x = (local_index % 4) * 480
            y = (local_index // 4) * 270
            with Image.open(row["local_path"]) as source:
                thumb = ImageOps.fit(source.convert("RGB"), (464, 214), Image.Resampling.LANCZOS)
            sheet.paste(thumb, (x + 8, y + 8))
            label = f"{offset + local_index + 1}. {row['title']}"
            draw.text((x + 9, y + 228), label, font=font, fill="white",
                      stroke_width=2, stroke_fill="black")
        sheet.save(args.contact_sheets / f"page-{offset // 16 + 1:03d}.jpg",
                   "JPEG", quality=92, subsampling=0)
    print(f"likely blurred-letterbox candidates: {len(rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
