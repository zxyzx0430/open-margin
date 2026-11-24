"""Tracks the last authenticated device sync for the setup wizard.

A marker file is written only after a sync request that authenticated with
X-Device-Token returns 200. It never contains the token; the wizard reads the
marker over loopback via GET /api/v1/device-status using X-Admin-Token.
"""
from __future__ import annotations

import json
import logging
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from config import DATA_DIR

logger = logging.getLogger("finance.device_status")

DEVICE_SEEN_FILE = Path(DATA_DIR) / "device-seen.json"

# The wizard's own self-test and QR check also send X-Device-Token, so the
# marker must additionally see a phone-like User-Agent.
DEVICE_UA_MARKERS = ("android", "dalvik", "okhttp", "cfnetwork", "openmargin")


def is_device_user_agent(user_agent: str | None) -> bool:
    text = (user_agent or "").lower()
    return any(marker in text for marker in DEVICE_UA_MARKERS)

# Endpoints that require X-Device-Token and represent a phone sync.
DEVICE_SYNC_PATHS = frozenset({
    "/api/v2/sync/changes",
    "/api/v2/sync/push",
    "/api/v2/sync/subscriptions",
    "/api/v2/sync/subscriptions/push",
    "/api/v1/transactions/batch",
    "/api/v1/transactions/correct-rounding",
})


def record_device_seen(device: str | None = None) -> None:
    """Best-effort atomic marker write. Never raises into the request path."""
    try:
        target = Path(DEVICE_SEEN_FILE)
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        payload: dict[str, str] = {
            "at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        }
        if device:
            payload["device"] = str(device)[:80]
        fd, tmp_name = tempfile.mkstemp(
            prefix=".device-seen-", suffix=".tmp", dir=str(target.parent)
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(payload, handle)
            os.replace(tmp_name, target)
        except Exception:
            try:
                os.unlink(tmp_name)
            except OSError:
                pass
            raise
    except Exception:
        logger.warning("device-seen marker write failed", exc_info=False)


def read_device_seen() -> dict | None:
    try:
        data = json.loads(Path(DEVICE_SEEN_FILE).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(data, dict):
        return None
    at = data.get("at")
    if not isinstance(at, str) or not at:
        return None
    device = data.get("device")
    return {"at": at, "device": device if isinstance(device, str) else None}
