"""One-time user-facing name refinement; preserve installed app, components and credential identity."""
from pathlib import Path
for root in ['runtime/cli-app/src/main/res','app/src/main/res']:
 for locale in ['values','values-en','values-zh-rCN']:
  path=Path(root)/locale/'strings.xml'
  path.write_text(path.read_text().replace('Helix Subscription Provider','Helix Subscriptions'))
