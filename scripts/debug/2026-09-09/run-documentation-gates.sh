#!/bin/sh
set -eu
sh scripts/check-docs.sh
sh scripts/verify-adr.sh
sh scripts/check-i18n.sh
sh scripts/check-secrets.sh
git diff --check
