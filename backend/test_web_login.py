"""Tests for Wallos-account login to the expense ledger."""
import sqlite3
import tempfile
import unittest
from pathlib import Path

import bcrypt
from fastapi.testclient import TestClient

import app as core
import oauth_support as oauth
from web_login import COOKIE_NAME

class WebLoginTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.original_db,self.original_user_db=core.DB,oauth.WALLOS_DB
        core.DB=Path(self.temp.name)/"finance.sqlite3"
        oauth.WALLOS_DB=Path(self.temp.name)/"wallos.sqlite3"
        with sqlite3.connect(oauth.WALLOS_DB) as c:
            c.execute("CREATE TABLE user(id INTEGER PRIMARY KEY,username TEXT UNIQUE,password TEXT NOT NULL)")
            c.execute("INSERT INTO user (username, password) VALUES (?,?)",("owner",
                  bcrypt.hashpw(b"example-test-password",bcrypt.gensalt()).decode()))
        self.client=TestClient(core.app,base_url="https://finance.example.com")
        self.headers={"Origin":"https://finance.example.com"}
    def tearDown(self):
        self.client.close()
        core.DB,oauth.WALLOS_DB=self.original_db,self.original_user_db
        self.temp.cleanup()

    def test_login_cookie_get_update_and_logout(self):
        self.assertEqual(self.client.get("/api/v1/dashboard").status_code,401)
        wrong=self.client.post("/auth/login",headers=self.headers,
                   json={"username":"owner","password":"no"})
        self.assertEqual(wrong.status_code,401)
        signin=self.client.post("/auth/login",headers=self.headers,
                   json={"username":"owner","password":"example-test-password"})
        self.assertEqual(signin.status_code,200)
        self.assertIn("HttpOnly",signin.headers["set-cookie"])
        self.assertIn("Secure",signin.headers["set-cookie"])
        self.assertIn("SameSite=strict",signin.headers["set-cookie"])
        cookie=signin.cookies.get(COOKIE_NAME)
        self.assertTrue(cookie)
        session={COOKIE_NAME:cookie}
        res=self.client.get("/api/v1/dashboard",cookies=session)
        self.assertEqual(res.status_code,200,res.text)
        subscription={"name":"Test","amount_cents":1999,"currency":"USD","cadence":"monthly",
            "next_due_ms":1792036800000}
        unsafe=self.client.post("/api/v1/subscriptions",json=subscription,cookies=session)
        self.assertEqual(unsafe.status_code,403)
        safe=self.client.post("/api/v1/subscriptions",json=subscription,
                        headers=self.headers,cookies=session)
        self.assertEqual(safe.status_code,410,safe.text)
        from unittest.mock import patch
        with patch("wallos_api.get_all",return_value=[]):
            self.assertEqual(self.client.get("/api/v1/subscriptions",cookies=session).json()["items"],[])
        signout=self.client.post("/auth/logout",headers=self.headers,cookies=session)
        self.assertEqual(signout.status_code,200)
        self.assertEqual(self.client.get("/api/v1/dashboard",cookies=session).status_code,401)

    def test_cross_site_and_bad_origin(self):
        bad=self.client.post("/auth/login",headers={"Origin":"https://evil.example"},
           json={"username":"owner","password":"example-test-password"})
        self.assertEqual(bad.status_code,403)

if __name__=="__main__": unittest.main()
