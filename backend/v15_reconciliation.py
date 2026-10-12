
"""V1.5 reconciliation primitives: imports, aliases and duplicate review."""
import csv, io, hashlib
from datetime import datetime

def schema(db):
    db.execute("""CREATE TABLE IF NOT EXISTS imported_statements(
      id INTEGER PRIMARY KEY, source TEXT NOT NULL, filename TEXT NOT NULL,
      imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)""")
    db.execute("""CREATE TABLE IF NOT EXISTS statement_rows(
      id INTEGER PRIMARY KEY, statement_id INTEGER NOT NULL, external_id TEXT,
      timestamp_ms INTEGER NOT NULL, amount_cents INTEGER NOT NULL,
      currency TEXT NOT NULL, direction TEXT NOT NULL, merchant TEXT NOT NULL,
      matched_tx_id INTEGER)""")
    db.execute("""CREATE TABLE IF NOT EXISTS merchant_aliases(
      id INTEGER PRIMARY KEY, canonical TEXT NOT NULL, alias TEXT NOT NULL UNIQUE)""")
    db.execute("""CREATE TABLE IF NOT EXISTS transaction_splits(
      id INTEGER PRIMARY KEY, transaction_id INTEGER NOT NULL,
      category TEXT NOT NULL, amount_cents INTEGER NOT NULL)""")

def normalize_merchant(db, name):
    row=db.execute('SELECT canonical FROM merchant_aliases WHERE lower(alias)=lower(?)',(name,)).fetchone()
    return row[0] if row else name

def parse_csv(content, source='CSV'):
    rows=[]
    for r in csv.DictReader(io.StringIO(content)):
        rows.append(r)
    return rows


def import_standard_csv(db, content, source, filename):
    cur=db.execute("INSERT INTO imported_statements(source,filename) VALUES(?,?)",(source,filename))
    sid=cur.lastrowid
    count=0
    for row in csv.DictReader(io.StringIO(content)):
        required=["timestamp_ms","amount_cents","currency","direction","merchant"]
        if not all(k in row for k in required):
            raise ValueError("missing required csv columns")
        ext=row.get("external_id") or hashlib.sha256(str(row).encode()).hexdigest()[:40]
        if db.execute("SELECT 1 FROM statement_rows WHERE external_id=?",(ext,)).fetchone():
            continue
        db.execute("INSERT INTO statement_rows(statement_id,external_id,timestamp_ms,amount_cents,currency,direction,merchant) VALUES(?,?,?,?,?,?,?)",(sid,ext,int(row["timestamp_ms"]),int(row["amount_cents"]),row["currency"],row["direction"],row["merchant"]))
        count+=1
    return sid,count

def reconcile(db):
    out=[]
    txs=db.execute("SELECT * FROM transactions").fetchall()
    for r in db.execute("SELECT * FROM statement_rows WHERE matched_tx_id IS NULL"):
        best=None; score=0
        for t in txs:
            s=(50 if r["amount_cents"]==t["amount_cents"] and r["currency"]==t["currency"] else 0)+(20 if r["direction"]==t["direction"] else 0)+(20 if abs(r["timestamp_ms"]-t["timestamp_ms"])<86400000 else 0)
            if s>score: best,score=t,s
        out.append({"statement_row_id":r["id"],"transaction_id":best["id"] if best else None,"score":score,"status":"MATCHED" if score>=80 else "CONFLICT" if score>=40 else "MISSING_LOCAL"})
    return out


def match_statement_rows(db):
    """Match imported rows without creating duplicate ledger entries."""
    rows=db.execute("SELECT * FROM statement_rows WHERE matched_tx_id IS NULL").fetchall()
    matched=0
    for row in rows:
        tx=db.execute("""SELECT id FROM transactions WHERE amount_cents=? AND currency=?
            AND direction=? AND ABS(timestamp_ms-?)<=86400000 LIMIT 1""",
            (row["amount_cents"],row["currency"],row["direction"],row["timestamp_ms"])).fetchone()
        if tx:
            db.execute("UPDATE statement_rows SET matched_tx_id=? WHERE id=?",(tx[0],row["id"]))
            matched+=1
    return matched
