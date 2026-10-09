# Personal Finance System

**Self-hosted personal finance bridge:** Android payment-notification collector → HTTPS → SQLite → Wallos subscriptions & daily expenses → ChatGPT / MCP.

This is an **integration project**, built on existing open-source software. The goal is to own your transaction data, avoid manually retyping payments, and make spending and subscriptions queryable from an AI client.

> **Status: pre-release / single-owner.** Real WeChat/Alipay notification coverage depends on the phone/ROM and requires reconciliation with official statements. Do not treat captured notifications as confirmed bank statements. No banking credentials or real payment actions are supported.

## Architecture

```text
Android (opt-in, Room SQLite)
  Payment notifications / optional bank SMS / manual entries
     └─ exact cents + stable event ID + immediate WorkManager upload
             └─ HTTPS reverse proxy
                 └─ FastAPI /api/v1/transactions/batch → private SQLite ledger
                                   ├─ Wallos /expenses.php (daily/monthly reports)
                                   ├─ CSV export, optional budget, review
                                   └─ MCP (OAuth 2.1 / PKCE; read, authorized writes)

Wallos upstream Docker image → separate Wallos SQLite for subscriptions
                                ↕ native PHP page and secure backend API
```

- **Wallos** manages subscriptions and future renewal estimates. An expected subscription charge is *not* treated as an actual completed payment.
- The **transaction ledger** records observed payments, keeps independent currencies, and supports review/exclusion. It is not a second subscription database.
- This project is a **single-owner design**. The daily ledger, OAuth, and MCP are restricted to the configured Wallos owner ID; additional Wallos accounts must not be given access to private ledger data.
- Android → server sync is **one way**. Clearing a phone record will not delete server data. Device notifications may be missing or duplicate.

## Requirements

- Linux with Docker Engine and Docker Compose v2.
- A hostname under your control with valid HTTPS and Nginx (or equivalent reverse proxy).
- Android 8+ with notification listener support to use the optional collector.
- Optional: ChatGPT-compatible OAuth/MCP client.

## Quick start (clean installation)

1. Clone this repository. Copy `.env.example` to `.env` and adjust host-local ports if needed.
2. Generate new deployment credentials **locally** (no built-in shared tokens):
   ```sh
   python3 scripts/init-config.py --url https://finance.example.com/finance --owner-id 1
   ```
   Replace `finance.example.com` with **your real HTTPS domain**. The example Nginx routing currently uses the `/finance` URL prefix.
3. Build and start private services:
   ```sh
   docker compose config -q
   docker compose up -d --build
   docker compose ps
   curl http://127.0.0.1:8767/health
   ```
   The services listen only on `127.0.0.1`; do **not** expose port 8767 or 18082 directly to the Internet.
4. Place the Nginx routing from `deploy/nginx-locations.conf.example` inside your existing **HTTPS** server block; update your domain/ports, test Nginx and reload it. Obtain your TLS certificate separately.
5. **Secure initial registration:** temporarily restrict the public Wallos URL to your IP (or use a local SSH tunnel) until you register the first owner; otherwise anyone who reaches the fresh site first could claim it. Visit `https://YOUR_DOMAIN/finance/wallos/` and register the owner. The initial owner ID is normally `1`; check that registration is closed afterward. The ledger page is `https://YOUR_DOMAIN/finance/wallos/expenses.php`.
6. Verify `https://YOUR_DOMAIN/finance/health` and `https://YOUR_DOMAIN/finance/.well-known/oauth-protected-resource`. An **unauthorized** request to `/finance/api/v1/transactions` must return 401; do not disable auth for troubleshooting.
7. Optional Android: build APK from `android/`, install, grant notification listener, enable HTTPS sync, enter `https://YOUR_DOMAIN/finance` and the device token from **your** `secrets/backend.env`. Keep this token private. Run a *real* low-value transaction and check its amount, time, merchant and deduplication against the Wallos ledger.

**Credentials:** `secrets/` is mode **0700**, `secrets/backend.env` is **0600**, and `secrets/wallos-ledger.env` is **0644 inside that non-traversable directory**, because the non-root PHP worker must be able to read the file inside its container. All are ignored by Git. Keep the private parent directory restricted and never commit/share these files. Changing a device upload token requires updating the phone. The Wallos browser never receives the backend ledger token.

