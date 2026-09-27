# HXA-210 模拟器专项

所有者在本次对话明确授权模拟器验证。范围是 API29/API36 × consumer/developer 的 bounded Workspace 专项；未使用真机、真实 Provider/账号或完整 device baseline。

使用本次新建 `Helix_HXA210_API29` / `Helix_HXA210_API36`，arm64-v8a。runner 以 read-only、无 snapshot 方式启动，拒绝占用已有 serial，每次运行只终止自己的进程组，不清空其他模拟器或用户数据。应用和 test APK 独立复制到每次输出目录并记录 SHA-256。

## 最终扩展矩阵

四组合共 **192/192 passed**，均为本次实际 instrumentation；四个 runner exit 0 且已关闭自己的模拟器。汇总脚本 `scripts/debug/2026-09-27/summarize-hxa210-closeout.py` 校验主套件、18 项宽度检查、6 项恢复检查、72 张截图尺寸/哈希以及各 flavor 相同 production APK，输出 `build/hxa210/workspace-closeout-device-summary.json`。

| 组合 | build/hxa210/device 输出 | 主套件 + UI + 恢复 | serial |
| --- | --- | --- | --- |
| consumer API29 | consumer-workspace-final-api29-r3 | 22 + 18 + 6 = 46 | emulator-5628 |
| developer API29 | developer-workspace-final-api29-r3 | 26 + 18 + 6 = 50 | emulator-5630 |
| consumer API36 | consumer-workspace-final-api36-r3 | 22 + 18 + 6 = 46 | emulator-5632 |
| developer API36 | developer-workspace-final-api36-r4 | 26 + 18 + 6 = 50 | emulator-5638 |

consumer production APK SHA-256：`d2e76be7408a2ca6fb8bce6607624a3f538ce70f709615cbad5319e94e1b1512`；developer：`46d331ce20b7457c8846f594e1f089a298296bba4b1ff2daaedd509dca66c798`。测试 APK 分别为 consumer `6b9d3932ed2fd902c9e9191afdea6087b7f65d64e19e84ba309bfe2aa685d714`、developer API29 `c48ca7c2f83be505befa0ff0c152d7fbcba8b1596873d6377a568ce7caaa0bbb`、developer API36 `63371dfeb18cb0bc84158b942cfcf1352da72b7f563d58033fa1a2d5adfc789a`。最后一个 fixture 只增加第二次请求超时诊断，断言与超时没有放宽；不伪称全部测试 APK 相同。

每轮实际覆盖系统 picker 持久授权、写入撤权拒绝/重授、旧产物位置与 hash、新请求 AGENTS.md 刷新；API36 还检查 Downloads 根拒选。清理 UI 使用真实 320/360/412dp 窗口、中文/英文/跟随系统三种语言模式、1/2 倍字号；检查可见性、取消、固定目标、成功及失败反馈。已检查两个 API、两渠道代表性的 320dp 大字截图，正文与按钮没有裁切。

清理恢复包含原有 fence+rename 以及追加 before-rename/purging/purged 状态：测试库预置持久切点后真实 kill/reopen，不冒称每个生产 CAS 都被精确打断。备份另在实际 delete 前、delete 后、restore create 后 kill，检查 PREPARED/RESTORING、无自动重放、冲突不覆盖及显式恢复。目录创建中断、备份不足等其余故障由 host 注入断言覆盖，不作为设备断电实验。

developer API36 r3 第二次请求曾超时；同 production APK、保留 30 秒超时的独立诊断 2/2 和 r4 全套 50/50 通过。失败轮次保留，根因没有足够证据定性；运行时曾并行两个 API36 emulator 和全量 host gate，后续避免此重负载组合。192 计数不包含诊断重跑或早期重复检查。

## 发现与修复

