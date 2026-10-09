"""Wallos <-> actual-payment integration. No synthesized payment transactions.

Wallos remains source of subscription schedules. Finance SQLite owns transaction
edits, merchant rules, per-cycle links, refunds and audit history.
"""
from __future__ import annotations
import calendar
import re
import sqlite3
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field
from typing import Literal

from wallos_ledger import require_wallos_token, CATEGORIES
from wallos_api import get_all, WallosError
from config import LOCAL_TIMEZONE

router=APIRouter(prefix="/api/v1/wallos-ledger",dependencies=[Depends(require_wallos_token)])
TZ=ZoneInfo(LOCAL_TIMEZONE)
VALID="review_status NOT IN ('excluded','transfer','refund')"

def schema(db: sqlite3.Connection):
    db.execute("""CREATE TABLE IF NOT EXISTS tx_subscription_links(
        tx_id INTEGER PRIMARY KEY REFERENCES transactions(id) ON DELETE CASCADE,
        sub_id INTEGER NOT NULL, cycle_key TEXT NOT NULL,
        linked_by TEXT NOT NULL DEFAULT 'user',
        created_at TEXT DEFAULT CURRENT_TIMESTAMP)""")
    db.execute("CREATE UNIQUE INDEX IF NOT EXISTS link_per_cycle ON tx_subscription_links(sub_id,cycle_key)")
    db.execute("""CREATE TABLE IF NOT EXISTS merchant_rules (
        id INTEGER PRIMARY KEY, needle TEXT NOT NULL COLLATE NOCASE UNIQUE,
        category TEXT NOT NULL, sub_id INTEGER,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP)""")
    db.execute("""CREATE TABLE IF NOT EXISTS refund_links(
        refund_tx_id INTEGER PRIMARY KEY REFERENCES transactions(id),
        original_tx_id INTEGER NOT NULL REFERENCES transactions(id),
        amount_cents INTEGER NOT NULL CHECK(amount_cents>0))""")
    db.execute("""CREATE TABLE IF NOT EXISTS transaction_edits(
        id INTEGER PRIMARY KEY,tx_id INTEGER NOT NULL, field TEXT NOT NULL,
        old_value TEXT, new_value TEXT,
        created_at TEXT DEFAULT CURRENT_TIMESTAMP)""")

def resolve_category(db, merchant:str, category:str):
    """User-approved rules override automatic classification only for OTHER."""
    if category!="OTHER" or not merchant:return category
    for r in db.execute("SELECT needle,category FROM merchant_rules ORDER BY LENGTH(needle) DESC"):
        if r["needle"].casefold() in merchant.casefold():
            return r["category"]
    return category

def dt(ms:int):
    return datetime.fromtimestamp(ms/1000,TZ)

def boundaries(month:str):
    try:
        y,m=map(int,month.split("-"))
        if not (2000<=y<=2099 and 1<=m<=12 and month==f"{y:04d}-{m:02d}"): raise ValueError()
    except ValueError: raise HTTPException(422,"Invalid month")
    a=datetime(y,m,1,tzinfo=TZ)
    b=datetime(y+1,1,1,tzinfo=TZ) if m==12 else datetime(y,m+1,1,tzinfo=TZ)
    return int(a.timestamp()*1000),int(b.timestamp()*1000)

def month_shift(d:datetime, months:int)->datetime:
    index=d.year*12+d.month-1+months
    y,m=divmod(index,12);m+=1
    return d.replace(year=y,month=m,day=min(d.day,calendar.monthrange(y,m)[1]))

def occurrences(s:dict, first_ms:int,last_ms:int)->list:
    due_ms=s.get("next_due_ms")
    if not due_ms or s.get("cadence") not in ("weekly","monthly","quarterly","yearly","one_time"):return []
    start=dt(first_ms);end=dt(last_ms)
    due=dt(due_ms)
    step=s["cadence"]
    # Wallos next_payment can be a future date. Project backward as well,
    # otherwise the current month would have no scheduled subscription.
    for _ in range(900):
        if due<end or step=="one_time":break
        due=due-timedelta(days=7) if step=="weekly" else month_shift(due,-{"monthly":1,"quarterly":3,"yearly":12}[step])
    result=[]
    for _ in range(900):
        if due < start:
            step=s["cadence"]
            if step=="one_time":break
            due=due+timedelta(days=7) if step=="weekly" else month_shift(due, {"monthly":1,"quarterly":3,"yearly":12}[step])
            continue
        if due>=end:break
        result.append({"subscription_id":s["id"],"name":s["name"],"amount_cents":s["amount_cents"],
                       "currency":s["currency"],"payment_channel":s["payment_channel"],
                       "due_ms":int(due.timestamp()*1000),"cycle_key":due.strftime("%Y-%m-%d")})
        step=s["cadence"]
        if step=="one_time":break
        due=due+timedelta(days=7) if step=="weekly" else month_shift(due, {"monthly":1,"quarterly":3,"yearly":12}[step])
    return result

