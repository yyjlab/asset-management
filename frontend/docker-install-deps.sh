#!/bin/sh
set -eu

case "${1:-}" in
    pnpm|dependencies) ;;
    *) echo "Usage: $0 pnpm|dependencies" >&2; exit 2 ;;
esac

for registry in https://registry.npmmirror.com https://repo.huaweicloud.com/repository/npm https://registry.npmjs.org; do
    echo "[frontend] Installing $1 using $registry"
    if [ "$1" = pnpm ]; then
        if npm install --global pnpm@10.34.7 --registry "$registry" \
            --fetch-timeout=30000 --fetch-retries=1 \
            --fetch-retry-mintimeout=1000 --fetch-retry-maxtimeout=5000 \
            --no-audit --no-fund; then
            exit 0
        fi
    else
        if CI=true pnpm install --frozen-lockfile --prefer-offline --registry "$registry" \
            --fetch-timeout=30000 --fetch-retries=1 \
            --fetch-retry-mintimeout=1000 --fetch-retry-maxtimeout=5000 \
            --network-concurrency=8 --reporter=append-only; then
            exit 0
        fi
    fi
    echo "[frontend] Installation failed; trying next registry"
done

echo "[frontend] All registry attempts failed" >&2
exit 1
