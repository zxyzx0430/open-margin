from pathlib import Path
import os,requests
from config import PUBLIC_BASE
token=os.environ.get("FINANCE_ADMIN_TOKEN","")
if not token: raise SystemExit("Set FINANCE_ADMIN_TOKEN before the optional MCP smoke test")
header={"Authorization":"Bearer "+token,
        "Content-Type":"application/json","Accept":"application/json, text/event-stream",
        "MCP-Protocol-Version":"2025-06-18"}
url=PUBLIC_BASE + "/mcp/"
for label,payload in [
    ("init",{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"vps-self-test","version":"1.0"}}}),
    ("list",{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}),
]:
    try:
        res=requests.post(url,headers=header,json=payload,timeout=15)
        print(label,"status",res.status_code,"type",res.headers.get("content-type"),"body",res.text[:440])
    except Exception as e:
        print(label,"failed",type(e).__name__,str(e)[:100])
