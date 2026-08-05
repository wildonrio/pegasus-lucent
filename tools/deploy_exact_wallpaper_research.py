#!/usr/bin/env python3
"""Deploy exact-title wallpaper research into a live Pegasus snapshot.

Only provider-labeled SteamGridDB heroes and TheGamesDB fanart are accepted.
Every source is rendered as a 1920x1080 crop without stretching by its source
pipeline before reaching this script. ROM files are never touched.
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path
import shutil
import subprocess


ADB = Path("/Users/tyleryoung/.codex/tools/android-platform-tools/adb")
SERIAL = "427c87b2"
DEVICE_ROOT = "/storage/6432-6661/PegasusMedia/game-wallpapers"
DEVICE_METADATA_ROOTS = (
    "/storage/emulated/0/pegasus-frontend",
    "/storage/emulated/0/pegasus-frontend/metadata-systems",
    "/storage/emulated/0/Android/data/org.pegasus_frontend.android/files/pegasus-frontend/metadata",
)


def read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def update_metadata(path: Path, replacements: dict[str, str]) -> bool:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    output: list[str] = []
    changed = False
    index = 0
    while index < len(lines):
        if not lines[index].startswith("game: "):
            output.append(lines[index])
            index += 1
            continue
        end = index + 1
        while end < len(lines) and not lines[end].startswith("game: "):
            end += 1
        block = lines[index:end]
        file_line = next((line for line in block if line.startswith("file: ")), "")
        rom_path = file_line.removeprefix("file: ").strip()
        replacement = replacements.get(rom_path)
        if replacement:
            filtered = [line for line in block if not line.startswith("assets.background: ")]
            asset_positions = [position for position, line in enumerate(filtered)
                               if line.startswith("assets.")]
            insert_at = asset_positions[-1] + 1 if asset_positions else len(filtered)
            filtered.insert(insert_at, "assets.background: " + replacement)
            changed = changed or filtered != block
            block = filtered
        output.extend(block)
        index = end
    if changed:
        path.write_text("\n".join(output).rstrip() + "\n", encoding="utf-8")
    return changed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--metadata-dir", type=Path, required=True)
    parser.add_argument("--missing-report", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--steamgriddb", type=Path, required=True)
    parser.add_argument("--steamgriddb-vision", type=Path, required=True)
    parser.add_argument("--thegamesdb", type=Path, required=True)
    parser.add_argument("--thegamesdb-vision", type=Path, required=True)
    parser.add_argument("--curated-approvals", type=Path)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    missing_rows = {row["file"]: row for row in read_tsv(args.missing_report)
                    if row.get("status") == "missing-field"}
    missing = set(missing_rows)
    manifest = {row["rom_path"]: row for row in read_tsv(args.manifest)}
    sgdb = {row["rom_path"]: row for row in read_tsv(args.steamgriddb)}
    tgdb = {row["rom_path"]: row for row in read_tsv(args.thegamesdb)}
    sgdb_vision = {row["rom_path"]: row for row in read_tsv(args.steamgriddb_vision)}
    tgdb_vision = {row["rom_path"]: row for row in read_tsv(args.thegamesdb_vision)}

    def approved(vision: dict[str, str], threshold: float) -> bool:
        return (vision.get("classification") == "key-art" and
                float(vision.get("score_key-art") or 0) >= threshold)

    curated: dict[tuple[str, str], str] = {}
    if args.curated_approvals:
        for row in read_tsv(args.curated_approvals):
            curated[(row.get("metadata", ""), row.get("title", ""))] = row.get("provider", "")

    selected: dict[str, tuple[Path, str, str]] = {}
    for rom_path in sorted(missing):
        row = manifest.get(rom_path)
        if not row:
            continue
        provider = ""
        source = Path()
        audit_row = missing_rows[rom_path]
        curated_provider = curated.get((audit_row.get("metadata", ""),
                                        audit_row.get("title", "")), "")
        # Exact title + exact platform fanart is strongest. SteamGridDB hero is
        # the secondary source, but is still an explicit wallpaper asset—not a
        # screenshot, cover, or generated composition.
        if (curated_provider == "thegamesdb" and
                tgdb.get(rom_path, {}).get("status") == "built"):
            provider = "thegamesdb-fanart-curated"
            source = Path(tgdb[rom_path]["output"])
        elif (curated_provider == "steamgriddb" and
              sgdb.get(rom_path, {}).get("status") == "built"):
            provider = "steamgriddb-hero-curated"
            source = Path(sgdb[rom_path]["output"])
        elif (tgdb.get(rom_path, {}).get("status") == "built" and
                approved(tgdb_vision.get(rom_path, {}), 0.30)):
            provider = "thegamesdb-fanart"
            source = Path(tgdb[rom_path]["output"])
        elif (sgdb.get(rom_path, {}).get("status") == "built" and
              approved(sgdb_vision.get(rom_path, {}), 0.48)):
            provider = "steamgriddb-hero"
            source = Path(sgdb[rom_path]["output"])
        if not provider or not source.is_file():
            continue
        selected[rom_path] = (source, row["device_output"], provider)

    providers: dict[str, int] = {}
    for _source, _device, provider in selected.values():
        providers[provider] = providers.get(provider, 0) + 1
    print(f"exact replacements ready: {len(selected)}")
    print(f"providers: {providers}")
    if not args.apply:
        return 0

    if args.stage.exists():
        shutil.rmtree(args.stage)
    args.stage.mkdir(parents=True)
    replacements: dict[str, str] = {}
    for rom_path, (source, device_path, _provider) in selected.items():
        relative = Path(device_path.split("/game-wallpapers/", 1)[1])
        destination = args.stage / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        replacements[rom_path] = device_path

    changed = [path for path in sorted(args.metadata_dir.glob("*.metadata.pegasus.txt"))
               if update_metadata(path, replacements)]
    subprocess.run([str(ADB), "-s", SERIAL, "push", str(args.stage) + "/.", DEVICE_ROOT],
                   check=True)
    for root in DEVICE_METADATA_ROOTS:
        for metadata in changed:
            subprocess.run([str(ADB), "-s", SERIAL, "push", str(metadata),
                            f"{root}/{metadata.name}"], check=True,
                           stdout=subprocess.DEVNULL)
    print(f"metadata files updated: {len(changed)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
