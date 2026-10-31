#!/usr/bin/env python3
"""
SMS Retriever 11-char hash — same algorithm as AppSignatureHelper.kt (ir.vinor.app).

Usage:
  keytool -exportcert -alias ALIAS -keystore KEY.jks -storepass PASS -file cert.der
  python3 scripts/sms_retriever_hash.py cert.der
"""
from __future__ import annotations

import base64
import hashlib
import sys
from pathlib import Path

PACKAGE_NAME = "ir.vinor.app"


def sms_retriever_hash_from_cert_der(cert_der: bytes) -> str:
    """Match Android Signature.toCharsString() + AppSignatureHelper.hash()."""
    # Android Signature.toCharsString() emits lowercase hex characters,
    # not the raw DER bytes. Using raw bytes produced an incompatible hash.
    signature = cert_der.hex()
    app_info = f"{PACKAGE_NAME} {signature}"
    digest = hashlib.sha256(app_info.encode("utf-8")).digest()[:9]
    return base64.b64encode(digest).decode("ascii")[:11]


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: sms_retriever_hash.py <cert.der>", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    if not path.is_file():
        print(f"File not found: {path}", file=sys.stderr)
        return 1
    cert_der = path.read_bytes()
    if not cert_der:
        print("Empty certificate file", file=sys.stderr)
        return 1
    h = sms_retriever_hash_from_cert_der(cert_der)
    print(h)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
