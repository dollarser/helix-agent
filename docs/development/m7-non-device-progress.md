# M7 合并与验证进展

更新时间：2026-09-05

本文记录原 `codex/m7-mcp` 并行 worktree 合入 `main` 后的当前事实，不是 HXA 完成记录。JVM/构建实现已合入；2026-09-05 已在本任务独占的 API 29/36 arm64-v8a 模拟器完成合并后 App 双 flavor 全量回归。该回归不替代各 HXA 的专项 Android Spike、真实服务、APK 增量、SBOM/notice 或真机门禁。

| HXA | 非设备状态 | 当前边界 |
| --- | --- | --- |
| 070 | **完成**；JVM、standalone R8、API 29/36 专项 fixture 各 12/12、双 flavor unsigned release/R8/lintVital 全部通过；ADR-0017 accepted | MCP Kotlin SDK client + Ktor OkHttp engine；Android 已覆盖 initialize/ping、真实版本 header、缺失版本 fail-closed、取消后复用、关闭后全新 facade 重连、SSE 断线 + `Last-Event-ID` 重连、1 MiB 响应、HTTP 401、TLS handshake 中断、bearer + 精确 loopback/DNS pinning、JSON/SSE 逐消息 16 MiB ceiling，以及 Activity 前后台切换期间 session 可用性；直接 MCP runtime/集成层增量约 3.89 MiB、21.0k method references，runtime 依赖许可证闭包已核对；签名/SBOM/notice 属于 M12 发布门禁 |
| 071 | **完成**；JVM fixture、API 29/36 consumer 全量验收 | disabled-by-default 配置、Secret alias、握手、规范 endpoint/residence、有界 capability/tool/resource/prompt snapshot |
| 072 | **完成**；JVM fixture、API 29/36 consumer 全量验收 | `mcp.<server>.<tool>` 动态注册、schema/contract hash、结果边界、egress/checkpoint |
| 073 | **完成**；HXA-084 真实 PRoot、JVM 与 API 29/36 专项验收 | 锁定 argv/指纹、严格 stdout JSON-RPC、独立 bounded stderr、环境 allowlist、进程组取消与 Job 对账；没有主 App shell 或占位 stdio 回退 |
| 074 | **完成**；29 个 JVM fixture（含固定官方 fixture 3 个） | Agent Skills Unicode/NFKC frontmatter validator；catalog 仅 name/description/source/hash；production 不依赖 `skills-ref` |
| 075 | 已实现并通过 JVM fixture | 目录/zip 导入、traversal/symlink/zip bomb 防护、不可变内容快照与更新预览 |
| 076 | 已实现并通过 JVM/App 单测 | Skill list/read/read_resource/enable/disable/remove 与五个内置 Skill；脚本不获得新执行器 |
| 077 | 已完成可执行 Spike 与 ADR 选型 | 官方 SDK 1.3.1.Final 仅作隔离证据；production 选择最小 A2A v1.0 Client；API 29/36 和真实 APK/SBOM 待验收 |
| 078 | 已实现并通过 JVM/App 单测 | disabled-by-default Agent、Secret alias、Card/extended Card、接口与版本协商、有界 Skill snapshot、逐 Skill 启用与 hash 失效 |
| 079 | 已实现并通过 JVM/App 单测 | 独立 A2A Tool origin、固定 schema、Dispatcher/Policy/Approval/Audit 接线、Send/stream/Get/Cancel/Subscribe、持久 Task 对账、SSE `Last-Event-ID`、不明确送达 `NEEDS_REVIEW`、不可信结果与 Workspace Artifact 副本 |

HXA-079 的恢复不变量是：一个本地 `toolCallId` 最多绑定一个远端 Task；已有 Task 只允许 `GetTask`、`SubscribeToTask` 或 `CancelTask` 对账，绝不以新 Message 重发。远端结构化 `toolCall` 只保留为 `UNTRUSTED_A2A_CONTENT` 数据，不能直接调用本机 Tool，也不能继承本机 Capability、Approval、Secret 或 verifier 权限。

合并后 App 回归命令为 `:app:connectedConsumerDebugAndroidTest` 与 `:app:connectedDeveloperDebugAndroidTest`：API 29 分别完成 117/136 项、API 36 分别完成 116/135 项，均 0 失败；skip 均为 SDK/外部服务条件门控。验证中修复了测试 Provider authority 的 flavor 冲突、turn 启动/终态竞态、整屏刷新覆盖瞬时 UI 状态，以及三处异步 UI 测试等待/清理问题。

当前专项验证命令仍以 [verification-matrix.md](verification-matrix.md) 的 M7 表为准。HXA-076/077/078/079 的功能级设备链路，以及 M12 的真机/签名/SBOM/notice 仍是明确保留项；不能用上述 App 全量回归代替。
