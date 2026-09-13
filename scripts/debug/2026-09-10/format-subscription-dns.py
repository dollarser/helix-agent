"""Format only files owned by manual subscription DNS work."""
from pathlib import Path
import subprocess
root=Path('runtime/cli-app/src')
paths=[root/'main/kotlin/com/helix/runtime/cli/app'/n for n in ['SubscriptionDnsSettings.kt','SubscriptionsApplication.kt','SubscriptionNetworkSettingsActivity.kt','CliRuntimeHomeActivity.kt','CodexOAuth.kt']]
paths += [root/'test/kotlin/com/helix/runtime/cli/app/SubscriptionDnsSettingsTest.kt',root/'androidTest/kotlin/com/helix/runtime/cli/app/SubscriptionDnsDeviceTest.kt']
with Path('build/debug/2026-09-10/subscription-dns-format.log').open('w') as log:
 r=subprocess.run(['./gradlew','spotlessApply','-PspotlessIdeHook='+','.join(str(p.resolve()) for p in paths)],stdout=log,stderr=subprocess.STDOUT)
raise SystemExit(r.returncode)
