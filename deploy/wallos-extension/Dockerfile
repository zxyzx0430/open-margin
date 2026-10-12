# Pin to the exact upstream release currently deployed (avoid :latest drift).
# Rebase header.php and index.php intentionally when upgrading Wallos.
FROM bellamy/wallos@sha256:0006879778f31a962a3873f7682514b92a3c7665dcbc4764fcc241ad968eaccd

COPY calendar.php /var/www/html/calendar.php
COPY stats.php /var/www/html/stats.php
COPY finance-unified-common.php /var/www/html/finance-unified-common.php
COPY finance-unified-data.php /var/www/html/finance-unified-data.php
COPY finance-unified-action.php /var/www/html/finance-unified-action.php
COPY finance-unified.js /var/www/html/finance-unified.js
COPY finance-unified.css /var/www/html/finance-unified.css
COPY expenses.php /var/www/html/expenses.php
COPY daily-spend-chart-v2.js /var/www/html/daily-spend-chart-v2.js
COPY expenses-action.php /var/www/html/expenses-action.php
COPY expenses-export.php /var/www/html/expenses-export.php
COPY ledger-common.php /var/www/html/ledger-common.php
COPY header.php /var/www/html/includes/header.php
COPY manifest.php /var/www/html/manifest.php
COPY mobile-menu-expenses.php /var/www/html/images/siteicons/svg/mobile-menu/expenses.php
COPY index.php /var/www/html/index.php
COPY service-worker.php /var/www/html/service-worker.php

LABEL org.opencontainers.image.title="Open Margin (清衡) · Wallos-powered finance" \
      org.opencontainers.image.description="Wallos vendor UI with custom expense ledger endpoint. Two separate databases."

# The retired subscription/payment reconciliation page must stay unavailable.
RUN rm -f /var/www/html/integration.php
