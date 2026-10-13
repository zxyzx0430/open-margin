"""OAuth 2.1 DCR + authorization code / PKCE + refresh token tests."""
import base64
import hashlib
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path
import bcrypt
from fastapi.testclient import TestClient
import app as core
import oauth_support as oauth

class OAuthTest(unittest.TestCase):
    def setUp(self):
        self.dir=tempfile.TemporaryDirectory()
        self.old=(oauth.OAUTH_DB,oauth.WALLOS_DB)
        oauth.OAUTH_DB=Path(self.dir.name)/"oauth.sqlite3"
        oauth.WALLOS_DB=Path(self.dir.name)/"wallos.sqlite3"
        with sqlite3.connect(oauth.WALLOS_DB) as db:
            db.execute("CREATE TABLE user (id INTEGER PRIMARY KEY, username TEXT UNIQUE, password TEXT NOT NULL)")
            db.execute("INSERT INTO user (username, password) VALUES (?,?)",("owner",bcrypt.hashpw(b"test-only-password",bcrypt.gensalt()).decode()))
        self.client=TestClient(core.app,follow_redirects=False)
        self.redirect="https://chatgpt.com/oauth/1234/callback"
        self.verifier="v"*43
        self.challenge=base64.urlsafe_b64encode(hashlib.sha256(self.verifier.encode()).digest()).rstrip(b"=").decode()
    def tearDown(self):
        self.client.close()
        oauth.OAUTH_DB,oauth.WALLOS_DB=self.old
        self.dir.cleanup()

    def test_second_wallos_account_cannot_authorize_global_ledger(self):
        # This project is intentionally a single-owner ledger.
        with sqlite3.connect(oauth.WALLOS_DB) as db:
            db.execute("INSERT INTO user (id,username,password) VALUES (?,?,?)",
                (2,"other",bcrypt.hashpw(b"second-password",bcrypt.gensalt()).decode()))
        self.assertTrue(oauth.owner_ok("owner","test-only-password"))
        self.assertFalse(oauth.owner_ok("other","second-password"))

    def test_default_scope_is_read_only_and_visible_on_consent(self):
        redirect = self.redirect
        registration = self.client.post("/oauth/register", json={
            "redirect_uris": [redirect], "token_endpoint_auth_method": "none",
        })
        self.assertEqual(registration.status_code, 201, registration.text)
        page = self.client.get("/oauth/authorize", params={
            "client_id": registration.json()["client_id"],
            "redirect_uri": redirect,
            "response_type": "code",
            "code_challenge_method": "S256",
            "code_challenge": self.challenge,
            "resource": oauth.RESOURCE,
            "state": "default-read-only",
            # Client intentionally does not supply a scope.
        })
        self.assertEqual(page.status_code, 200, page.text)
        self.assertIn("finance:read", page.text)
        self.assertIn("只允许查询财务信息", page.text)
        self.assertNotIn("finance:write", page.text)
        with oauth.store() as db:
            self.assertEqual(db.execute(
                "SELECT scope FROM pending WHERE state='default-read-only'"
            ).fetchone()[0], "finance:read")

    def test_dcr_authorize_token_refresh(self):
        discovery=self.client.get("/oauth/.well-known/oauth-authorization-server")
        self.assertEqual(discovery.status_code,200)
        self.assertIn("S256",discovery.json()["code_challenge_methods_supported"])
        bad=self.client.post("/oauth/register",json={"redirect_uris":["https://evil.example/callback"]})
        self.assertEqual(bad.status_code,400)
        r=self.client.post("/oauth/register",json={"redirect_uris":[self.redirect],
            "token_endpoint_auth_method":"none","client_name":"ChatGPT"})
        self.assertEqual(r.status_code,201,r.text)
        client=r.json()["client_id"]
        params={"client_id":client,"redirect_uri":self.redirect,"response_type":"code",
                "code_challenge_method":"S256","code_challenge":self.challenge,
                "scope":"finance:read finance:write","state":"xyz","resource":oauth.RESOURCE}
        request=self.client.get("/oauth/authorize",params=params)
        self.assertEqual(request.status_code,200,request.text)
        # Browser CSP form-action applies to redirects too: restricting it to
        # 'self' blocks the 303 OAuth callback at chatgpt.com.
        self.assertNotIn("form-action",request.headers.get("content-security-policy",""))
        import re
        request_id=re.search('name="request_id" value="([^"]+)"',request.text).group(1)
        fail=self.client.post("/oauth/authorize",data={"request_id":request_id,
            "username":"owner","password":"wrong"})
        self.assertEqual(fail.status_code,200)
        accepted=self.client.post("/oauth/authorize",data={"request_id":request_id,
            "username":"owner","password":"test-only-password"})
        self.assertEqual(accepted.status_code,303)
        again=self.client.post("/oauth/authorize",data={"request_id":request_id,
            "username":"owner","password":"test-only-password"})
        self.assertEqual(again.status_code,410)
        self.assertIn("ChatGPT",again.text)
        from urllib.parse import urlparse,parse_qs
        redirect=accepted.headers["location"]
        self.assertTrue(redirect.startswith(self.redirect+"?"))
        qs=parse_qs(urlparse(redirect).query)
        self.assertEqual(qs["state"],["xyz"])
        code=qs["code"][0]
        exchange=self.client.post("/oauth/token",data={"grant_type":"authorization_code",
                "client_id":client,"code":code,"redirect_uri":self.redirect,"code_verifier":self.verifier})
        self.assertEqual(exchange.status_code,200,exchange.text)
        tokens=exchange.json()
        self.assertTrue(oauth.verify_access_token(tokens["access_token"],"finance:read"))
        self.assertTrue(oauth.verify_access_token(tokens["access_token"],"finance:write"))
        replay=self.client.post("/oauth/token",data={"grant_type":"authorization_code",
                "client_id":client,"code":code,"redirect_uri":self.redirect,"code_verifier":self.verifier})
        self.assertEqual(replay.status_code,400)
        new=self.client.post("/oauth/token",data={"grant_type":"refresh_token",
                "client_id":client,"refresh_token":tokens["refresh_token"]})
        self.assertEqual(new.status_code,200,new.text)
        reused=self.client.post("/oauth/token",data={"grant_type":"refresh_token",
                "client_id":client,"refresh_token":tokens["refresh_token"]})
        self.assertEqual(reused.status_code,400)
        self.assertTrue(oauth.verify_access_token(new.json()["access_token"]))

if __name__=="__main__":unittest.main()
