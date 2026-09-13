"""Record bounded physical execution from retained results, including failures and cleanup."""
from pathlib import Path
import json
root=Path('build/debug/2026-09-10')
app=json.loads((root/'physical-app-01/results.json').read_text())
libs=json.loads((root/'physical-libraries-current/results.json').read_text())
cleanup=json.loads((root/'physical-libraries-current/cleanup.json').read_text())
browser=json.loads((root/'physical-libraries-01/results.json').read_text())[0]
assert app['state']=='PASS' and app['passed']==48
assert len(libs)==3 and all('state' in r for r in libs)
assert all(r.get('uninstall')=='Success' for r in app['cleanup'])
assert all(r.get('uninstall')=='Success' for r in cleanup if 'package' in r)
assert any(r.get('autofillRestored') for r in cleanup)
rows='\n'.join(f"| {r['module']} | {r['state']} | {r.get('passed',0)} | {r.get('assumptions',0)} |" for r in libs)
p=Path('docs/completion-records/HXA-186.md');assert not p.exists()
p.write_text('''# HXA-186：API35 真机隔离回归执行记录

日期：2026-09-10。所有者授权真机测试；本项完成的是有界测试执行与取证，**不是全量真机或产品发布验收通过**。存在两项未收口停滞，不能写成全绿。

## 设备与范围

OnePlus PLC110，Android15/API35，arm64，4 KiB页，1272×2800@560；WebView `com.google.android.webview` 151.0.7922.199。设备序列号只保存在本地执行环境，不进入本文。未操作 Claude 在途 API29 模拟器。

手机已有 `com.helix.agent.developer`。本轮保留该包与全部数据；使用此前不存在的独立库测试包和临时 `com.helix.agent` consumer 沙箱，测试完卸载自己新增的包、恢复原 Autofill 服务。未清除用户数据、调用真实模型/付费账号、授予 Root 或运行长稳。

决策记录：不适用；按既有机制验证，没有修改生产代码或扩大能力。

## 结果

| 套件 | 状态 | 实际通过 | 条件跳过 |
| --- | --- | ---: | ---: |
| consumer App 八个类 | PASS | 48 | 0 |
'''+rows+f'''
| 浏览器冻结 HXA-185 制品 | 未完成，宿主终止停滞 | {browser.get('passed',0)} | {browser.get('assumptions',0)} |

App 覆盖 Goal 报告/暂停/blocked、上下文压缩、长Turn、后台存储、会话生命周期/顶部和独立文件页。浏览器 Autofill 真实填充/保存与 Activity 重建用例已通过；2项跳过为显式长稳/原生诊断 opt-in。部分通过的套件不计为完整通过；未运行到的剩余用例不计 skip。

库测试包先发现存在旧制品，首轮存储47项和文件38项仅保留为历史执行，QuickJS旧包被主动中断以换当前构建，不能算当前代码验收。随后执行：

```sh
./gradlew :core:storage:assembleDebugAndroidTest :feature:files:assembleDebugAndroidTest :runtime:quickjs:assembleDebugAndroidTest --max-workers=1
```

BUILD SUCCESSFUL；重跑结果以上表为准。没有重建/替换 Claude 在途使用的 browser APK。App与browser使用 HXA-185 冻结SHA；本轮所有安装包再复制冻结到各输出目录。

## 待修复或定位

1. **QuickJS 默认超时未能返回**：当前构建在 `JsAttackE2eTest.infiniteLoopRunsFullDefaultWallAndTimesOut` 停滞，预期默认10秒及短宽限返回 TIMEOUT；前19项ABI用例通过后无后续结算，宿主300秒截止终止测试包。旧包也停在同方法，但旧轮被主动中断，不能替代当前复现证据。正常代码执行的部分通过不代表失控代码可被可靠中断。源码的 `JsExecutionClient.sendInterrupt` 是同步 Binder transact，应优先核对等待是否被中断发送阻塞，以及服务侧deadline/引擎中断；这是待验证的调用路径假设，尚未确定根因。
2. **连续 WebView 创建/销毁停滞**：最后开始的方法是 `repeatedCreateDestroyDoesNotGrowTargetProcessDescriptors`。此前的销毁回调用例已通过；最初logcat进度滞后，曾误指向回调用例，完整 instrumentation 已纠正并保存单独说明。约3分钟无进展后宿主只 force-stop 本轮测试包，日志 Process crashed 是人为终止后果，不能声称自发crash。该资源夹具直接以 ApplicationContext 创建宿主，与生产 Activity owner 有差异；目前既不能判定产品死锁，也不能直接归咎模拟器，因为本次是真机。

非root手机拒绝 debuggerd 原生堆栈读取（root is required），未升级权限。保留阶段、process-state与raw instrumentation；后续需独立诊断与最小修复，不延长超时/删除断言以制造通过。

## 复现与证据

```sh
python3 scripts/debug/2026-09-10/run-physical-library-regression.py --serial <明确真机serial> --output <新的忽略目录>
python3 scripts/debug/2026-09-10/run-physical-app-regression.py --serial <明确真机serial> --output <新的忽略目录>
python3 scripts/debug/2026-09-10/run-physical-library-regression.py --serial <明确真机serial> --output <新的忽略目录> --modules core/storage feature/files runtime/quickjs
```

要求 ANDROID_HOME；runner 拒绝模拟器和已有的目标测试包，不自动清除已有 App。App runner要求固定 HXA-185 SHA，制品更新后需先审查新的冻结身份，不能直接绕过。

本地证据根 `build/debug/2026-09-10/`：`physical-libraries-01`（首轮及浏览器停滞）、`physical-app-01`（48项及清理）、`physical-libraries-current`（重建后库回归及300秒上限）、`physical-module-build.log`。包含APK/SHA、每类命令与原始日志、结果、设置恢复和卸载记录。未提交或推送本轮修改；系统JNI/Binder根因、Root、真机长稳、OEM矩阵和发行仍开放。
''')
p=Path('docs/development/roadmap.md');s=p.read_text();a=s.index('### HXA-186');s=s[:a]+s[a:].replace('状态：in progress。','状态：completed（有界执行完成，含未收口失败），见 [执行记录](../completion-records/HXA-186.md)。',1);p.write_text(s)
p=Path('docs/development/status.md');s=p.read_text();a=s.index('## Completed');s=s[:a]+'''## 真机最新结果

HXA-186 的 API35 真机隔离回归已执行：App48、当前存储50和文件38通过；浏览器连续创建/销毁与QuickJS默认超时出现停滞，尚未修复。原developer数据保留，临时包已清理；这不是全量真机通过，详见 [记录](../completion-records/HXA-186.md)。后续优先核实QuickJS中断和浏览器资源夹具，不操作Claude在途模拟器。

'''+s[a:];b=s.index('## 历史验收索引');s=s[:b]+'- M12 / HXA-186 已完成：真机有界回归执行及失败取证，未声明全部通过，见 [执行记录](../completion-records/HXA-186.md)。\n\n'+s[b:];p.write_text(s)
p=Path('docs/development/verification-matrix.md');p.write_text(p.read_text()+'''\n## HXA-186 真机有界执行

API35 OnePlus：App48、存储50、文件38通过；浏览器连续创建/销毁与QuickJS超时停滞未关闭，部分通过不代表套件全绿。见 [执行记录](../completion-records/HXA-186.md)。\n''')
print('Physical execution and unresolved failures recorded')
