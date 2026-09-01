# ADR-0008: Git Workspace 持久化与离线执行域

Status: proposed
Date: 2026-08-31
HXA: HXA-088
Deciders: pending
Supersedes: none
Superseded by: none

## Context

HXA-081 计划在无网络的 PRoot Runtime 中固定 `git` 二进制，HXA-086 只验证离线 Job 副本里的版本和基本执行。现有 [PRoot Job 数据流](../03-local-code-execution.md#66-独立-uid-与-ipc)把主 App Workspace 的有界输入复制到独立 UID，运行后只把经验证的输出快照交回；Runtime 不挂载真实 Workspace。

持久 Git 仓库不是普通文件批量复制问题。`.git/objects`、index、refs、工作树、锁文件和进行中的操作必须保持一致；部分导入、并发修改或进程死亡可能留下仓库损坏。hooks、alias、filter、external diff/merge、pager、submodule、worktree、仓库 config 和 credential helper 还会引入隐式执行、路径、网络或凭据边界。普通用户也不应为了获得撤销/历史而被迫理解 Git。

因此“Runtime 中有 Git”不能作为“Helix 已内置 Git 管理”的实现证据。仓库权威位置、IPC 粒度、崩溃恢复、大小预算和产品 UI 必须先由 HXA-088 真机 Spike 决定。

## Decision

在本 ADR 被接受前，Helix 不提供持久 Git 仓库管理：

- Standard 继续以 Workspace 原子写、diff、Trash/restore 和审计历史作为面向普通用户的恢复体验，不暴露 Git 配置。
- PRoot 的 `git` 仅可在每次批准的离线 Job 副本中使用；输出不得以零散 `.git` 文件形式覆盖主 Workspace，也不承诺跨 Job 保留 repository state。
- 不实现 `clone/fetch/pull/push`、远程凭据、credential helper、SSH agent 或任意联网 Git；PRoot 继续不声明 `INTERNET`。
- 不让结构化 UI 隐式触发 hooks、alias、filter、external diff/merge、pager、submodule 或 worktree。

HXA-088 必须比较并用设备证据选择以下方案之一，之后由项目所有者接受、修改或拒绝本 ADR：

1. 主 App Workspace 持有权威完整仓库，跨 UID 只进行可原子验证的完整 repository transaction。
2. PRoot Runtime 私有目录持有权威仓库，主 App 通过受限结构化协议读取状态并交换工作树快照。
3. 主 App 使用经审计的 Android Git 库管理仓库，PRoot Git 仅执行明确需要 Linux 的命令。

若接受结构化 Advanced Git，首版候选只包含离线 `status/diff/log/init/add/commit`；破坏性 `reset --hard`/`clean` 默认不进入首版。最终集合、风险级别和审批仍以 HXA-088 后的决定为准，本段不是功能授权。

## Alternatives considered

1. **直接把整个 Workspace（含 `.git`）打包给每个 PRoot Job再全量导回。** 与现有快照架构最接近，但大仓库成本高，主 App 并发修改和中途死亡会产生冲突；只有 Spike 证明可锁定、原子交换和恢复时才可采用。
2. **让 PRoot Runtime 永久持有仓库。** Git 原生语义较完整，但 Runtime 卸载/更新、用户文件可见性、备份和主 App Workspace 所有权更复杂；需要稳定协议和迁移策略。
3. **在主 App 引入 JGit/libgit2 等实现。** 可避免跨 UID 导入 `.git`，但会增加依赖、native ABI/许可证/体积或兼容性成本；必须单独做供应链和 Android 性能证据。
4. **立即开放原始 `bash git ...` 作为完整 Git 产品。** 实现成本低，但会把 hooks/config/凭据/破坏命令和持久化一致性推给用户，且不满足 Standard 的低配置目标，因此不采用。

## Consequences

- 文档和 UI 必须区分“Git binary/smoke”“单次 Job 内离线 Git”和“持久仓库管理”。
- HXA-081/086 可以独立完成，不被 Git 产品设计阻塞；HXA-088 在真实 PRoot snapshot/IPC 可测后再决策。
- 普通用户先获得 Helix 原生的安全恢复体验；高级 Git 能力晚于基础 Workspace/PRoot。
- 远程 Git 保留为不同安全问题：需要有网执行域、凭据所有权、host key/endpoint Policy、数据出境和新的 ADR，不能通过接受本 ADR 自动获得授权。

## Verification

Required before acceptance（HXA-088）：

- 在 API 29/36、arm64 真机或代表性设备上记录含小/中型 `.git` 仓库的 snapshot 大小、传输时间和峰值空间。
- 在 archive、import、index/refs 更新和 terminal commit 前后 kill 主 App/Runtime，证明不会出现静默半仓库或自动重放。
- 覆盖主 App 并发修改、symlink/path traversal、对象膨胀/损坏、恶意 hooks/config/alias/filter/external diff、submodule/worktree 和 credential helper fixture。
- 为候选结构化命令给出 Tool schema、动态风险、scope、Approval 和审计示例；证明 Standard 不需要 Git 配置。
- 对任何候选第三方 Git 库记录版本、来源、许可证、ABI/体积、依赖验证与替代方案。

当前已验证事实仅是文档边界；尚未执行 HXA-088 Spike，也未接受持久 Git 方案。

## Reconsider when

- HXA-088 证明某一方案可在预算内原子恢复并通过攻击测试。
- Android 存储/后台限制或 Runtime 更新模型使候选方案不可行。
- 产品明确要求多人协作或 remote Git；此时必须另建联网/凭据 ADR，而不是只修改本记录。
- 选定 Git 库停止维护、许可证或 ABI/体积不满足发布门禁。

## References

- [总体技术方案](../02-architecture-design.md)
- [本地代码执行方案](../03-local-code-execution.md)
- [路线 HXA-088](../04-roadmap-and-backlog.md)
- [安全测试与发布门禁](../07-security-testing-release.md)
- [ADR-0005：Standard/Advanced 安全配置](0005-standard-advanced-safety-profiles.md)
- [ADR-0007：Companion Runtime 生命周期](0007-companion-runtime-lifecycle.md)
