# Third-party licenses and attribution

This project integrates existing open-source software instead of replacing it.

- **Wallos** by henrique.pt / [ellite/Wallos](https://github.com/ellite/Wallos): **GPL-3.0**. The files `deploy/wallos-extension/header.php` and `index.php` are modified vendor files. The `deploy/wallos-extension/Dockerfile` derives from a pinned Wallos image. The root `LICENSE` provides GPL-3.0 terms for the Wallos-derived distribution; retain original copyright notices.
- **Seamless Bookkeeping** by Dyki / [DykiSensei/seamless-bookkeeping](https://github.com/DykiSensei/seamless-bookkeeping): **MIT**. Modifications to the Android application include opt-in HTTPS upload, exact cents, and synchronization retry. Keep `android/LICENSE` with the fork.
- Python FastAPI, SQLite, MCP and Android build dependencies carry their own licenses. Consult upstream repositories or dependency manifests.

The license of a reused upstream component must not be erased or replaced. This repository's root GPL license is not intended to relicense the upstream Android MIT files.
