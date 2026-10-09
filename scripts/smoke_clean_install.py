#!/usr/bin/env python3
"""Clean-room smoke simulation. Only talks to 127.0.0.1 staging ports and
uses throwaway credentials. Do NOT point this at production.
"""
from pathlib import Path
import requests, secrets, uuid, re, hashlib, base64
from urllib.parse import parse_qs, urlparse
from datetime import datetime
from zoneinfo import ZoneInfo

HOME=Path(__file__).resolve().parent.parent
# Do not allow this synthetic-transaction integration test to touch production.
local_env=(HOME/".env").read_text()
assert "FINANCE_API_PORT=18767" in local_env and "WALLOS_PORT=18083" in local_env, "Staging-only ports required"
assert "FINANCE_PUBLIC_URL=https://finance.example.com/finance" in (HOME/"secrets/backend.env").read_text(), "Example-domain staging required"
cfg={}
for s in (HOME/"secrets/backend.env").read_text().splitlines():
    if "=" in s: a,b=s.split("=",1);cfg[a]=b
finance="http://127.0.0.1:18767"
wallos="http://127.0.0.1:18083"
assert "127.0.0.1" in finance and "127.0.0.1" in wallos
owner="test-owner-"+secrets.token_hex(3)
passphrase=secrets.token_urlsafe(25)
session=requests.Session()
def log(msg): print("CHECK",msg,flush=True)
def check(c,msg):
    if not c: raise AssertionError(msg)
    log(msg)
r=session.get(wallos+"/registration.php",timeout=20,allow_redirects=False)
check(r.status_code==200, "First Wallos registration page loads")
data={"username":owner,"firstname":"Test","lastname":"Owner",
      "email":"test+"+secrets.token_hex(4)+"@example.com",
      "password":passphrase,"confirm_password":passphrase,
      "main_currency":"CNY","language":"en_US"}
r=session.post(wallos+"/registration.php",data=data,allow_redirects=False,timeout=20)
print("REGISTRATION_RESULT",r.status_code,r.headers.get("Location","")[:90],flush=True)
check(r.status_code in (301,302,303) and "login.php" in r.headers.get("Location",""),
      "New Wallos owner registered using original HTTP form")
r=session.get(wallos+"/login.php",timeout=20)
check(r.status_code==200,"Wallos login screen loads")
r=session.post(wallos+"/login.php",data={"username":owner,"password":passphrase},
               allow_redirects=False,timeout=20)
print("LOGIN_RESULT",r.status_code,r.headers.get("Location","")[:90],flush=True)
check(r.status_code in (301,302,303),"Wallos login redirect succeeds")
anon=requests.get(wallos+"/expenses.php",allow_redirects=False,timeout=20)
check(anon.status_code in (301,302,303,401,403),"Anonymous daily ledger not accessible")
r=session.get(wallos+"/expenses.php?month=2026-10",timeout=25)
print("EXPENSES_PAGE",r.status_code,r.url,len(r.content),flush=True)
check(r.status_code==200 and "日常消费" in r.text,"Authenticated native daily spending page renders")

# Use the same device batching API an Android WorkManager task calls.
event={
    "event_id":"android:demo-"+secrets.token_hex(5)+":1",
    "timestamp_ms":int(datetime(2026,10,9,18,0,tzinfo=ZoneInfo("Asia/Shanghai")).timestamp()*1000),
    "amount_cents":1881,"currency":"CNY","direction":"EXPENSE","source":"WECHAT",
    "category":"FOOD","merchant":"Demo Merchant"
}
headers={"X-Device-Token":cfg["FINANCE_DEVICE_TOKEN"]}
invalid=requests.post(finance+"/api/v1/transactions/batch",json={"events":[event]},
                        headers={"X-Device-Token":"wrong"},timeout=12)
check(invalid.status_code==401,"Wrong device upload token denied")
res=requests.post(finance+"/api/v1/transactions/batch",json={"events":[event]},
                  headers=headers,timeout=12)
print("UPLOAD_STATUS",res.status_code,res.json(),flush=True)
check(res.status_code==200 and res.json()["inserted"]==1,"Android-style upload persisted")
res=requests.post(finance+"/api/v1/transactions/batch",json={"events":[event]},
                  headers=headers,timeout=12)
check(res.status_code==200 and res.json()["already_present"]==1,"Duplicate notification upload idempotent")
r=session.get(wallos+"/expenses.php?month=2026-10",timeout=25)
check(r.status_code==200 and "18.81" in r.text,"Wallos renders exact 18.81 yuan from Android")

# Native PHP transaction creation with its per-session CSRF.
m=re.search(r'name=["\']csrf["\'][^>]*value=["\']([^"\']+)',r.text,re.I)
if not m:
    m=re.search(r'value=["\']([^"\']+)["\'][^>]*name=["\']csrf',r.text,re.I)
check(m is not None,"Native Wallos ledger CSRF present")
csrf=m.group(1)
idmatch=re.search(r'name=["\']request_id["\'][^>]*value=["\']([^"\']+)',r.text,re.I)
if not idmatch:
    idmatch=re.search(r'value=["\']([^"\']+)["\'][^>]*name=["\']request_id',r.text,re.I)
