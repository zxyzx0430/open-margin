"""Subscription tracking and candidate discovery; no external transfers or charges."""
import calendar
import csv
import hashlib
import io
import re
from collections import defaultdict
from datetime import datetime, timezone
from typing import Literal
from fastapi import Depends, HTTPException, Query, UploadFile, File
from pydantic import BaseModel, Field

def attach(api, core):
    class Subscription(BaseModel):
        name: str = Field(min_length=1,max_length=140)
        amount_cents: int = Field(ge=0,le=100_000_000_000)
        currency: str = Field(default="CNY",pattern=r"^[A-Z]{3}$")
        cadence: Literal["monthly","yearly","weekly","quarterly","one_time"]
        next_due_ms: int = Field(gt=946684800000,lt=4102444800000)
        payment_channel: str = Field(default="",max_length=90)
        note: str = Field(default="",max_length=300)
        active: bool = True

    class Review(BaseModel):
        status: Literal["unverified","confirmed","possible_duplicate","excluded","transfer","refund"]

    def schema(db):
        db.execute("""CREATE TABLE IF NOT EXISTS subscriptions(
          id INTEGER PRIMARY KEY,
          name TEXT NOT NULL, amount_cents INTEGER NOT NULL, currency TEXT NOT NULL,
          cadence TEXT NOT NULL, next_due_ms INTEGER NOT NULL, payment_channel TEXT NOT NULL DEFAULT '',
          note TEXT NOT NULL DEFAULT '', active INTEGER NOT NULL DEFAULT 1,
          created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)""")
        db.commit()

    @api.post("/api/v1/subscriptions", dependencies=[Depends(core.admin_auth)])
    def subscriptions_create_legacy(subscription: Subscription):
        raise HTTPException(410,"Subscriptions are managed in Wallos. Use its UI or the Personal Finance MCP.")

    @api.get("/api/v1/subscriptions",dependencies=[Depends(core.admin_auth)])
    def subscriptions(active_only: bool=False):
        from wallos_api import get_all,WallosError
        try:
            rows=get_all()
            return {"items":[s for s in rows if s["active"]] if active_only else rows, "source":"Wallos"}
        except WallosError as exc:
            raise HTTPException(503,str(exc))

    @api.put("/api/v1/subscriptions/{subscription_id}", dependencies=[Depends(core.admin_auth)])
    def subscriptions_update_legacy(subscription_id:int,subscription:Subscription):
        raise HTTPException(410,"Subscriptions are managed in Wallos. Use its UI or the Personal Finance MCP.")

    @api.patch("/api/v1/transactions/{tx_id}/review",dependencies=[Depends(core.admin_auth)])
    def review(tx_id:int, review: Review):
        with core.db_connection() as db:
            cur=db.execute("UPDATE transactions SET review_status=? WHERE id=?",(review.status,tx_id))
            if cur.rowcount!=1: raise HTTPException(404,"Transaction not found")
        return {"updated":True}

    @api.get("/api/v1/candidates",dependencies=[Depends(core.admin_auth)])
    def recurring_candidates(months:int=Query(12,ge=2,le=60)):
        """Suggestions only; never silently create subscription or charge user."""
        now=int(datetime.now(tz=timezone.utc).timestamp()*1000)
        earliest=now-months*31*86400*1000
        with core.db_connection() as db:
            rows=db.execute("""SELECT * FROM transactions
                    WHERE direction='EXPENSE' AND timestamp_ms>=?
                    AND review_status NOT IN ('excluded','transfer','refund')
                    ORDER BY timestamp_ms""",(earliest,)).fetchall()
        by_merchant=defaultdict(list)
        for row in rows:
            m=re.sub(r"\\s+","",row["merchant"]).casefold()
            if m and m not in ("unknown","未知","其他","微信支付","支付宝"):
                by_merchant[(m,row["currency"],row["amount_cents"])].append(row)
        candidates=[]
        for (merchant,currency,amount),records in by_merchant.items():
            if len(records)<2:continue
            unique_dates=sorted({datetime.fromtimestamp(r["timestamp_ms"]/1000,timezone.utc).date() for r in records})
            if len(unique_dates)<2:continue
            monthly=sum(1 for a,b in zip(unique_dates,unique_dates[1:]) if 25<=(b-a).days<=35)
            annual=sum(1 for a,b in zip(unique_dates,unique_dates[1:]) if 350<=(b-a).days<=380)
            if monthly or annual:
                candidates.append({"merchant":records[-1]["merchant"],"currency":currency,
                  "amount_cents":amount,"cadence":"monthly" if monthly>=annual else "yearly",
                  "occurrences":len(records),"matching_intervals":max(monthly,annual),
                  "last_seen_ms":max(r["timestamp_ms"] for r in records),
                  "status":"requires_user_confirmation"})
        return {"items":sorted(candidates,key=lambda r:r["matching_intervals"],reverse=True)}

    @api.post("/api/v1/import/standard-csv",dependencies=[Depends(core.admin_auth)])
    async def import_standard_csv(file:UploadFile=File(...)):
        """Explicitly imports a standard normalized CSV, never attempts brittle guessing on bank exports."""
        data=await file.read(3_000_001)
        if len(data)>3_000_000:raise HTTPException(413,"CSV too large")
        try:
            txt=data.decode("utf-8-sig")
            rows=csv.DictReader(io.StringIO(txt))
            required={"timestamp_ms","amount_cents","currency","direction","merchant","source"}
            if not required.issubset(set(rows.fieldnames or [])):
                raise HTTPException(422,"Missing required columns: "+",".join(sorted(required)))
            inserted=0
            rejected=0
            with core.db_connection() as db:
                for i,row in enumerate(rows):
                    if i>10000:raise HTTPException(413,"Too many rows")
                    try:
                        timestamp=int(row["timestamp_ms"])
                        amount=int(row["amount_cents"])
                        source=row["source"]
                        direction=row["direction"]
                        if timestamp<=946684800000 or timestamp>=4102444800000 or amount<0 or amount>100_000_000_000:raise ValueError()
                        if timestamp < core.ledger_start_ms(db):
                            rejected += 1
                            continue
                        if source not in ("WECHAT","ALIPAY","BANK_SMS","MANUAL","OTHER") or direction not in ("EXPENSE","INCOME"):raise ValueError()
                        currency=row["currency"].upper()
                        if not re.fullmatch(r"[A-Z]{3}",currency):raise ValueError()
                        merchant=(row.get("merchant") or "")[:140]
                        cat=(row.get("category") or "OTHER")[:45]
                        dedup="csv:"+hashlib.sha256(("|".join([str(timestamp),str(amount),currency,direction,source,merchant])).encode()).hexdigest()
                        inserted+=db.execute("""INSERT OR IGNORE INTO transactions
                          (event_id,timestamp_ms,amount_cents,currency,direction,source,category,merchant,review_status)
                          VALUES (?,?,?,?,?,?,?,?,?)""",
                          (dedup,timestamp,amount,currency,direction,source,cat,merchant,"unverified")).rowcount
                    except (ValueError,TypeError,KeyError):rejected+=1
            return {"inserted":inserted,"invalid_rows":rejected,
                    "notice":"Imports are not reconciled automatically against notifications; review possible duplicates."}
        except UnicodeDecodeError:
            raise HTTPException(422,"Only UTF-8/UTF-8-BOM CSV supported")

    @api.get("/api/v1/dashboard",dependencies=[Depends(core.admin_auth)])
    def dashboard():
        now=datetime.now(timezone.utc)
        start=datetime(now.year,now.month,1,tzinfo=timezone.utc)
        start_ms=int(start.timestamp()*1000); end_ms=int(now.timestamp()*1000)
        with core.db_connection() as db:
            totals=db.execute("""SELECT currency,direction,COUNT(*) AS count,SUM(amount_cents) AS cents
               FROM transactions WHERE timestamp_ms>=? AND timestamp_ms<=?
               AND review_status NOT IN ('excluded','transfer','refund')
               GROUP BY currency,direction""",(start_ms,end_ms)).fetchall()
            recent=db.execute("SELECT * FROM transactions ORDER BY timestamp_ms DESC LIMIT 25").fetchall()
        from wallos_api import get_all, WallosError
        try:
            subs=[row for row in get_all() if row["active"]]
            wallos_available=True
        except WallosError:
            subs=[]
            wallos_available=False
        equivalents=defaultdict(float)
        for row in subs:
            factor={"monthly":1,"yearly":1/12,"quarterly":1/3,"weekly":52/12,"one_time":0}.get(row["cadence"],0)
            equivalents[row["currency"]] += row["amount_cents"]*factor
        upcoming=sum(1 for row in subs if row["next_due_ms"] and end_ms<=row["next_due_ms"]<=end_ms+7*86400000)
        return {"month":start.strftime("%Y-%m"),"totals":[dict(r) for r in totals],
                "subscriptions":subs,"subscription_source":"Wallos",
                "subscription_service_available":wallos_available,
                "monthly_equivalent_cents":{k:round(v) for k,v in equivalents.items()},
                "upcoming_7d":upcoming,
                "recent":[dict(r) for r in recent]}

    @api.get("/api/v1/export/standard-csv",dependencies=[Depends(core.admin_auth)])
    def export_standard_csv():
        from fastapi.responses import Response
        with core.db_connection() as db:
            rows=db.execute("""SELECT timestamp_ms,amount_cents,currency,direction,source,merchant,category
                   FROM transactions ORDER BY timestamp_ms ASC LIMIT 100000""").fetchall()
        output=io.StringIO()
        writer=csv.writer(output)
        writer.writerow(["timestamp_ms","amount_cents","currency","direction","source","merchant","category"])
        for row in rows:
            cells=list(row)
            for col in (5,6):
                if str(cells[col]).startswith(("=","+","-","@")):
                    cells[col]="'"+str(cells[col])
            writer.writerow(cells)
        return Response("\ufeff"+output.getvalue(),media_type="text/csv; charset=utf-8",
                        headers={"Content-Disposition":'attachment; filename="finance-transactions.csv"'},
                        )
