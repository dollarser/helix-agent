#!/usr/bin/env bash
set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

readonly secret_pattern='(sk-ant-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{30,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[0-9A-Za-z-]{20,}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----)'

if rg \
    --hidden \
    --line-number \
    --pcre2 \
    --glob '!/.git/**' \
    --glob '!/.gradle/**' \
    --glob '!**/build/**' \
    --glob '!scripts/check-secrets.sh' \
    "$secret_pattern" \
    "$project_root"; then
    printf 'Potential committed secret detected.\n' >&2
    exit 1
fi

printf 'Secret scan passed.\n'
