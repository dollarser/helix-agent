#!/usr/bin/env bash
set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

readonly secret_patterns="$project_root/scripts/secret-pattern.txt"
if [ ! -s "$secret_patterns" ]; then
    printf 'check-secrets: secret patterns missing or empty; refusing to pass.\n' >&2
    exit 1
fi

if ! command -v rg >/dev/null 2>&1; then
    printf 'check-secrets: ripgrep (rg) is required and not installed; refusing to pass.\n' >&2
    exit 1
fi

# Fail closed on scanner errors (rc != 1): a broken or missing scan must never print
# "Secret scan passed". Output is discarded on purpose — a match would echo the secret
# itself into the terminal/CI log; re-run rg locally to locate a hit.
set +e
rg \
    --hidden \
    --line-number \
    --pcre2 \
    --binary \
    --glob '!/.git/**' \
    --glob '!/.gradle/**' \
    --glob '!**/build/**' \
    --glob '!scripts/check-secrets.sh' \
    --file "$secret_patterns" \
    "$project_root" >/dev/null 2>&1
scan_rc=$?
set -e

case "$scan_rc" in
    0)
        printf 'Potential committed secret detected (match output suppressed to avoid echoing the secret; re-run rg locally to locate it).\n' >&2
        exit 1
        ;;
    1)
        : # no match — pass
        ;;
    *)
        printf 'check-secrets: ripgrep failed with exit code %s; refusing to pass.\n' "$scan_rc" >&2
        exit 1
        ;;
esac

printf 'Secret scan passed.\n'