def forecast(db, month, subscriptions):
    a,b=boundaries(month)
    # Allow nearby billing date variance without altering Wallos schedules.
    items=[]
    for sub in subscriptions:
        if not sub["active"]:continue
        items.extend(occurrences(sub,a,b))
    items.sort(key=lambda r:r["due_ms"])
    links={ (r["sub_id"],r["cycle_key"]):dict(r) for r in db.execute(
        """SELECT l.*,t.amount_cents AS paid_cents,t.currency AS paid_currency,
        t.merchant AS merchant,t.source AS source,t.review_status AS review_status
        FROM tx_subscription_links l JOIN transactions t ON t.id=l.tx_id""") }
    for item in items:
        link=links.get((item["subscription_id"],item["cycle_key"]))
        item["transaction_id"]=link["tx_id"] if link else None
        item["paid_cents"]=link["paid_cents"] if link else None
        item["payment_source"]=link["source"] if link else None
        item["status"]="matched" if link else "pending"
    return items

def expected_payment_source(channel:str)->str|None:
    text=(channel or "").casefold()
    if "wechat" in text or "微信" in text:return "WECHAT"
    if "alipay" in text or "支付宝" in text:return "ALIPAY"
    if "bank transfer" in text or "银行转账" in text:return "BANK_SMS"
    return None

def candidate_matches(db, month, schedule):
    a,b=boundaries(month)
    events=db.execute("""SELECT t.id,t.timestamp_ms,t.amount_cents,t.currency,t.merchant,t.source,
       t.review_status FROM transactions t LEFT JOIN tx_subscription_links l ON l.tx_id=t.id
       WHERE t.timestamp_ms>=? AND t.timestamp_ms<? AND t.direction='EXPENSE'
       AND t.review_status NOT IN ('excluded','transfer','refund') AND l.tx_id IS NULL""",(a,b)).fetchall()
    out=[]
    invalid={"","未知","其他","微信支付","支付宝","paid","payment","付款成功"}
    for row in events:
        merchant=row["merchant"].strip().casefold()
        for sub in schedule:
            if sub["transaction_id"] or sub["currency"]!=row["currency"]:continue
            days=abs(row["timestamp_ms"]-sub["due_ms"])/86400000
            if days>6:continue
            amount_diff=abs(row["amount_cents"]-sub["amount_cents"])
            close=(amount_diff<=max(100,round(sub["amount_cents"]*.15)))
            name=sub["name"].strip().casefold()
            strong=bool(merchant not in invalid and name and (name in merchant or merchant in name) and len(merchant)>=3)
            if not (close and (strong or days<=2)):continue
            expected_source=expected_payment_source(sub["payment_channel"])
            channel_match=(expected_source==row["source"]) if expected_source else None
            confidence="high" if strong and amount_diff<=1 and days<=3 and channel_match is not False else "medium" if strong else "low"
            out.append({"tx_id":row["id"],"merchant":row["merchant"],"paid_cents":row["amount_cents"],
              "currency":row["currency"],"payment_source":row["source"],
              "channel_match":channel_match,
              "subscription_id":sub["subscription_id"],"subscription_name":sub["name"],
              "expected_cents":sub["amount_cents"],"cycle_key":sub["cycle_key"],
              "confidence":confidence,"days_apart":round(days,1),
              "requires_confirmation":True})
    return sorted(out,key=lambda x:({"high":0,"medium":1,"low":2}[x["confidence"]],x["days_apart"]))[:100]

