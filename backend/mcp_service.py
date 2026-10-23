"""Read-mostly MCP interface for self-hosted transaction/recurring expense data."""
from datetime import datetime, date, timezone, timedelta
from mcp.server.fastmcp import FastMCP
from mcp.types import ToolAnnotations
from mcp.server.transport_security import TransportSecuritySettings
from config import PUBLIC_HOST, PUBLIC_ORIGIN, LOCAL_TIMEZONE

mcp = FastMCP("Personal Finance", stateless_http=True, json_response=True, streamable_http_path="/",
    transport_security=TransportSecuritySettings(
        allowed_hosts=[PUBLIC_HOST, f"{PUBLIC_HOST}:*", "127.0.0.1:*", "localhost:*", "finance-api:*"],
        allowed_origins=[PUBLIC_ORIGIN, "http://127.0.0.1:*", "http://localhost:*"]))

# These annotations describe observable behavior to ChatGPT. Authentication
# and authorization are enforced separately by the API middleware.
# All tools operate on this owner's bounded, private Finance / Wallos data.
READ_ONLY = ToolAnnotations(
    readOnlyHint=True,
    destructiveHint=False,
    idempotentHint=True,
    openWorldHint=False,
)
MUTATES_LEDGER = ToolAnnotations(
    readOnlyHint=False,
    destructiveHint=True,  # conservative: durable user financial changes
    idempotentHint=False,
    openWorldHint=False,
)

@mcp.tool(annotations=READ_ONLY)
def list_recent_transactions(limit:int=30) -> dict:
    """Return up to 100 latest recorded payment events. Values are integer cents; unverified events are not bank-confirmed."""
    import app as core
    limit=max(1,min(100,limit))
    with core.db_connection() as db:
        return {"items":[dict(r) for r in db.execute("SELECT * FROM transactions ORDER BY timestamp_ms DESC LIMIT ?",(limit,)).fetchall()]}

@mcp.tool(annotations=READ_ONLY)
def spending_summary(start_ms:int,end_ms:int) -> dict:
    """Summarize spending, by currency and category, within an epoch-ms interval. Never combine currencies."""
    import app as core
    if end_ms<=start_ms or end_ms-start_ms>366*86400000: return {"error":"invalid time range"}
    with core.db_connection() as db:
        rows=db.execute("""SELECT currency,direction,category,COUNT(*) AS count,
          SUM(amount_cents) AS amount_cents FROM transactions WHERE timestamp_ms >=?
          AND timestamp_ms <? AND review_status NOT IN ('excluded','transfer','refund')
          AND (direction='INCOME' OR category<>'TRANSFER')
          GROUP BY currency,direction,category ORDER BY currency,category""",(start_ms,end_ms)).fetchall()
    return {"start_ms":start_ms,"end_ms":end_ms,"groups":[dict(r) for r in rows],
      "caveat":"Captured transactions may be incomplete or duplicated until verified against exports."}

@mcp.tool(annotations=READ_ONLY)
def get_subscription_list() -> dict:
    """Retrieve current scheduled subscriptions directly from Wallos (not actual confirmed charges)."""
    from wallos_api import get_all, WallosError
    try: return {"items":get_all(),"source":"Wallos"}
    except WallosError as exc: return {"error":str(exc)}

