"""One-way projection: canonical subscriptions (finance.sqlite3) -> Wallos API.

Wallos account and API key must be initialized separately. Never writes to Wallos SQLite directly.
"""
from pathlib import Path
from config import DB, WALLOS_DB, WALLOS_API_URL
from datetime import datetime,timezone
import json
import os
import sqlite3
import urllib.parse
import urllib.request
import hashlib

def get_api_key():
    key = os.environ.get("WALLOS_API_KEY", "")
    if key:
        return key
    local_db = WALLOS_DB
    if not local_db.exists():
        return ""
    with sqlite3.connect(f"file:{local_db}?mode=ro", uri=True) as connection:
        rows = connection.execute("SELECT api_key FROM user").fetchall()
    return rows[0][0] if len(rows) == 1 and rows[0][0] else ""

API_KEY=get_api_key()
WALLOS_URL=WALLOS_API_URL
CURRENCIES={"EUR":1,"USD":2,"JPY":3,"GBP":7,"CNY":20,"HKD":21}
CYCLES={"weekly":(1,2),"monthly":(1,3),"quarterly":(3,3),"yearly":(1,4)}

def sync():
    if not API_KEY:
        return {"enabled":False,"reason":"Wallos account/API key has not been configured"}
    if not DB.exists():
        return {"enabled":True,"synced":0,"reason":"No finance database yet"}
    with sqlite3.connect(DB) as db:
        db.row_factory=sqlite3.Row
        if not db.execute("SELECT name FROM sqlite_master WHERE name='subscriptions'").fetchone():
            return {"enabled":True,"synced":0,"reason":"No subscriptions yet"}
        db.execute("""CREATE TABLE IF NOT EXISTS wallos_map(
            finance_id INTEGER PRIMARY KEY,
            wallos_id INTEGER NOT NULL,
            fingerprint TEXT NOT NULL)""")
        db.commit()
        subscriptions=db.execute("SELECT * FROM subscriptions ORDER BY id").fetchall()
        changed=0;skipped=0;errors=[]
        for s in subscriptions:
            if s["currency"] not in CURRENCIES or s["cadence"] not in CYCLES:
                skipped+=1;continue
            freq,cycle=CYCLES[s["cadence"]]
            date=datetime.fromtimestamp(s["next_due_ms"]/1000,tz=timezone.utc).strftime("%Y-%m-%d")
            payload={"api_key":API_KEY,
                     "name":s["name"],"price":f'{s["amount_cents"]/100:.2f}',
                     "currency_id":CURRENCIES[s["currency"]],"frequency":freq,"cycle":cycle,
                     "next_payment":date,"auto_renew":1,"inactive":0 if s["active"] else 1,
                     "notify":0,"notes":f'Imported from Personal Finance #{s["id"]}. '+s["note"]}
            fingerprint=hashlib.sha256(json.dumps(payload,sort_keys=True,ensure_ascii=False).replace(API_KEY,"").encode()).hexdigest()
            existing=db.execute("SELECT wallos_id,fingerprint FROM wallos_map WHERE finance_id=?",(s["id"],)).fetchone()
            if existing and existing["fingerprint"]==fingerprint:continue
            payload["action"]="edit" if existing else "add"
            if existing:payload["id"]=existing["wallos_id"]
            body=urllib.parse.urlencode(payload).encode()
            req=urllib.request.Request(WALLOS_URL+"/api/subscriptions/set_subscriptions.php",
                           data=body,headers={"Content-Type":"application/x-www-form-urlencoded"})
            try:
                with urllib.request.urlopen(req,timeout=12) as response:
                    result=json.load(response)
                if not result.get("success"):raise ValueError(str(result.get("message","unknown"))[:120])
                remote=int(existing["wallos_id"]) if existing else int(result["subscriptionId"])
                db.execute("INSERT OR REPLACE INTO wallos_map(finance_id,wallos_id,fingerprint) VALUES (?,?,?)",
                           (s["id"],remote,fingerprint))
                db.commit()
                changed+=1
            except Exception as err:
                errors.append({"finance_id":s["id"],"error":type(err).__name__})
        return {"enabled":True,"synced":changed,"skipped":skipped,"failed":errors}

if __name__=="__main__":
    print(json.dumps(sync(),ensure_ascii=False))
