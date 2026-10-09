"""Create verified, atomic, day-retained SQLite snapshots for both finance databases.

- Missing source is an ERROR, not a silently successful "backup".
- SQLite online backup API prevents torn copies while services are running.
- Temporary files are atomically replaced only after integrity_check succeeds.
- Keep latest 3 backup sets + latest set from every day over 30 days.
- Fail without deleting older valid backups.
"""
from datetime import datetime,timezone,timedelta
from pathlib import Path
import os
from configparser import ConfigParser
import sqlite3

BACKUPS=Path(os.getenv("FINANCE_BACKUP_DIR", "/data/backups"))
SOURCES={
    "transactions":Path(os.getenv("FINANCE_DB", "/data/finance/finance.sqlite3")),
    "wallos":Path(os.getenv("FINANCE_WALLOS_DB", "/data/wallos/wallos.db")),
}

def snapshot_one(src:Path,dest:Path):
    if not src.is_file():
        raise FileNotFoundError("Required finance database missing: "+str(src))
    tmp=dest.with_name(dest.name+".partial")
    tmp.unlink(missing_ok=True)
    try:
        with sqlite3.connect(f"file:{src}?mode=ro",uri=True,timeout=20) as live:
            with sqlite3.connect(tmp,timeout=20) as snapshot:
                live.backup(snapshot, pages=100, sleep=0.05)
        with sqlite3.connect(f"file:{tmp}?mode=ro",uri=True) as verify:
            result=verify.execute("PRAGMA integrity_check").fetchone()
            if not result or result[0]!="ok":
                raise RuntimeError(f"SQLite integrity check failed: {src.name}")
        os.chmod(tmp,0o600)
        os.replace(tmp,dest)
    finally:
        tmp.unlink(missing_ok=True)

def retention(name:str,now:datetime,folder:Path):
    files=sorted(folder.glob(f"{name}-*.sqlite3"),reverse=True)
    keep=set(files[:3]) # last few explicit/manual snapshots
    seen_days=set()
    oldest=(now-timedelta(days=30)).strftime("%Y%m%d")
    for f in files:
        day=f.name[len(name)+1:len(name)+9]
        if day>=oldest and day not in seen_days:
            keep.add(f)
            seen_days.add(day)
    for f in files:
        if f not in keep:
            f.unlink(missing_ok=True)

def run():
    BACKUPS.mkdir(mode=0o700,parents=True,exist_ok=True)
    os.chmod(BACKUPS,0o700)
    missing=[str(p) for p in SOURCES.values() if not p.is_file()]
    if missing:raise FileNotFoundError("Missing required SQLite source(s): "+", ".join(missing))
    now=datetime.now(timezone.utc)
    stamp=now.strftime("%Y%m%d-%H%M%S")
    created=[]
    # Prefer all-or-nothing observable snapshots, even though the two stores
    # represent separate domains and do not need cross-database transactions.
    try:
        for name,src in SOURCES.items():
            dest=BACKUPS/f"{name}-{stamp}.sqlite3"
            snapshot_one(src,dest)
            created.append(dest)
    except Exception:
        for dest in created:
            dest.unlink(missing_ok=True)
        raise
    for name in SOURCES:
        retention(name,now,BACKUPS)
    print("Verified backups completed for both finance databases.")

if __name__=="__main__":
    run()