@mcp.tool(annotations=READ_ONLY)
def get_recurring_expense_candidates() -> dict:
    """Find likely recurring expenses; candidates require user confirmation."""
    from collections import defaultdict
    import app as core
    with core.db_connection() as db:
        rows=db.execute("SELECT * FROM transactions WHERE direction='EXPENSE' AND category<>'TRANSFER' AND review_status NOT IN ('excluded','transfer','refund') ORDER BY timestamp_ms").fetchall()
    groups=defaultdict(list)
    for row in rows:
        if row["merchant"]:groups[(row["merchant"].strip().lower(),row["amount_cents"],row["currency"])].append(row)
    out=[]
    for (name,cents,currency),events in groups.items():
        stamps=sorted({int(e["timestamp_ms"])//86400000 for e in events})
        hits=sum(1 for a,b in zip(stamps,stamps[1:]) if 25<=b-a<=35)
        if hits:out.append({"merchant":name,"amount_cents":cents,"currency":currency,"monthly_intervals":hits,"requires_confirmation":True})
    return {"items":out}

SUBSCRIPTION_CURRENCIES=frozenset(("CNY","USD","EUR","HKD","JPY","GBP"))
SUBSCRIPTION_CADENCES=frozenset(("weekly","monthly","quarterly","yearly"))

def _subscription_date(value:str|None) -> date|None:
    if value is None:return None
    if len(value)!=10:raise ValueError("start_date must be YYYY-MM-DD")
    parsed=date.fromisoformat(value)
    if parsed.isoformat()!=value:raise ValueError("start_date must be YYYY-MM-DD")
    if not 2000<=parsed.year<=2099:raise ValueError("start_date year out of range")
    return parsed

def _subscription_due(ms:int) -> date:
    from zoneinfo import ZoneInfo
    if not isinstance(ms,int) or not 946684800000<ms<4102444800000:
        raise ValueError("bad next due date")
    return datetime.fromtimestamp(ms/1000,ZoneInfo(LOCAL_TIMEZONE)).date()

def _subscription_fields(name=None,amount_cents=None,currency=None,cadence=None,
                         next_due_ms=None,start_date=None,notify_days_before=None):
    if name is not None and (not name.strip() or len(name)>140):raise ValueError("bad subscription name")
    if amount_cents is not None and (not isinstance(amount_cents,int) or not 0<=amount_cents<=100000000000):
        raise ValueError("bad amount")
    if currency is not None and currency not in SUBSCRIPTION_CURRENCIES:raise ValueError("unsupported currency")
    if cadence is not None and cadence not in SUBSCRIPTION_CADENCES:
        raise ValueError("unsupported cadence; Wallos does not support one_time")
    if next_due_ms is not None:_subscription_due(next_due_ms)
    _subscription_date(start_date)
    if notify_days_before is not None and not 0<=notify_days_before<=365:
        raise ValueError("notify_days_before must be 0..365")


@mcp.tool(annotations=MUTATES_LEDGER)
def add_subscription(name:str, amount_cents:int, currency:str, cadence:str, next_due_ms:int,
                     payment_channel:str="",start_date:str|None=None,
                     auto_renew:bool|None=None,notify:bool|None=None,
                     notify_days_before:int|None=None) -> dict:
    """Schedule a Wallos subscription. start_date is optional YYYY-MM-DD (Asia/Shanghai).
    Payment schedules are not evidence of real payment. Does not initiate charges.
    """
    from wallos_api import add,get_all,WallosError
    try:
        _subscription_fields(name,amount_cents,currency,cadence,next_due_ms,start_date,notify_days_before)
        due=_subscription_due(next_due_ms)
        first=_subscription_date(start_date)
        if first and first>due:raise ValueError("start_date cannot be later than next due date")
        for sub in get_all():
            if (sub["name"]==name and sub["amount_cents"]==amount_cents
                    and sub["currency"]==currency and sub["cadence"]==cadence
                    and sub["next_due_ms"] is not None and _subscription_due(sub["next_due_ms"])==due):
                conflicts=[]
                if first and (sub.get("start_ms") is None or _subscription_due(sub["start_ms"])!=first):conflicts.append("start_date")
                if payment_channel and sub.get("payment_channel","").casefold()!=payment_channel.casefold():conflicts.append("payment_channel")
                if auto_renew is not None and sub.get("auto_renew",True)!=auto_renew:conflicts.append("auto_renew")
                if notify is not None and sub.get("notify",False)!=notify:conflicts.append("notify")
                if notify_days_before is not None and sub.get("notify_days_before")!=notify_days_before:conflicts.append("notify_days_before")
                if conflicts:
                    return {"error":"subscription already exists but specified fields differ; use edit_subscription",
                            "subscription_id":sub["id"],"conflicting_fields":conflicts}
                return {"created":False,"already_exists":True,"subscription_id":sub["id"],"source":"Wallos"}
        sid=add(name,amount_cents,currency,cadence,next_due_ms,payment_channel,
                start_date=start_date,auto_renew=True if auto_renew is None else auto_renew,
                notify=False if notify is None else notify,
                notify_days_before=notify_days_before)
        created=next((s for s in get_all() if s["id"]==sid),None)
        verified=bool(created and (first is None or
            (created.get("start_ms") is not None and _subscription_due(created["start_ms"])==first)))
        return {"created":True,"subscription_id":sid,"source":"Wallos",
                "verified":verified,"subscription":created}
    except (WallosError,ValueError,OverflowError) as exc:
        return {"error":str(exc)}


@mcp.tool(annotations=MUTATES_LEDGER)
def edit_subscription(subscription_id:int,name:str|None=None,amount_cents:int|None=None,
                      currency:str|None=None,cadence:str|None=None,next_due_ms:int|None=None,
                      start_date:str|None=None,payment_channel:str|None=None,
                      auto_renew:bool|None=None,notify:bool|None=None,
                      notify_days_before:int|None=None,active:bool|None=None) -> dict:
    """Edit an existing Wallos subscription by ID; only supplied fields change.
    start_date is YYYY-MM-DD in the configured Finance timezone.
    """
    from wallos_api import edit,get_all,WallosError
    try:
        if not isinstance(subscription_id,int) or subscription_id<1:raise ValueError("bad subscription ID")
        changes={k:v for k,v in locals().items()
                 if k not in ("subscription_id","edit","get_all","WallosError") and v is not None}
        # Only API-editable fields may reach the Wallos adapter.
        allowed={"name","amount_cents","currency","cadence","next_due_ms",
                 "start_date","payment_channel","auto_renew","notify","notify_days_before","active"}
        changes={k:v for k,v in changes.items() if k in allowed}
        if not changes:raise ValueError("No changes supplied")
        _subscription_fields(name,amount_cents,currency,cadence,next_due_ms,start_date,notify_days_before)
        original=next((s for s in get_all() if s["id"]==subscription_id),None)
        if original is None:raise ValueError("Subscription not found")
        first=_subscription_date(start_date) if start_date else (
            _subscription_due(original["start_ms"]) if original.get("start_ms") else None)
        due=_subscription_due(next_due_ms if next_due_ms is not None else original["next_due_ms"])
        if first and first>due:raise ValueError("start_date cannot be later than next due date")
        edit(subscription_id,**changes)
        updated=next((s for s in get_all() if s["id"]==subscription_id),None)
        if updated is None:return {"updated":True,"verified":False,"error":"Wallos edit succeeded but readback missing"}
        expected={"name":"name","amount_cents":"amount_cents","currency":"currency",
                  "cadence":"cadence","next_due_ms":"next_due_ms","payment_channel":"payment_channel",
                  "auto_renew":"auto_renew","notify":"notify","notify_days_before":"notify_days_before",
                  "active":"active"}
        mismatches=[]
        for key,value in changes.items():
            if key=="start_date":
                if updated.get("start_ms") is None or _subscription_due(updated["start_ms"])!=_subscription_date(value):
                    mismatches.append(key)
            elif key=="next_due_ms":
                if updated.get(key) is None or _subscription_due(updated[key])!=_subscription_due(value):mismatches.append(key)
            elif key in expected and updated.get(expected[key])!=value:
                mismatches.append(key)
        return {"updated":True,"subscription_id":subscription_id,"verified":not mismatches,
                "mismatches":mismatches,"subscription":updated,"source":"Wallos"}
    except (WallosError,ValueError,OverflowError) as exc:
        return {"error":str(exc)}

# Retired legacy subscription-reconciliation integration.
def personal_finance_overview(month: str) -> dict:
    """Combine REAL spending with Wallos subscriptions, paid cycles, pending charges,
    current CNY budget/forecast, anomalies and suggested matches for YYYY-MM.
    This never counts a scheduled subscription as a real payment.
    """
    from integration import snapshot
    try:return snapshot(month)
    except Exception as exc:return {"error":str(exc)[:150]}

@mcp.tool(annotations=READ_ONLY)
def personal_finance_long_term_trends(months: int=12) -> dict:
    """Historical monthly spending by currency, including linked refund deductions."""
    from integration import trends
    if not 2<=months<=36:return {"error":"months must be 2..36"}
    return trends(months)

# Retired legacy subscription-reconciliation integration.
def personal_finance_recurring_discovery(months:int=12) -> dict:
    """Candidate recurring payments requiring confirmation before adding to Wallos."""
    from integration import discover
    if not 2<=months<=36:return {"error":"months must be 2..36"}
    return discover(months)

# Retired legacy subscription-reconciliation integration.
def confirm_subscription_payment(tx_id:int,subscription_id:int,cycle_key:str) -> dict:
    """Explicitly confirm that an existing payment belongs to this Wallos billing cycle.
    Does not create an additional expense or advance Wallos renewal dates.
    """
    from integration import link, Link
    return link(Link(tx_id=tx_id,subscription_id=subscription_id,cycle_key=cycle_key))

@mcp.tool(annotations=MUTATES_LEDGER)
def edit_record(tx_id:int,amount_cents:int|None=None,merchant:str|None=None,category:str|None=None) -> dict:
    """Correct an existing ledger entry, recording an audit trail of edited fields."""
    from integration import edit_transaction, Edit
    values={k:v for k,v in {"amount_cents":amount_cents,"merchant":merchant,"category":category}.items() if v is not None}
    return edit_transaction(tx_id,Edit(**values))

@mcp.tool(annotations=MUTATES_LEDGER)
def register_merchant_rule(needle:str,category:str) -> dict:
    """After owner confirmation, classify future OTHER transactions from this merchant."""
    from integration import rule_add, Rule
    return rule_add(Rule(needle=needle,category=category))

@mcp.tool(annotations=MUTATES_LEDGER)
def link_refund(original_tx_id:int,refund_tx_id:int,amount_cents:int) -> dict:
    """Associate an existing income record with a previous expense as a refund.
    Never invent a payment or refund transaction.
    """
    from integration import refund, Refund
    return refund(Refund(original_tx_id=original_tx_id,refund_tx_id=refund_tx_id,amount_cents=amount_cents))

@mcp.tool(annotations=READ_ONLY)
def finance_reconciliation_queue() -> dict:
    """Return unresolved statement reconciliation items requiring review."""
    import app as core
    with core.db_connection() as db:
        rows=db.execute("SELECT * FROM statement_rows WHERE matched_tx_id IS NULL ORDER BY id DESC LIMIT 100").fetchall()
    return {"items":[dict(r) for r in rows]}

@mcp.tool(annotations=READ_ONLY)
def finance_duplicate_review(limit:int=50) -> dict:
    """Return transactions flagged as possible duplicates."""
    import app as core
    limit=max(1,min(100,limit))
    with core.db_connection() as db:
        rows=db.execute("SELECT * FROM transactions WHERE review_status='possible_duplicate' ORDER BY timestamp_ms DESC LIMIT ?",(limit,)).fetchall()
    return {"items":[dict(r) for r in rows]}
