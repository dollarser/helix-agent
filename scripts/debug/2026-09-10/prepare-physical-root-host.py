from pathlib import Path
p=Path('feature/browser/src/androidTest/kotlin/com/helix/feature/browser/BrowserTestActivity.kt')
s=p.read_text().replace('import android.os.Bundle','import android.os.Bundle\nimport android.view.WindowManager').replace('        super.onCreate(savedInstanceState)','        super.onCreate(savedInstanceState)\n        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)');p.write_text(s)
b=Path('tools/root/src/androidTest');d=b/'kotlin/com/helix/tools/root'
s=Path('runtime/quickjs/src/androidTest/kotlin/com/helix/runtime/quickjs/QuickJsDeviceTestHost.kt').read_text().replace('com.helix.runtime.quickjs','com.helix.tools.root').replace('QuickJsDeviceTestHost','RootDeviceTestHost').replace('QuickJsTestActivity','RootTestActivity').replace('helix.quickjs.headless','helix.root.headless').replace('Helix QuickJS test\\nRunning isolated execution checks…','Helix Root test\\nRunning bounded Root lifecycle checks…')
(d/'RootDeviceTestHost.kt').write_text(s)
for p in d.glob('*Test.kt'):
 s=p.read_text().replace('class '+p.stem+' {','class '+p.stem+' : RootDeviceTestHost() {');p.write_text(s)
(b/'AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n    <application>\n        <activity android:name="com.helix.tools.root.RootTestActivity" android:exported="false" />\n    </application>\n</manifest>\n')
p=Path('docs/development/roadmap.md');s=p.read_text();s+='\n\n### HXA-188 真机浏览器冻结、后台恢复与 Root 验证\n\n状态：in progress。所有者授权按浏览器卡住、真实 HOME/锁屏恢复、Root 真机顺序执行。允许 feature/browser、app、tools/root 的相关代码/测试、日期脚本与 docs；不操作 Claude 模拟器、不改个人 developer 数据或系统锁屏策略。验证：独立构建的浏览器全套及失败隔离重跑；临时 consumer 的真实 HOME/熄屏/解锁后 Chat 与 Goal 状态、请求去重和恢复；临时 Root APK 的被动状态、显式授权/拒绝、服务死亡及有界只读工具。主机执行 spotlessCheck、detekt、相关 JVM/lint/构建及 docs/ADR/i18n/secrets/diff。安全锁屏由所有者正常解锁；阻塞项保留，不能以模拟生命周期代替实际设备证据。\n';p.write_text(s)
p=Path('docs/development/status.md');s=p.read_text().replace('## In progress\n','## In progress\n\nHXA-188：Codex 独占已选 USB 真机处理浏览器测试进程冻结、真实后台/锁屏恢复与 Root 验证；不操作下述 Claude 模拟器。\n');p.write_text(s)