def snapshot(month:str):
    import app as core
    try:subs=get_all()
    except WallosError as exc: raise HTTPException(503,"Wallos subscriptions unavailable") from exc
    with core.db_connection() as db:
        schema(db)
        a,b=boundaries(month)
        schedule=forecast(db,month,subs)
        rows=db.execute(f"""SELECT currency,direction,category,amount_cents FROM transactions
            WHERE timestamp_ms>=? AND timestamp_ms<? AND {VALID}""",(a,b)).fetchall()
        totals=defaultdict(int); categories=defaultdict(int)
        for row in rows:
            totals[(row["currency"],row["direction"])]+=row["amount_cents"]
            if row["direction"]=="EXPENSE":categories[(row["currency"],row["category"])]+=row["amount_cents"]
        refunds=db.execute(f"""SELECT t.currency,SUM(r.amount_cents) cents FROM refund_links r
              JOIN transactions t ON t.id=r.original_tx_id WHERE t.timestamp_ms>=?
              AND t.timestamp_ms<? AND {VALID} GROUP BY t.currency""",(a,b)).fetchall()
        refund_map={r["currency"]:r["cents"] for r in refunds}
        refund_records=[dict(r) for r in db.execute("""SELECT r.refund_tx_id,r.original_tx_id,
              r.amount_cents,o.currency FROM refund_links r
              JOIN transactions o ON o.id=r.original_tx_id ORDER BY r.refund_tx_id DESC LIMIT 40""")]
        budget_row=db.execute("SELECT amount_cents FROM expense_budgets WHERE month=?",(month,)).fetchone()
        budget=budget_row[0] if budget_row else None
        matches=candidate_matches(db,month,schedule)
        pending=[x for x in schedule if x["status"]=="pending"]
        paid=[x for x in schedule if x["status"]=="matched"]
        alerts=[]
        now_ms=int(datetime.now(timezone.utc).timestamp()*1000)
        for item in schedule:
            if item["status"]=="matched":
                source_hint=expected_payment_source(item["payment_channel"])
                if source_hint and source_hint!=item["payment_source"]:
                    alerts.append({"kind":"payment_method_mismatch","subscription":item["name"],
                                   "cycle_key":item["cycle_key"],"expected":source_hint,
                                   "actual":item["payment_source"]})
                if abs(item["paid_cents"]-item["amount_cents"])>max(1,round(item["amount_cents"]*.05)):
                    alerts.append({"kind":"price_change","subscription":item["name"],"cycle_key":item["cycle_key"],
                                   "expected_cents":item["amount_cents"],"actual_cents":item["paid_cents"]})
            elif item["due_ms"]<now_ms-2*86400000:
                alerts.append({"kind":"missing_payment","subscription":item["name"],"cycle_key":item["cycle_key"]})
        dups=db.execute("""SELECT COUNT(*) FROM transactions WHERE timestamp_ms>=? AND timestamp_ms<?
              AND review_status='possible_duplicate'""",(a,b)).fetchone()[0]
        if dups:alerts.append({"kind":"possible_duplicates","count":dups})
        # Pending scheduled payments are forecasts only and NEVER enter the actual total.
        reserved=sum(i["amount_cents"] for i in pending if i["currency"]=="CNY")
        net_cny=totals[("CNY","EXPENSE")]-refund_map.get("CNY",0)
        return {"month":month,"subscriptions":schedule,"suggestions":matches,
         "alerts":alerts,"total_subscriptions":len(subs),"active_subscriptions":sum(s["active"] for s in subs),
         "actual_totals":[{"currency":c,"direction":d,"amount_cents":v} for (c,d),v in totals.items()],
         "category_totals":[{"currency":c,"category":cat,"amount_cents":v} for (c,cat),v in categories.items()],
         "linked_count":len(paid),"pending_count":len(pending),"refunds_cents_by_currency":refund_map,
         "refund_links":refund_records,
         "budget_cny_cents":budget,"net_actual_spend_cny_cents":net_cny,
         "expected_unpaid_cny_cents":reserved,
         "forecast_total_cny_cents":net_cny+reserved,
         "budget_remaining_after_forecast_cny_cents":(budget-net_cny-reserved) if budget else None,
         "note":"Expected subscriptions are forecasts only; actual ledger counts matched payments once."}

@router.get("/integration")
def integration(month:str=Query(...)):
    return snapshot(month)

class Link(BaseModel):
    tx_id:int=Field(gt=0)
    subscription_id:int=Field(gt=0)
    cycle_key:str=Field(pattern=r"^\d{4}-\d{2}-\d{2}$")

