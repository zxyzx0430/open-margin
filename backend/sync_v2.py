"""Bidirectional transaction sync: append-only revisions, tombstones, optimistic conflicts.

The DB journal is managed by triggers so Wallos/PHP, imports and administrative
edits all publish changes, not only transactions written through this router.
Never sync SMS bodies, local notes or bank credentials.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
import time
import uuid
from datetime import datetime
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field

DEFAULT_FX_RATES: dict[str, float] = {
    "CNY": 1.0,
    "USD": 7.25,
    "HKD": 0.93,
    "EUR": 7.85,
    "JPY": 0.048,
    "GBP": 9.15,
}

router = APIRouter(prefix="/api/v2/sync")

RECORD_JSON = """json_object(
 'event_id',NEW.event_id,'timestamp_ms',NEW.timestamp_ms,
 'amount_cents',NEW.amount_cents,'currency',NEW.currency,
 'direction',NEW.direction,'source',NEW.source,
 'category',NEW.category,'merchant',NEW.merchant,
 'review_status',NEW.review_status,
 'event_time_needs_confirmation',json(CASE WHEN NEW.event_time_needs_confirmation=1
   THEN 'true' ELSE 'false' END))"""


def schema(db: sqlite3.Connection) -> None:
    db.execute("""CREATE TABLE IF NOT EXISTS sync_journal(
      revision INTEGER PRIMARY KEY AUTOINCREMENT,
      event_id TEXT NOT NULL,
      action TEXT NOT NULL CHECK(action IN ('upsert','delete')),
      payload TEXT,
      changed_at_ms INTEGER NOT NULL
    )""")
    db.execute("CREATE INDEX IF NOT EXISTS sync_event_latest ON sync_journal(event_id,revision DESC)")
    db.execute("""CREATE TABLE IF NOT EXISTS sync_mutations(
      mutation_id TEXT PRIMARY KEY,
      event_id TEXT NOT NULL,
      revision INTEGER NOT NULL,
      request_hash TEXT
    )""")
    if not any(col[1]=="request_hash" for col in db.execute("PRAGMA table_info(sync_mutations)")):
        db.execute("ALTER TABLE sync_mutations ADD COLUMN request_hash TEXT")
    # Stable ledger identity. URLs can remain the same even after a local
    # reinstall or replacing Docker volumes; a persisted UUID prevents a
    # device's old download cursor from silently targeting a different DB.
    if db.execute("SELECT 1 FROM ledger_settings WHERE key='sync_v2_server_id'").fetchone() is None:
        db.execute("INSERT OR IGNORE INTO ledger_settings(key,value) VALUES('sync_v2_server_id',?)",
                   (uuid.uuid4().hex,))
    # Seed pre-existing transactions exactly once. This migration is safe for
    # online SQLite backups and does not change pre-existing ledger rows.
    if db.execute("SELECT 1 FROM ledger_settings WHERE key='sync_v2_seeded'").fetchone() is None:
        for row in db.execute("SELECT * FROM transactions ORDER BY id").fetchall():
            payload={key:row[key] for key in ("event_id","timestamp_ms","amount_cents","currency",
                                              "direction","source","category","merchant","review_status")}
            payload["event_time_needs_confirmation"] = bool(row["event_time_needs_confirmation"])
            db.execute("""INSERT INTO sync_journal(event_id,action,payload,changed_at_ms)
              VALUES(?,'upsert',?,?)""", (row["event_id"], json.dumps(payload,ensure_ascii=False),0))
        db.execute("INSERT INTO ledger_settings(key,value) VALUES('sync_v2_seeded','1')")

    _ensure_journal_triggers(db)

    db.execute("""CREATE TABLE IF NOT EXISTS subscription_sync_meta(
      sync_uuid TEXT PRIMARY KEY,
      wallos_id INTEGER UNIQUE,
      payment_channel TEXT NOT NULL DEFAULT '',
      pending_cancel INTEGER NOT NULL DEFAULT 0,
      note TEXT NOT NULL DEFAULT '',
      updated_at_ms INTEGER NOT NULL,
      deleted_at_ms INTEGER,
      last_wallos_hash TEXT NOT NULL DEFAULT ''
    )""")
    if not any(col[1] == "payment_channel" for col in db.execute("PRAGMA table_info(subscription_sync_meta)")):
        db.execute("ALTER TABLE subscription_sync_meta ADD COLUMN payment_channel TEXT NOT NULL DEFAULT ''")


def _journal_triggers_need_upgrade(db: sqlite3.Connection) -> bool:
    triggers = {
        row[0]: row[1]
        for row in db.execute("""SELECT name,sql FROM sqlite_master
          WHERE type='trigger' AND name IN
            ('sync_journal_insert','sync_journal_update','sync_journal_delete')""")
    }
    insert_sql = triggers.get("sync_journal_insert") or ""
    update_sql = triggers.get("sync_journal_update") or ""
    return (
        "event_time_needs_confirmation" not in insert_sql
        or "event_time_needs_confirmation" not in update_sql
        or triggers.get("sync_journal_delete") is None
    )


def _ensure_journal_triggers(db: sqlite3.Connection) -> None:
    # The trigger bodies only need changing once for databases created before
    # the event-time flag was added. Avoid any trigger DDL on normal requests.
    if not _journal_triggers_need_upgrade(db):
        return

    # Do not commit a transaction owned by the caller. For an idle connection,
    # reserve SQLite's writer lock before rechecking and changing the triggers.
    owns_transaction = not db.in_transaction
    savepoint = f"sync_journal_trigger_upgrade_{uuid.uuid4().hex}"
    if owns_transaction:
        db.execute("BEGIN IMMEDIATE")
    else:
        db.execute(f"SAVEPOINT {savepoint}")

    try:
        if not owns_transaction:
            # A caller can have an explicit deferred transaction that has not
            # reserved the writer lock. This no-op update reserves it before
            # trigger DDL while leaving the caller's transaction open.
            db.execute("UPDATE ledger_settings SET value=value WHERE key='sync_v2_server_id'")

        # Another connection may have completed this one-time migration while
        # this connection waited for the writer lock.
        if _journal_triggers_need_upgrade(db):
            db.execute("DROP TRIGGER IF EXISTS sync_journal_insert")
            db.execute("DROP TRIGGER IF EXISTS sync_journal_update")
            db.execute(f"""CREATE TRIGGER sync_journal_insert
              AFTER INSERT ON transactions BEGIN
               INSERT INTO sync_journal(event_id,action,payload,changed_at_ms)
               VALUES(NEW.event_id,'upsert',{RECORD_JSON},(CAST(strftime('%s','now') AS INTEGER)*1000 + CAST(substr(strftime('%f','now'),4,3) AS INTEGER)));
              END""")
            db.execute(f"""CREATE TRIGGER sync_journal_update
              AFTER UPDATE OF timestamp_ms,amount_cents,currency,direction,source,
                category,merchant,review_status,event_time_needs_confirmation ON transactions
              WHEN OLD.timestamp_ms<>NEW.timestamp_ms
                OR OLD.amount_cents<>NEW.amount_cents OR OLD.currency<>NEW.currency
                OR OLD.direction<>NEW.direction OR OLD.source<>NEW.source
                OR OLD.category<>NEW.category OR OLD.merchant<>NEW.merchant
                OR OLD.review_status<>NEW.review_status
                OR OLD.event_time_needs_confirmation<>NEW.event_time_needs_confirmation
              BEGIN
               INSERT INTO sync_journal(event_id,action,payload,changed_at_ms)
               VALUES(NEW.event_id,'upsert',{RECORD_JSON},(CAST(strftime('%s','now') AS INTEGER)*1000 + CAST(substr(strftime('%f','now'),4,3) AS INTEGER)));
              END""")
            db.execute("""CREATE TRIGGER IF NOT EXISTS sync_journal_delete
              AFTER DELETE ON transactions BEGIN
               INSERT INTO sync_journal(event_id,action,payload,changed_at_ms)
               VALUES(OLD.event_id,'delete',NULL,(CAST(strftime('%s','now') AS INTEGER)*1000 + CAST(substr(strftime('%f','now'),4,3) AS INTEGER)));
              END""")

        if owns_transaction:
            db.commit()
        else:
            db.execute(f"RELEASE SAVEPOINT {savepoint}")
    except BaseException:
        if owns_transaction:
            db.rollback()
        else:
            db.execute(f"ROLLBACK TO SAVEPOINT {savepoint}")
            db.execute(f"RELEASE SAVEPOINT {savepoint}")
        raise


def latest(db: sqlite3.Connection, event_id: str):
    return db.execute("""SELECT revision,action,payload,changed_at_ms FROM sync_journal
      WHERE event_id=? ORDER BY revision DESC LIMIT 1""", (event_id,)).fetchone()


def current_change(row):
    return {
        "revision": row["revision"], "action": row["action"],
        "record": record_from_payload(row["payload"]),
        "changed_at_ms": row["changed_at_ms"]
    }


def record_from_payload(payload):
    if not payload:
        return None
    record=json.loads(payload)
    # Historical journal entries predate the flag and represent the false default.
    record["event_time_needs_confirmation"] = bool(
        record.get("event_time_needs_confirmation",False))
    return record


class Record(BaseModel):
    timestamp_ms: int = Field(gt=946684800000,lt=4102444800000)
    amount_cents: int = Field(gt=0,le=100_000_000_000)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    direction: Literal["EXPENSE","INCOME"]
    source: Literal["WECHAT","ALIPAY","BANK_SMS","MANUAL","OTHER"]
    category: str = Field(min_length=1,max_length=45)
    merchant: str = Field(default="",max_length=140)
    event_time_needs_confirmation: bool = False
    # Refund links are managed by the dedicated server workflow, not a naked flag.
    review_status: Literal["unverified","confirmed","excluded","transfer","possible_duplicate"] | None = None


class Mutation(BaseModel):
    mutation_id: str = Field(min_length=8,max_length=128,pattern=r"^[a-zA-Z0-9_.:-]+$")
    event_id: str = Field(min_length=8,max_length=130,pattern=r"^[a-zA-Z0-9_.:-]+$")
    action: Literal["upsert","delete"]
    base_revision: int = Field(ge=0)
    record: Record | None = None


class Mutations(BaseModel):
    operations: list[Mutation] = Field(min_length=1,max_length=50)


def changes(since: int=Query(0,ge=0),limit:int=Query(100,ge=1,le=500)):
    import app as core
    with core.db_connection() as db:
        rows=db.execute("""SELECT * FROM sync_journal WHERE revision>?
          ORDER BY revision LIMIT ?""",(since,limit+1)).fetchall()
        page=rows[:limit]
        identity=db.execute(
            "SELECT value FROM ledger_settings WHERE key='sync_v2_server_id'"
        ).fetchone()[0]
        head=db.execute("SELECT COALESCE(MAX(revision),0) FROM sync_journal").fetchone()[0]
        return {
            "server_id":identity,
            "head_revision":head,
            "items":[{
                "revision":row["revision"],"event_id":row["event_id"],
                "action":row["action"],
                "record":record_from_payload(row["payload"]),
                "changed_at_ms":row["changed_at_ms"]
            } for row in page],
            "cursor":page[-1]["revision"] if page else since,
            "has_more":len(rows)>limit
        }


def mutation_hash(operation:Mutation) -> str:
    """Bind a retry ID to exactly one operation and payload."""
    payload=operation.model_dump()
    if (operation.record is not None and
        "event_time_needs_confirmation" not in operation.record.model_fields_set):
        # Keep omitted-field retries byte-compatible with the pre-field protocol,
        # while distinguishing an explicit false from omission.
        payload["record"].pop("event_time_needs_confirmation",None)
    canonical=json.dumps(payload,sort_keys=True,separators=(",",":"),
                         ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def push(body:Mutations):
    import app as core
    from integration import resolve_category
    results=[]
    with core.db_connection() as db:
        for operation in body.operations:
            request_hash=mutation_hash(operation)
            with db:
                # Serialize the version check AND mutation. A deferred SQLite
                # transaction does not start when latest() SELECTs. Without a
                # writer reservation, two devices can observe the same revision
                # and both overwrite one another while reporting "applied".
                # BEGIN IMMEDIATE waits for the first writer to commit, then
                # performs the revision comparison against its committed state.
                db.execute("BEGIN IMMEDIATE")
                prior=db.execute("SELECT revision,event_id,request_hash FROM sync_mutations WHERE mutation_id=?",
                                 (operation.mutation_id,)).fetchone()
                if prior is not None:
                    if prior["event_id"] != operation.event_id or (
                        prior["request_hash"] is not None and prior["request_hash"]!=request_hash
                    ):
                        raise HTTPException(409,"Mutation ID was reused with a different operation")
                    results.append({"mutation_id":operation.mutation_id,"status":"applied",
                                    "revision":prior["revision"],"replayed":True})
                    continue
                previous=latest(db,operation.event_id)
                current=previous["revision"] if previous else 0
                if current!=operation.base_revision:
                    results.append({"mutation_id":operation.mutation_id,"status":"conflict",
                                    "current":current_change(previous) if previous else None})
                    continue
                if operation.action=="upsert":
                    if operation.record is None:
                        raise HTTPException(422,"Missing transaction record")
                    record=operation.record
                    existing=db.execute("SELECT * FROM transactions WHERE event_id=?",
                                        (operation.event_id,)).fetchone()
                    if existing:
                        # Preserve refund accounting constraints: edits to linked
                        # amounts/direction/currency must first unlink the refund.
                        linked=db.execute("""SELECT 1 FROM refund_links
                          WHERE original_tx_id=? OR refund_tx_id=? LIMIT 1""",
                          (existing["id"],existing["id"])).fetchone()
                        requested_status=record.review_status or existing["review_status"]
                        needs_confirmation=bool(existing["event_time_needs_confirmation"])
                        if "event_time_needs_confirmation" in record.model_fields_set:
                            if record.event_time_needs_confirmation:
                                needs_confirmation=True
                            elif record.timestamp_ms!=existing["timestamp_ms"]:
                                # An explicit false only clears on an actual
                                # event-time change, not an unrelated update.
                                needs_confirmation=False
                        if linked and (existing["amount_cents"]!=record.amount_cents
                                or existing["currency"]!=record.currency
                                or existing["direction"]!=record.direction
                                or existing["review_status"]!=requested_status):
                            results.append({"mutation_id":operation.mutation_id,
                                            "status":"linked_refund"})
                            continue
                        # Never erase Wallos review / refund status from a phone edit.
                        db.execute("""UPDATE transactions SET timestamp_ms=?,amount_cents=?,
                          currency=?,direction=?,category=?,merchant=?,review_status=?,
                          event_time_needs_confirmation=?
                          WHERE event_id=?""",
                          (record.timestamp_ms,record.amount_cents,record.currency,
                           record.direction,record.category,record.merchant,
                           requested_status,int(needs_confirmation),operation.event_id))
                    else:
                        db.execute("""INSERT INTO transactions(event_id,timestamp_ms,amount_cents,
                          currency,direction,source,category,merchant,review_status,
                          event_time_needs_confirmation)
                          VALUES(?,?,?,?,?,?,?,?,?,?)""",
                          (operation.event_id,record.timestamp_ms,record.amount_cents,
                           record.currency,record.direction,record.source,
                           resolve_category(db,record.merchant,record.category),record.merchant,
                           record.review_status or "unverified",
                           int(record.event_time_needs_confirmation)))
                else:
                    existing=db.execute("SELECT id FROM transactions WHERE event_id=?",
                                        (operation.event_id,)).fetchone()
                    if existing is not None:
                        if db.execute("SELECT 1 FROM refund_links WHERE original_tx_id=? OR refund_tx_id=?",
                                      (existing["id"],existing["id"])).fetchone():
                            results.append({"mutation_id":operation.mutation_id,
                                            "status":"linked_refund"})
                            continue
                        db.execute("DELETE FROM tx_subscription_links WHERE tx_id=?",(existing["id"],))
                        db.execute("DELETE FROM transaction_splits WHERE transaction_id=?",(existing["id"],))
                        db.execute("DELETE FROM transactions WHERE id=?",(existing["id"],))
                    else:
                        # A deleted-before-first-upload item still needs a tombstone
                        # to prevent an old v1 client reintroducing the transaction.
                        db.execute("""INSERT INTO sync_journal(event_id,action,payload,changed_at_ms)
                          VALUES(?,'delete',NULL,?)""",(operation.event_id,int(time.time()*1000)))
                updated=latest(db,operation.event_id)
                assert updated is not None
                db.execute("INSERT INTO sync_mutations(mutation_id,event_id,revision,request_hash) VALUES(?,?,?,?)",
                           (operation.mutation_id,operation.event_id,updated["revision"],request_hash))
                results.append({"mutation_id":operation.mutation_id,"status":"applied",
                                "revision":updated["revision"]})
    return {"results":results}


class SubscriptionPushItem(BaseModel):
    sync_uuid: str = Field(min_length=8, max_length=128, pattern=r"^[a-zA-Z0-9_.:-]+$")
    wallos_id: int | None = None
    name: str = Field(min_length=1, max_length=140)
    amount_cents: int = Field(ge=0, le=100_000_000_000)
    currency: str = Field(default="CNY", pattern=r"^[A-Z]{3}$")
    cadence: Literal["weekly", "monthly", "quarterly", "yearly"]
    next_due_ms: int = Field(gt=946684800000, lt=4102444800000)
    start_ms: int | None = None
    payment_channel: str = Field(default="", max_length=90)
    auto_renew: bool = True
    pending_cancel: bool = False
    notify_enabled: bool = True
    notify_days_before: int = Field(default=3, ge=0, le=365)
    active: bool = True
    note: str = Field(default="", max_length=500)
    updated_at_ms: int = Field(gt=0)
    deleted_at_ms: int | None = None


class SubscriptionPushRequest(BaseModel):
    items: list[SubscriptionPushItem] = Field(default_factory=list, max_length=200)


def _fx_rates(db: sqlite3.Connection) -> dict[str, float]:
    rates = dict(DEFAULT_FX_RATES)
    row = db.execute("SELECT value FROM ledger_settings WHERE key='fx_rates_json'").fetchone()
    if row and row[0]:
        try:
            loaded = json.loads(row[0])
            if isinstance(loaded, dict):
                for code, val in loaded.items():
                    if isinstance(code, str) and isinstance(val, (int, float)) and val > 0:
                        rates[code.upper()] = float(val)
        except ValueError:
            pass
    return rates


def _wallos_sub_hash(sub: dict) -> str:
    payload = {
        "name": sub.get("name", ""),
        "amount_cents": int(sub.get("amount_cents", 0)),
        "currency": sub.get("currency", ""),
        "cadence": sub.get("cadence", ""),
        "next_due_ms": sub.get("next_due_ms"),
        "start_ms": sub.get("start_ms"),
        "payment_channel": sub.get("payment_channel", ""),
        "auto_renew": bool(sub.get("auto_renew", True)),
        "notify": bool(sub.get("notify", False)),
        "notify_days_before": sub.get("notify_days_before"),
        "active": bool(sub.get("active", True)),
    }
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _clean_wallos_note(raw_note: str) -> str:
    if not raw_note:
        return ""
    lines = [
        line for line in raw_note.splitlines()
        if not line.strip().startswith("Payment channel:")
    ]
    return "\n".join(lines).strip()


def _channel_matches_preserved(preserved: str, wallos_channel: str) -> bool:
    if not preserved.strip():
        return False
    if not wallos_channel.strip():
        return True
    import wallos_api
    candidates = {c.strip().casefold() for c in wallos_api._fallback_candidates_for(preserved)}
    return wallos_channel.strip().casefold() in candidates


def _reconcile_wallos(db: sqlite3.Connection, now_ms: int | None = None) -> tuple[list[dict], dict[int, dict]]:
    import wallos_api
    if now_ms is None:
        now_ms = int(time.time() * 1000)
    wallos_subs = wallos_api.get_all()
    wallos_by_id = {sub["id"]: sub for sub in wallos_subs if sub.get("id") is not None}
    meta_rows = db.execute("SELECT * FROM subscription_sync_meta").fetchall()
    meta_by_wallos_id = {
        row["wallos_id"]: row for row in meta_rows if row["wallos_id"] is not None
    }

    for wid, sub in wallos_by_id.items():
        w_hash = _wallos_sub_hash(sub)
        meta = meta_by_wallos_id.get(wid)
        if meta is None:
            sync_uuid = str(uuid.uuid4())
            db.execute(
                """INSERT INTO subscription_sync_meta(
                     sync_uuid, wallos_id, payment_channel, pending_cancel, note,
                     updated_at_ms, deleted_at_ms, last_wallos_hash
                   ) VALUES(?,?,?,?,?,?,NULL,?)""",
                (
                    sync_uuid,
                    wid,
                    sub.get("payment_channel") or "",
                    0,
                    _clean_wallos_note(sub.get("note") or ""),
                    now_ms,
                    w_hash,
                ),
            )
        elif meta["last_wallos_hash"] != w_hash:
            new_channel = sub.get("payment_channel") or ""
            preserved_channel = meta["payment_channel"] or ""
            if preserved_channel and _channel_matches_preserved(preserved_channel, new_channel):
                effective_channel = preserved_channel
            else:
                effective_channel = new_channel
            web_note = _clean_wallos_note(sub.get("note") or "")
            effective_note = web_note if web_note and not meta["note"] else meta["note"]
            db.execute(
                """UPDATE subscription_sync_meta
                   SET payment_channel=?, note=?, updated_at_ms=?, deleted_at_ms=NULL, last_wallos_hash=?
                   WHERE sync_uuid=?""",
                (effective_channel, effective_note, now_ms, w_hash, meta["sync_uuid"]),
            )

    for meta in meta_rows:
        wid = meta["wallos_id"]
        if wid is not None and wid not in wallos_by_id and meta["deleted_at_ms"] is None:
            db.execute(
                """UPDATE subscription_sync_meta
                   SET deleted_at_ms=?, updated_at_ms=?
                   WHERE sync_uuid=?""",
                (now_ms, now_ms, meta["sync_uuid"]),
            )

    return wallos_subs, wallos_by_id


def _build_subscription_snapshot(db: sqlite3.Connection, wallos_subs: list[dict]) -> dict:
    identity_row = db.execute(
        "SELECT value FROM ledger_settings WHERE key='sync_v2_server_id'"
    ).fetchone()
    server_id = identity_row[0] if identity_row else ""
    meta_rows = db.execute("SELECT * FROM subscription_sync_meta").fetchall()
    meta_by_wallos_id = {
        row["wallos_id"]: row for row in meta_rows if row["wallos_id"] is not None
    }

    items = []
    for sub in wallos_subs:
        wid = sub["id"]
        meta = meta_by_wallos_id.get(wid)
        if meta is None or meta["deleted_at_ms"] is not None:
            continue
        channel = (meta["payment_channel"] or sub.get("payment_channel") or "")
        note = meta["note"] or _clean_wallos_note(sub.get("note") or "")
        notify_days = sub.get("notify_days_before")
        items.append({
            "sync_uuid": meta["sync_uuid"],
            "wallos_id": wid,
            "name": sub["name"],
            "amount_cents": int(sub["amount_cents"]),
            "currency": sub["currency"],
            "cadence": sub["cadence"],
            "next_due_ms": sub["next_due_ms"],
            "start_ms": sub.get("start_ms"),
            "payment_channel": channel,
            "auto_renew": bool(sub.get("auto_renew", True)),
            "pending_cancel": bool(meta["pending_cancel"]),
            "notify_enabled": bool(sub.get("notify", False)),
            "notify_days_before": int(notify_days) if notify_days is not None else 3,
            "active": bool(sub.get("active", True)),
            "note": note,
            "updated_at_ms": int(meta["updated_at_ms"]),
            "deleted_at_ms": None,
        })

    tombstones = [
        {
            "sync_uuid": row["sync_uuid"],
            "wallos_id": row["wallos_id"],
            "updated_at_ms": int(row["updated_at_ms"]),
            "deleted_at_ms": int(row["deleted_at_ms"]),
        }
        for row in meta_rows
        if row["deleted_at_ms"] is not None
    ]

    return {
        "server_id": server_id,
        "fx_rates": _fx_rates(db),
        "items": items,
        "tombstones": tombstones,
    }


def subscriptions_pull():
    import app as core
    from wallos_api import WallosError

    with core.db_connection() as db:
        try:
            wallos_subs, _ = _reconcile_wallos(db)
        except WallosError as exc:
            raise HTTPException(status_code=503, detail=str(exc))
        return _build_subscription_snapshot(db, wallos_subs)


def subscriptions_push(body: SubscriptionPushRequest):
    import app as core
    import wallos_api
    from wallos_api import LOCAL_TZ, WallosError

    id_map: dict[str, int] = {}
    results: list[dict] = []
    mutated_uuids: set[str] = set()

    with core.db_connection() as db:
        try:
            wallos_subs, wallos_by_id = _reconcile_wallos(db)
        except WallosError as exc:
            raise HTTPException(status_code=503, detail=str(exc))

        for item in body.items:
            meta = db.execute(
                "SELECT * FROM subscription_sync_meta WHERE sync_uuid=?",
                (item.sync_uuid,),
            ).fetchone()
            if meta is None and item.wallos_id is not None:
                meta = db.execute(
                    "SELECT * FROM subscription_sync_meta WHERE wallos_id=?",
                    (item.wallos_id,),
                ).fetchone()

            if item.deleted_at_ms is not None:
                if meta is not None and meta["deleted_at_ms"] is None and int(meta["updated_at_ms"]) > item.updated_at_ms:
                    results.append({
                        "sync_uuid": item.sync_uuid,
                        "wallos_id": meta["wallos_id"],
                        "status": "server_wins",
                    })
                    continue

                if meta is not None and meta["deleted_at_ms"] is None and meta["wallos_id"] is not None and meta["wallos_id"] in wallos_by_id:
                    try:
                        wallos_api.delete(int(meta["wallos_id"]))
                    except WallosError as exc:
                        raise HTTPException(status_code=503, detail=str(exc))
                    wallos_by_id.pop(meta["wallos_id"], None)

                db.execute(
                    """INSERT INTO subscription_sync_meta(
                         sync_uuid, wallos_id, payment_channel, pending_cancel, note,
                         updated_at_ms, deleted_at_ms, last_wallos_hash
                       ) VALUES(?,?,?,?,?,?,?,?)
                       ON CONFLICT(sync_uuid) DO UPDATE SET
                         payment_channel=excluded.payment_channel,
                         pending_cancel=excluded.pending_cancel,
                         note=excluded.note,
                         updated_at_ms=excluded.updated_at_ms,
                         deleted_at_ms=excluded.deleted_at_ms,
                         last_wallos_hash=''""",
                    (
                        item.sync_uuid,
                        meta["wallos_id"] if meta else None,
                        item.payment_channel,
                        int(item.pending_cancel),
                        item.note,
                        item.updated_at_ms,
                        item.deleted_at_ms,
                        "",
                    ),
                )
                mutated_uuids.add(item.sync_uuid)
                results.append({
                    "sync_uuid": item.sync_uuid,
                    "wallos_id": meta["wallos_id"] if meta else None,
                    "status": "deleted",
                })
                continue

            start_date_str = (
                datetime.fromtimestamp(item.start_ms / 1000, LOCAL_TZ).strftime("%Y-%m-%d")
                if item.start_ms is not None else None
            )

            if meta is None or (meta["deleted_at_ms"] is not None and item.updated_at_ms >= int(meta["updated_at_ms"])):
                try:
                    wid = wallos_api.add(
                        name=item.name,
                        amount_cents=item.amount_cents,
                        currency=item.currency,
                        cadence=item.cadence,
                        next_due_ms=item.next_due_ms,
                        payment_channel=item.payment_channel,
                        start_date=start_date_str,
                        auto_renew=item.auto_renew,
                        notify=item.notify_enabled,
                        notify_days_before=item.notify_days_before,
                        note=item.note,
                    )
                except WallosError as exc:
                    raise HTTPException(status_code=503, detail=str(exc))

                db.execute(
                    """INSERT INTO subscription_sync_meta(
                         sync_uuid, wallos_id, payment_channel, pending_cancel, note,
                         updated_at_ms, deleted_at_ms, last_wallos_hash
                       ) VALUES(?,?,?,?,?,?,NULL,'')
                       ON CONFLICT(sync_uuid) DO UPDATE SET
                         wallos_id=excluded.wallos_id,
                         payment_channel=excluded.payment_channel,
                         pending_cancel=excluded.pending_cancel,
                         note=excluded.note,
                         updated_at_ms=excluded.updated_at_ms,
                         deleted_at_ms=NULL,
                         last_wallos_hash=''""",
                    (
                        item.sync_uuid,
                        wid,
                        item.payment_channel,
                        int(item.pending_cancel),
                        item.note,
                        item.updated_at_ms,
                    ),
                )
                id_map[item.sync_uuid] = wid
                mutated_uuids.add(item.sync_uuid)
                results.append({
                    "sync_uuid": item.sync_uuid,
                    "wallos_id": wid,
                    "status": "created",
                })
                continue

            if meta["deleted_at_ms"] is not None and int(meta["updated_at_ms"]) > item.updated_at_ms:
                results.append({
                    "sync_uuid": item.sync_uuid,
                    "wallos_id": meta["wallos_id"],
                    "status": "server_wins",
                })
                continue

            if int(meta["updated_at_ms"]) > item.updated_at_ms:
                if meta["wallos_id"] is not None:
                    id_map[item.sync_uuid] = int(meta["wallos_id"])
                results.append({
                    "sync_uuid": item.sync_uuid,
                    "wallos_id": meta["wallos_id"],
                    "status": "server_wins",
                })
                continue

            wid = int(meta["wallos_id"])
            edit_fields = {
                "name": item.name,
                "amount_cents": item.amount_cents,
                "currency": item.currency,
                "cadence": item.cadence,
                "next_due_ms": item.next_due_ms,
                "payment_channel": item.payment_channel,
                "auto_renew": item.auto_renew,
                "notify": item.notify_enabled,
                "notify_days_before": item.notify_days_before,
                "active": item.active,
                "note": wallos_api.format_wallos_notes(item.payment_channel, item.note),
            }
            if start_date_str is not None:
                edit_fields["start_date"] = start_date_str
            try:
                wallos_api.edit(wid, **edit_fields)
            except WallosError as exc:
                raise HTTPException(status_code=503, detail=str(exc))

            db.execute(
                """UPDATE subscription_sync_meta
                   SET payment_channel=?, pending_cancel=?, note=?, updated_at_ms=?, deleted_at_ms=NULL
                   WHERE sync_uuid=?""",
                (
                    item.payment_channel,
                    int(item.pending_cancel),
                    item.note,
                    item.updated_at_ms,
                    meta["sync_uuid"],
                ),
            )
            id_map[item.sync_uuid] = wid
            mutated_uuids.add(meta["sync_uuid"])
            results.append({
                "sync_uuid": item.sync_uuid,
                "wallos_id": wid,
                "status": "updated",
            })

        if mutated_uuids:
            try:
                wallos_subs = wallos_api.get_all()
            except WallosError as exc:
                raise HTTPException(status_code=503, detail=str(exc))
            by_id = {sub["id"]: sub for sub in wallos_subs if sub.get("id") is not None}
            for sync_uuid in mutated_uuids:
                row = db.execute(
                    "SELECT wallos_id FROM subscription_sync_meta WHERE sync_uuid=?",
                    (sync_uuid,),
                ).fetchone()
                if row and row["wallos_id"] in by_id:
                    db.execute(
                        "UPDATE subscription_sync_meta SET last_wallos_hash=? WHERE sync_uuid=?",
                        (_wallos_sub_hash(by_id[row["wallos_id"]]), sync_uuid),
                    )

        snapshot = _build_subscription_snapshot(db, wallos_subs)
        return {
            **snapshot,
            "id_map": id_map,
            "results": results,
        }
