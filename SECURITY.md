# Security

This repository includes an Android notification listener and personal finance ingestion system. Treat all captured spending and configuration as sensitive.

**Before first deployment:** Generate unique credentials with `scripts/init-config.py`. Do not use the example tokens. Expose only an HTTPS reverse proxy; do not publish Docker's local API ports. Restrict access to Wallos registration until the first owner has registered, otherwise an outsider could claim a new installation. Confirm registration is closed after the first owner setup. Use one Wallos owner only.

**Secrets and signing:** Keep `secrets/*.env`, Android signing keystores, Wallos databases, and production backups out of GitHub. The host `secrets/` directory is `0700`; `backend.env` is `0600`, while the Wallos PHP secret is `0644` within the non-traversable directory to accommodate Docker Compose's bind-mounted file-secret permissions. Do not expose the secrets directory via HTTP or share the PHP secret's host path. Keep a separate encrypted offline backup of your signing identity if you distribute Android releases. Any key accidentally committed should be rotated even if the offending commit is later deleted.

**Reconciliation:** Captured notification data is not an official bank statement. Require human verification of large amounts, refunds, transfers and duplicates. The system does not initiate payments.

**Access:** The Finance API uses different credentials for Android ingestion, admin access and the server-to-server Wallos ledger. The MCP interface uses OAuth 2.1 PKCE. The ledger is intentionally restricted to the configured Wallos account ID; there is no per-user tenant isolation.

**Reporting a vulnerability:** Do not put real tokens, personal transaction data or sensitive screenshots into public Issues. Contact the maintainer privately via the repository owner's GitHub profile or use GitHub's private security advisory feature, if enabled. Include a minimal sanitized reproduction.

**Supported deployment:** Docker Compose with a trusted HTTPS reverse proxy. Vendor Wallos PHP overlays are pinned; review upstream security updates and rebase manually before upgrading the base image.
