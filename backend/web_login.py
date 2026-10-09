"""Session authentication for the private finance dashboard.

Verify directly against the existing Wallos account. Browser receives a short-lived,
HttpOnly session cookie, never the administrator API secret.
"""
from __future__ import annotations
import hashlib
import secrets
import time
from fastapi import APIRouter, Cookie, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from oauth_support import owner_ok
from config import PUBLIC_BASE, PUBLIC_ORIGIN, URL_PREFIX

router = APIRouter()
COOKIE_NAME = "personal_finance_session"
SESSION_DURATION = 8 * 3600

class LoginRequest(BaseModel):
    username: str = Field(min_length=1,max_length=100)
    password: str = Field(min_length=1,max_length=200)

def origin_ok(request: Request) -> bool:
    origin = request.headers.get("origin","")
    referer = request.headers.get("referer","")
    if origin:
        return origin == PUBLIC_ORIGIN
    return referer.startswith(PUBLIC_BASE + "/")

def valid_session(value: str | None) -> bool:
    if not value or len(value)<35: return False
    from app import db_connection
    digest=hashlib.sha256(value.encode()).hexdigest()
    with db_connection() as db:
        db.execute("""CREATE TABLE IF NOT EXISTS browser_sessions (
           token_hash TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)""")
        row=db.execute("SELECT expires_at FROM browser_sessions WHERE token_hash=?",(digest,)).fetchone()
    return bool(row and row["expires_at"]>int(time.time()))

@router.post("/auth/login")
def login(req: LoginRequest, request: Request):
    if not origin_ok(request):
        raise HTTPException(403,"Origin not allowed")
    if not owner_ok(req.username, req.password):
        raise HTTPException(401,"Wallos 用户名或密码错误")
    from app import db_connection
    session_id=secrets.token_urlsafe(40)
    with db_connection() as db:
        db.execute("""CREATE TABLE IF NOT EXISTS browser_sessions (
           token_hash TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)""")
        db.execute("DELETE FROM browser_sessions WHERE expires_at<?",(int(time.time()),))
        db.execute("INSERT INTO browser_sessions VALUES (?,?)",
          (hashlib.sha256(session_id.encode()).hexdigest(), int(time.time())+SESSION_DURATION))
    response=JSONResponse({"ok":True})
    response.set_cookie(COOKIE_NAME,session_id,path=URL_PREFIX or "/",secure=True,
                        httponly=True,samesite="strict",max_age=SESSION_DURATION)
    response.headers["Cache-Control"]="no-store"
    return response

@router.post("/auth/logout")
def logout(request: Request):
    if not origin_ok(request):
        raise HTTPException(403,"Origin not allowed")
    old=request.cookies.get(COOKIE_NAME)
    if old:
        from app import db_connection
        with db_connection() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS browser_sessions (
               token_hash TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)""")
            db.execute("DELETE FROM browser_sessions WHERE token_hash=?",
                       (hashlib.sha256(old.encode()).hexdigest(),))
    resp=JSONResponse({"ok":True})
    resp.delete_cookie(COOKIE_NAME,path=URL_PREFIX or "/")
    return resp

@router.get("/auth/status")
def status(request: Request):
    return {"authenticated":valid_session(request.cookies.get(COOKIE_NAME))}
