"""Restricted Wallos extension API: day-to-day transaction ledger, not subscriptions.

Authenticated by a separate server-side-only token. This token can list and
manually append/review ledger rows but cannot access normal admin endpoints.
"""
import hmac
import csv
import io
import os
import sqlite3
import uuid
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
from config import LOCAL_TIMEZONE

from fastapi import APIRouter, Depends, Header, HTTPException, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/v1/wallos-ledger")
LOCAL = ZoneInfo(LOCAL_TIMEZONE)

CATEGORIES = {
    "FOOD": "餐饮", "TRANSPORT": "交通", "SHOPPING": "购物",
    "ENTERTAINMENT": "娱乐", "HOUSING": "住房", "UTILITIES": "水电通信",
    "HEALTH": "医疗", "EDUCATION": "学习", "SUBSCRIPTION": "会员订阅",
    "OTHER": "其他", "SALARY": "工资", "TRANSFER": "转账",
}
CURRENCIES = {"CNY","USD","HKD","EUR","JPY","GBP"}

def require_wallos_token(x_wallos_ledger_token: str | None = Header(default=None)) -> None:
    expected=os.getenv("FINANCE_WALLOS_LEDGER_TOKEN","")
    if not expected or not x_wallos_ledger_token or not hmac.compare_digest(expected,x_wallos_ledger_token):
        raise HTTPException(status_code=401,detail="Not authorized")

class NewEntry(BaseModel):
    idempotency_key: str = Field(pattern=r"^[0-9a-fA-F-]{36}$",max_length=36)
    timestamp_ms: int = Field(gt=946684800000,lt=4102444800000)
    amount_cents: int = Field(gt=0,lt=100_000_000_000)
    currency: str = Field(default="CNY")
    direction: str = Field(default="EXPENSE")
    category: str = Field(default="OTHER")
    merchant: str = Field(default="",max_length=140)

class ReviewEntry(BaseModel):
    status: str

def check_new(t: NewEntry) -> None:
    if t.currency not in CURRENCIES or t.direction not in {"EXPENSE","INCOME"} or t.category not in CATEGORIES:
        raise HTTPException(status_code=422,detail="Invalid currency, direction, or category")
    try:
        if str(uuid.UUID(t.idempotency_key)) != t.idempotency_key.lower():
            raise ValueError()
    except ValueError:
        raise HTTPException(status_code=422,detail="Invalid request ID")

