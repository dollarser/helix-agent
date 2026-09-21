# HXA-198 main 整合与真实主进程恢复

日期：2026-09-21。所有者授权先合并再由协调者收尾，`main` 从 `d5645774` 快进到 `14cfbb44`；小模型准备切片与双手动终端实现一并保留。本记录只覆盖本次整合，199 长稳、206 产品集成及196真机豁免不因此关闭。

## 发现与修复

首次普通应用页面测试在“新建终端”失败：页面传入当前目录，而 `DeveloperManualTerminal` 与 `ProotTerminalHost` 都拒绝已有终端使用的目录。先前不同目录的服务测试没有覆盖这个产品入口。

依 accepted ADR-RUNTIME-002，两个手动会话共享 UID/文件系统；独立 shell、cwd/env 与输出不要求目录路径互斥。移除两处同目录拒绝，保留最多两个会话、逐会话单写、Agent 执行互斥、租期、停止证明和显式结算。设备测试新增同目录两会话成功、第三会话拒绝的回归。首次失败证据保留于 `build/hxa198-main-death-v1-api29/`。

20次生命周期测试同时输出实际 PID、FD 与线程起止值；每个 shell 结束后检查 PID 不存在。FD 差值上限15、线程差值上限10是短测容差，不是长稳结论。

设备回归还发现测试竞态：shell重定向先创建空文件再写入，测试只等待文件存在会偶发读到空串。改为有界等待预期内容，保留最终相等断言；没有放宽产品结果要求。旧单终端测试的第二会话拒绝断言更新为第三会话拒绝，保留原重连/租期/结算检查。

## 可复现入口

重型命令均通过 `python3 scripts/debug/2026-09-18/with-host-slot.py --` 串行运行。

- `./gradlew spotlessApply :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest`
- `python3 scripts/debug/2026-09-21/run-dual-terminal-recovery-gates.py hxa198-main-death-v5`
- `./scripts/check-all.sh --all`
- `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`

双API runner使用自有 `Helix191_API29` / `Helix191_API36` 进程；拒绝已有serial，保存APK SHA、owner、关闭结果和原始日志。先执行多会话/单会话服务测试与普通页面准备，再用普通应用完成实际主进程SIGKILL；不以Activity重建或instrumentation重启替代主进程死亡。

宿主分别经页面新建两个终端，记录两个shell PID、各自环境变量和启动计数；杀死主PID后，确认Runtime PID与boot_id不变，再从新主PID连接两个原shell。最后先结算第一个，再结算第二个，检查共享执行占用释放。无Agent PTY输入工具、Root应用授权或命令自动重放。

## 验证状态

- 同目录修复提交：`5d97fc01`；随后仅调整测试内容等待与宿主脚本端口，不改变被测生产代码。
- 完整主机 `check-all.sh --all`：exit 0，日志 `build/hxa198-closeout/check-all-final.log`；两次 BUILD SUCCESSFUL，36锁、consumer/developer APK与Runtime边界通过。
- 测试竞态修正后 `spotlessApply :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest detekt`：exit 0，`build/hxa198-closeout/fix-build-v5.log`。
- Python：51/51，exit 0。
- 双API每端10项Runtime测试及1项页面准备通过，另各1条普通应用双终端主进程死亡旅程通过；各自owned进程正常退出。原始目录 `build/hxa198-main-death-v5-api29/` 与 `build/hxa198-main-death-v5-api36/`。
- API29：主PID `4960 → 5531`，Runtime `5238`，原shell `5283 / 5418`；20次循环FD `76 → 76`，线程 `35 → 36`。
- API36：主PID `4649 → 5168`，Runtime `4960`，原shell `4996 / 5114`；20次循环FD `139 → 139`，线程 `39 → 40`。
- 两端每个shell启动计数均为1，原环境变量与PID匹配，结束后共享admission已释放。真实Runtime死亡由设备测试验证为UNKNOWN并拒绝同boot结算；其中reboot证明通过测试构造注入，不声称本次实际重启设备。真实reboot路径历史证据见197完成记录。

两端共同主APK SHA-256：`7bf9a4a778e0dc536bc4a1654e42ba95ed1100f0ef2ace71564840527d1d8a65`；测试APK：`bcb68c449f7cf7f43b1482daa7a5140800a53b6af0d7b8d0fdfec830d666e526`。APK与原始设备结果的绑定以各目录`artifacts.json`为准。

失败尝试：v2为旧单会话断言污染后继夹具；v3已通过两个shell重连，但多发返回键退出页面导致清理失败；v4暴露文件创建与写入竞态。均保留原始日志，不计通过。短循环不替代199的idle/长租期/OEM/Doze/热压/真实16KiB验收，199/206仍开放。


## 扩展回归

`python3 scripts/debug/2026-09-20/run-terminal-page-gates.py hxa198-runtime-final --runtime-only`：exit 0。API29/36 各49/49（61.817s / 77.473s），包含既有Runtime、工具/订阅fixture、PTY及真实终端页面中文/REPL/重建/输入输出压力；两端owner正常关闭，release consumer/developer产物边界通过。该集合与上面的10项服务测试有重叠，不相加宣称独立用例总数。

日志：`build/hxa198-closeout/runtime-final.log`，原始目录 `build/hxa198-runtime-final-api{29,36}/`。主APK与上面相同；此轮重新打包的测试APK SHA-256为 `7425c6525a104e1ad940ab46016f886ec6a24e001abc350e5de07b9b07642cb8`，不混用两轮测试制品身份。

最终源码/文档门禁：`./scripts/check-all.sh --source` exit 0，488份Markdown、194项登记HXA、31份ADR、1369条三语资源；未闭合义务12项不等于登记总数。
