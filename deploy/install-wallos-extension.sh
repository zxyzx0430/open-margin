#!/usr/bin/env bash
# Idempotent Wallos extension install with a pinned vendor image.
# PHP overrides are BAKED IN THE IMAGE (never bind-mounted writable code).
# Preserve database/logos volumes. Keep previous container stopped for rollback.
set -euo pipefail
ROOT="${FINANCE_ROOT:-/srv/personal-finance}"
FILES="${FINANCE_WALLOS_SOURCE_DIR:-$ROOT/project/deploy/wallos-extension}"
NAME=finance-wallos
TOKEN="${FINANCE_WALLOS_SECRET_FILE:?Set path to secret file}"
STAMP="$(date +%Y%m%d%H%M%S)"
for f in Dockerfile expenses.php daily-spend-chart-v2.js expenses-action.php expenses-export.php ledger-common.php header.php index.php service-worker.php mobile-menu-expenses.php calendar.php stats.php finance-unified-common.php finance-unified-data.php finance-unified-action.php finance-unified.js finance-unified.css; do
    test -r "$FILES/$f"
done
test -r "$TOKEN"

# Content-addressed image tag ensures repeated invocation does not cause
# unnecessary image rebuilds or container restarts.
overlay_hash="$(
  cd "$FILES"
  sha256sum Dockerfile expenses.php daily-spend-chart-v2.js expenses-action.php expenses-export.php ledger-common.php header.php index.php service-worker.php mobile-menu-expenses.php calendar.php stats.php finance-unified-common.php finance-unified-data.php finance-unified-action.php finance-unified.js finance-unified.css | sha256sum | cut -c1-16
)"
IMAGE="finance-wallos-extension:$overlay_hash"
running_id="$(docker inspect "$NAME" --format '{{.Image}}' 2>/dev/null || true)"
running_image_name="$(docker inspect "$NAME" --format '{{.Config.Image}}' 2>/dev/null || true)"

# Safe one-time migration from the older :local tag if its baked code
# exactly matches what is in source control (never trust a writable bind).
if ! docker image inspect "$IMAGE" >/dev/null 2>&1 &&
   [[ "$running_image_name" == "finance-wallos-extension:local" ]] &&
   ! docker inspect "$NAME" --format '{{range .Mounts}}{{.Destination}}{{println}}{{end}}' | grep -qE '^/var/www/html/(expenses.php|index.php|includes/header.php)$'; then
    matches=1
    for mapping in "daily-spend-chart-v2.js:daily-spend-chart-v2.js" "expenses.php:expenses.php" "expenses-action.php:expenses-action.php" \
                   "expenses-export.php:expenses-export.php"                    "ledger-common.php:ledger-common.php" "header.php:includes/header.php"                    "index.php:index.php" "service-worker.php:service-worker.php"; do
        source="${mapping%%:*}"
        target="${mapping#*:}"
        ours="$(sha256sum "$FILES/$source" | cut -d' ' -f1)"
        theirs="$(docker exec "$NAME" sha256sum "/var/www/html/$target" 2>/dev/null | cut -d' ' -f1 || true)"
        if [ "$ours" != "$theirs" ]; then matches=0; break; fi
    done
    if [ "$matches" -eq 1 ]; then docker tag "$running_id" "$IMAGE"; fi
fi

# Never rely on a mutable vendor :latest; the Dockerfile pins the upstream digest.
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    docker build --pull=false -q -t "$IMAGE" "$FILES" >/dev/null
fi
# Always compare baked code against source, not only a Docker tag/image ID.
# This guards against a stale image accidentally carrying a new hash tag.
verify_baked_assets() {
    for asset in expenses.php daily-spend-chart-v2.js service-worker.php images/siteicons/svg/mobile-menu/expenses.php includes/header.php calendar.php stats.php finance-unified-common.php finance-unified-data.php finance-unified-action.php finance-unified.js finance-unified.css; do
        local expected actual
        local source="$FILES/$asset"
        if [[ "$asset" == "includes/header.php" ]]; then source="$FILES/header.php"; fi
        if [[ "$asset" == "images/siteicons/svg/mobile-menu/expenses.php" ]]; then source="$FILES/mobile-menu-expenses.php"; fi
        expected="$(sha256sum "$source" | cut -d' ' -f1)"
        actual="$(docker exec "$NAME" sha256sum "/var/www/html/$asset" 2>/dev/null | cut -d' ' -f1 || true)"
        if [[ "$expected" != "$actual" ]]; then
            echo "Baked asset mismatch: $asset" >&2
            return 1
        fi
    done
}
# Wallos runs php-fpm as Alpine www-data (currently GID 82). The host's
# www-data is a different numeric GID (33). A 0640 host secret owned by GID
# 33 is unreadable to the container: fail closed, do not widen to 0644.
# Resolve the target image GID, not the host's group name.
test "$(stat -c %u "$TOKEN")" = 0
web_gid="$(docker run --rm --entrypoint id "$IMAGE" -g www-data)"
test "$web_gid" -ge 1
chgrp "$web_gid" "$TOKEN"
chmod 0640 "$TOKEN"