request_id=idmatch.group(1) if idmatch else str(uuid.uuid4())
form={"csrf":csrf,"month":"2026-10","action":"add","amount":"12.34",
      "spent_at":"2026-10-09T18:01","request_id":request_id,"merchant":"Form demo",
      "currency":"CNY","direction":"EXPENSE","category":"FOOD"}
r=session.post(wallos+"/expenses-action.php",data=form,allow_redirects=False,timeout=22)
print("PHP_MANUAL_STATUS",r.status_code,r.headers.get("Location",""),flush=True)
check(r.status_code==303 and "notice=added" in r.headers.get("Location",""),
      "Native PHP manual expense saved")
r=session.get(wallos+"/expenses.php?month=2026-10",timeout=25)
check("12.34" in r.text,"Manually added transaction visible")
integrated=session.get(wallos+"/integration.php?month=2026-10",timeout=30)
check(integrated.status_code==200 and "订阅 × 消费对账" in integrated.text,
      "Wallos-native subscription reconciliation page renders")
check("综合月度概览" in integrated.text and "商户自动分类规则" in integrated.text,
      "Unified forecast, matching and merchant-rule panels available")

# Export and PHP budget save test, both same Wallos login.
export=session.get(wallos+"/expenses-export.php?month=2026-10",timeout=25)
print("EXPORT_RESULT",export.status_code,export.headers.get("Content-Type","")[:55],len(export.content),flush=True)
check(export.status_code==200 and "text/csv" in export.headers.get("Content-Type",""),
      "Authenticated CSV export works")
check("Demo Merchant" in export.text and "Form demo" in export.text,
      "CSV contains both Android and manually entered transactions")
form={"csrf":csrf,"month":"2026-10","action":"budget","budget_amount":"500.00"}
r=session.post(wallos+"/expenses-action.php",data=form,allow_redirects=False,timeout=22)
check(r.status_code==303 and "notice=budget" in r.headers.get("Location",""),
      "Native PHP monthly budget saved")

# OAuth: dynamic client registration, PKCE, owner consent.
uri="https://chatgpt.com/oauth/example/callback"
r=requests.post(finance+"/oauth/register",
    json={"client_name":"Smoke test","redirect_uris":[uri],"token_endpoint_auth_method":"none"},
    timeout=12)
check(r.status_code==201,"OAuth DCR registers sample ChatGPT client")
client=r.json()["client_id"]
verifier="a"*43
challenge=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
parameters={"client_id":client,"redirect_uri":uri,"response_type":"code",
            "code_challenge_method":"S256","code_challenge":challenge,
            "scope":"finance:read","state":"test-state",
            "resource":"https://finance.example.com/finance/mcp/"}
r=requests.get(finance+"/oauth/authorize",params=parameters,timeout=15)
check(r.status_code==200,"OAuth consent screen loads")
match=re.search(r'name="request_id" value="([^"]+)"',r.text)
check(match is not None,"OAuth one-time request stored")
r=requests.post(finance+"/oauth/authorize",
    data={"request_id":match.group(1),"username":owner,"password":passphrase},
    allow_redirects=False,timeout=15)
check(r.status_code==303 and urlparse(r.headers.get("Location","")).hostname=="chatgpt.com",
      "Owner authenticates and authorizes using Wallos credentials")
qs=parse_qs(urlparse(r.headers["Location"]).query)
code=qs["code"][0]
r=requests.post(finance+"/oauth/token",
  data={"grant_type":"authorization_code","client_id":client,
        "redirect_uri":uri,"code_verifier":verifier,"code":code},timeout=15)
check(r.status_code==200 and bool(r.json().get("access_token")),
      "OAuth PKCE returns valid short-lived access token")
token=r.json()["access_token"]
r=requests.get(finance+"/.well-known/oauth-protected-resource",timeout=12)
check(r.status_code==200 and "authorization_servers" in r.json(),
      "OAuth discovery metadata available")
r=requests.post(finance+"/mcp/",json={"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}},
   headers={"Authorization":"Bearer "+token,"Accept":"application/json, text/event-stream",
            "Content-Type":"application/json","MCP-Protocol-Version":"2025-06-18"},timeout=20)
print("MCP_RESULT",r.status_code,r.text[:220],flush=True)
check(r.status_code==200,"OAuth token grants access to MCP tools")
r=requests.post(finance+"/mcp/",json={"jsonrpc":"2.0","id":2,"method":"tools/call",
      "params":{"name":"add_subscription","arguments":{"name":"Read only check","amount_cents":10,
      "currency":"CNY","cadence":"monthly","next_due_ms":1791511200000}}},
   headers={"Authorization":"Bearer "+token,"Accept":"application/json, text/event-stream",
            "Content-Type":"application/json","MCP-Protocol-Version":"2025-06-18"},timeout=20)
check(r.status_code==403,"Read-only OAuth scope denies write MCP tool")
log("ALL_STAGING_INTEGRATION_CHECKS_PASSED")
