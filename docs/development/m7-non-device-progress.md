# M7 非设备进展

更新时间：2026-09-04

本文只记录 `codex/m7-mcp` 并行 worktree 的当前事实，不是 HXA 完成记录。按当前授权，模拟器、真机和其他 Android 外部验收均未执行；这些门禁仍须在正式完成 M7 前补齐。

| HXA | 非设备状态 | 当前边界 |
| --- | --- | --- |
| 070 | 已实现并通过 JVM、standalone R8 Spike | MCP Kotlin SDK client + Ktor OkHttp engine；API 29/36 运行、真实 App 体积/后台行为待验收 |
| 071 | 已实现并通过 JVM fixture | disabled-by-default 配置、Secret alias、握手、规范 endpoint/residence、有界 capability/tool/resource/prompt snapshot |
| 072 | 已实现并通过 JVM/App 单测 | `mcp.<server>.<tool>` 动态注册、schema/contract hash、结果边界、egress/checkpoint；设备链路待验收 |
| 073 | 未启动 | 任务书明确依赖 HXA-084 PRoot runner；不能用主 App shell 或占位 stdio 绕过 |
| 074 | 已实现并通过 JVM fixture | Agent Skills frontmatter validator/catalog；production 不依赖 `skills-ref` |
| 075 | 已实现并通过 JVM fixture | 目录/zip 导入、traversal/symlink/zip bomb 防护、不可变内容快照与更新预览 |
| 076 | 已实现并通过 JVM/App 单测 | Skill list/read/read_resource/enable/disable/remove 与五个内置 Skill；脚本不获得新执行器 |
| 077 | 已完成可执行 Spike 与 ADR 选型 | 官方 SDK 1.3.1.Final 仅作隔离证据；production 选择最小 A2A v1.0 Client；API 29/36 和真实 APK/SBOM 待验收 |
| 078 | 已实现并通过 JVM/App 单测 | disabled-by-default Agent、Secret alias、Card/extended Card、接口与版本协商、有界 Skill snapshot、逐 Skill 启用与 hash 失效 |
| 079 | 已实现并通过 JVM/App 单测 | 独立 A2A Tool origin、固定 schema、Dispatcher/Policy/Approval/Audit 接线、Send/stream/Get/Cancel/Subscribe、持久 Task 对账、SSE `Last-Event-ID`、不明确送达 `NEEDS_REVIEW`、不可信结果与 Workspace Artifact 副本 |

HXA-079 的恢复不变量是：一个本地 `toolCallId` 最多绑定一个远端 Task；已有 Task 只允许 `GetTask`、`SubscribeToTask` 或 `CancelTask` 对账，绝不以新 Message 重发。远端结构化 `toolCall` 只保留为 `UNTRUSTED_A2A_CONTENT` 数据，不能直接调用本机 Tool，也不能继承本机 Capability、Approval、Secret 或 verifier 权限。

当前非设备验证命令以 [verification-matrix.md](verification-matrix.md) 的 M7 表为准。HXA-073、API 29/36、模拟器、真机、真实 APK 增量和发布 SBOM/notice 是明确保留项。
