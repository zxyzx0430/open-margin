"""Direct Wallos HTTP API client.

Wallos is the single subscription database. The finance DB is only a transaction
ledger; the original subscription table is retained as historical migration data.
"""
from datetime import datetime
from zoneinfo import ZoneInfo
from contextlib import closing
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
        "note":row.get("notes") or "",
        "source":"Wallos", "payment_confirmed":False,
    }

def get_all():
    result=call("/api/subscriptions/get_subscriptions.php",{})
    return sorted([normalize(item) for item in result.get("subscriptions",[])],
                  key=lambda row:row["next_due_ms"] or 0)

PAYMENT_CHANNEL_CANDIDATES: dict[str, tuple[str, ...]] = {
    "": ("未指定（待确认）", "未指定", "Money", "Cash", "现金"),
    "未指定（待确认）": ("未指定（待确认）", "未指定", "Money", "Cash", "现金"),
    "未指定": ("未指定（待确认）", "未指定", "Money", "Cash", "现金"),
    "待确认": ("未指定（待确认）", "未指定", "Money", "Cash", "现金"),
    "unspecified": ("未指定（待确认）", "未指定", "Money", "Cash", "现金"),
    "alipay": ("AliPay", "支付宝"),
    "支付宝": ("支付宝", "AliPay"),
    "花呗": ("支付宝", "AliPay", "花呗", "蚂蚁花呗"),
    "蚂蚁花呗": ("支付宝", "AliPay", "蚂蚁花呗", "花呗"),
    "huabei": ("支付宝", "AliPay", "花呗"),
    "wechat": ("WeChat", "WeChat Pay", "微信", "微信支付"),
    "wechat pay": ("WeChat", "WeChat Pay", "微信", "微信支付"),
    "微信": ("微信", "微信支付", "WeChat", "WeChat Pay"),
    "微信支付": ("微信支付", "微信", "WeChat", "WeChat Pay"),
    "credit card": ("Credit Card", "信用卡", "银行卡", "Debit Card", "储蓄卡"),
    "信用卡": ("信用卡", "Credit Card", "银行卡"),
    "debit card": ("Debit Card", "储蓄卡", "银行卡", "Credit Card", "信用卡"),
    "银行卡": ("银行卡", "储蓄卡", "Debit Card", "Credit Card", "信用卡"),
    "储蓄卡": ("储蓄卡", "银行卡", "Debit Card", "Credit Card", "信用卡"),
    "借记卡": ("借记卡", "储蓄卡", "银行卡", "Debit Card", "Credit Card", "信用卡"),
    "云闪付": ("云闪付", "UnionPay", "Credit Card", "信用卡", "银行卡"),
    "unionpay": ("UnionPay", "云闪付", "Credit Card", "信用卡", "银行卡"),
    "京东白条": ("京东白条", "白条", "Credit Card", "信用卡", "AliPay", "支付宝"),
    "白条": ("白条", "京东白条", "Credit Card", "信用卡", "AliPay", "支付宝"),
    "apple pay": ("Apple Pay", "Credit Card", "信用卡", "银行卡"),
    "applepay": ("Apple Pay", "Credit Card", "信用卡", "银行卡"),
    "抖音支付": ("抖音支付", "AliPay", "支付宝", "WeChat", "微信", "Credit Card"),
    "douyin": ("抖音支付", "AliPay", "支付宝", "WeChat", "微信", "Credit Card"),
}

_GENERIC_FALLBACK_METHODS: tuple[str, ...] = (
    "未指定（待确认）",
    "未指定",
    "Money",
    "Cash",
    "现金",
)


def _fallback_candidates_for(raw_channel: str) -> list[str]:
    key = raw_channel.strip().casefold()
    candidates: list[str] = []
    if raw_channel.strip():
        candidates.append(raw_channel.strip())
    if key in PAYMENT_CHANNEL_CANDIDATES:
        candidates.extend(PAYMENT_CHANNEL_CANDIDATES[key])
    elif key:
        if "微信" in key or "wechat" in key:
            candidates.extend(PAYMENT_CHANNEL_CANDIDATES["微信"])
        elif "支付宝" in key or "alipay" in key or "花呗" in key:
            candidates.extend(PAYMENT_CHANNEL_CANDIDATES["花呗"])
        elif "银行" in key or "储蓄" in key or "信用" in key or "卡" in key or "白条" in key or "闪付" in key or "apple" in key or "huawei" in key or "samsung" in key:
            candidates.extend(("Credit Card", "信用卡", "Debit Card", "储蓄卡", "银行卡"))
    candidates.extend(_GENERIC_FALLBACK_METHODS)
    return candidates


