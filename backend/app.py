"""Personal Finance Sync API. No raw SMS, card numbers or secrets in database."""
from __future__ import annotations
import hmac
import os
import sqlite3
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from pydantic import BaseModel, Field, field_validator

from config import DB, PUBLIC_BASE
DEVICE_TOKEN = os.getenv("FINANCE_DEVICE_TOKEN", "")
ADMIN_TOKEN = os.getenv("FINANCE_ADMIN_TOKEN", "")
app = FastAPI(title="Personal Finance API", version="1.3.0", docs_url=None, redoc_url=None)

class Event(BaseModel):
    event_id: str = Field(min_length=8, max_length=130, pattern=r"^[a-zA-Z0-9_.:-]+$")
    timestamp_ms: int = Field(gt=946684800000, lt=4102444800000)
    amount_cents: int = Field(ge=0, le=100_000_000_000)
    currency: str = Field(default="CNY", pattern=r"^[A-Z]{3}$")
    direction: Literal["EXPENSE","INCOME"]
    source: Literal["WECHAT","ALIPAY","BANK_SMS","MANUAL","OTHER"]
    category: str = Field(default="OTHER", max_length=45)
    merchant: str = Field(default="", max_length=140)
    # transaction account fields, SMS contents, card digits, OTP and notes intentionally omitted

class Batch(BaseModel):
    events: list[Event] = Field(min_length=1, max_length=100)

def auth(expected: str, supplied: str | None) -> None:
    if not expected or not supplied or not hmac.compare_digest(expected, supplied):
        raise HTTPException(status_code=401, detail="Unauthorized")

def device_auth(x_device_token: str | None = Header(default=None)):
    auth(DEVICE_TOKEN, x_device_token)

def admin_auth(request: Request, x_admin_token: str | None = Header(default=None)):
    if x_admin_token is not None:
        auth(ADMIN_TOKEN, x_admin_token)
        return
    from web_login import valid_session, origin_ok, COOKIE_NAME
    if not valid_session(request.cookies.get(COOKIE_NAME)):
        raise HTTPException(status_code=401, detail="Wallos sign-in required")
    if request.method in ("POST", "PUT", "PATCH", "DELETE") and not origin_ok(request):
        raise HTTPException(status_code=403, detail="Origin verification failed")

from web_login import router as dashboard_auth_router
app.include_router(dashboard_auth_router)

@contextmanager
def db_connection():
    DB.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    db = sqlite3.connect(str(DB), timeout=10)
    try:
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA busy_timeout=10000")
        db.execute("""CREATE TABLE IF NOT EXISTS transactions (
            id INTEGER PRIMARY KEY,
            event_id TEXT NOT NULL UNIQUE,
            timestamp_ms INTEGER NOT NULL,
            amount_cents INTEGER NOT NULL,
            currency TEXT NOT NULL,
            direction TEXT NOT NULL,
            source TEXT NOT NULL,
            category TEXT NOT NULL,
            merchant TEXT NOT NULL,
            review_status TEXT NOT NULL DEFAULT 'unverified',
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)""")
        db.execute("CREATE INDEX IF NOT EXISTS tx_timestamp ON transactions(timestamp_ms)")
        # The optional ledger epoch is a durable, DB-wide import boundary.
        # It stops offline Android retries and manual imports reintroducing
        # expenses from before the user's requested fresh start.
        db.execute("""CREATE TABLE IF NOT EXISTS ledger_settings (
            key TEXT PRIMARY KEY, value TEXT NOT NULL)""")
        db.execute("""CREATE TRIGGER IF NOT EXISTS transactions_start_date_guard
            BEFORE INSERT ON transactions
            WHEN EXISTS (
                SELECT 1 FROM ledger_settings
                WHERE key='start_timestamp_ms'
                  AND NEW.timestamp_ms < CAST(value AS INTEGER))
            BEGIN SELECT RAISE(IGNORE); END""")
        db.execute("""CREATE TABLE IF NOT EXISTS expense_budgets (
            month TEXT PRIMARY KEY, amount_cents INTEGER NOT NULL
            CHECK(amount_cents>0))""")
        db.commit()
        yield db
        db.commit()
    finally:
        db.close()

def ledger_start_ms(db: sqlite3.Connection) -> int:
    """Permanent date boundary for the user-requested fresh ledger."""
    row = db.execute(
        "SELECT value FROM ledger_settings WHERE key='start_timestamp_ms'"
    ).fetchone()
    return int(row[0]) if row is not None else 0


@app.get("/health")
def health():
    return {"ok": True, "service": "finance-api", "version": "1.3.0"}

@app.post("/api/v1/transactions/batch", dependencies=[Depends(device_auth)])
def ingest(batch: Batch):
    inserted, existed, ignored_before_start = 0, 0, 0
    with db_connection() as db:
        cutoff = ledger_start_ms(db)
        for e in batch.events:
            if e.timestamp_ms < cutoff:
                ignored_before_start += 1
                continue
            possible = db.execute("""SELECT id FROM transactions WHERE amount_cents=? AND currency=?
                  AND direction=? AND source<>? AND ABS(timestamp_ms-?)<=90000
                  AND review_status NOT IN ('excluded','transfer','refund') LIMIT 1""",
                  (e.amount_cents,e.currency,e.direction,e.source,e.timestamp_ms)).fetchone()
            status = "possible_duplicate" if possible else "unverified"
            cur = db.execute("""INSERT OR IGNORE INTO transactions
                (event_id,timestamp_ms,amount_cents,currency,direction,source,category,merchant,review_status)
                VALUES (?,?,?,?,?,?,?,?,?)""",
                (e.event_id,e.timestamp_ms,e.amount_cents,e.currency,e.direction,e.source,e.category,e.merchant,status))
            if cur.rowcount == 1:
                inserted += 1
                if possible:
                    db.execute("UPDATE transactions SET review_status='possible_duplicate' WHERE id=? AND review_status='unverified'",(possible['id'],))
            else:
                existed += 1
    return {"accepted": len(batch.events), "inserted": inserted,
            "already_present": existed, "ignored_before_start": ignored_before_start}

