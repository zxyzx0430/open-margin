"""Bidirectional transaction sync: append-only revisions, tombstones, optimistic conflicts.

The DB journal is managed by triggers so Wallos/PHP, imports and administrative
edits all publish changes, not only transactions written through this router.
Never sync SMS bodies, local notes or bank credentials.
"""
from __future__ import annotations

import json
import sqlite3
import time
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/v2/sync")

RECORD_JSON = """json_object(
 'event_id',NEW.event_id,'timestamp_ms',NEW.timestamp_ms,
 'amount_cents',NEW.amount_cents,'currency',NEW.currency,
 'direction',NEW.direction,'source',NEW.source,
 'category',NEW.category,'merchant',NEW.merchant,
 'review_status',NEW.review_status)"""


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
      revision INTEGER NOT NULL
    )""")
    # Seed pre-existing transactions exactly once. This migration is safe for
    # online SQLite backups and does not change pre-existing ledger rows.
    if db.execute("SELECT 1 FROM ledger_settings WHERE key='sync_v2_seeded'").fetchone() is None:
        for row in db.execute("SELECT * FROM transactions ORDER BY id").fetchall():
            db.execute("""INSERT INTO sync_journal(event_id,action,payload,changed_at_ms)
              VALUES(?,'upsert',?,?)""", (row["event_id"], json.dumps({
                key:row[key] for key in ("event_id","timestamp_ms","amount_cents","currency",
                                       "direction","source","category","merchant","review_status")
            }, ensure_ascii=False),int(time.time()*1000)))
        db.execute("INSERT INTO ledger_settings(key,value) VALUES('sync_v2_seeded','1')")

    db.execute(f"""CREATE TRIGGER IF NOT EXISTS sync_journal_insert
      AFTER INSERT ON transactions BEGIN
       INSERT INTO sync_journal(event_id,action,payload,changed_at_ms)
       VALUES(NEW.event_id,'upsert',{RECORD_JSON},(CAST(strftime('%s','now') AS INTEGER)*1000 + CAST(substr(strftime('%f','now'),4,3) AS INTEGER)));
      END""")
    db.execute(f"""CREATE TRIGGER IF NOT EXISTS sync_journal_update
      AFTER UPDATE OF timestamp_ms,amount_cents,currency,direction,source,
        category,merchant,review_status ON transactions
      WHEN OLD.timestamp_ms<>NEW.timestamp_ms
        OR OLD.amount_cents<>NEW.amount_cents OR OLD.currency<>NEW.currency
        OR OLD.direction<>NEW.direction OR OLD.source<>NEW.source
        OR OLD.category<>NEW.category OR OLD.merchant<>NEW.merchant
        OR OLD.review_status<>NEW.review_status
      BEGIN
       INSERT INTO sync_journal(event_id,action,payload,changed_at_ms)
       VALUES(NEW.event_id,'upsert',{RECORD_JSON},(CAST(strftime('%s','now') AS INTEGER)*1000 + CAST(substr(strftime('%f','now'),4,3) AS INTEGER)));
      END""")
    db.execute("""CREATE TRIGGER IF NOT EXISTS sync_journal_delete
      AFTER DELETE ON transactions BEGIN
       INSERT INTO sync_journal(event_id,action,payload,changed_at_ms)
       VALUES(OLD.event_id,'delete',NULL,(CAST(strftime('%s','now') AS INTEGER)*1000 + CAST(substr(strftime('%f','now'),4,3) AS INTEGER)));
      END""")


def latest(db: sqlite3.Connection, event_id: str):
    return db.execute("""SELECT revision,action,payload,changed_at_ms FROM sync_journal
      WHERE event_id=? ORDER BY revision DESC LIMIT 1""", (event_id,)).fetchone()


def current_change(row):
    return {
        "revision": row["revision"], "action": row["action"],
        "record": json.loads(row["payload"]) if row["payload"] else None,
        "changed_at_ms": row["changed_at_ms"]
    }


class Record(BaseModel):
    timestamp_ms: int = Field(gt=946684800000,lt=4102444800000)
    amount_cents: int = Field(gt=0,le=100_000_000_000)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    direction: Literal["EXPENSE","INCOME"]
    source: Literal["WECHAT","ALIPAY","BANK_SMS","MANUAL","OTHER"]
    category: str = Field(min_length=1,max_length=45)
    merchant: str = Field(default="",max_length=140)


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
        return {
            "items":[{
                "revision":row["revision"],"event_id":row["event_id"],
                "action":row["action"],
                "record":json.loads(row["payload"]) if row["payload"] else None,
                "changed_at_ms":row["changed_at_ms"]
            } for row in page],
            "cursor":page[-1]["revision"] if page else since,
            "has_more":len(rows)>limit
        }


def push(body:Mutations):
    import app as core
    from integration import resolve_category
    results=[]
    with core.db_connection() as db:
        cutoff=core.ledger_start_ms(db)
        for operation in body.operations:
            with db:
                prior=db.execute("SELECT revision,event_id FROM sync_mutations WHERE mutation_id=?",
                                 (operation.mutation_id,)).fetchone()
                if prior is not None:
                    if prior["event_id"] != operation.event_id:
                        raise HTTPException(409,"Mutation ID was reused for another event")
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
                    if record.timestamp_ms<cutoff:
                        results.append({"mutation_id":operation.mutation_id,"status":"before_start"})
                        continue
                    existing=db.execute("SELECT * FROM transactions WHERE event_id=?",
                                        (operation.event_id,)).fetchone()
                    if existing:
                        # Never erase Wallos review / refund status from a phone edit.
                        db.execute("""UPDATE transactions SET timestamp_ms=?,amount_cents=?,
                          currency=?,direction=?,category=?,merchant=? WHERE event_id=?""",
                          (record.timestamp_ms,record.amount_cents,record.currency,
                           record.direction,record.category,record.merchant,operation.event_id))
                    else:
                        db.execute("""INSERT INTO transactions(event_id,timestamp_ms,amount_cents,
                          currency,direction,source,category,merchant) VALUES(?,?,?,?,?,?,?,?)""",
                          (operation.event_id,record.timestamp_ms,record.amount_cents,
                           record.currency,record.direction,record.source,
                           resolve_category(db,record.merchant,record.category),record.merchant))
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
                db.execute("INSERT INTO sync_mutations(mutation_id,event_id,revision) VALUES(?,?,?)",
                           (operation.mutation_id,operation.event_id,updated["revision"]))
                results.append({"mutation_id":operation.mutation_id,"status":"applied",
                                "revision":updated["revision"]})
    return {"results":results}