@router.get("/month", dependencies=[Depends(require_wallos_token)])
def month_view(
    month: str = Query(...,pattern=r"^\d{4}-(0[1-9]|1[0-2])$"),
    day: str | None = Query(default=None,pattern=r"^\d{4}-\d{2}-\d{2}$"),
    category: str | None = None,
    direction: str | None = None,
    q: str = Query(default="",max_length=80),
    offset: int = Query(default=0,ge=0,le=1000000),
):
    """Live read from the Android-ledger SQLite; no duplicate transaction store.

    Monthly, today's and trailing-seven-day summaries use identical exclusion
    rules. Day/category/search filters affect the list, NOT monthly totals.
    """
    import app as core
    try:
        year, mon = map(int,month.split("-"))
        start = datetime(year,mon,1,tzinfo=LOCAL)
        end = datetime(year+1,1,1,tzinfo=LOCAL) if mon==12 else datetime(year,mon+1,1,tzinfo=LOCAL)
        if day:
            parsed = datetime.strptime(day,"%Y-%m-%d").replace(tzinfo=LOCAL)
            if not (start<=parsed<end):
                raise ValueError("Day outside selected month")
    except ValueError:
        raise HTTPException(status_code=422,detail="Invalid month/day")
    if category and category not in CATEGORIES:
        raise HTTPException(status_code=422,detail="Invalid category")
    if direction and direction not in {"EXPENSE","INCOME"}:
        raise HTTPException(status_code=422,detail="Invalid direction")
    start_ms=int(start.timestamp()*1000)
    end_ms=int(end.timestamp()*1000)
    now=datetime.now(LOCAL)
    today=datetime(now.year,now.month,now.day,tzinfo=LOCAL)
    today_ms=int(today.timestamp()*1000)
    tomorrow_ms=int((today+timedelta(days=1)).timestamp()*1000)
    trailing_ms=int((today-timedelta(days=6)).timestamp()*1000)
    valid="review_status NOT IN ('excluded','transfer','refund')"
    conditions=["timestamp_ms>=?","timestamp_ms<?"]
    params=[start_ms,end_ms]
    if day:
        parsed=datetime.strptime(day,"%Y-%m-%d").replace(tzinfo=LOCAL)
        conditions+=["timestamp_ms>=?","timestamp_ms<?"]
        params+=[int(parsed.timestamp()*1000),int((parsed+timedelta(days=1)).timestamp()*1000)]
    if category:
        conditions.append("category=?");params.append(category)
    if direction:
        conditions.append("direction=?");params.append(direction)
    if q.strip():
        # Escape LIKE operators: user input is a literal substring, not a pattern.
        pattern=q.strip().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")
        conditions.append("merchant LIKE ? ESCAPE '\\'")
        params.append("%"+pattern+"%")
    where=" AND ".join(conditions)
    with core.db_connection() as db:
        rows=db.execute(f"""SELECT id,timestamp_ms,amount_cents,currency,direction,source,category,
                merchant,review_status FROM transactions WHERE {where}
                ORDER BY timestamp_ms DESC,id DESC LIMIT 101 OFFSET ?""",(*params,offset)).fetchall()
        total=db.execute(f"""SELECT currency,direction,category,COUNT(*) AS count,
                SUM(amount_cents) AS total_cents
                FROM transactions WHERE timestamp_ms>=? AND timestamp_ms<? AND {valid}
                GROUP BY currency,direction,category""",(start_ms,end_ms)).fetchall()
        # Group by the configured IANA timezone, not a hard-coded UTC offset.
        # This also handles daylight-saving transitions correctly.
        daily_points=db.execute(f"""SELECT timestamp_ms,currency,direction,amount_cents
                FROM transactions WHERE timestamp_ms>=? AND timestamp_ms<? AND {valid}
                ORDER BY timestamp_ms""",(start_ms,end_ms)).fetchall()
        daily_groups={}
        for event in daily_points:
            local_day=datetime.fromtimestamp(event["timestamp_ms"]/1000,LOCAL).strftime("%Y-%m-%d")
            key=(local_day,event["currency"],event["direction"])
            group=daily_groups.setdefault(key,{"day":local_day,"currency":event["currency"],
                                                 "direction":event["direction"],"total_cents":0,"count":0})
            group["total_cents"]+=event["amount_cents"]
            group["count"]+=1
        daily=[daily_groups[key] for key in sorted(daily_groups)]
        def period_total(a,b):
            return [dict(r) for r in db.execute(f"""SELECT currency,direction,
                SUM(amount_cents) AS total_cents,COUNT(*) AS count
                FROM transactions WHERE timestamp_ms>=? AND timestamp_ms<? AND {valid}
                GROUP BY currency,direction""",(a,b)).fetchall()]
        today_totals=period_total(today_ms,tomorrow_ms)
        last7_totals=period_total(trailing_ms,tomorrow_ms)
        previous_end=start
        previous_start=datetime(year-1,12,1,tzinfo=LOCAL) if mon==1 else datetime(year,mon-1,1,tzinfo=LOCAL)
        previous_month_totals=period_total(int(previous_start.timestamp()*1000),int(previous_end.timestamp()*1000))
        monthly_count=db.execute("SELECT COUNT(*) FROM transactions WHERE timestamp_ms>=? AND timestamp_ms<?",(start_ms,end_ms)).fetchone()[0]
        last_synced=db.execute("SELECT MAX(created_at) FROM transactions").fetchone()[0]
        budget=db.execute("SELECT amount_cents FROM expense_budgets WHERE month=?",(month,)).fetchone()
    has_more=len(rows)>100
    return {
        "month":month,
        "items":[dict(r) for r in rows[:100]],
        "totals":[dict(r) for r in total],
        "today_totals":today_totals,
        "last7_totals":last7_totals,
        "previous_month_totals":previous_month_totals,
        "daily_totals":[dict(r) for r in daily],
        "count_in_page":min(len(rows),100),
        "month_count":monthly_count,
        "budget_cny_cents":budget[0] if budget else None,
        "offset":offset,"next_offset":offset+100 if has_more else None,
        "filters":{"day":day,"category":category,"direction":direction,"q":q},
        "last_synced_at_utc":last_synced,
        "category_labels":CATEGORIES,
        "note":"Actual, recorded expenses only. 7-day window includes today. No FX assumptions.",
    }


