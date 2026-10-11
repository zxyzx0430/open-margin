# Portable WAL-mode SQLite backups and file permissions

## Finding and fix (2026-10-11)

SQLite online backup preserves the source database's journal-mode header. When Finance uses WAL mode, opening the intermediate `.partial` snapshot in read-only mode for integrity checks can leave `.partial-wal` and `.partial-shm` files behind. These are not standalone backups and accumulate despite retention rules matching only `.sqlite3`.

`deploy/backup.py` now converts **only the temporary snapshot** to SQLite's DELETE journal mode after the online backup, verifies its integrity, atomically replaces the destination, and cleans temporary database/WAL/SHM sidecars. The source database and its journal mode are unchanged. Connections are closed deterministically.

`backend/test_backup_regression.py` adds a WAL-mode fixture test that checks standalone restore in read-only immutable mode, source journal-mode preservation, and absence of temporary sidecars.

`deploy/healthcheck.py` now also inspects file permissions for the Wallos database directory, `wallos.db`, and optional `setup_token.db`, warning if group or other users can access them. Deployments should ensure the service owner can continue reading and writing these files; directory permissions `0700`, and database files `0600`, are appropriate for a single-owner deployment with a consistent container UID.

## Scope and safeguards

- Tests create disposable SQLite databases only.
- No live transaction values, tokens, or payment data are logged.
- No historical backup or source SQLite content is deleted or modified by this fix.
- Old orphan sidecar files from earlier versions are not automatically removed. Inspect them separately before any cleanup.
- Host-local backups do not protect against VPS/disk failure. Maintain verified, encrypted off-host copies.

## Testing

```sh
cd backend
python3 -m unittest test_backup_regression -v
python3 -m unittest discover -s . -p 'test_*.py' -q
```

The scheduled backup service picks up the revised script on its next scheduled run; it does not require a restart or a database migration.
