"""Personal single-owner OAuth 2.1 server for ChatGPT custom MCP connectors.

DCR + authorization code with S256 PKCE + rotating refresh tokens. Owner signs in
with an existing Wallos account at the consent page (no ChatGPT credentials needed).
No plaintext access/refresh tokens or account passwords are stored.
"""
from __future__ import annotations
import base64
import hashlib
import hmac
import html
import json
import os
import re
import secrets
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import urlencode

import bcrypt
from fastapi import APIRouter, HTTPException, Request, Form
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from config import PUBLIC_BASE, OAUTH_DB, WALLOS_DB, ALLOWED_CALLBACK_HOSTS, WALLOS_OWNER_ID
ISSUER = PUBLIC_BASE + "/oauth"
RESOURCE = PUBLIC_BASE + "/mcp/"
SCOPES = {"finance:read", "finance:write"}

router=APIRouter()

@contextmanager
def store():
    OAUTH_DB.parent.mkdir(parents=True,exist_ok=True)
    db=sqlite3.connect(str(OAUTH_DB),timeout=10)
    db.row_factory=sqlite3.Row
    try:
        db.execute("PRAGMA busy_timeout=10000")
        db.execute("""CREATE TABLE IF NOT EXISTS clients (
           client_id TEXT PRIMARY KEY, redirect_uris TEXT NOT NULL,
           client_name TEXT NOT NULL, created_at INTEGER NOT NULL)""")
        db.execute("""CREATE TABLE IF NOT EXISTS pending (
           request_id TEXT PRIMARY KEY, client_id TEXT NOT NULL, redirect_uri TEXT NOT NULL,
           challenge TEXT NOT NULL, state TEXT, scope TEXT NOT NULL,
           resource TEXT NOT NULL, expires INTEGER NOT NULL)""")
        db.execute("""CREATE TABLE IF NOT EXISTS auth_codes (
           code_hash TEXT PRIMARY KEY, client_id TEXT NOT NULL,
           redirect_uri TEXT NOT NULL, challenge TEXT NOT NULL,
           scope TEXT NOT NULL, resource TEXT NOT NULL, expires INTEGER NOT NULL)""")
        db.execute("""CREATE TABLE IF NOT EXISTS tokens (
           token_hash TEXT PRIMARY KEY, token_type TEXT NOT NULL,
           client_id TEXT NOT NULL, scope TEXT NOT NULL,
           resource TEXT NOT NULL, expires INTEGER NOT NULL)""")
        yield db
        db.commit()
    finally: db.close()

def sha(x: str) -> str:
    return hashlib.sha256(x.encode()).hexdigest()

def callback_ok(uri: str) -> bool:
    from urllib.parse import urlsplit
    try:
        parsed=urlsplit(uri)
        return (parsed.scheme == "https" and parsed.hostname in ALLOWED_CALLBACK_HOSTS
            and not parsed.username and not parsed.password and parsed.port in (None,443)
            and parsed.path.startswith("/") and not parsed.fragment)
    except (ValueError,TypeError): return False

def details_url(base: str, **kwargs: str) -> str:
    return base + ("&" if "?" in base else "?") + urlencode(kwargs)

@router.get("/.well-known/oauth-protected-resource")
def protected_resource():
    return {"resource": RESOURCE, "authorization_servers":[ISSUER],
            "bearer_methods_supported":["header"],"scopes_supported":sorted(SCOPES)}

@router.get("/oauth/.well-known/oauth-authorization-server")
def oauth_metadata():
    return {
      "issuer":ISSUER,
      "authorization_endpoint":ISSUER+"/authorize",
      "token_endpoint":ISSUER+"/token",
      "registration_endpoint":ISSUER+"/register",
      "response_types_supported":["code"],
      "grant_types_supported":["authorization_code","refresh_token"],
      "token_endpoint_auth_methods_supported":["none"],
      "code_challenge_methods_supported":["S256"],
      "scopes_supported":sorted(SCOPES),
      "client_id_metadata_document_supported":False
    }

@router.post("/oauth/register")
async def register(request: Request):
    body=await request.json()
    uri_list=body.get("redirect_uris")
    if (not isinstance(uri_list,list) or not 1<=len(uri_list)<=5 or
        not all(isinstance(x,str) and callback_ok(x) for x in uri_list)):
        raise HTTPException(400, "Redirect URIs must be HTTPS ChatGPT callbacks")
    if body.get("token_endpoint_auth_method","none")!="none":
        raise HTTPException(400,"Only public PKCE clients accepted")
    client_name=str(body.get("client_name") or "ChatGPT Personal Finance")[:100]
    client_id="chatgpt_"+secrets.token_urlsafe(24)
    with store() as db:
        if db.execute("SELECT count(*) FROM clients").fetchone()[0]>60:
            raise HTTPException(429,"Too many clients")
        db.execute("INSERT INTO clients VALUES (?,?,?,?)",
              (client_id,json.dumps(uri_list),client_name,int(time.time())))
    return JSONResponse(status_code=201,content={"client_id":client_id,
        "client_id_issued_at":int(time.time()),
        "client_name":client_name,
        "redirect_uris":uri_list,
        "grant_types":["authorization_code","refresh_token"],
        "response_types":["code"],"token_endpoint_auth_method":"none"})

