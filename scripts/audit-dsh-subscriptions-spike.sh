#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <installed-dsh-plugin-subscriptions-directory>" >&2
  exit 2
fi

readonly plugin_dir="$1"
readonly package_json="$plugin_dir/package.json"
readonly auth_store="$plugin_dir/lib/auth/store.js"
readonly claude_creds="$plugin_dir/lib/auth/claude-code-creds.js"
readonly codex_provider="$plugin_dir/lib/providers/codex.js"
readonly claude_provider="$plugin_dir/lib/providers/claude.js"

for required in \
  "$package_json" \
  "$plugin_dir/LICENSE" \
  "$auth_store" \
  "$claude_creds" \
  "$codex_provider" \
  "$claude_provider"; do
  [[ -f "$required" ]] || { echo "missing required plugin file: $required" >&2; exit 1; }
done

node --input-type=module - "$package_json" <<'NODE'
import { readFileSync } from 'node:fs'

const path = process.argv[2]
const pkg = JSON.parse(readFileSync(path, 'utf8'))
if (pkg.name !== 'dsh-plugin-subscriptions') throw new Error(`unexpected package: ${pkg.name}`)
if (pkg.license !== 'MIT') throw new Error(`unexpected declared license: ${pkg.license}`)
if (!/^\d+\.\d+\.\d+$/.test(pkg.version)) throw new Error(`non-fixed version: ${pkg.version}`)
console.log(`package=${pkg.name}@${pkg.version} license=${pkg.license}`)
NODE

rg -q "auth\.json" "$auth_store"
rg -q "accessToken" "$auth_store"
rg -q "refreshToken" "$auth_store"
rg -q "readClaudeCodeCredentials" "$claude_creds"
rg -q "writeBackClaudeCodeCredentials" "$claude_creds"
rg -q "chatgpt\.com/backend-api/codex/responses" "$codex_provider"
rg -q "CODEX_CLIENT_ID" "$codex_provider"
rg -q "api\.anthropic\.com/v1/messages" "$claude_provider"
rg -q "CLAUDE_CLIENT_ID" "$claude_provider"
rg -q "claude-cli/" "$claude_provider"

if rg -q "jobId|executionId|Binder|DeadObjectException" \
  "$plugin_dir/lib/auth" "$codex_provider" "$claude_provider"; then
  echo "unexpected Helix-style durable job/Android IPC signal found; review required" >&2
  exit 1
fi

shasum -a 256 "$package_json" "$plugin_dir/LICENSE" "$auth_store"
echo "result=REFERENCE_ONLY token_owner=plugin official_cli=false android_job_protocol=false"