@router.get("/export", dependencies=[Depends(require_wallos_token)])
def export_month_csv(month: str = Query(...,pattern=r"^\d{4}-(0[1-9]|1[0-2])$")):
    """Read-only CSV export of all recorded transactions in one local month.

    Export also contains excluded records with explicit review status, so the
    user can reconcile against payment-provider statements.
    """
    import app as core
    try:
        year,mon=map(int,month.split("-"))
        start=datetime(year,mon,1,tzinfo=LOCAL)
        end=datetime(year+1,1,1,tzinfo=LOCAL) if mon==12 else datetime(year,mon+1,1,tzinfo=LOCAL)
    except ValueError:
        raise HTTPException(422,"Invalid month")
    def safe(value):
        text=str(value if value is not None else "")
        # Prevent formulas from running if the user opens the exported CSV.
        if text.lstrip()[:1] in ("=","+","-","@"):
            text="'"+text
        return text
    def stream():
        yield "\ufeff"
        names=["北京时间","金额","币种","收支","商户","分类","来源","审核状态"]
        buf=io.StringIO()
        writer=csv.writer(buf)
        writer.writerow(names);yield buf.getvalue();buf.seek(0);buf.truncate(0)
        with core.db_connection() as db:
            rows=db.execute("""SELECT timestamp_ms,amount_cents,currency,direction,
                 merchant,category,source,review_status
                 FROM transactions WHERE timestamp_ms>=? AND timestamp_ms<?
                 ORDER BY timestamp_ms DESC,id DESC""",
                 (int(start.timestamp()*1000),int(end.timestamp()*1000)))
            for row in rows:
                stamp=datetime.fromtimestamp(row["timestamp_ms"]/1000,LOCAL).strftime("%Y-%m-%d %H:%M:%S")
                writer.writerow([stamp,f"{row['amount_cents']/100:.2f}",row["currency"],row["direction"],
                      safe(row["merchant"]),safe(row["category"]),safe(row["source"]),safe(row["review_status"])])
                yield buf.getvalue()
                buf.seek(0);buf.truncate(0)
    return StreamingResponse(stream(),media_type="text/csv; charset=utf-8",
         headers={"Content-Disposition":f'attachment; filename="finance-{month}.csv"',"Cache-Control":"no-store"})


class BudgetUpdate(BaseModel):
    amount_cents: int = Field(ge=0,le=100_000_000_000)

@router.put("/budget/{month}", dependencies=[Depends(require_wallos_token)])
def set_monthly_budget(month: str, t:BudgetUpdate):
    """Personal daily-expense CNY budget, kept separate from Wallos subscription budgets."""
    import app as core
    try:
        year,mon=map(int,month.split("-"))
        if not (2000<=year<=2099 and 1<=mon<=12 and month==f"{year:04d}-{mon:02d}"):
            raise ValueError()
    except ValueError:
        raise HTTPException(422,"Invalid month")
    with core.db_connection() as db:
        if t.amount_cents==0:
            db.execute("DELETE FROM expense_budgets WHERE month=?",(month,))
        else:
            db.execute("""INSERT INTO expense_budgets(month,amount_cents) VALUES(?,?)
                      ON CONFLICT(month) DO UPDATE SET amount_cents=excluded.amount_cents""",
                      (month,t.amount_cents))
    return {"ok":True,"month":month,"budget_cny_cents":t.amount_cents or None}


@router.post("/manual", dependencies=[Depends(require_wallos_token)])
def add_manual(t: NewEntry):
    import app as core
    check_new(t)
    event_id="wallos:manual:"+t.idempotency_key.lower()
    with core.db_connection() as db:
        if t.timestamp_ms < core.ledger_start_ms(db):
            raise HTTPException(status_code=422, detail="Transaction predates the new ledger start (2026-10-10 CST)")
        cur=db.execute("""INSERT OR IGNORE INTO transactions
            (event_id,timestamp_ms,amount_cents,currency,direction,source,category,merchant,review_status)
            VALUES (?,?,?,?,?,'MANUAL',?,?, 'unverified')""",
            (event_id,t.timestamp_ms,t.amount_cents,t.currency,t.direction,t.category,t.merchant))
        row=db.execute("SELECT id FROM transactions WHERE event_id=?",(event_id,)).fetchone()
    return {"ok":True,"created":cur.rowcount==1,"id":row["id"]}

@router.patch("/review/{entry_id}", dependencies=[Depends(require_wallos_token)])
def review(entry_id:int,t:ReviewEntry):
    import app as core
    if t.status not in {"confirmed","unverified","excluded","transfer","refund","possible_duplicate"}:
        raise HTTPException(status_code=422,detail="Invalid review status")
    with core.db_connection() as db:
        cur=db.execute("UPDATE transactions SET review_status=? WHERE id=?",(t.status,entry_id))
        if cur.rowcount!=1:raise HTTPException(status_code=404,detail="Transaction not found")
    return {"ok":True}
