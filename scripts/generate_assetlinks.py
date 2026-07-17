#!/usr/bin/env python3
"""Generate Digital Asset Links JSON from release keystore or signed APK."""
from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

PACKAGE_NAME = "ir.vinor.app"
ROOT = Path(__file__).resolve().parent.parent


def _run(cmd: list[str]) -> str:
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        return (p.stdout or "") + (p.stderr or "")
    except Exception as e:
        return str(e)


def _sha_from_text(text: str) -> str | None:
    m = re.search(r"SHA256:\s*([0-9A-Fa-f:]+)", text)
    return m.group(1).upper() if m else None


def write_assetlinks(sha256: str) -> None:
    fp = sha256.strip().upper()
    if not re.fullmatch(r"[0-9A-F:]+", fp):
        raise SystemExit(f"Invalid SHA-256 fingerprint: {fp}")
    body = (
        "[\n"
        "  {\n"
        '    "relation": ["delegate_permission/common.handle_all_urls"],\n'
        '    "target": {\n'
        '      "namespace": "android_app",\n'
        f'      "package_name": "{PACKAGE_NAME}",\n'
        '      "sha256_cert_fingerprints": [\n'
        f'        "{fp}"\n'
        "      ]\n"
        "    }\n"
        "  }\n"
        "]\n"
    )
    targets = [
        ROOT / "well-known" / "assetlinks.json",
        ROOT.parent / "vinor" / "app" / "static" / ".well-known" / "assetlinks.json",
    ]
    for path in targets:
        if not path.parent.exists():
            if "vinor" in str(path):
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
        if not path.parent.exists():
            continue
        path.write_text(body, encoding="utf-8", newline="\n")
        print(f"Wrote {path}")
    print(f"SHA-256={fp}")


def sha_from_keystore() -> str | None:
    props_path = ROOT / "key.properties"
    if not props_path.is_file():
        return None
    props: dict[str, str] = {}
    for line in props_path.read_text(encoding="utf-8", errors="ignore").splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    store_file = props.get("storeFile") or ""
    store_password = props.get("storePassword") or ""
    key_alias = props.get("keyAlias") or "vinor_release"
    if not store_file or not store_password or store_password.startswith("CHANGE_ME"):
        return None
    store_path = Path(store_file)
    if not store_path.is_absolute():
        store_path = ROOT / store_path
    if not store_path.is_file():
        return None
    out = _run(
        [
            "keytool",
            "-list",
            "-v",
            "-keystore",
            str(store_path),
            "-alias",
            key_alias,
            "-storepass",
            store_password,
        ]
    )
    return _sha_from_text(out)


def sha_from_apk(apk_path: Path) -> str | None:
    if not apk_path.is_file():
        return None
    out = _run(["keytool", "-printcert", "-jarfile", str(apk_path)])
    sha = _sha_from_text(out)
    if sha:
        return sha
    try:
        with zipfile.ZipFile(apk_path) as zf, tempfile.TemporaryDirectory() as tmp:
            for name in zf.namelist():
                if re.match(r"META-INF/.*\.(RSA|DSA|EC)$", name, re.I):
                    dest = Path(tmp) / Path(name).name
                    dest.write_bytes(zf.read(name))
                    out2 = _run(["keytool", "-printcert", "-file", str(dest)])
                    return _sha_from_text(out2)
    except Exception:
        return None
    return None


def main() -> int:
    sha = sha_from_keystore()
    if sha:
        print("Source: keystore")
        write_assetlinks(sha)
        return 0

    candidates: list[Path] = []
    out_dir = ROOT / "app" / "build" / "outputs" / "apk"
    if out_dir.is_dir():
        candidates.extend(out_dir.rglob("*.apk"))
    uploads = ROOT.parent / "vinor" / "app" / "data" / "uploads" / "apk"
    if uploads.is_dir():
        candidates.extend(uploads.glob("*.apk"))
    # CI: also try app/build outputs release
    candidates = sorted(candidates, key=lambda p: p.stat().st_mtime, reverse=True)
    for apk in candidates:
        print(f"Source: APK {apk}")
        sha = sha_from_apk(apk)
        if sha:
            write_assetlinks(sha)
            return 0

    print(
        "Could not resolve release SHA-256 (keystore missing/placeholder and no signed APK found).",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