target_id="$(docker image inspect "$IMAGE" --format '{{.Id}}')"
if [ "$running_id" = "$target_id" ] &&
   [ "$(docker inspect "$NAME" --format '{{.State.Running}}' 2>/dev/null || true)" = true ]; then
    verify_baked_assets
    docker exec "$NAME" test ! -e /var/www/html/integration.php
    # The page's actual PHP identity, not root's docker-exec identity,
    # must be able to read the auth secret and make an API request.
    docker exec --user www-data "$NAME" php -r '
      require "/var/www/html/ledger-common.php";
      $r=ledger_request("GET","month?month=".date("Y-m"));
      if (!isset($r["items"]) || !is_array($r["items"])) exit(10);
    '
    echo "Correct pinned Wallos extension image is already running."
    exit 0
fi

OLD=""
if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
    OLD="finance-wallos-rollback-$STAMP"
    docker rename "$NAME" "$OLD"
    docker stop -t 15 "$OLD" >/dev/null
fi

rollback() {
    echo "Image upgrade failed. Restoring previous Wallos instance." >&2
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    if [ -n "$OLD" ]; then
        docker rename "$OLD" "$NAME" || true
        docker start "$NAME" || true
    fi
}
trap rollback ERR

docker run -d --name "$NAME" \
    -p 127.0.0.1:18082:80 \
    --memory=256m --memory-swap=384m \
    --restart unless-stopped -e TZ=Asia/Shanghai \
    --security-opt no-new-privileges:true \
    -v "$ROOT/wallos/db:/var/www/html/db" \
    -v "$ROOT/wallos/logos:/var/www/html/images/uploads/logos" \
    -v "$TOKEN:/run/secrets/wallos-ledger.env:ro" \
    "$IMAGE" >/dev/null

ready=0
for attempt in $(seq 1 75); do
    if curl -fsS --max-time 3 http://127.0.0.1:18082/health.php >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 1
done
if [ "$ready" -ne 1 ]; then
    echo "Health check failed" >&2
    exit 6
fi
for php in expenses.php expenses-action.php expenses-export.php ledger-common.php includes/header.php index.php service-worker.php images/siteicons/svg/mobile-menu/expenses.php calendar.php stats.php finance-unified-common.php finance-unified-data.php finance-unified-action.php; do
    docker exec "$NAME" php -l "/var/www/html/$php" >/dev/null
done
verify_baked_assets
docker exec "$NAME" test ! -e /var/www/html/integration.php
if docker inspect "$NAME" --format '{{range .Mounts}}{{.Destination}}{{println}}{{end}}' | grep -qE '^/var/www/html/(expenses.php|index.php|includes/header.php)$'; then
    echo "Code remains on a writable bind mount. Refusing deployment." >&2
    exit 7
fi
# Detect the previously missed failure: root could read the secret but
# real HTTP requests from php-fpm (www-data) could not.
docker exec --user www-data "$NAME" php -r '
  require "/var/www/html/ledger-common.php";
  $r=ledger_request("GET","month?month=".date("Y-m"));
  if (!isset($r["items"]) || !is_array($r["items"])) exit(10);
'
# Confirm the deployed application still has the original Wallos DB and
# expenses functionality without risking user data.
docker exec "$NAME" php -r '
  $db=new SQLite3("/var/www/html/db/wallos.db", SQLITE3_OPEN_READONLY);
  if ($db->querySingle("SELECT count(*) FROM user") < 1) exit(1);
  if ($db->querySingle("SELECT count(*) FROM subscriptions") < 1) exit(2);
'
trap - ERR
echo "Wallos extension image installed; restart-proof PHP, subscriptions retained."
echo "Stopped rollback image: $OLD"
