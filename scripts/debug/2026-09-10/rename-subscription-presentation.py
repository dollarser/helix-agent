"""One-time display-name and native screen integration; never rename package/IPC/vault identities."""
from pathlib import Path
root = Path('runtime/cli-app/src/main')
manifest = root/'AndroidManifest.xml'
s = manifest.read_text().replace('android:label="Helix CLI Runtime"', 'android:label="@string/subscription_app_name"\n        android:theme="@style/Theme.Helix.Subscription"')
manifest.write_text(s)
for locale in ['values', 'values-en', 'values-zh-rCN']:
    path = root/'res'/locale/'strings.xml'
    s = path.read_text().replace('</resources>', '    <string name="subscription_app_name">Helix Subscription Provider</string>\n    <string name="subscription_back">'+('Back' if locale == 'values-en' else '返回')+'</string>\n</resources>')
    path.write_text(s)
    path = Path('app/src/main/res')/locale/'strings.xml'
    path.write_text(path.read_text().replace('CLI Runtime', 'Helix Subscription Provider'))
base = root/'kotlin/com/helix/runtime/cli/app'
for name in ['CodexLoginActivity', 'CopilotLoginActivity', 'GrokLoginActivity']:
    path = base/(name+'.kt')
    path.write_text(path.read_text().replace('setContentView(content.root)', 'SubscriptionScreen.show(this, content.root)'))
path = base/'ClaudeLoginActivity.kt'
s = path.read_text().replace('setContentView(buildContent())','SubscriptionScreen.show(this, buildContent())').replace('private fun buildContent(): View =','private fun buildContent(): LinearLayout =').replace('import android.view.View\n','')
path.write_text(s)
path = base/'CliRuntimeHomeActivity.kt'
s = path.read_text().replace('import android.widget.ScrollView\n','').replace('title = "Helix CLI Runtime"','title = getString(R.string.subscription_app_name)').replace('setContentView(ScrollView(this).apply { addView(column) })\n        column.requestApplyInsets()', 'SubscriptionScreen.show(this, column, home = true)')
path.write_text(s)
# Presentation insets now have one owner: the shared shell.
import re
for name in ['CopilotLoginContent', 'GrokLoginContent', 'ClaudeLoginActivity']:
    path = base/(name+'.kt')
    s = path.read_text()
    s = re.sub(r'    @Suppress\("DEPRECATION"\)[^\n]*\n', '', s)
    s = re.sub(r'            val attributes = .*?            requestApplyInsets\(\)\n', '', s, flags=re.S)
    path.write_text(s)
path = base/'CliRuntimeHomeActivity.kt'
s = path.read_text()
s = re.sub(r'    @Suppress\("DEPRECATION"\)[^\n]*\n', '', s)
s = re.sub(r'            val padding = .*?\n        }', '\n        }', s, flags=re.S)
path.write_text(s)
