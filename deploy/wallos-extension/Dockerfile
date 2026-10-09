# Pin to the exact upstream release currently deployed (avoid :latest drift).
# Rebase header.php and index.php intentionally when upgrading Wallos.
FROM bellamy/wallos@sha256:0006879778f31a962a3873f7682514b92a3c7665dcbc4764fcc241ad968eaccd

COPY expenses.php /var/www/html/expenses.php
COPY integration.php /var/www/html/integration.php
COPY expenses-action.php /var/www/html/expenses-action.php
COPY expenses-export.php /var/www/html/expenses-export.php
COPY ledger-common.php /var/www/html/ledger-common.php
COPY header.php /var/www/html/includes/header.php
COPY index.php /var/www/html/index.php

LABEL org.opencontainers.image.title="Wallos + self-hosted daily expense extension" \
      org.opencontainers.image.description="Wallos vendor UI with custom expense ledger endpoint. Two separate databases."