class AmountCorrection(BaseModel):
    event_id: str = Field(min_length=8, max_length=130, pattern=r"^android:[a-zA-Z0-9_.:-]+$")
    old_cents: int = Field(gt=0, le=100_000_000_000)
    corrected_cents: int = Field(gt=0, le=100_000_000_000)

@app.post("/api/v1/transactions/correct-rounding", dependencies=[Depends(device_auth)])
def correct_rounding(item: AmountCorrection):
    # A narrowly scoped legacy repair for amounts formerly truncated from
    # floating point: only one-cent discrepancies from auto-captured sources.
    # A retry is idempotent. Never silently modify manually reviewed records.
    if item.corrected_cents != item.old_cents + 1:
        raise HTTPException(status_code=422, detail="Only one-cent rounding repairs allowed")
    with db_connection() as db:
        row = db.execute("SELECT id,amount_cents,source,review_status FROM transactions WHERE event_id=?",
                         (item.event_id,)).fetchone()
        if row is None:
            return {"ok": True, "not_found": True}
        if row["source"] not in ("WECHAT", "ALIPAY", "BANK_SMS") or row["review_status"] not in ("unverified", "possible_duplicate"):
            raise HTTPException(status_code=409, detail="Transaction not eligible for automatic repair")
        if row["amount_cents"] == item.corrected_cents:
            return {"ok": True, "already_correct": True}
        if row["amount_cents"] != item.old_cents:
            raise HTTPException(status_code=409, detail="Amount changed since capture")
        db.execute("UPDATE transactions SET amount_cents=? WHERE id=?", (item.corrected_cents,row["id"]))
    return {"ok": True, "corrected": True}

@app.get("/api/v1/transactions", dependencies=[Depends(admin_auth)])
def transactions(limit: int = Query(50,ge=1,le=500), before_ms: int | None = None):
    with db_connection() as db:
        if before_ms is None:
            rows = db.execute("SELECT * FROM transactions ORDER BY timestamp_ms DESC LIMIT ?", (limit,)).fetchall()
        else:
            rows = db.execute("SELECT * FROM transactions WHERE timestamp_ms<? ORDER BY timestamp_ms DESC LIMIT ?", (before_ms,limit)).fetchall()
    return {"items": [dict(row) for row in rows]}

@app.get("/api/v1/summary", dependencies=[Depends(admin_auth)])
def summary(start_ms: int = Query(...), end_ms: int = Query(...)):
    if end_ms <= start_ms:
        raise HTTPException(422, "End must be later than start")
    with db_connection() as db:
        rows = db.execute("""SELECT currency,direction,category,COUNT(*) AS tx_count,
                  SUM(amount_cents) AS total_cents FROM transactions
                  WHERE timestamp_ms>=? AND timestamp_ms<?
                  AND review_status NOT IN ('excluded','transfer','refund')
                  GROUP BY currency,direction,category ORDER BY currency,direction,category""",
                  (start_ms,end_ms)).fetchall()
    # Different currencies must never be added together without an FX rate.
    return {"start_ms":start_ms,"end_ms":end_ms,"groups":[dict(r) for r in rows]}

# Extensions registered after core data API.
from features import attach as attach_features
attach_features(app, __import__(__name__))

from wallos_ledger import router as wallos_ledger_router
app.include_router(wallos_ledger_router)

# The MCP surface requires the same administrator token and runs a read-only-by-default toolset.
from contextlib import asynccontextmanager
from fastapi import Request
from mcp_service import mcp
from oauth_support import router as oauth_router, verify_access_token
app.include_router(oauth_router)

@asynccontextmanager
async def finance_lifespan(application):
    async with mcp.session_manager.run():
        yield

app.router.lifespan_context = finance_lifespan

@app.middleware("http")
async def mcp_authentication(request: Request, call_next):
    if request.url.path.startswith("/mcp"):
        header=request.headers.get("authorization","")
        token=header[len("Bearer "):] if header.startswith("Bearer ") else ""
        admin_ok = bool(ADMIN_TOKEN and token and hmac.compare_digest(ADMIN_TOKEN, token))
        oauth_ok = bool(token and verify_access_token(token))
        if not (admin_ok or oauth_ok):
            from fastapi.responses import JSONResponse
            return JSONResponse(status_code=401,content={"detail":"OAuth authorization required"},
              headers={"WWW-Authenticate": f'Bearer resource_metadata="{PUBLIC_BASE}/.well-known/oauth-protected-resource"'})
        if oauth_ok and not admin_ok and request.method == "POST":
            # An OAuth read-only grant must not call a modifying MCP tool.
            try:
                payload = await request.json()
                is_write = payload.get("method") == "tools/call" and payload.get("params",{}).get("name") == "add_subscription"
            except (ValueError,AttributeError,TypeError):
                is_write = False
            if is_write and not verify_access_token(token,"finance:write"):
                from fastapi.responses import JSONResponse
                return JSONResponse(status_code=403,content={"detail":"finance:write scope required"})
    return await call_next(request)

app.mount("/mcp", mcp.streamable_http_app())

from fastapi.responses import RedirectResponse
@app.get("/", include_in_schema=False)
def finance_dashboard():
    # One portal: Wallos native subscription pages plus the integrated
    # daily transaction page under the same Wallos login.
    return RedirectResponse("/finance/wallos/expenses.php",status_code=302)
