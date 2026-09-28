# P1 Memory + Workspace 增量设备验收（2026-09-28）

本记录对应 `docs/development/remaining-work-plan-2026-09-28.md` 的 P1。范围只验证已经实现的 Global Memory 与 Workspace fork/recovery 增量，不新增产品能力、不改变 Core Engine / AgentLoop / Dispatcher / permission / effect owner。未使用 GitHub device CI、真实账号或物理设备。

## 源码与制品身份

- production 基点：`263135ddf7cdcb0cb613c53900d0cd6c21a19949`（`feat: freeze post-refactor product baseline`）。P1 未修改 production 源码。
- P1 测试差异 SHA-256：`d1d393359f4edbfa31804bcf75a66758a031080074cda870fb262ea8cc5f87aa`，只包含 `SessionForkDeviceTest` fixture 修正和新增 `MemoryProcessRecoveryDeviceTest`。
- consumer app APK SHA-256：`d4885c750b844455b9101e94d1c488f66b6f99b4b4bd4d33524b20b5a85564aa`。
- consumer AndroidTest APK SHA-256：`58669f82490bb01ac83a1b60e585f28b147c0b5b1a32cb71f7dffcb1ab1ffe38`。
- developer app APK SHA-256：`9e0e9df90ec4af33e9410283e32c70b95bce297b3067d51980b43522a0aee62f`。
- developer AndroidTest APK SHA-256：`84394e95dab3d7fb036caee4f224df132a91625d51acf55f9ccf21d6c725adf3`。

## Targeted 2×2 matrix

最终固定测试集合：

- `MemoryProcessRecoveryDeviceTest`
- `MemoryPromptDeviceTest`
- `MemoryDialogDeviceTest`
- `SessionForkDeviceTest`
- `WorkspaceLayoutDeviceTest`

每轮都通过 `scripts/run-owned-emulator.py` 启动独占 read-only AVD，先以 `MemoryProcessRecoveryDeviceTest` setup instrumentation 写入 durable state 并 `Process.killProcess`，确认旧 PID 后再运行正式测试。最终结果：

| API | Flavor | AVD | 结果 |
| --- | --- | --- | --- |
| 29 | consumer | `Helix_HXA210_API29` | 31/31 passed |
| 29 | developer | `Helix_HXA210_API29` | 31/31 passed |
| 36 | consumer | `Helix_HXA210_API36` | 31/31 passed |
| 36 | developer | `Helix_HXA210_API36` | 31/31 passed |

四个最终输出均有 `closed.json` 且 exit=0；验收结束后 `adb devices` 为空。没有借用既有设备实例。

## Memory 证明范围

- enabled=false 时不注入；允许读取的 session 才能得到 Memory context。
- Prompt 中 `memory.context` 的 trust 为 `UNTRUSTED`；Memory 不获得工具权限或事实权威。
- Markdown 手动保存、重新读取、确认删除正常。
- 外部修改与 UI draft 冲突时 expected-hash 检查拒绝覆盖，外部内容保留，错误对用户可见。
- actual process death：setup 保存 Global `memory_summary.md`、`enabled=true` 和 FULL_ACCESS session permission 后杀死 app process；恢复 instrumentation 验证 PID 已变化、Markdown 与 SharedPreferences 仍在，并重新构建 Prompt 验证内容和 `UNTRUSTED` trust。
- Workspace UI 同轮覆盖 320/360/412dp、中文/英文与 fontScale=2.0。

## Workspace 增量证明范围

- fork 复用来源 Workspace，但权限使用新 Session 默认值；后续双方绑定独立。
- Path/SAF subdirectory 绑定被 fork 快照，来源 Session 后续切换不改写 fork。
- 来源 Workspace 已 unavailable 时 fork 得到新的空私有目录并保留 recovery notice，不改写来源 Session。
- 原 subdirectory 消失时 recovery 只给 future work 建立新 Workspace；原文件保留，旧 model request 继续引用原 workspaceId + relative path。
- fresh-directory 分配失败回滚 Session binding；用户显式重新选择目录可清除 recovery notice。
- 目录选择失败保持 dialog，并允许 private-directory retry。

## 首轮失败与归因

第一次 API36 consumer 组合执行 30 项时出现 1 个失败：`missingSubdirectoryRecoversFutureWorkWithoutMovingSharedFilesOrRequests` 在 `recordRequest("old-request", ...)` 触发 Room `FOREIGN KEY`。fixture 直接构造了不存在的 modelCallId，因此还没有执行到需要验证的 recovery invariant。

修复仅在测试中先创建真实 `Turn` 与 `ModelCall`，再记录 Workspace request binding；AndroidTest 双 flavor 重新编译、Spotless/detekt 通过，之后 API36 consumer 30/30 以及最终四象限 31/31 全部通过。没有因此修改 Workspace production repository 或放宽外键/断言。

## 主机与静态检查

P1 fixture 修正与新增 process-recovery test 后：

- `:app:assembleConsumerDebugAndroidTest`：passed。
- `:app:assembleDeveloperDebugAndroidTest`：passed。
- `spotlessCheck`：passed。
- `detekt`：passed。

最终文档更新后另执行 source/diff gate；其结果记录在本轮提交前检查中。

## 未覆盖

- Android 文档 Provider 的 Memory import/export 实机行为；
- 真实模型 Memory off/on 对任务完成率、token 或污染的 A/B；
- production Project Memory；
- SAF 间歇空来源列表根因（转入 P2 bounded diagnosis）；
- 物理真机/OEM、Doze/热压、16 KiB、真实账号、发行。
