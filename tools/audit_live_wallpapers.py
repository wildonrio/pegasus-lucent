#!/usr/bin/env python3
"""Audit the exact wallpapers referenced by a live Pegasus metadata snapshot.

The audit is path-exact.  It never guesses that a similarly named game shares
artwork, and it treats a missing file, a blank field, and reused cross-title art
as separate failures.
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter, defaultdict
import json
from pathlib import Path
import subprocess

from PIL import Image


ADB = Path("/Users/tyleryoung/.codex/tools/android-platform-tools/adb")
SERIAL = "427c87b2"


def parse_metadata(directory: Path) -> list[dict[str, str]]:
    games: list[dict[str, str]] = []
    for metadata in sorted(directory.glob("*.metadata.pegasus.txt")):
        system = metadata.name
        current: dict[str, str] | None = None
        for line in metadata.read_text(encoding="utf-8", errors="replace").splitlines() + ["game: __end__"]:
            if line.startswith("game: "):
                if current and current.get("file"):
                    games.append(current)
                current = {
                    "title": line.removeprefix("game: ").strip(),
                    "metadata": metadata.name,
                    "system": system,
                }
            elif current is not None and line.startswith("file: "):
                current["file"] = line.removeprefix("file: ").strip()
            elif current is not None and line.startswith("assets.background: "):
                current["background"] = line.removeprefix("assets.background: ").strip()
            elif current is not None and line.startswith("assets.boxFront: "):
                current["box_front"] = line.removeprefix("assets.boxFront: ").strip()
    # Pegasus merges the three roots, but identical ROM paths represent one
    # library game. Prefer the richest exact-path stanza.
    richest: dict[str, dict[str, str]] = {}
    for game in games:
        previous = richest.get(game["file"])
        quality = int(bool(game.get("background"))) + int(bool(game.get("box_front")))
        previous_quality = (int(bool(previous.get("background"))) +
                            int(bool(previous.get("box_front")))) if previous else -1
        if quality > previous_quality:
            richest[game["file"]] = game
    return list(richest.values())


def device_files() -> set[str]:
    command = (
        "find /storage/6432-6661/PegasusMedia /storage/emulated/0/PegasusMedia "
        "/storage/emulated/0/pegasus-frontend -type f 2>/dev/null"
    )
    result = subprocess.run(
        [str(ADB), "-s", SERIAL, "shell", command],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode not in (0, 1) or not result.stdout.strip():
        raise RuntimeError(result.stderr.strip() or "device media inventory failed")
    return {line.strip() for line in result.stdout.splitlines() if line.strip()}


def local_candidate(device_path: str, roots: list[Path]) -> Path | None:
    marker = "/game-wallpapers/"
    if marker not in device_path:
        return None
    relative = Path(device_path.split(marker, 1)[1])
    for root in roots:
        candidate = root / relative
        if candidate.is_file():
            return candidate
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("metadata", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--local-art-root", action="append", type=Path, default=[])
    args = parser.parse_args()

    games = parse_metadata(args.metadata)
    files = device_files()
    rows: list[dict[str, str]] = []
    by_background: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    counts: Counter[str] = Counter()
    systems: dict[str, Counter[str]] = defaultdict(Counter)
    for game in games:
        background = game.get("background", "")
        reason = ""
        dimensions = ""
        if not background:
            reason = "missing-field"
        elif background == game.get("box_front", ""):
            reason = "background-is-box-path"
        elif background not in files:
            reason = "missing-device-file"
        else:
            candidate = local_candidate(background, args.local_art_root)
            if candidate:
                try:
                    with Image.open(candidate) as image:
                        dimensions = f"{image.width}x{image.height}"
                    if dimensions != "1920x1080":
                        reason = "not-1920x1080"
                except Exception as error:
                    reason = f"decode-error:{error}"
        status = reason or "present"
        row = {**game, "status": status, "dimensions": dimensions}
        rows.append(row)
        counts[status] += 1
        systems[game["system"]][status] += 1
        if background:
            by_background[(game["system"], background)].append(row)

    reused: list[dict[str, str]] = []
    for (_system, background), group in by_background.items():
        titles = {row["title"].casefold() for row in group}
        if len(titles) <= 1:
            continue
        for row in group:
            reused.append({**row, "status": "same-system-cross-title-reuse",
                           "reuse_count": str(len(group)), "background": background})
    counts["same-system-cross-title-reuse"] = len(reused)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    tsv = args.output.with_suffix(".tsv")
    fields = ("system", "title", "file", "metadata", "background", "box_front",
              "status", "dimensions", "reuse_count")
    with tsv.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, delimiter="\t",
                                lineterminator="\n", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(row for row in rows if row["status"] != "present")
        writer.writerows(reused)
    report = {
        "games": len(games),
        "counts": dict(counts),
        "systems": {system: dict(counter) for system, counter in sorted(systems.items())},
        "problem_report": str(tsv),
    }
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