@router.post("/integration/link")
def link(t:Link):
    import app as core
    try:subs=get_all()
    except WallosError:raise HTTPException(503,"Wallos unavailable")
    sub=next((s for s in subs if s["id"]==t.subscription_id and s["active"]),None)
    if sub is None:raise HTTPException(404,"Active subscription not found")
    try:day=datetime.strptime(t.cycle_key,"%Y-%m-%d").replace(tzinfo=TZ)
    except ValueError:raise HTTPException(422,"Invalid billing date")
    cyc=occurrences(sub,int((day-timedelta(days=1)).timestamp()*1000),int((day+timedelta(days=2)).timestamp()*1000))
    if not any(x["cycle_key"]==t.cycle_key for x in cyc):raise HTTPException(422,"Not a Wallos billing cycle")
    with core.db_connection() as db:
        schema(db)
        tx=db.execute("SELECT * FROM transactions WHERE id=?",(t.tx_id,)).fetchone()
        if not tx:raise HTTPException(404,"Transaction not found")
        if tx["direction"]!="EXPENSE" or tx["review_status"] in ("refund","transfer","excluded"):
            raise HTTPException(422,"Ineligible transaction")
        if tx["currency"]!=sub["currency"]:raise HTTPException(422,"Currency mismatch")
        if abs(tx["timestamp_ms"]-int(day.timestamp()*1000))>7*86400000:
            raise HTTPException(422,"Transaction too far from billing date")
        try:
            db.execute("INSERT INTO tx_subscription_links(tx_id,sub_id,cycle_key) VALUES (?,?,?)",
                       (t.tx_id,t.subscription_id,t.cycle_key))
        except sqlite3.IntegrityError:raise HTTPException(409,"Transaction or billing cycle already linked")
        db.execute("UPDATE transactions SET category='SUBSCRIPTION' WHERE id=? AND category='OTHER'",(t.tx_id,))
    return {"ok":True,"matched":True,"counted_as_expense_once":True}

@router.delete("/integration/link/{tx_id}")
def unlink(tx_id:int):
    import app as core
    with core.db_connection() as db:
        schema(db)
        deleted=db.execute("DELETE FROM tx_subscription_links WHERE tx_id=?",(tx_id,)).rowcount
    if not deleted:raise HTTPException(404,"Link not found")
    return {"ok":True}

class Rule(BaseModel):
    needle:str=Field(min_length=3,max_length=90)
    category:str
    subscription_id:int|None=None

@router.get("/integration/rules")
def rules():
    import app as core
    with core.db_connection() as db:
        schema(db)
        return {"items":[dict(x) for x in db.execute("SELECT * FROM merchant_rules ORDER BY id DESC")]}

@router.post("/integration/rules")
def rule_add(t:Rule):
    import app as core
    if t.category not in CATEGORIES:raise HTTPException(422,"Unknown category")
    needle=t.needle.strip()
    if len(needle)<3 or needle.casefold() in ("微信支付","支付宝","付款成功","unknown","paid"):
        raise HTTPException(422,"Merchant name too generic")
    if t.subscription_id is not None:
        try:
            if not any(s["id"]==t.subscription_id for s in get_all()):
                raise HTTPException(404,"Subscription not found")
        except WallosError:raise HTTPException(503,"Wallos unavailable")
    with core.db_connection() as db:
        schema(db)
        db.execute("""INSERT INTO merchant_rules(needle,category,sub_id) VALUES (?,?,?)
              ON CONFLICT(needle) DO UPDATE SET category=excluded.category,sub_id=excluded.sub_id""",
              (needle,t.category,t.subscription_id))
    return {"ok":True}

@router.delete("/integration/rules/{rule_id}")
def rule_remove(rule_id:int):
    import app as core
    with core.db_connection() as db:
        schema(db)
        n=db.execute("DELETE FROM merchant_rules WHERE id=?",(rule_id,)).rowcount
    if not n:raise HTTPException(404,"Rule not found")
    return {"ok":True}

class Edit(BaseModel):
    amount_cents:int|None=Field(default=None,gt=0,le=100_000_000_000)
    merchant:str|None=Field(default=None,max_length=140)
    category:str|None=None

