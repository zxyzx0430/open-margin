"""Deployment configuration shared by the API, web login and MCP.

Set FINANCE_PUBLIC_URL to the externally reachable HTTPS URL including any path
prefix (example: https://finance.example.com/finance).
"""
import os
from pathlib import Path
from urllib.parse import urlsplit

PUBLIC_BASE = os.getenv("FINANCE_PUBLIC_URL", "https://finance.example.com/finance").rstrip("/")
parsed = urlsplit(PUBLIC_BASE)
if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
    raise ValueError("FINANCE_PUBLIC_URL must be an HTTPS base URL without credentials")
PUBLIC_ORIGIN = f"{parsed.scheme}://{parsed.netloc}"
URL_PREFIX = parsed.path.rstrip("/")
PUBLIC_HOST = parsed.hostname
DATA_DIR = Path(os.getenv("FINANCE_DATA_DIR", "/data/finance"))
DB = Path(os.getenv("FINANCE_DB", str(DATA_DIR / "finance.sqlite3")))
OAUTH_DB = Path(os.getenv("FINANCE_OAUTH_DB", str(DATA_DIR / "oauth.sqlite3")))
WALLOS_DB = Path(os.getenv("FINANCE_WALLOS_DB", "/data/wallos/wallos.db"))
WALLOS_API_URL = os.getenv("WALLOS_API_URL", "http://wallos:80").rstrip("/")
LOCAL_TIMEZONE = os.getenv("FINANCE_TIMEZONE", "Asia/Shanghai")
ALLOWED_CALLBACK_HOSTS = frozenset(
    h.strip().lower() for h in os.getenv(
        "FINANCE_OAUTH_CALLBACK_HOSTS", "chatgpt.com,chat.openai.com"
    ).split(",") if h.strip()
)

# The ledger is deliberately SINGLE OWNER. Multi-user isolation is not implemented.
WALLOS_OWNER_ID = int(os.getenv("FINANCE_WALLOS_OWNER_ID", "1"))