## Wallos × actual payment integration (v1.4)

- A native Wallos **Subscriptions × Spending** screen reconciles expected subscription billing cycles against existing Android transactions, without inventing payments.
- Suggested matches require human confirmation; one transaction cannot be linked to multiple billing periods. Linked real expenses are counted **once**.
- Supports refund reconciliation, transaction corrections with an audit log, merchant-category rules, recurrence discovery, estimated remaining budgets, anomaly cards and up to 36 months of separate-currency trends.
- The private Wallos API, background mobile ingestion and OAuth MCP tools expose the same data model. Write tools require a finance:write grant.
- Access: /finance/wallos/integration.php (after Wallos owner login). See [technical guide](docs/wallos-integration-v14.md).

## Daily workflow

- Android queues new structured transaction events immediately; WorkManager retries and schedules additional sync at **minimum 15-minute intervals** subject to Android battery restrictions.
- Wallos opens the current ledger from live SQLite, then checks for changes around once per minute while the page is visible.
- Today, seven-day, monthly and per-day CNY summaries; category/merchant/date filters; status review; optional monthly spending budget; CSV export.
- Currency summaries remain independent without assumed FX rates; the daily bar chart currently displays CNY only.
- ChatGPT/MCP uses OAuth DCR + S256 PKCE and scopes. Only grant access to trusted clients.

## Backups and recovery

The two databases must be backed up separately: Wallos **subscriptions** and Finance **transactions**.

After registering the Wallos owner and initializing the ledger, run a verified on-demand snapshot:

```sh
docker compose --profile maintenance run --rm backup
```

The maintenance service mounts the two named SQLite volumes and writes verified snapshots to its dedicated backup volume. SQLite WAL access requires the source volume to permit shared-memory journal files; the Python connection itself is read-only. Schedule this command **daily** using your host scheduler (or a dedicated cron container). Snapshots have integrity checks, atomic writes and 30-day daily retention. Backups on the same host do not survive a disk or provider failure: also maintain **encrypted off-VPS backups**.

Never delete or reset the live SQLite database to fix an app problem. Keep immutable snapshots and verify restores against **copies** before relying on them.

## Tests

```sh
python3 -m pip install -r backend/requirements.txt "httpx>=0.27,<1"
cd backend && FINANCE_PUBLIC_URL=https://finance.example.com/finance python3 -m unittest discover -p "test_*.py" -v
cd .. && python3 scripts/check-public-ready.py
```

A clean-room HTTP integration script is included at `scripts/smoke_clean_install.py`. It requires a **throwaway**, empty staging stack bound to ports 18767 and 18083 with `https://finance.example.com/finance` in the staging secrets. It deliberately creates synthetic Wallos and transaction records. See [`docs/ACCEPTANCE.md`](docs/ACCEPTANCE.md) for the full reproducible test and results; **never run it against live data**.

CI also runs Android tests and outputs **unsigned** release APKs. Never store signing keys in this repository. If publishing signed binaries, create a dedicated permanent signing key privately and use a repeatable offline process.

## Known limitations

- No automatic import of an old on-device ledger into a new installation. **Do not uninstall** an old Android APK until its unsynced transactions are backed up.
- Payment-notification formats vary and may miss transfers, split payments or refunds.
- The Wallos `header.php` and `index.php` overlays are based on a pinned upstream release; **rebase and review** these files before any upstream image upgrade.
- Single-owner deployment only; multi-tenant ledger isolation is *not* implemented.
- No bundled cloud TLS, remote backups, payment connections, or live bank reconciliation.
- No guarantee of unattended mobile collection on all Android OEM ROMs.

## Acknowledgements and license

This repository combines an Android fork of **[Seamless Bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping)** (MIT; attribution in `android/LICENSE`) and a **[Wallos](https://github.com/ellite/Wallos)** derivative (GPL-3.0; license in root `LICENSE`). See `THIRD_PARTY.md` for details. We do not claim authorship of the upstream implementations.

The root GPL-3.0 license covers the Wallos-derived distribution; it does not erase the Android project's existing MIT license. Check all components' upstream license terms when redistributing.