def valid_subscription_links(payment_channel: str = "") -> dict:
    """Resolve required Wallos UI relationships from the owner's actual DB.

    Omission used to create subscriptions with NULL member/category/payment_method
    which broke Wallos subscriptions.php under PHP 8.
    Supports Chinese payment channels and safely falls back to the closest
    configured Wallos payment method so sync never fails on unconfigured or
    custom channel names.
    """
    path=WALLOS_DB
    with closing(sqlite3.connect(f"file:{path}?mode=ro",uri=True)) as db:
        owners=db.execute("SELECT id FROM user").fetchall()
        if len(owners)!=1:
            raise WallosError("Expected one Wallos owner")
        owner=owners[0][0]
        household=db.execute("SELECT id FROM household WHERE user_id=? ORDER BY id LIMIT 1",(owner,)).fetchone()
        category=db.execute("SELECT id FROM categories WHERE user_id=? AND name=? LIMIT 1",(owner,"无分类")).fetchone()
        if not category:
            category=db.execute("SELECT id FROM categories WHERE user_id=? ORDER BY id LIMIT 1",(owner,)).fetchone()
        enabled_methods=db.execute(
            "SELECT id,name FROM payment_methods WHERE user_id=? AND enabled=1 ORDER BY id",
            (owner,),
        ).fetchall()
        by_name = {
            str(row[1]).strip().casefold(): row[0]
            for row in enabled_methods
            if row[1] is not None
        }
        method_id = None
        for candidate in _fallback_candidates_for(payment_channel):
            matched = by_name.get(candidate.strip().casefold())
            if matched is not None:
                method_id = matched
                break
        if method_id is None and enabled_methods:
            method_id = enabled_methods[0][0]
        if not (household and category and method_id is not None):
            raise WallosError("Wallos member, category or payment method missing")
        return {"payer_user_id":household[0],"category_id":category[0],
                "payment_method_id":method_id}


def format_wallos_notes(payment_channel: str = "", note: str = "") -> str:
    parts: list[str] = []
    clean_note = (note or "").strip()
    if clean_note:
        parts.append(clean_note)
    clean_channel = (payment_channel or "").strip()
    if clean_channel:
        channel_tag = f"Payment channel: {clean_channel}"
        if channel_tag not in clean_note:
            parts.append(channel_tag)
    return "\n".join(parts)


def add(name: str,amount_cents:int,currency:str,cadence:str,next_due_ms:int,
        payment_channel:str="",start_date:str|None=None,
        auto_renew:bool=True,notify:bool=False,notify_days_before:int|None=None,
        note:str=""):
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
        "notes":format_wallos_notes(payment_channel, note)
    }
    if start_date is not None: payload["start_date"]=start_date
    if notify_days_before is not None: payload["notify_days_before"]=notify_days_before
    result=call("/api/subscriptions/set_subscriptions.php",payload)
    return int(result["subscriptionId"])


def delete(subscription_id:int):
    """Permanently delete one subscription through the native Wallos API.

    Upstream set_subscriptions.php has handled action=delete since v5.0.0
    (2026-07-11); this deployment pins a later image. This is not the same as
    edit(active=False), which keeps history and only stops renewal.
    Never writes Wallos SQLite.
    """
    if not isinstance(subscription_id,int) or subscription_id<1:
        raise WallosError("bad subscription ID")
    call("/api/subscriptions/set_subscriptions.php",{"action":"delete","id":subscription_id})
    return subscription_id


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
        elif key=="note": payload["notes"]=value
        else: raise WallosError("Unsupported edit field: "+key)
    if len(payload)==2: raise WallosError("No edit fields supplied")
    call("/api/subscriptions/set_subscriptions.php",payload)
    return subscription_id
