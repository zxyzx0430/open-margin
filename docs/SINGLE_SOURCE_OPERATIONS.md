# Single-source maintenance and release

This repository is the **only active code source** for the Personal Finance System. A previously separate production repository was merged into the portable public codebase without including its Git history, private settings, financial records, databases, or signing keys.

## Reference behavior

Existing production behavior is the compatibility contract. Specifically:
- Finance API v1.5 routes, Android ingestion/deduplication, optional transaction reconciliation and MCP compatibility;
- Wallos native dashboard, expenses, calendar, statistics, budgets and subscription displays;
- excluded/refund/transfer semantics and distinction between actual expenses and scheduled subscriptions;
- current dynamic Wallos exchange-rate conversion and the animated daily/trend bar selection;
- single-owner Wallos ledger, OAuth and browser login; no unauthorized read or write access.

Never reconstruct real amounts or subscriptions from demo fixtures. Test on **temporary copies** of the databases, without making test HTTP mutations against live services.

## Production structure

Code: `$FINANCE_ROOT/project` (one clone of this repository).

Private files: `$FINANCE_DB`, `$FINANCE_WALLOS_DB`, `$FINANCE_OAUTH_DB`, backend and Wallos environment files (paths local to the operator). The server may run a host systemd API and a Dockerized Wallos. The API and Wallos continue to have **separate** SQLite databases.

The Wallos release installer is `deploy/install-wallos-extension.sh`; set `FINANCE_ROOT`, `FINANCE_WALLOS_SOURCE_DIR`, and `FINANCE_WALLOS_SECRET_FILE` to the host's actual locations. The script pins image contents and verifies them before and after replacement; it retains the previous Wallos container for rollback.

For deployments behind a reverse-proxy path prefix, `FINANCE_LEDGER_API_URL` may include the prefix, e.g. `https://example.com/finance/api/v1/wallos-ledger`. It must never contain a token or query string.

## Release gates

1. Verify the public commit's file scan, full backend unit tests, PHP lint, JS syntax, clean-install CI and no private data.
2. Build an isolated Wallos image and run browser tests on mobile 320/390px and desktop 768/1440px, in both themes.
3. Compare the new and old API read-only responses on **the same verified SQLite snapshot**, including effective transaction totals, excluded expenses, and retained v1.5 routes.
4. Make online SQLite backups, confirm integrity, preserve service configurations, save the old running image/commit and prepare tested rollback commands.
5. Deploy the API and Wallos image from the **same pinned public commit**; validate health, authenticated PHP-to-API reads, dashboard, budget, calendar, expenses, trend-selection controls, refund/exclusion behavior and the subscription forecast.
6. Roll back on any unexpected amount, login, data-access or runtime change. Do not migrate, reset, or truncate databases to fix a release.

## Repository governance

Work on branches and merge only verified changes into this repository's `main`. Do not push to the retired private source repository. Keep the old private repository archived offline or private for historical reference and rollback; do not make its Git history public.

Always update one codebase. Production-specific values are environment configuration, not a separate fork of the application.
