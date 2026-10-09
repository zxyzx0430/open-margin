import io
import json
import sqlite3
import tempfile
import unittest
import urllib.parse
from pathlib import Path
from unittest.mock import patch
import wallos_bridge as bridge

class DummyReply(io.BytesIO):
    def __init__(self, result):
        super().__init__(json.dumps(result).encode())

class WallosMirrorTest(unittest.TestCase):
    def test_add_idempotent_edit(self):
        with tempfile.TemporaryDirectory() as tmp:
            previous=(bridge.DB,bridge.API_KEY)
            bridge.DB=Path(tmp)/"finance.sqlite3";bridge.API_KEY="test-only"
            with sqlite3.connect(bridge.DB) as db:
                db.execute("""CREATE TABLE subscriptions(
                    id INTEGER PRIMARY KEY,name TEXT,amount_cents INTEGER,currency TEXT,cadence TEXT,
                    next_due_ms INTEGER,payment_channel TEXT,note TEXT,active INTEGER)""")
                db.execute("INSERT INTO subscriptions VALUES(1,'Test Subscription',1999,'USD','monthly',1791500000000,'','',1)")
                db.commit()
            observed=[]
            def fake_open(req,timeout=0):
                params=urllib.parse.parse_qs(req.data.decode())
                observed.append(params)
                return DummyReply({"success":True,"subscriptionId":1234})
            try:
                with patch.object(bridge.urllib.request,"urlopen",side_effect=fake_open):
                    a=bridge.sync()
                    self.assertEqual(a["synced"],1)
                    self.assertEqual(observed[-1]["action"],["add"])
                    b=bridge.sync()
                    self.assertEqual(b["synced"],0)
                    with sqlite3.connect(bridge.DB) as db:
                        db.execute("UPDATE subscriptions SET amount_cents=2500 WHERE id=1")
                    c=bridge.sync()
                    self.assertEqual(c["synced"],1)
                    self.assertEqual(observed[-1]["action"],["edit"])
                    self.assertEqual(observed[-1]["id"],["1234"])
            finally:
                bridge.DB,bridge.API_KEY=previous

if __name__=="__main__": unittest.main()
