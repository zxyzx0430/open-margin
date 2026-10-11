"""Hourly read-only health audit for the self-hosted finance stack.

Checks API, Wallos, container restart configuration, recent SQLite backups,
DB integrity, space headroom and the protected daily-expense API.
Never logs/prints passwords or device/API tokens. Nonzero exit records a failed
systemd check; it does NOT restart or mutate user services automatically.
"""
from __future__ import annotations
from datetime import datetime, timezone, timedelta
from pathlib import Path
import json
import os
import shutil
import sqlite3
import subprocess
import sys
from urllib.request import Request, urlopen

ROOT=Path(os.getenv("FINANCE_ROOT", "/data"))
API_HEALTH=os.getenv("FINANCE_HEALTH_URL","http://finance-api:8767/health")
WALLOS_HEALTH=os.getenv("WALLOS_HEALTH_URL","http://wallos:80/health.php")
BACKUP_DIR=Path(os.getenv("FINANCE_BACKUP_DIR",str(ROOT/"backups")))
BACKUP_MAX_AGE_HOURS=int(os.getenv("FINANCE_BACKUP_MAX_AGE_HOURS","48"))
CHECK_DOCKER=os.getenv("FINANCE_HEALTH_CHECK_DOCKER","0")=="1"
CHECK_SYSTEMD=os.getenv("FINANCE_HEALTH_CHECK_SYSTEMD","0")=="1"
LEDGER_API_URL=os.getenv("FINANCE_LEDGER_API_URL","http://finance-api:8767/api/v1/wallos-ledger")

ERR=[]

def check(ok:bool,reason:str):
    if not ok:ERR.append(reason)

def get_json(url:str,headers:dict|None=None)->dict:
    with urlopen(Request(url,headers=headers or {}),timeout=10) as r:
        check(r.status==200,"HTTP health failure: "+url.split("/")[2])
        return json.load(r)

def main():
    try:
        status=get_json(API_HEALTH)
        check(status.get("ok") is True,"Finance API reported failure")
    except Exception as exc:
        ERR.append("Finance API unresponsive: "+type(exc).__name__)

    try:
        with urlopen(WALLOS_HEALTH,timeout=10) as r:
            check(r.status==200,"Wallos local health failed")
    except Exception as exc:
        ERR.append("Wallos unresponsive: "+type(exc).__name__)

    if CHECK_DOCKER:
      try:
        raw=subprocess.check_output(["docker","inspect","finance-wallos",
             "--format","{{json .State}};{{.Config.Image}};{{.HostConfig.RestartPolicy.Name}}"],
              timeout=8,text=True).strip()
        state,image,policy=raw.split(";",2)
        state=json.loads(state)
        check(state.get("Running") and not state.get("OOMKilled"),"Wallos container stopped or OOM-killed")
        check(image.startswith("finance-wallos-extension:"),"Wrong Wallos image; custom pages could disappear")
        check(policy=="unless-stopped","Missing Wallos restart policy")
      except Exception as exc:
        ERR.append("Unable to inspect Wallos container: "+type(exc).__name__)

    for name,src in {
        "transactions":Path(os.getenv("FINANCE_DB",str(ROOT/"finance/finance.sqlite3"))),
        "wallos":Path(os.getenv("FINANCE_WALLOS_DB",str(ROOT/"wallos/wallos.db"))),
    }.items():
        if not src.is_file():
            ERR.append(name+" database missing")
            continue
        try:
            with sqlite3.connect(f"file:{src}?mode=ro",uri=True,timeout=7) as db:
                check(db.execute("PRAGMA quick_check").fetchone()[0]=="ok",name+" SQLite quick_check failed")
        except Exception as exc:
            ERR.append(name+" database unavailable: "+type(exc).__name__)

        backups=sorted(BACKUP_DIR.glob(f"{name}-*.sqlite3"),reverse=True)
        if not backups:
            ERR.append(name+" backup missing")
        else:
            age=(datetime.now(timezone.utc).timestamp()-backups[0].stat().st_mtime)/3600
            check(age<BACKUP_MAX_AGE_HOURS,name+" backup older than configured threshold")

    if CHECK_SYSTEMD:
      try:
        active=subprocess.check_output(["systemctl","is-active","finance-backup.timer"],
                                       timeout=4,text=True).strip()
        check(active=="active","Daily backup timer inactive")
      except Exception:
        ERR.append("Daily backup timer unavailable")

    # Every other non-root OS account on the VPS must not be able to read
    # Wallos subscription metadata, setup token or password hashes. The
    # service itself owns these files and retains full access (UID 82).
    wallos_db_path=Path(os.getenv("FINANCE_WALLOS_DB",str(ROOT/"wallos/wallos.db")))
    for private_path,label in (
        (wallos_db_path.parent, "Wallos database directory"),
        (wallos_db_path, "Wallos database"),
        (wallos_db_path.parent/"setup_token.db", "Wallos setup token"),
    ):
        if private_path.exists():
            try:
                check(private_path.stat().st_mode & 0o077 == 0,
                      label+" permissions allow group/other access")
            except OSError:
                ERR.append(label+" permission inspection unavailable")

    filesystem=shutil.disk_usage("/")
    check(filesystem.free>2*1024**3,"Disk free space under 2 GiB")
    check(filesystem.free/filesystem.total>0.10,"Disk has less than 10% free space")

    secret=Path(os.getenv("FINANCE_LEDGER_CONFIG_FILE","/run/secrets/wallos-ledger.env"))
    if secret.exists():
        try:
            values=dict(line.split("=",1) for line in secret.read_text().splitlines() if "=" in line)
            token=values.get("FINANCE_WALLOS_LEDGER_TOKEN","")
            if not token:ERR.append("Wallos ledger token missing")
            else:
                month=datetime.now(timezone(timedelta(hours=8))).strftime("%Y-%m")
                data=get_json(f"{LEDGER_API_URL.rstrip(chr(47))}/month?month={month}",
                   {"X-Wallos-Ledger-Token":token})
                check(isinstance(data.get("items"),list),"Wallos daily ledger API data missing")
        except Exception as exc:
            ERR.append("Wallos daily ledger integration failure: "+type(exc).__name__)
    else:
        ERR.append("Wallos ledger integration secret missing")

    if ERR:
        for problem in ERR:print("FINANCE_HEALTH_WARN:",problem,file=sys.stderr)
        return 2
    print("FINANCE_HEALTH_OK: API, Wallos, backup, DB integrity, disk and ledger integration")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
