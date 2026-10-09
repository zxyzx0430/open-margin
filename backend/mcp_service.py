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
        rows=db.execute("SELECT * FROM transactions WHERE direction='EXPENSE' ORDER BY timestamp_ms").fetchall()
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