1. API29/API36 第一轮暴露目录身份误失效：NIO `creationTime()` 在 Android 无 birth time 支持时回退为 mtime，写入子文件后会被误判为不同目录。依据 [Android API 契约](https://developer.android.com/reference/java/nio/file/attribute/BasicFileAttributes)，不能将该回退值作为不可变身份。最终实现使用只读 fd + device/inode/allocation generation，不写用户目录；不支持该能力时明确拒绝。
2. 中间 statx 实验在 API29 被 seccomp 终止（SIGSYS），已全部移除，保留失败日志。最终实现不调用 statx。
3. fixture 修正：提交可合法返回 Enqueued，测试等待实际请求与 durable Turn；异步来源加载需要等到状态出现后再断言布局。过程中的失败不算通过。
4. 真进程恢复 fixture 初版误拼测试专用 Workspace 路径，修正为数据库隔离目录。产品没有自动删除隔离文件。
5. 实际 Job 补测发现旧输出导入权限代码直接比较 scope ID 与完整 binding reference；改为复用现有分类器的资源/子目录判断。测试显式区分 workspace DENY/external ALLOW 与 external DENY/workspace ALLOW，避免其他拒绝掩盖错误。Job fixture 首轮未创建选定的 output 父目录而失败，已按新目录不强制布局契约修正 fixture。

## 早期矩阵与制品（后续扩展前）

| 组合 | 输出目录（build/hxa210/device 下） | 当前结果 |
| --- | --- | --- |
| consumer API29 | consumer-api29-r6 | 20/20 passed，包含真实 Process.killProcess 后重开 |
| developer API29 | developer-api29-r6 | 21/21 passed，额外包含实际 PRoot 终端占用/切换/清理 |
| consumer API36 | consumer-api36-r6 | 20/20 passed，包含真实 Process.killProcess 后重开 |
| developer API36 | developer-api36-r6 | 21/21 passed，额外包含实际 PRoot 终端占用/切换/清理 |

API29 首轮 r1 为 17/18，API36 r1 两项失败；API29 r2 为 syscall 崩溃；API36 r2/r3/r4 的失败与诊断均保留于独立输出。不得将这些运行覆盖成通过。

r6 场景包括：Room reopen、跨会话产物保留、迟到产物被 fence 拒绝、身份对文件修改稳定且拒绝重建、请求挂起切换、审批保持旧参数、当前 DENY、可控 SAF Provider、320/360/412dp 默认/大字号布局。密度 400dpi、1080px 宽，确保 412dp 容器不被物理屏幕宽度裁小。

真实进程恢复在 fence + 原目录隔离后终止应用进程，下一次 instrumentation 校验 PID 改变、文件仍在、状态不自动前进，再显式清理。developer 终端反例使用实际 PTY，切换会话绑定后仍写原目录；删除会话后 Runtime 占用阻止清理，stop + settle 后清理成功，另一个目录保留。

Job 补充检查最终 `developer-job-api29-r3` 与 `developer-job-api36-r3` 各 3/3 passed：`WorkspaceJobDeviceTest` 通过真实 Dispatcher/PRoot/结果导入链路验证输入快照、运行中切换、占用拒绝清理、目录内/外当前 DENY、原目录结果；另两项既有 collection 用例验证原结果重试与工具禁用。没有真实模型调用。r2 同样通过，但完整 gate 后 APK 重打包，因此 r3 重新验证与 r6 相同制品。

最终共 88/88 passed，汇总为 `build/hxa210/device-verification-final.json`，四组合 r6 与 Job r3 均使用以下各 flavor 固定制品；六次 runner 均已关闭自己的模拟器进程。源码清单 SHA-256：`6d18aa523de6a1f8064a0d7791749d8388bc29a94a36aa1acf873c420a553552`。

| 制品 | SHA-256 |
| --- | --- |
| consumer app | `09dc59db8fb0d5433c9e6092dd66a3d0de8986728f1e672ded47ebd00bc93f4a` |
| consumer test | `eb7c6b61d4d2e36393e51870f07439623536e33cd4cb0819efc60bbd2a791b79` |
| developer app | `5032d46177575e0319f39afac6353792771c172ffd548974ddfbc8e3649d2a2d` |
| developer test | `8209eb32c3a7f835bb46c074ca60a30924fb4a29f622b8c5b96a0972c0dba847` |

## 保留边界

可控 DocumentsProvider 不代表所有系统/OEM picker；此轮不声明真实账号、全部设备、所有进程中断切点或完整 trajectory baseline 通过。实际 Agent Job 输入/output/当前权限反例另行记录。HXA-210 的最终逐项验收见收口审查；本段早期记录不替代上方扩展矩阵。

## 追加验收过程（最终结果见上表）

最新 API29 `consumer-workspace-final-api29-r3` 与 `developer-workspace-final-api29-r3` 分别完成 22+18+6=46、26+18+6=50 项，runner exit 0；API36 最终使用上述 r3/r4 记录。新请求确实加载新目录 AGENTS.md，旧挂起请求保持原指令。此前 API29 请求超时诊断为尚无 Turn 的 Enqueued 输入：fixture 在异步 `setMode(ACT)` 后立即发送，配置变化保护可能先于执行生效。改为等待 durable SessionRunControl 和 UI 均达到 ACT 后发送，原审批、路径与当前 DENY 断言保持。

consumer API36 扩展 r2 的主套件 22/22、宽度 18/18 通过，随后只读 instrumentation 枚举遇到 ADB offline，整轮仍记失败。follow-up 现在只等待同一个 owned emulator 的 transport；不重启模拟器或自动重放 instrumentation。旧失败输出全部保留。

- 清理确认：`consumer-cleanup-api36-r1`、`developer-cleanup-api36-r1` 各 18 个实际窗口宽度/字号/语言模式组合通过。截图与 `cleanup-widths.json` 保存在对应输出；已查看 320dp / 2倍字号中英文截图，正文与确认/取消按钮均可见。取消不调用清理、改变浏览目录不改变确认目标、失败显示失败状态。
- 系统 SAF：真实 DocumentsUI + ExternalStorageProvider 替换假 activity result/checker。发现 UI 未取得持久 URI 授权，以及 live probe 直接查询 tree URI 两个实际缺陷；picker 现在保留 OS flags，仅持久化已授予读写位，probe 查询由 tree 构造的根 document URI。原位读写、释放持久授权后的写拒绝、重授后恢复、API30+ Downloads 根拒选均由设备检查。API29 的 `home:` 文档别名不被硬编码误判；不据此推广不同 URI 全局去重。
- 旧产物 UI：改变绑定后同名新文件不替代旧产物，原文件变化仍触发 hash 提示；API29 consumer、API36 developer 通过。
- 清理中断：新增 before-rename、purging、purged 三种精确的测试库持久状态，随后真实终止进程、重开、显式恢复与重复调用；不将 seeded state 说成真实生产调用恰好在该 CAS 行被杀。
- 备份中断：新增生产备份协议的 delete 前、delete 后、restore create 后实际 Process.killProcess；保留 PREPARED/RESTORING 证据，禁止未结算 purge，不自动覆盖恢复冲突。最终四组合均通过。
- 诊断保留：consumer API36 扩展 r1 在预期 PID 标记前异常退出，未得到足够根因证据，记为失败而非预期恢复。runner 已增加命令失败 logcat 捕获；之后的重复验证单独记录，不能覆盖该轮。

新增完整 host gate `build/hxa210/workspace-final-host-gate.log` exit 0。中间 lint 要求使用 `String.toUri()`，已修正并重新执行全部门禁；未新增 lint baseline 或跳过检查。

## 可复现入口

每组调用 `scripts/run-owned-emulator.py`，参数为 `--avd Helix_HXA210_API{29|36} --density-dpi 400 --port <独占偶数端口>`，应用路径 `app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk`，测试路径 `app/build/outputs/apk/androidTest/<flavor>/debug/app-<flavor>-debug-androidTest.apk`。consumer runner 为 `com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`，developer runner 为 `com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`。

四组合使用 `--recovery-setup-class com.helix.app.chat.WorkspaceProcessRecoveryDeviceTest --timeout 900`；`--classes` 为以下逗号连接的完整类名：

- `com.helix.app.chat.WorkspaceBindingDeviceTest`
- `com.helix.app.chat.WorkspaceRequestBindingDeviceTest`
- `com.helix.app.files.ManualSafFileDeviceTest`
- `com.helix.app.ui.WorkspaceLayoutDeviceTest`
- `com.helix.app.chat.WorkspaceProcessRecoveryDeviceTest`
- developer 额外 `com.helix.app.proot.ProotMultiSessionDeviceTest#workspaceSwitchAndCleanupRespectRunningTerminal`

Job 专项不带 recovery setup，`--timeout 600`，classes 为 `com.helix.app.proot.WorkspaceJobDeviceTest` 和 `com.helix.app.proot.DetachedJobCollectionDeviceTest` 的 `realOutputImportHonorsNewDenialAndRetriesTheOriginalTarget`、`disabledOriginalToolCannotApplyDeferredOutput` 两个方法，各以 `类名#方法` 指定。每组 `--output` 必须为未存在的独立目录。

输出保存 `instrumentation.txt`、`recovery-setup.txt`（适用时）、`device-properties.json`、`artifacts.json` 与 `closed.json`；失败轮次另存 logcat/UI hierarchy。汇总入口为 `scripts/debug/2026-09-27/summarize-hxa210-devices.py`，要求每轮成功退出并验证相同 flavor 的最终 APK 身份一致。
