#!/usr/bin/env python3
"""PNG آیکن PWA — حذف پس‌زمینهٔ مشکی برای launcher/splash شفاف."""
from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC_DIR = ROOT.parent / "vinor" / "app" / "static" / "icons"
RES = ROOT / "app" / "src" / "main" / "res"


def to_transparent(path: Path) -> Image.Image:
    """Remove edge-connected black background; keep inner logo mark."""
    from collections import deque

    img = Image.open(path).convert("RGBA")
    px = img.load()
    w, h = img.size
    threshold = 10

    def is_outer_bg(x: int, y: int) -> bool:
        r, g, b, a = px[x, y]
        return a > 0 and r < threshold and g < threshold and b < threshold

    q: deque[tuple[int, int]] = deque()
    seen: set[tuple[int, int]] = set()
    for x in range(w):
        for y in (0, h - 1):
            if is_outer_bg(x, y):
                seen.add((x, y))
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_outer_bg(x, y) and (x, y) not in seen:
                seen.add((x, y))
                q.append((x, y))
    while q:
        x, y = q.popleft()
        px[x, y] = (0, 0, 0, 0)
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < w and 0 <= ny < h and (nx, ny) not in seen and is_outer_bg(nx, ny):
                seen.add((nx, ny))
                q.append((nx, ny))
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
