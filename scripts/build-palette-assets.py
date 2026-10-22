"""Convert pindou-color-data raw JSON into the compact asset format used by the app.

Input : assets/palette/raw/pindou-color-data-main/mard-{221,291}-github/colors.json
Output: app/src/main/assets/palette/mard{221,291}.json

Output schema (kept small on purpose, parsed with org.json on device):
{
  "id": "mard221",
  "title": "MARD 221",
  "count": 221,
  "groups": ["A", "B", ...],          # ordered as they appear in the source
  "colors": [{"code":"A1","hex":"#FAF5CD","g":"A"}, ...]
}

Usage: python scripts/build-palette-assets.py   (run from the project root)
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "assets", "palette", "raw", "pindou-color-data-main")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "palette")

SPECS = [
    ("mard-221-github", "mard221", "MARD 221"),
    ("mard-291-github", "mard291", "MARD 291"),
]


def convert(src_dir: str, out_id: str, title: str) -> dict:
    src = os.path.join(RAW, src_dir, "colors.json")
    with open(src, encoding="utf-8") as fh:
        data = json.load(fh)

    groups: list[str] = []
    colors = []
    for item in data["colors"]:
        code = item["code"]
        group = item.get("group") or code.rstrip("0123456789") or "?"
        rgb = item.get("rgb")
        if rgb:
            hex_value = "#%02X%02X%02X" % (rgb[0], rgb[1], rgb[2])
        else:  # fall back to the published hex when rgb is missing
            hex_value = item["hex"].upper()
        if group not in groups:
            groups.append(group)
        colors.append({"code": code, "hex": hex_value, "g": group})

    return {
        "id": out_id,
        "title": title,
        "count": len(colors),
        "groups": groups,
        "colors": colors,
    }


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    for src_dir, out_id, title in SPECS:
        payload = convert(src_dir, out_id, title)
        dst = os.path.join(OUT, out_id + ".json")
        with open(dst, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(payload, fh, ensure_ascii=False, separators=(",", ":"))
        size = os.path.getsize(dst)
        print(f"{dst}  colors={payload['count']}  groups={len(payload['groups'])}  {size} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
