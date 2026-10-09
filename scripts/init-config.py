#!/usr/bin/env python3
"""Initialise a PERSONAL deployment, without ever printing keys to stdout."""
import argparse
import os
import secrets
import stat
from pathlib import Path
from urllib.parse import urlsplit

root=Path(__file__).resolve().parent.parent
parser=argparse.ArgumentParser()
parser.add_argument("--url",required=True,help="https://YOUR_DOMAIN/finance (HTTPS, no trailing slash)")
parser.add_argument("--owner-id",type=int,default=1)
args=parser.parse_args()
url=args.url.rstrip("/")
parsed=urlsplit(url)
if parsed.scheme!="https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
    parser.error("Use a public HTTPS URL without credentials, query or fragment")
if parsed.path not in ("/finance",""):
    parser.error("The provided Nginx example currently supports only the /finance path")
if args.owner_id<1:parser.error("Owner ID must be positive")

folder=root/"secrets"
folder.mkdir(mode=0o700,parents=True,exist_ok=True)
folder.chmod(0o700)
backend=folder/"backend.env"
wallos=folder/"wallos-ledger.env"
if backend.exists() or wallos.exists():
    parser.error("One or both config files already exist: refusing to overwrite existing credentials")
ledger=secrets.token_urlsafe(48)
device=secrets.token_urlsafe(48)
admin=secrets.token_urlsafe(48)
content_backend="\n".join([
  f"FINANCE_PUBLIC_URL={url}",
  f"FINANCE_DEVICE_TOKEN={device}",
  f"FINANCE_ADMIN_TOKEN={admin}",
  f"FINANCE_WALLOS_LEDGER_TOKEN={ledger}",
  "FINANCE_TIMEZONE=Asia/Shanghai",
  "FINANCE_OAUTH_CALLBACK_HOSTS=chatgpt.com,chat.openai.com",
  f"FINANCE_WALLOS_OWNER_ID={args.owner_id}",
])+"\n"
content_ledger="\n".join([
  f"FINANCE_WALLOS_LEDGER_TOKEN={ledger}",
  "FINANCE_LEDGER_API_URL=http://finance-api:8767/api/v1/wallos-ledger",
  f"FINANCE_WALLOS_OWNER_ID={args.owner_id}",
  "FINANCE_TIMEZONE=Asia/Shanghai",
])+"\n"
try:
    # Exclusive, restrictive create. These files are git-ignored.
    for path,content in ((backend,content_backend),(wallos,content_ledger)):
        fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
        with os.fdopen(fd,"w") as f:
            f.write(content)
        os.chmod(path,stat.S_IRUSR|stat.S_IWUSR)
except BaseException:
    backend.unlink(missing_ok=True)
    wallos.unlink(missing_ok=True)
    raise
print("Created git-ignored secrets/backend.env and secrets/wallos-ledger.env (0600).")
print("No token was printed. Do not copy secrets into issues, commits, or support chats.")
print("Set up HTTPS reverse proxy before connecting Android or ChatGPT.")
