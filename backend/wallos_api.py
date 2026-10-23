"""Direct Wallos HTTP API client.

Wallos is the single subscription database. The finance DB is only a transaction
ledger; the original subscription table is retained as historical migration data.
"""
from datetime import datetime
from zoneinfo import ZoneInfo
import json
import sqlite3
from decimal import Decimal, ROUND_HALF_UP, InvalidOperation
from pathlib import Path
import urllib.parse
import urllib.request

from wallos_bridge import get_api_key
from config import WALLOS_API_URL, WALLOS_DB, LOCAL_TIMEZONE

WALLOS = WALLOS_API_URL
CURRENCIES={"EUR":1,"USD":2,"JPY":3,"GBP":7,"CNY":20,"HKD":21}
CURRENCY_CODES={value:key for key,value in CURRENCIES.items()}
CYCLES={"weekly":(1,2),"monthly":(1,3),"quarterly":(3,3),"yearly":(1,4)}
# Wallos set_subscriptions.php accepts cycle IDs 1..4; its former "one_time"=5
# mapping was impossible to persist. Do not pretend this cadence is supported.
LOCAL_TZ=ZoneInfo(LOCAL_TIMEZONE)

class WallosError(Exception):
    pass

def call(endpoint: str, data: dict) -> dict:
    token=get_api_key()
    if not token:raise WallosError("Wallos account or API access unavailable")
    payload=urllib.parse.urlencode({"api_key":token,**data}).encode()
    request=urllib.request.Request(WALLOS+endpoint,data=payload,
                       headers={"Content-Type":"application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(request,timeout=12) as response:
            result=json.load(response)
    except (OSError,ValueError) as exc:
        raise WallosError("Wallos API request failed") from exc
    if not result.get("success"):
        raise WallosError(str(result.get("message") or result.get("title") or "Wallos error")[:150])
    return result

def subscription_start_ms(raw) -> int | None:
    if raw in (None, "", 0, "0"):return None
    try:
        if isinstance(raw,(int,float)) or str(raw).isdigit():
            n=int(raw)
            if n<946684800:return None
            return n if n>10_000_000_000 else n*1000
        return int(datetime.strptime(str(raw)[:10],"%Y-%m-%d")
                   .replace(tzinfo=LOCAL_TZ).timestamp()*1000)
    except (ValueError,TypeError,OverflowError):return None

def normalize(row: dict) -> dict:
    cycle=int(row.get("cycle",0))
    frequency=int(row.get("frequency",0))
    cadence=next((c for c,parts in CYCLES.items() if parts==(frequency,cycle)),
                 f"every_{frequency}_units_{cycle}")
    due=row.get("next_payment") or ""
    try:
        ms=int(datetime.strptime(str(due),"%Y-%m-%d").replace(tzinfo=LOCAL_TZ).timestamp()*1000)
    except ValueError:
        ms=None
    try:
        price_cents=int((Decimal(str(row.get("price") or "0"))*100)
                        .quantize(Decimal("1"),rounding=ROUND_HALF_UP))
    except (InvalidOperation,ValueError):
        price_cents=0
    return {
        "id":int(row["id"]),"name":row["name"],
        "amount_cents":price_cents,
        "currency":CURRENCY_CODES.get(int(row.get("currency_id") or 0),"UNKNOWN"),
        "cadence":cadence,"next_due_ms":ms,
        "start_ms":subscription_start_ms(row.get("start_date")),
        "payment_channel":row.get("payment_method_name") or "",
        "auto_renew":bool(int(row.get("auto_renew",1) or 0)),
        "notify":bool(int(row.get("notify") or 0)),
        "notify_days_before":row.get("notify_days_before"),
        "active":not bool(int(row.get("inactive") or 0)),
        "source":"Wallos", "payment_confirmed":False,
    }

def get_all():
    result=call("/api/subscriptions/get_subscriptions.php",{})
    return sorted([normalize(item) for item in result.get("subscriptions",[])],
                  key=lambda row:row["next_due_ms"] or 0)

def valid_subscription_links(payment_channel: str = "") -> dict:
    """Resolve required Wallos UI relationships from the owner's actual DB.

    Omission used to create subscriptions with NULL member/category/payment_method
    which broke Wallos subscriptions.php under PHP 8.
    """
    path=WALLOS_DB
    with sqlite3.connect(f"file:{path}?mode=ro",uri=True) as db:
        owners=db.execute("SELECT id FROM user").fetchall()
        if len(owners)!=1:
            raise WallosError("Expected one Wallos owner")
        owner=owners[0][0]
        household=db.execute("SELECT id FROM household WHERE user_id=? ORDER BY id LIMIT 1",(owner,)).fetchone()
        category=db.execute("SELECT id FROM categories WHERE user_id=? AND name=? LIMIT 1",(owner,"无分类")).fetchone()
        if not category:
            category=db.execute("SELECT id FROM categories WHERE user_id=? ORDER BY id LIMIT 1",(owner,)).fetchone()
        channel=payment_channel.strip().lower()
        unspecified=channel in ("", "未指定（待确认）")
        if unspecified:
            method_name="未指定（待确认）"
        elif channel in ("alipay","支付宝"):
            method_name="AliPay"
        elif channel in ("wechat","微信","微信支付"):
            method_name="WeChat"
        elif channel in ("credit card","debit card","银行卡","信用卡"):
            method_name="Credit Card"
        else:
            raise WallosError("Unsupported payment channel; use a known Wallos payment method")
        method=db.execute("SELECT id FROM payment_methods WHERE user_id=? AND enabled=1 AND name=? LIMIT 1",
                          (owner,method_name)).fetchone()
        if not method and unspecified:
            # A new upstream installation may have no custom unspecified method.
            method=db.execute("""SELECT id FROM payment_methods
                WHERE user_id=? AND enabled=1
                ORDER BY CASE WHEN name IN ('Money','Cash','现金') THEN 0 ELSE 1 END,id LIMIT 1""",
                (owner,)).fetchone()
        if not method and not unspecified:
            raise WallosError("Specified payment channel is not configured in Wallos")
        if not (household and category and method):
            raise WallosError("Wallos member, category or payment method missing")
        return {"payer_user_id":household[0],"category_id":category[0],
                "payment_method_id":method[0]}

def add(name: str,amount_cents:int,currency:str,cadence:str,next_due_ms:int,
        payment_channel:str="",start_date:str|None=None,
        auto_renew:bool=True,notify:bool=False,notify_days_before:int|None=None):
    """Create through the native Wallos HTTP API; never write Wallos SQLite."""
    if currency not in CURRENCIES or cadence not in CYCLES:
        raise WallosError("Unsupported currency or cadence")
    freq,cycle=CYCLES[cadence]
    local_date=datetime.fromtimestamp(next_due_ms/1000,LOCAL_TZ).strftime("%Y-%m-%d")
    links=valid_subscription_links(payment_channel)
    payload={
        **links,
        "action":"add","name":name,"price":f"{amount_cents/100:.2f}",
        "currency_id":CURRENCIES[currency],"frequency":freq,"cycle":cycle,
        "next_payment":local_date,"auto_renew":int(auto_renew),"notify":int(notify),
        "notes":("Payment channel: "+payment_channel) if payment_channel else ""
    }
    if start_date is not None: payload["start_date"]=start_date
    if notify_days_before is not None: payload["notify_days_before"]=notify_days_before
    result=call("/api/subscriptions/set_subscriptions.php",payload)
    return int(result["subscriptionId"])


def edit(subscription_id:int, **changes):
    """Partial edit through native Wallos API. Missing fields remain untouched."""
    payload={"action":"edit","id":subscription_id}
    for key,value in changes.items():
        if key=="name": payload["name"]=value
        elif key=="amount_cents": payload["price"]=f"{value/100:.2f}"
        elif key=="currency": payload["currency_id"]=CURRENCIES[value]
        elif key=="cadence":
            payload["frequency"],payload["cycle"]=CYCLES[value]
        elif key=="next_due_ms":
            payload["next_payment"]=datetime.fromtimestamp(value/1000,LOCAL_TZ).strftime("%Y-%m-%d")
        elif key=="start_date": payload["start_date"]=value
        elif key=="payment_channel":
            payload["payment_method_id"]=valid_subscription_links(value)["payment_method_id"]
        elif key in ("auto_renew","notify"): payload[key]=int(value)
        elif key=="active": payload["inactive"]=int(not value)
        elif key=="notify_days_before": payload[key]=value
        else: raise WallosError("Unsupported edit field: "+key)
    if len(payload)==2: raise WallosError("No edit fields supplied")
    call("/api/subscriptions/set_subscriptions.php",payload)
    return subscription_id