def consent_page(request_id: str,client_name: str,msg: str="",scopes: str="finance:read") -> HTMLResponse:
    notice='<p class="warn">'+html.escape(msg)+'</p>' if msg else ''
    granted=set(scopes.split())
    if {"finance:read", "finance:write"} <= granted:
        access_note="允许查询并修改清衡账本中的交易、分类规则和订阅信息（不会发起真实支付）。"
    elif "finance:write" in granted:
        access_note="只允许执行账本写入工具，不包含财务数据的查询权限（不会发起真实支付）。"
    else:
        access_note="只允许查询财务信息，不允许修改交易、分类规则或订阅。"
    body=f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <meta http-equiv="Cache-Control" content="no-store"><title>授权 Personal Finance</title>
    <style>body{{font-family:system-ui,'Noto Sans SC',sans-serif;background:#f4f6fa;color:#15243b;
    max-width:450px;margin:9vh auto;padding:20px}}main{{background:white;padding:26px;
    border-radius:15px;border:1px solid #dce4ee}}input{{box-sizing:border-box;width:100%;
    padding:12px;margin:8px 0 16px;border:1px solid #bbc7d8;border-radius:8px}}
    button{{background:#165ac6;color:white;padding:12px;border:0;border-radius:8px;width:100%}}
    .muted{{color:#65728a;font-size:.9em}}.warn{{color:#a02d31}}</style></head><body>
    <main><h2>授权 ChatGPT 查询个人财务</h2><p>应用：{html.escape(client_name)}</p>
    <p class="muted">{html.escape(access_note)}</p>
    <p class="muted">本次授权权限：{html.escape(scopes)}</p>{notice}
    <form method="post" action="{ISSUER}/authorize" autocomplete="off">
      <input type="hidden" name="request_id" value="{html.escape(request_id)}">
      <label>Wallos 用户名</label><input name="username" autocomplete="username" required>
      <label>Wallos 密码</label><input type="password" name="password" autocomplete="current-password" required>
      <button type="submit">验证并授权 ChatGPT</button>
    </form><p class="muted">尚未设置 Wallos 账户？请先到 Wallos 页面完成一次注册。</p></main></body></html>"""
    return HTMLResponse(body,headers={"Cache-Control":"no-store",
        "Content-Security-Policy":"default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"})

def owner_ok(username:str,password:str)->bool:
    if not WALLOS_DB.exists() or not username or not password:
        return False
    with sqlite3.connect(str(WALLOS_DB)) as db:
        row=db.execute("SELECT password FROM user WHERE username=? AND id=? LIMIT 1",(username,WALLOS_OWNER_ID)).fetchone()
    if not row: return False
    stored=row[0].encode().replace(b"$2y$",b"$2b$",1)
    try: return bcrypt.checkpw(password.encode(),stored)
    except ValueError: return False

@router.get("/oauth/authorize")
async def authorize(request:Request):
    q=request.query_params
    client_id=q.get("client_id","")
    redirect_uri=q.get("redirect_uri","")
    challenge=q.get("code_challenge","")
    if (q.get("response_type")!="code" or q.get("code_challenge_method")!="S256" or
        not re.fullmatch(r"[A-Za-z0-9_~-]{43,128}",challenge)):
        raise HTTPException(400,"Authorization code with PKCE S256 required")
    scopes=set((q.get("scope") or "finance:read").split())
    if not scopes or not scopes.issubset(SCOPES):
        raise HTTPException(400,"Unsupported scope")
    resource=q.get("resource",RESOURCE)
    if resource.rstrip("/")!=RESOURCE.rstrip("/"):
        raise HTTPException(400,"Invalid MCP resource")
    with store() as db:
        row=db.execute("SELECT * FROM clients WHERE client_id=?",(client_id,)).fetchone()
        if not row or redirect_uri not in json.loads(row["redirect_uris"]):
            raise HTTPException(400,"Unknown client or redirect URI")
        request_id=secrets.token_urlsafe(30)
        db.execute("DELETE FROM pending WHERE expires<?",(int(time.time()),))
        db.execute("INSERT INTO pending VALUES (?,?,?,?,?,?,?,?)",
                   (request_id,client_id,redirect_uri,challenge,q.get("state"),
                    " ".join(sorted(scopes)),RESOURCE,int(time.time())+600))
    return consent_page(request_id,row["client_name"],scopes=" ".join(sorted(scopes)))

@router.post("/oauth/authorize")
async def accept(request_id:str=Form(...),username:str=Form(...),password:str=Form(...)):
    with store() as db:
        row=db.execute("""SELECT p.*,c.client_name FROM pending p JOIN clients c
                            ON p.client_id=c.client_id
                            WHERE p.request_id=? AND p.expires>?""",
                            (request_id,int(time.time()))).fetchone()
        if not row:
            # OAuth is one-time use. Browser back/retry must not display cryptic JSON
            # or issue a code without the original pending authorization context.
            return HTMLResponse("""<!doctype html><html lang="zh-CN">
              <meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
              <title>需要重新连接</title><body style="font-family:system-ui;margin:10vh auto;max-width:480px;padding:20px">
              <h2>此次授权请求已完成或失效</h2>
              <p>请返回 ChatGPT 的插件连接页面，重新点击“连接/授权”，不要重复提交旧网页。</p>
              <p>这不代表账号密码错误。新的连接会生成新的一次性授权请求。</p>
              </body></html>""",status_code=410,headers={"Cache-Control":"no-store"})
        if not owner_ok(username,password):
            return consent_page(request_id,row["client_name"],"Wallos 用户名或密码不正确。",scopes=row["scope"])
        db.execute("DELETE FROM pending WHERE request_id=?",(request_id,))
        code=secrets.token_urlsafe(48)
        db.execute("INSERT INTO auth_codes VALUES (?,?,?,?,?,?,?)",
            (sha(code),row["client_id"],row["redirect_uri"],row["challenge"],
            row["scope"],row["resource"],int(time.time())+180))
        redirect=details_url(row["redirect_uri"],code=code,
                             **({"state":row["state"]} if row["state"] is not None else {}))
    return RedirectResponse(redirect,status_code=303)

def new_tokens(db, client_id:str,scopes:str,resource:str):
    access=secrets.token_urlsafe(48)
    refresh=secrets.token_urlsafe(48)
    now=int(time.time())
    db.execute("INSERT INTO tokens VALUES (?,?,?,?,?,?)",
               (sha(access),"access",client_id,scopes,resource,now+3600))
    db.execute("INSERT INTO tokens VALUES (?,?,?,?,?,?)",
               (sha(refresh),"refresh",client_id,scopes,resource,now+30*86400))
    return {"access_token":access,"token_type":"Bearer","expires_in":3600,
            "scope":scopes,"refresh_token":refresh}

@router.post("/oauth/token")
async def exchange(request:Request):
    body=await request.form()
    grant=body.get("grant_type")
    client_id=str(body.get("client_id") or "")
    now=int(time.time())
    with store() as db:
        client=db.execute("SELECT * FROM clients WHERE client_id=?",(client_id,)).fetchone()
        if not client: raise HTTPException(400,"invalid_client")
        if grant=="authorization_code":
            code=str(body.get("code") or "")
            verifier=str(body.get("code_verifier") or "")
            redirect=str(body.get("redirect_uri") or "")
            if not re.fullmatch(r"[A-Za-z0-9_~-]{43,128}",verifier):
                raise HTTPException(400,"invalid_grant")
            row=db.execute("SELECT * FROM auth_codes WHERE code_hash=? AND client_id=? AND expires>?",
                        (sha(code),client_id,now)).fetchone()
            if not row or row["redirect_uri"]!=redirect:
                raise HTTPException(400,"invalid_grant")
            computed=base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
            if not hmac.compare_digest(computed,row["challenge"]):
                raise HTTPException(400,"invalid_grant")
            db.execute("DELETE FROM auth_codes WHERE code_hash=?",(sha(code),))
            issued=new_tokens(db,client_id,row["scope"],row["resource"])
        elif grant=="refresh_token":
            refresh=str(body.get("refresh_token") or "")
            row=db.execute("""SELECT * FROM tokens WHERE token_hash=? AND token_type='refresh'
                          AND client_id=? AND expires>?""",(sha(refresh),client_id,now)).fetchone()
            if not row:raise HTTPException(400,"invalid_grant")
            db.execute("DELETE FROM tokens WHERE token_hash=?",(sha(refresh),))
            issued=new_tokens(db,client_id,row["scope"],row["resource"])
        else: raise HTTPException(400,"unsupported_grant_type")
        db.execute("DELETE FROM tokens WHERE expires<?",(now,))
    return JSONResponse(issued,headers={"Cache-Control":"no-store","Pragma":"no-cache"})

def verify_access_token(token:str,scope:str|None=None)->bool:
    if not token: return False
    with store() as db:
        row=db.execute("""SELECT * FROM tokens WHERE token_hash=? AND token_type='access'
                   AND resource=? AND expires>?""",(sha(token),RESOURCE,int(time.time()))).fetchone()
    return bool(row and (scope is None or scope in row["scope"].split()))
