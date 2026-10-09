# Pre-publication acceptance report

**Review date:** 2026-10-09 · Test target: disposable isolated Docker Compose deployment, project `pfs-acceptance`, bound to `127.0.0.1:18767` (Finance) and `127.0.0.1:18083` (Wallos).

> This report describes **simulated functional integration and license provenance checks**, not proof of real payment capture on every Android device. No real user records or production services are included in the test environment.

## Repeat the isolated clean-install smoke test

1. Create a **separate clean checkout**. Copy `.env.example` to `.env`; set `FINANCE_API_PORT=18767`, `WALLOS_PORT=18083`.
2. Use `python3 scripts/init-config.py --url https://finance.example.com/finance` to create **throwaway** credentials. This requires local-only ports and the example domain; it never talks to the real finance server.
3. Start services: `docker compose -p pfs-acceptance up -d --build`.
4. Install the test dependency (`pip install -r backend/requirements-dev.txt`), then `python3 scripts/smoke_clean_install.py`. **This creates a synthetic account and synthetic payments in disposable staging only; it refuses non-staging ports/domains.**
5. Snapshot: `docker compose -p pfs-acceptance --profile maintenance run --rm backup`. Confirm the two generated SQLite snapshots pass integrity checks.
6. Clean up **only that isolated project**: `docker compose -p pfs-acceptance down -v`. Do not substitute the production Compose project name.

## Tested and observed

| Verification | Result |
|---|---|
| License provenance: original Wallos GPL-3.0 | GitHub API SPDX GPL-3.0; root license text matches installed upstream |
| License provenance: Android Seamless Bookkeeping | GitHub API SPDX MIT; bundled `android/LICENSE` SHA-256 matches upstream |
| Explicit notice on modified vendor header/index | Added 2026-10-09; original license retained |
| Clean Docker Compose image build | Passed |
| Fresh Wallos registration via HTTP | Passed; creates first owner |
| Wallos native form login | Passed |
| Daily-expense page unauthenticated | Redirected / denied |
| Android-shaped batch with wrong token | HTTP 401 |
| Android-shaped batch with proper token | HTTP 200; one SQLite insertion |
| Resend identical batch | Idempotent; no duplicate |
| Cents precision (1881 cents) displayed in Wallos | Correct, ¥18.81 |
| Native PHP manual expense submit + CSRF | HTTP 303 success; visible after reload |
| Monthly spending budget edit | HTTP 303 success |
| CSV export through logged-in Wallos | HTTP 200; includes synthetic payments |
| OAuth client registration, consent, S256 PKCE, token | Passed |
| OAuth bearer token MCP tools/list | HTTP 200 |
| Read-only OAuth token invoking writing MCP tool | HTTP 403 |
| Additional Wallos account reading ledger/export | HTTP 403 (fixed response code + suppressed vendor markup) |
| Finance & Wallos database integrity via backup | Both SQLite integrity checks returned OK |
| Data persists after staging container restart | Passed |
| Python unit/integration regression suite | 23 tests passed |
| CI for backend/readiness | Passed; Android build CI passed for prior unchanged Android tree |
| Secrets/hard-coded personal domains/production DB in new Git history | Static scan passed; fresh Git history has no production commits |

## Bugs discovered and repaired by simulation

1. **Docker bind-mounted Wallos secret unreadable by non-root PHP-FPM:** generated host file was mode 0600. Solved by mode 0644 **inside a mode-0700 secrets directory**. The backend admin/device credential file stays 0600. Tested from the PHP worker identity.
2. **Unauthorized Wallos secondary account received HTTP 200 with partial HTML:** access control prevented data reads, but vendor header markup had been sent before the denial. Buffered the upstream header until authorization, and cleared the buffer on failure. Retest now returns **HTTP 403 without vendor HTML or ledger data**.
3. **Portable daily grouping used a fixed UTC+8 SQL offset:** changed to configured IANA timezone conversion, including DST.

## Not established by simulation

- Real phone/ROM notification delivery, accessibility capture, SMS permission behavior, Doze/WorkManager timing, and device upgrades/signatures.
- A live, public HTTPS/TLS certificate and public reverse-proxy ingress. All functional probes here use isolated loopback, with example-domain OAuth metadata.
- Real Pay/WeChat and Alipay event formats outside the existing parser examples.
- Unattended off-VPS encrypted backup recovery after complete server loss.
- A formal professional security audit, vulnerability-free guarantee, or formal legal opinion on mixed-license redistributions.

**Disposition:** Source is suitable for a careful preview release after manual review of documentation and upstream copyright obligations; keep the original production repository private. Publishing the OSS repo is a separate, deliberate visibility change.
