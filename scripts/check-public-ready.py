#!/usr/bin/env python3
"""Pre-publication check; scans the exact files intended for a clean-root commit.

This is a conservative local gate, not a substitute for an external security
audit or a Git-history secret scan. Export to a NEW repo (no private history).
"""
from pathlib import Path
import re
import subprocess
import sys

root=Path(__file__).resolve().parent.parent
if (root/".git").exists():
    names=subprocess.check_output(["git","ls-files","-z"],cwd=root).decode().split("\0")
else:
    names=[str(p.relative_to(root)) for p in root.rglob("*") if p.is_file()]
blocked_path=[
    ".env","local.properties","oauth.sqlite3","finance.sqlite3",
    "release-password",".p12",".keystore",".jks",".pem",
]
allowed_samples={"backend/.env.example","secrets/backend.env.example",
                 "secrets/wallos-ledger.env.example",".env.example"}
bad=[]
for name in names:
    if not name:continue
    posix=name.replace("\\","/")
    if "__pycache__" in posix or posix.endswith(".pyc"):continue
    if any(posix.endswith(s) for s in blocked_path) and posix not in allowed_samples:
        bad.append(f"{posix}: likely credential or personal data")
        continue
    path=root/name
    if not path.is_file() or path.stat().st_size>1_000_000:continue
    if posix=="scripts/check-public-ready.py":continue  # scanner rules necessarily name sensitive markers
    try:text=path.read_text(encoding="utf-8")
    except UnicodeDecodeError:continue
    findings=[
        ("private key material",r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        ("personal VPS absolute path",r"/(?:opt|etc)/personal-finance-system/"),
        ("root-private credential path",r"/etc/personal-finance/"),
        ("private key PEM header",r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    ]
    for title,pattern in findings:
        if re.search(pattern,text,re.I):
            bad.append(f"{posix}: {title}")
        # Do not print any matching secret into logs.
for required in ("README.md","LICENSE","THIRD_PARTY.md","compose.yaml",
                 "backend/config.py","scripts/init-config.py",
                 "secrets/backend.env.example","secrets/wallos-ledger.env.example"):
    if not (root/required).exists():bad.append(f"Missing {required}")
if bad:
    print("NOT READY FOR PUBLICATION:",file=sys.stderr)
    for entry in bad:print(" - "+entry,file=sys.stderr)
    raise SystemExit(1)
print(f"PUBLICATION_STATIC_CHECK_OK ({len(names)} candidate files).")
print("Review license provenance, deployment and full Git history manually before changing visibility.")
