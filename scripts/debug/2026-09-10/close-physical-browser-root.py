"""One-time HXA-188 closure after checking preserved device evidence."""
import json
from pathlib import Path
b=Path('build/debug/2026-09-10')
browser=json.loads((b/'physical-browser-suite-awake/results.json').read_text())[0]
background=json.loads((b/'physical-background-native/results.json').read_text())
root=json.loads((b/'physical-root-fixed/results.json').read_text())[0]
assert (browser['state'],browser['passed'],browser['assumptions'])==('PASS',36,2)
assert (background['state'],background['passed'],background['assumptions'])==('PASS',3,0)
assert (root['state'],root['passed'],root['assumptions'])==('PASS',6,1)
for mode in ['granted','denied']:
 assert json.loads((b/f'physical-root-policy/{mode}.json').read_text())['state']=='PASS'
assert 'Success' in json.loads((b/'physical-root-policy/cleanup.json').read_text())['uninstall']
for directory in ['physical-browser-suite-awake','physical-root-fixed']:
 for row in json.loads((b/directory/'cleanup.json').read_text()):
  assert not any('error' in k.lower() for k in row)
  if 'uninstall' in row: assert 'Success' in row['uninstall']
for row in background['cleanup']:assert 'Success' in row['uninstall']
installed=json.loads((b/'physical-developer-root-fix/result.json').read_text())
assert 'Success' in installed['install'] and 'Status: ok' in installed['launch']
assert 'BUILD SUCCESSFUL' in (b/'physical-final-gates.log').read_text()
text='''# HXA-188：真机浏览器冻结、后台恢复与 Root 崩溃修复

日期：2026-09-10。状态：本轮有界任务完成；不等于长稳或全量发布验收。当前 main 工作树仍含并行测试工作，未提交/推送。本轮不操作 Claude 的模拟器。

## 浏览器：测试进程冻结已定位并修复

API35 OnePlus PLC110、arm64/4 KiB、WebView 151.0.7922.199。原冻结 APK `b9382e97…` 单跑反复创建/销毁通过，整套仍在同一资源用例停滞；120秒宿主取证显示主线程、JUnit、WebView 等全部为 `do_freezer_trap`。当时熄屏/锁屏也导致 Autofill JS result、弹窗节点等待失败，不能据此认定生产 WebView 回归。

短资源测试在每个用例内启动空的可见 Activity；实际 WebView 的 Application Context、创建/销毁路径、描述符 +8 门限和回调断言保持不变。BrowserTestActivity 添加 KEEP_SCREEN_ON，仅在测试窗口可见时保持亮屏，不修改系统电池/锁屏策略。显式长稳/control 用例保留原宿主契约；没有借此重标在途长稳为通过。

修复后已解锁真机整套 **36通过、2条件跳过，53.2秒**。Autofill、弹窗取消、资源回收均通过。两项跳过仍是需显式参数的长稳/原生诊断，不计为通过。新 APK 使用独立 buildDirectory；Claude 正在使用的 browser APK SHA 仍为 `b9382e9768568bf71f6e4536b3485388e49e08e108d460a68b036f2c29583778`。

## 实际 HOME / 熄屏 / 解锁恢复

使用全新 consumer 沙箱和真实 ChatService、Provider transport、Room/FGS，服务端是端内 loopback 合成流，不使用个人会话、账号或真实模型。

**3通过、0跳过，31.522秒**：

- HOME 后 FGS 与原任务继续；后台输出完成并持久化，返回前台恢复同一会话且仅一个 Turn。
- 熄屏后 Chat 完成并落盘；经正常解锁返回会话，未重复请求。
- 熄屏后 Goal 显式暂停，保存 USER_PAUSED；回到前台后显式继续创建新的 Turn，再次暂停，合计两次运行，无盲目重放。

每次后台观察保持3秒，记录真实 PowerManager/window 状态和阶段；安全锁屏由所有者正常解锁。该证据不覆盖长时间后台、强制 Doze、低内存杀进程、网络断连或完整 OEM 矩阵。

早期夹具失败均保留：Compose 获取后台 Activity/ActivityScenario 清理等待、冻结，以及合成 SSE 未关闭输出侧。最终采用普通 instrumentation 驱动实际 Activity，提供 HTTP EOF，Activity 保持到结果交付，由宿主统一卸载沙箱；未更改产品后台/Goal 机制。早期失败轮次不计入通过。

## Root：发现并修复真实客户端崩溃

MagiskSU 30.7。原真机 RootService 授权和连接成功，但服务死亡时客户端出现 `ArrayIndexOutOfBoundsException`，堆栈为 libsu `RootServiceManager.dropConnections` → `ArrayMap.removeAt`。

结合 [libsu 6.0.0 连接清理源码](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java) 与 Helix 回调：libsu 在迭代连接时发断开通知，Helix 同步 unbind 重入并删除同一项。使用其现有 [Executor 回调接口](https://github.com/topjohnwu/libsu/blob/6.0.0/service/src/main/java/com/topjohnwu/superuser/ipc/RootService.java)，将回调始终排到主线程队列；Binder 死亡和 IPC 异常也排队，核对连接/Binder身份后才清理，旧死亡通知不能影响新连接。发布 binder 引用使用 volatile。不升级依赖、不吞掉该崩溃、不自动重绑或重放。

修复后模块 **6通过、1条件跳过，22.086秒**：

- 被动状态/Profile 不请求 Root；显式授权连接后模拟自身 RootService 死亡，状态变 LOST，清除 PID/连接。
- 新增连续3轮自身服务死亡与显式重连；每次断开后保持 LOST，不自动重绑。
- 有界 `/system/etc/hosts` 读取、测试包信息、限量进程/日志、路径越界拒绝及服务死亡后工具失败。
- 无会话时高层操作拒绝、被动 Root 状态可读。

跳过的1项是声明只适用于 rootless 的立即取消授权请求；没有计为 Root grant/cancel 通过。

另外独立测试 UID 先完成 granted **1/1**，随后仅在 Magisk UI 撤销该临时测试包授权（系统要求正常身份验证），新进程显式请求得到 DENIED、无 RootService 连接，**1/1**。不通过 SQL 修改授权策略；未改全局或其他 App 权限。本轮不宣称验证“管理器撤权会立即杀死已存活的 Root shell”；生产后台主动关闭旧 Root 授权的语义保持原样。

## 主机与制品

- 浏览器 JVM **117/117**、Root JVM **21/21**。
- `./gradlew -I scripts/debug/2026-09-10/isolated-browser-build.gradle spotlessCheck detekt :feature:browser:testDebugUnitTest :feature:browser:lintDebug :tools:root:testDebugUnitTest :tools:root:lintDebug :app:lintConsumerDebug` 通过。
- 最终 `./gradlew spotlessCheck detekt :tools:root:testDebugUnitTest :tools:root:lintDebug :app:lintConsumerDebug :app:lintDeveloperDebug` 通过；双沙箱测试 APK、Root测试 APK和developer APK构建通过。
- docs / ADR / i18n / secrets / diff 检查通过。
- developer SHA：`DEVELOPER_SHA`，覆盖安装成功、冷启动999ms，保留原数据。
- 当前手机只保留原 developer；本轮 consumer/库/Root测试包及临时设备 XML 已清理，未留下自身 RootService。

执行脚本按日期保存在 `scripts/debug/2026-09-10/`，所有序列号只通过命令参数传入。原始证据位于忽略目录 `build/debug/2026-09-10/`：`physical-browser-suite-before`、`physical-browser-suite-after`、`physical-browser-suite-awake`、`physical-background-*`、`physical-root-granted`（修复前崩溃）、`physical-root-fixed`、`physical-root-policy`、`physical-developer-root-fix`，包含 APK SHA、instrumentation 原文及清理结果。故障/阶段性失败没有覆写或改成 PASS。

## 仍未关闭

系统 JNI/Binder 长期累积根因、API29在途/更宽浏览器长稳、真实Doze/长时间后台/进程死亡恢复、Root长稳及更多OEM、Root控制台写操作/发布验收仍不在本次短回归结论内。
'''.replace('DEVELOPER_SHA',installed['sha256'])
Path('docs/completion-records/HXA-188.md').write_text(text)
p=Path('docs/development/roadmap.md');s=p.read_text();start=s.index('### HXA-188');s=s[:start]+s[start:].replace('状态：in progress。','状态：completed，见 [完成记录](../completion-records/HXA-188.md)。',1);p.write_text(s)
p=Path('docs/development/verification-matrix.md');s=p.read_text().replace('进行中：浏览器36通过/2条件跳过，后台与Root执行中','浏览器36通过/2条件跳过；实际后台3通过；Root6通过/1条件跳过，独立grant/deny各1通过，见 [HXA-188](../completion-records/HXA-188.md)');p.write_text(s)
p=Path('docs/development/status.md');s=p.read_text().replace('HXA-188：Codex 独占已选 USB 真机处理浏览器测试进程冻结、真实后台/锁屏恢复与 Root 验证；不操作下述 Claude 模拟器。','HXA-188 真机有界任务已完成，见 [记录](../completion-records/HXA-188.md)：浏览器36通过/2条件跳过、实际后台/锁屏3通过、Root6通过/1条件跳过及独立grant/deny各1通过；RootService死亡回调重入崩溃已修复。未操作下述Claude模拟器。').replace('浏览器资源夹具停滞仍待归因。','浏览器资源夹具停滞已由HXA-188归因为测试进程冻结并修复。').replace('不代表后台/锁屏或全量真机验收通过。','HXA-188已补充短时后台/锁屏与Root证据，不代表全量真机或长稳验收通过。');p.write_text(s)
p=Path('scripts/debug/2026-09-10/README.md');s=p.read_text();s+='''\n- `fix-root-callback-reentrancy.py`、`close-physical-browser-root.py`：一次性修复/证据校验归档来源，不重放。\n- `run-physical-root-policy.py`：按 setup → granted → 管理器对临时包的正常撤权 → denied → cleanup 执行；ownership.json 核对安装 UID，拒绝已有包或所有权变化，不编辑 Magisk 数据库。\n- 最终后台夹具使用普通 instrumentation，不使用 Compose/ActivityScenario 跨锁屏清理；回收由独占手机 runner 的 finally 完成。\n''';p.write_text(s)