@router.patch("/integration/transaction/{tx_id}")
def edit_transaction(tx_id:int, t:Edit):
    import app as core
    changes=t.model_dump(exclude_unset=True)
    if not changes:raise HTTPException(422,"No edits supplied")
    if "category" in changes and changes["category"] not in CATEGORIES:raise HTTPException(422,"Unknown category")
    if any(v is None for v in changes.values()):raise HTTPException(422,"Null values not permitted")
    with core.db_connection() as db:
        schema(db)
        row=db.execute("SELECT * FROM transactions WHERE id=?",(tx_id,)).fetchone()
        if not row:raise HTTPException(404,"Transaction not found")
        if "amount_cents" in changes:
            linked=db.execute("SELECT COALESCE(SUM(amount_cents),0) FROM refund_links WHERE original_tx_id=?",
                              (tx_id,)).fetchone()[0]
            if linked>changes["amount_cents"]:
                raise HTTPException(409,"Corrected amount below existing linked refunds")
            income_refund=db.execute("SELECT amount_cents FROM refund_links WHERE refund_tx_id=?",
                                    (tx_id,)).fetchone()
            if income_refund and income_refund[0]>changes["amount_cents"]:
                raise HTTPException(409,"Corrected income below linked refund")
        for key,new in changes.items():
            old=row[key]
            if old!=new:
                db.execute(f"UPDATE transactions SET {key}=? WHERE id=?",(new,tx_id))
                db.execute("INSERT INTO transaction_edits(tx_id,field,old_value,new_value) VALUES (?,?,?,?)",
                           (tx_id,key,str(old),str(new)))
    return {"ok":True,"changed_fields":list(changes)}

class Refund(BaseModel):
    original_tx_id:int=Field(gt=0)
    refund_tx_id:int=Field(gt=0)
    amount_cents:int=Field(gt=0)

@router.post("/integration/refund")
def refund(t:Refund):
    import app as core
    if t.original_tx_id==t.refund_tx_id:raise HTTPException(422,"Cannot refund itself")
    with core.db_connection() as db:
        schema(db)
        original=db.execute("SELECT * FROM transactions WHERE id=?",(t.original_tx_id,)).fetchone()
        received=db.execute("SELECT * FROM transactions WHERE id=?",(t.refund_tx_id,)).fetchone()
        if not original or not received:raise HTTPException(404,"Transaction not found")
        if original["direction"]!="EXPENSE" or received["direction"]!="INCOME":
            raise HTTPException(422,"Refund must link income to expense")
        if original["currency"]!=received["currency"]:raise HTTPException(422,"Currency mismatch")
        if t.amount_cents>received["amount_cents"]:raise HTTPException(422,"Refund exceeds income")
        used=db.execute("SELECT COALESCE(SUM(amount_cents),0) FROM refund_links WHERE original_tx_id=?",
                        (t.original_tx_id,)).fetchone()[0]
        if used+t.amount_cents>original["amount_cents"]:raise HTTPException(422,"Refund exceeds original expense")
        try:db.execute("INSERT INTO refund_links VALUES(?,?,?)",
                       (t.refund_tx_id,t.original_tx_id,t.amount_cents))
        except sqlite3.IntegrityError:raise HTTPException(409,"Refund already linked")
        db.execute("UPDATE transactions SET review_status='refund' WHERE id=?",(t.refund_tx_id,))
    return {"ok":True,"net_spend_reduced_by_cents":t.amount_cents}

@router.delete("/integration/refund/{refund_tx_id}")
def unlink_refund(refund_tx_id:int):
    import app as core
    with core.db_connection() as db:
        schema(db)
        n=db.execute("DELETE FROM refund_links WHERE refund_tx_id=?",(refund_tx_id,)).rowcount
        if n:
            db.execute("UPDATE transactions SET review_status='unverified' WHERE id=? AND review_status='refund'",
                       (refund_tx_id,))
    if not n:raise HTTPException(404,"Refund link not found")
    return {"ok":True}

@router.post("/integration/duplicate/{tx_id}")
def mark_duplicate(tx_id:int):
    import app as core
    with core.db_connection() as db:
        schema(db)
        row=db.execute("SELECT id FROM transactions WHERE id=?",(tx_id,)).fetchone()
        if not row:raise HTTPException(404,"Transaction not found")
        if db.execute("SELECT 1 FROM refund_links WHERE original_tx_id=? OR refund_tx_id=?",
                      (tx_id,tx_id)).fetchone():
            raise HTTPException(409,"Unlink refund before excluding this transaction")
        db.execute("UPDATE transactions SET review_status='excluded' WHERE id=?",(tx_id,))
        db.execute("DELETE FROM tx_subscription_links WHERE tx_id=?",(tx_id,))
    return {"ok":True,"excluded":True}

