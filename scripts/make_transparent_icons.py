#!/usr/bin/env python3
"""PNG آیکن PWA — حذف پس‌زمینهٔ مشکی برای launcher/splash شفاف."""
from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = ROOT.parent / "vinor" / "app" / "static" / "icons"
RES = ROOT / "app" / "src" / "main" / "res"


def to_transparent(path: Path) -> Image.Image:
    img = Image.open(path).convert("RGBA")
    px = img.load()
    w, h = img.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if r < 24 and g < 24 and b < 24:
                px[x, y] = (r, g, b, 0)
    return img


def main() -> None:
    master_src = SRC_DIR / "icon-192.png"
    if not master_src.is_file():
        raise SystemExit(f"missing: {master_src}")

    master = to_transparent(master_src)
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    master.save(nodpi / "ic_pwa_logo.png")

    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for folder, size in sizes.items():
        src = SRC_DIR / f"icon-{size}.png"
        out_dir = RES / f"mipmap-{folder}"
        out_dir.mkdir(parents=True, exist_ok=True)
        if src.is_file():
            img = to_transparent(src)
        else:
            img = master.resize((size, size), Image.LANCZOS)
        img.save(out_dir / "ic_launcher.png")
        img.save(out_dir / "ic_launcher_round.png")
        print(f"{folder}: ok")

    print("done")


if __name__ == "__main__":
    main()
