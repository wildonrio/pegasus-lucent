#!/usr/bin/env python3
"""Render readable contact sheets for manual wallpaper source verification."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--title-filter", default="")
    parser.add_argument("--audit", type=Path)
    parser.add_argument("--metadata-contains", default="")
    parser.add_argument("--vision", type=Path)
    parser.add_argument("--classification", default="")
    parser.add_argument("--maximum-score", type=float)
    parser.add_argument("--page-size", type=int, default=16)
    args = parser.parse_args()
    with args.report.open(encoding="utf-8", newline="") as stream:
        rows = [row for row in csv.DictReader(stream, delimiter="\t")
                if row.get("status") == "built" and row.get("output") and
                Path(row["output"]).is_file()]
    if args.title_filter:
        wanted = {line.strip() for line in Path(args.title_filter).read_text(encoding="utf-8").splitlines()
                  if line.strip()}
        rows = [row for row in rows if row.get("rom_path") in wanted]
    if args.audit and args.metadata_contains:
        with args.audit.open(encoding="utf-8", newline="") as stream:
            wanted = {row["file"] for row in csv.DictReader(stream, delimiter="\t")
                      if args.metadata_contains in row.get("metadata", "")}
        rows = [row for row in rows if row.get("rom_path") in wanted]
    if args.vision:
        with args.vision.open(encoding="utf-8", newline="") as stream:
            vision = {row["rom_path"]: row for row in csv.DictReader(stream, delimiter="\t")}
        if args.classification:
            rows = [row for row in rows if
                    vision.get(row.get("rom_path", ""), {}).get("classification") ==
                    args.classification]
        if args.maximum_score is not None:
            rows = [row for row in rows if
                    float(vision.get(row.get("rom_path", ""), {}).get(
                        "score_key-art") or 0) < args.maximum_score]
    font_path = Path("/System/Library/Fonts/Supplemental/Arial Bold.ttf")
    font = ImageFont.truetype(str(font_path), 23) if font_path.exists() else ImageFont.load_default()
    args.output.mkdir(parents=True, exist_ok=True)
    for offset in range(0, len(rows), args.page_size):
        page = rows[offset:offset + args.page_size]
        sheet = Image.new("RGB", (1920, 1080), "#090b0f")
        draw = ImageDraw.Draw(sheet)
        for local_index, row in enumerate(page):
            column = local_index % 4
            line = local_index // 4
            x = column * 480
            y = line * 270
            with Image.open(row["output"]) as source:
                thumb = ImageOps.fit(source.convert("RGB"), (464, 214), Image.Resampling.LANCZOS)
            sheet.paste(thumb, (x + 8, y + 8))
            label = f"{offset + local_index + 1}. {row.get('system','')} | {row.get('title','')}"
            draw.text((x + 9, y + 228), label, font=font, fill="#ffffff",
                      stroke_width=2, stroke_fill="#000000")
        sheet.save(args.output / f"page-{offset // args.page_size + 1:03d}.jpg",
                   "JPEG", quality=92, subsampling=0)
    print(f"rows: {len(rows)}; pages: {(len(rows) + args.page_size - 1) // args.page_size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
