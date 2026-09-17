#!/usr/bin/env bash
# Local documentation rendering; CLI and browser config are explicit inputs.
set -euo pipefail
: "${MERMAID_CLI:?Set MERMAID_CLI to an installed mermaid-cli entry point}"
: "${PUPPETEER_CONFIG:?Set PUPPETEER_CONFIG to a local browser config JSON}"
output=build/research-refresh-20260916
mkdir -p "$output"
node "$MERMAID_CLI" -p "$PUPPETEER_CONFIG" -j 2 \
  -i docs/research/helix-mermaid-architecture-diagrams.md \
  -o "$output/rendered.md"
node "$MERMAID_CLI" -p "$PUPPETEER_CONFIG" -j 2 -e png \
  -i docs/research/helix-mermaid-architecture-diagrams.md \
  -o "$output/preview.md"
