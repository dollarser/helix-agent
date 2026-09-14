"""One-time HXA-187 closure after full physical results and cleanup are verified."""
from pathlib import Path
import json
root=Path('build/debug/2026-09-10')
run=root/'physical-quickjs-verified'
rows=json.loads((run/'results.json').read_text())
assert len(rows)==1 and rows[0]['state']=='PASS' and rows[0]['passed']==77
assert rows[0]['assumptions']==0 and rows[0]['ignored']==0
cleanup=json.loads((run/'cleanup.json').read_text())
assert all(r['uninstall']=='Success' for r in cleanup if 'package' in r)
assert any(r.get('autofillRestored') for r in cleanup)
assert 'BUILD SUCCESSFUL' in (root/'quickjs-release-host-gates.log').read_text()
assert 'do_freezer_trap' in (root/'physical-quickjs-headless-control/runtime-quickjs-timeout-threads.log').read_text()
update=json.loads((root/'physical-developer-quickjs-fix/result.json').read_text())
assert 'Success' in update['install'] and 'Status: ok' in update['launch']
p=Path('docs/completion-records/HXA-187.md');assert not p.exists()
p.write_text('''# HXA-187：QuickJS 真机冻结诊断与测试宿主修复

日期：2026-09-10。范围：HXA-186 的 QuickJS 超时停滞。决策记录：不适用，修正测试生命周期与不成立的平台假设，没有改变生产执行底座、IPC、隔离、10秒默认限额或审批机制。

## 原因与证据

OnePlus PLC110 / Android15 API35 / arm64 / 4 KiB 上，原库测试没有可见 Activity。超时测试挂起期间，通过测试包自身 `run-as` 读取 `/proc/<pid>/task/*/wchan`：主线程、JUnit 测试线程及 Binder 线程均处于 **do_freezer_trap**。早期增加的20秒诊断线程也停止运行，无法打印线程栈。这说明整个测试进程被冻结，而非证明 QuickJS 的中断回调或同步 Binder 正在死锁。

35秒有界 headless 对照再次捕获全部线程 freezer 状态，记录在 `build/debug/2026-09-10/physical-quickjs-headless-control/`。这是有意保留的失败对照，不计为验收通过。先前300秒宿主终止后的 Process crashed 也不是产品自发崩溃。

增加可见宿主后，同一生产执行路径返回 TIMEOUT，首轮单用例记录 **elapsedMs=10044**，整条测试11.676秒，主进程存活及下一次返回42的恢复断言通过；旧PID观测在下述修复前不足以单独证明实际回收，最终77项使用改正后的检测。冻结对照阶段没有修改 QuickJS 生产代码或引擎依赖；后续另发现并修复绑定早退时的连接泄漏，见下文。原先同步 `sendInterrupt` 的代码疑点没有被证实为本次根因，不以猜测修改协议。

Android 的冻结机制会暂停进程内全部线程，见 [AOSP freezer说明](https://source.android.com/docs/core/perf/cached-apps-freezer)。本机观测确认冻结状态，但未追到具体OEM策略服务，不能直接把它归因于AOSP默认缓存策略；也不代表生产App的后台/锁屏矩阵已通过。

## 修复

- QuickJS全部11个设备测试类使用 `QuickJsDeviceTestHost`：每个用例的 `@Before` 启动仅测试包内的非导出Activity，`@After` 结束；窗口可见期间保持屏幕开启，结束后自然释放。初始化必须在用例内，因为放在runner启动阶段的Activity会被框架在测试前自动关闭。
- 不改系统电池/冻结开关，不申请忽略电池优化，不添加生产保活组件，不删除超时/进程回收/取消/恢复断言。测试Activity不进入developer或consumer生产APK。
- 原OOM测试把所有API>29设备的错误文本按API36模拟器固定，API35真机合法返回 `<empty message>`，同轮另有回收观测竞态，共73通过/2失败。现仅允许既有契约中的空消息标记或 `out of memory` 两种形式，仍严格断言OOM状态、PID/UID隔离和后续恢复。错误文本的分配结果不能由API版本推断。
- 回收观测不再把ActivityManager调用方过滤列表中的PID缺席当作死亡，改用signal 0检查，ESRCH才算不存在、EPERM仍算存活，不发送实际信号；同名重绑单测由BIND_FAILED变为通过。原有Binder death专项仍保留。
- 生产 `JsExecutionClient.bindInstance` 增加交接前finally：系统接受绑定后、连接交给execute之前的取消/超时/异常均解绑。原外层finally只能释放已经返回的BoundInstance，因此遗漏早退。新增2项真实绑定测试，故意阻止回调投递；修复前均失败（期望解绑1次、实际0次），修复后通过；失败测试自身finally清理，防止夹具留下连接。
- 日期runner支持单方法选择、20～300秒宿主截止与超时前仅本测试包的线程wait-channel取证；`--headless` 是显式对照入口，不是正常验收模式。

## 验收

```sh
./gradlew :runtime:quickjs:assembleDebugAndroidTest :runtime:quickjs:testDebugUnitTest :runtime:quickjs:lintDebug spotlessCheck detekt :app:assembleDeveloperDebug --max-workers=1
python3 scripts/debug/2026-09-10/run-physical-library-regression.py --serial <明确真机serial> --output <新的忽略目录> --modules runtime/quickjs
```

主机 BUILD SUCCESSFUL：QuickJS JVM **85通过，0失败/跳过**，模块lint、全仓Spotless/Detekt通过。最终真机模块 **77通过，0失败/条件跳过/ignored**，覆盖默认10秒死循环、取消竞态、崩溃/新实例恢复、OOM、输出边界、PFD与ABI攻击。已卸载本轮临时测试包；原developer包与数据保留，未操作Claude模拟器。已重新构建developer并按用户授权覆盖安装绑定清理修复，保留会话和配置；安装及启动证据见 `physical-developer-quickjs-fix/`。

最终APK SHA-256：`'''+rows[0]['apkSha256']+'''`。

证据根 `build/debug/2026-09-10/`：`physical-quickjs-diagnostic-01`、`physical-quickjs-visible-01`为诊断中间轮；`physical-quickjs-visible-02`为单用例通过；`physical-quickjs-headless-control`为冻结对照；`physical-quickjs-final`为OOM文本断言暴露轮；`physical-quickjs-verified`为最终77项与清理；`quickjs-release-host-gates.log`为主机门禁。`physical-quickjs-binding-before`保留生产修复前的2项失败，`physical-quickjs-reclamation-probe`保留同名实例回收验证；各轮冻结APK和原始日志保留，不覆盖失败记录。

HXA-186的浏览器资源夹具停滞仍待独立归因，本项没有操作浏览器或认定它同源。QuickJS后台/锁屏/OEM及真机长稳也不是本次前台回归的验收范围。未提交、推送。
''')
p=Path('docs/development/roadmap.md');s=p.read_text();i=s.index('### HXA-187');s=s[:i]+s[i:].replace('状态：in progress。','状态：completed，见 [完成记录](../completion-records/HXA-187.md)。',1);p.write_text(s)
p=Path('docs/development/status.md');s=p.read_text();start=s.index('HXA-186 的 API35');end=s.index('\n',start);s=s[:start]+'''HXA-186：App48、存储50、文件38通过；HXA-187已修复QuickJS无界面测试冻结、PID/OOM平台假设及绑定早退连接泄漏，真机77项、JVM85项通过，详见 [记录](../completion-records/HXA-187.md)。生产超时仍为10秒，未修改引擎/IPC；浏览器资源夹具停滞仍待归因。临时包已清理，developer数据保留；未操作Claude模拟器，不代表后台/锁屏或全量真机验收通过。'''+s[end:];s=s.replace('Codex 本轮不运行测试，不操作原模拟器，不自动推送；HXA-185 验证完成前不声明收口。','Codex 按后续授权完成 HXA-186/187 真机工作，不操作原模拟器，不自动推送；Claude 在途结果独立落盘。');p.write_text(s)
p=Path('docs/completion-records/HXA-186.md');s=p.read_text();needle='## 待修复或定位';s=s.replace(needle,needle+'\n\n后续更正：QuickJS停滞已由 [HXA-187](HXA-187.md) 定位为无界面测试进程冻结，修复宿主与绑定清理后77项通过。下文保留当时观察和未证实的Binder假设，不再作为当前根因结论。浏览器项仍开放。',1);p.write_text(s)
p=Path('docs/development/verification-matrix.md');s=p.read_text();needle='## HXA-185 验证结果';s=s.replace(needle,'| HXA-187 | QuickJS冻结诊断、回收证据与绑定早退清理 | JVM85与API35真机77项通过；生产10秒限额不变，见 [记录](../completion-records/HXA-187.md) |\n\n'+needle,1);s += '\nHXA-186 QuickJS状态已被 [HXA-187](../completion-records/HXA-187.md) 的冻结诊断与77项通过结果更新；原失败证据保留。\n';p.write_text(s)
print('HXA-187 physical fix verified and recorded')
