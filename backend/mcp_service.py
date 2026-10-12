"""Read-mostly MCP interface for self-hosted transaction/recurring expense data."""
from datetime import datetime, timezone, timedelta
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from config import PUBLIC_HOST, PUBLIC_ORIGIN, LOCAL_TIMEZONE

mcp = FastMCP("Personal Finance", stateless_http=True, json_response=True, streamable_http_path="/",
    transport_security=TransportSecuritySettings(
        allowed_hosts=[PUBLIC_HOST, f"{PUBLIC_HOST}:*", "127.0.0.1:*", "localhost:*", "finance-api:*"],
        allowed_origins=[PUBLIC_ORIGIN, "http://127.0.0.1:*", "http://localhost:*"]))

@mcp.tool()
def list_recent_transactions(limit:int=30) -> dict:
    """Return up to 100 latest recorded payment events. Values are integer cents; unverified events are not bank-confirmed."""
    import app as core
    limit=max(1,min(100,limit))
    with core.db_connection() as db:
        return {"items":[dict(r) for r in db.execute("SELECT * FROM transactions ORDER BY timestamp_ms DESC LIMIT ?",(limit,)).fetchall()]}

@mcp.tool()
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

@mcp.tool()
def get_subscription_list() -> dict:
    """Retrieve current scheduled subscriptions directly from Wallos (not actual confirmed charges)."""
    from wallos_api import get_all, WallosError
    try: return {"items":get_all(),"source":"Wallos"}
    except WallosError as exc: return {"error":str(exc)}

@mcp.tool()
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

@mcp.tool()
def add_subscription(name:str, amount_cents:int, currency:str, cadence:str, next_due_ms:int,
                     payment_channel:str="") -> dict:
    """Add a subscription schedule in Wallos. Does not initiate any real payment."""
    from wallos_api import add,get_all,WallosError
    if not name or len(name)>140 or not 0<=amount_cents<=100000000000: return {"error":"bad name or amount"}
    if currency not in ("CNY","USD","EUR","HKD","JPY","GBP"):return {"error":"unsupported currency"}
    if cadence not in ("weekly","monthly","quarterly","yearly","one_time"):return {"error":"unsupported cadence"}
    if not 946684800000<next_due_ms<4102444800000:return {"error":"bad next due date"}
    try:
        from datetime import datetime
        from zoneinfo import ZoneInfo
        day=datetime.fromtimestamp(next_due_ms/1000,ZoneInfo(LOCAL_TIMEZONE)).date()
        for sub in get_all():
            if sub["name"]==name and sub["amount_cents"]==amount_cents and sub["currency"]==currency and sub["cadence"]==cadence:
                if sub["next_due_ms"] is not None and datetime.fromtimestamp(sub["next_due_ms"]/1000,ZoneInfo(LOCAL_TIMEZONE)).date()==day:
                    return {"created":False,"already_exists":True,"subscription_id":sub["id"],"source":"Wallos"}
        return {"created":True,"subscription_id":add(name,amount_cents,currency,cadence,next_due_ms,payment_channel),"source":"Wallos"}
    except WallosError as exc:
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

@mcp.tool()
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

@mcp.tool()
def edit_record(tx_id:int,amount_cents:int|None=None,merchant:str|None=None,category:str|None=None) -> dict:
    """Correct an existing ledger entry, recording an audit trail of edited fields."""
    from integration import edit_transaction, Edit
    values={k:v for k,v in {"amount_cents":amount_cents,"merchant":merchant,"category":category}.items() if v is not None}
    return edit_transaction(tx_id,Edit(**values))

@mcp.tool()
def register_merchant_rule(needle:str,category:str) -> dict:
    """After owner confirmation, classify future OTHER transactions from this merchant."""
    from integration import rule_add, Rule
    return rule_add(Rule(needle=needle,category=category))

@mcp.tool()
def link_refund(original_tx_id:int,refund_tx_id:int,amount_cents:int) -> dict:
    """Associate an existing income record with a previous expense as a refund.
    Never invent a payment or refund transaction.
    """
    from integration import refund, Refund
    return refund(Refund(original_tx_id=original_tx_id,refund_tx_id=refund_tx_id,amount_cents=amount_cents))