@router.get("/integration/trends")
def trends(months:int=Query(12,ge=2,le=36)):
    import app as core
    now=datetime.now(TZ)
    start=month_shift(now.replace(day=1,hour=0,minute=0,second=0,microsecond=0),-(months-1))
    with core.db_connection() as db:
        schema(db)
        rows=db.execute(f"""SELECT timestamp_ms,amount_cents,currency,direction,category
             FROM transactions WHERE timestamp_ms>=? AND timestamp_ms<? AND {VALID}""",
             (int(start.timestamp()*1000),int((now+timedelta(days=1)).timestamp()*1000))).fetchall()
        refunds=db.execute("""SELECT o.timestamp_ms, o.currency, r.amount_cents
            FROM refund_links r JOIN transactions o ON o.id=r.original_tx_id
            WHERE o.timestamp_ms>=? AND o.review_status NOT IN ('excluded','transfer','refund')""",(int(start.timestamp()*1000),)).fetchall()
    totals=defaultdict(int)
    for r in rows:totals[(dt(r["timestamp_ms"]).strftime("%Y-%m"),r["currency"],r["direction"])]+=r["amount_cents"]
    for r in refunds:totals[(dt(r["timestamp_ms"]).strftime("%Y-%m"),r["currency"],"REFUND")]+=r["amount_cents"]
    return {"months":months,"series":[{"month":m,"currency":c,"direction":d,"amount_cents":v}
             for (m,c,d),v in sorted(totals.items())],
            "note":"Refunds are separate deductions and are attributed to the original purchase month."}

@router.get("/integration/discover")
def discover(months:int=Query(12,ge=2,le=36)):
    """Suggest possible recurring services, NEVER create a Wallos subscription."""
    import app as core
    threshold=int((datetime.now(TZ)-timedelta(days=31*months)).timestamp()*1000)
    with core.db_connection() as db:
        schema(db)
        rows=db.execute(f"""SELECT * FROM transactions WHERE timestamp_ms>=?
                 AND direction='EXPENSE' AND {VALID} ORDER BY timestamp_ms""",(threshold,)).fetchall()
    groups=defaultdict(list)
    for r in rows:
        merchant=r["merchant"].strip().casefold()
        if merchant and merchant not in ("微信支付","支付宝","其他","unknown","paid"):
            groups[(merchant,r["currency"])].append(r)
    out=[]
    for (merchant,currency),records in groups.items():
        if len(records)<2:continue
        recurring=[]
        for a,b in zip(records,records[1:]):
            gap=(b["timestamp_ms"]-a["timestamp_ms"])/86400000
            if 25<=gap<=35 or 350<=gap<=380:
                if abs(a["amount_cents"]-b["amount_cents"])<=max(100,a["amount_cents"]//5):
                    recurring.append("yearly" if gap>100 else "monthly")
        if recurring:
            cycle=max(set(recurring),key=recurring.count)
            prev=dt(records[-1]["timestamp_ms"])
            next_due=month_shift(prev,12 if cycle=="yearly" else 1)
            out.append({"merchant":records[-1]["merchant"],"currency":currency,
                 "amount_cents":records[-1]["amount_cents"],"cadence":cycle,
                 "next_due_date":next_due.strftime("%Y-%m-%d"),
                 "observed_payments":len(records),"requires_confirmation":True,
                 "note":"Create a subscription in Wallos only after verifying recurring billing."})
    return {"items":out[:100]}

class NewSubscription(BaseModel):
    name:str=Field(min_length=3,max_length=140)
    currency:str=Field(pattern=r"^[A-Z]{3}$")
    amount_cents:int=Field(gt=0,le=100_000_000_000)
    cadence:Literal["monthly","yearly"]
    next_due_ms:int=Field(gt=946684800000,lt=4102444800000)

@router.post("/integration/subscribe")
def create_discovered_subscription(t:NewSubscription):
    """Only an explicit user action may create a Wallos subscription."""
    from wallos_api import add
    try:
        if any(s["active"] and s["name"].casefold()==t.name.casefold()
               and s["currency"]==t.currency and s["cadence"]==t.cadence for s in get_all()):
            raise HTTPException(409,"A similar Wallos subscription already exists")
        sub_id=add(t.name,t.amount_cents,t.currency,t.cadence,t.next_due_ms)
    except WallosError as exc:raise HTTPException(503,str(exc)) from exc
    return {"ok":True,"subscription_id":sub_id,"requires_billing_verification":True}
