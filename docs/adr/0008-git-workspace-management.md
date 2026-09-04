# ADR-0008: Git Workspace 持久化与离线执行域

Status: accepted
Date: 2026-08-31
HXA: HXA-088
Deciders: 项目所有者（2026-09-05 明确接受；HXA-088 Spike 证据见 Verification 节，2026-09-04）
Supersedes: none
Superseded by: none

## Context

HXA-081 计划在无网络的 PRoot Runtime 中固定 `git` 二进制，HXA-086 只验证离线 Job 副本里的版本和基本执行。现有 [PRoot Job 数据流](../architecture/local-code-execution.md#66-独立-uid-与-ipc)把主 App Workspace 的有界输入复制到独立 UID，运行后只把经验证的输出快照交回；Runtime 不挂载真实 Workspace。

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

### HXA-088 Spike 结果（2026-09-04，Status 仍为 proposed，待所有者决定）

设备证据（`ProotGitSpikeDeviceTest`，6 例 × 两台：emulator-5554 API 36 / emulator-5556 API 29，均 arm64-v8a 4 KiB 模拟器 = ADR 允许的代表性设备；**本环境无 arm64 真机**，4/16 KiB 真机与最低设备集证据沿用 HXA-086 真机缺口记录）：

- **规模/耗时（真实 084 Job 管线：JobZipWriter 输入 → guest git 操作 `/workspace` 快照 → JobZipWriter 输出）**：
  - 小仓库（64 文件 × 8 KiB ≈ 552 KiB 工作树）：输入归档 31,056 B / 13–46 ms；guest `git init+status` ≈ 251–255 ms；输出归档 44,876 B / 83 文件。guest 侧 `.git`（init 后）17 文件 / 108–212 KiB。
  - 中仓库（512 文件 × 32 KiB ≈ 16 MiB 工作树；guest `init+add+commit+gc`）：输入归档 312,373 B / 13–46 ms（fixture 载荷高压缩比；不可压缩载荷时归档 ≈ 原始 ≈ 16.8 MiB，仍在 084 限额内：单归档 ≤128 MiB / 单文件 ≤64 MiB / ≤4,096 文件）；guest 全链 ≈ 1.3–1.5 s；输出归档 336,608 B / 542 文件；`git gc` 后 `.git` = 28 文件 / 204–364 KiB（pack 化后对象 DB 相对工作树很小——**首版仓库预算应按 pack 后形态表达**）。
- **进程死亡/对账**：job id 对同一终态记录幂等（查询稳定、无重放）；输出是整归档边界（SUCCEEDED 必有完整可解包归档，不存在半解包树）。配合 086 生命周期（宿主 kill 期间 → ORPHANED 稳定终态 + 孤儿清扫），"静默半仓库" 无落点。
- **损坏恢复**：损坏 loose tree 对象后 `git fsck --full` 报告 `bad sha1 file: .git/objects/62/…`——**从不静默判净**。记录的平台事实：`git fsck` 在报告损坏时**可以退出码 0**——结构化执行器必须解析 fsck 输出文本，不能只看退出码。
- **攻击面证据（快照内携带恶意仓库内容，首版命令集 status/diff/log/init/add/commit）**：
  - 无防护时：`post-commit` hook 在 commit 时**被执行**（写 `/workspace/HOOK-RAN` 成功）；`.gitattributes` 的 `filter=evil` clean 命令在 `add` 时**被执行**（写标记 + **清空文件内容** = 静默篡改通道进入快照）；alias（`status = !sh -c …`）**不展开**（结构化子命令不走 alias 表）；submodule 配置不引发递归/克隆（离线 + 无 submodule 命令）；credential helper 不触发（首版无远程命令）。
  - 有防护时（每次调用 `-c core.hooksPath=/dev/null`）：hook 被抑制（HOOK_INERT），首版命令集全成功；**但 filter 仍被执行**——hooksPath 防护不能中和 attribute filter。
- **guest 平台事实**：Alpine 3.22.5 rootfs 的 `/bin/sh`（BusyBox ash）**不支持 `$(...)` 命令替换**（backtick 可用）——结构化执行器的命令模板必须按此约束书写；git 2.49.1 工作正常。

**三路径比较（Spike 结论，供所有者决定）**：

1. **主 App Workspace 持有权威仓库（每操作一次完整原子事务）**：全部组件已在现有管线内设备验证（归档/限额/对账/损坏/攻击面）；中仓库每操作往返 ≈ 0.3–17 MiB 归档 + 秒级 guest 时间（模拟器）。首版预算内（pack 后 `.git` 小；工作树受 084 归档限额约束，超预算仓库应 fail-closed 拒绝并提示）。**不引入新 IPC/新挂载面/新依赖。**
2. **Runtime 私有目录持有权威仓库**：guest git 操作成本与路径 1 相同（已测量），节省的只是每操作 zip 往返；但需要**新的 bind 面 + 新的跨 uid 仓库事务协议**（084 Job 模型中 guest 只能看到 job 快照——设备实证：私有目录对 guest 不可达），且把仓库放进 companion 的卸载/更新/删除语义（087）里。成本显著高于收益，首版不推荐（保留为后续选项）。
3. **主 App 引入 Android Git 库**（候选调查，**未引入任何依赖**）：
   - **JGit**（Eclipse，纯 Java，EPL-2.0 OR GPL-2.0-with-classpath-exception；Maven Central 最新 7.7.1.202607240634-r）：无 native ABI 负担，但引入一个新的供应链依赖 + 体积 + 与 guest git 的**双实现语义分叉**（同一仓库两种 git 语义）；需要单独的 Android 性能/体积证据。
   - **libgit2**（v1.9.7，2026-08，GPL-2.0-or-later OR MIT 双许可；C 库需 arm64-v8a NDK 构建 + JNI 绑定层）：native ABI/许可证/构建链负担最大。
   - 两条都需要独立供应链评估；且**不解决**路径 1 已设备验证的原子性（仓库仍在主 App 侧）。首版不推荐（保留为后续选项）。

**推荐（供所有者决定，非授权）**：首版采用**路径 1**（主 App Workspace 权威仓库 + 每操作完整原子事务，复用 084/085 管线）+ 结构化 Advanced 命令集 `status/diff/log/init/add/commit`（**`reset --hard`/`clean` 不进入首版**），配套强制策略：
- 每次调用 env：`GIT_CONFIG_GLOBAL=/dev/null`、`GIT_CONFIG_SYSTEM=/dev/null`、`GIT_TERMINAL_PROMPT=0`、`GIT_PAGER=cat` + `-c core.hooksPath=/dev/null`；
- **宿主侧仓库预扫描（拒绝 fail-closed）**：`.git/config`/`.gitattributes` 含 `filter`/`diff`/`merge` 命令配置、或存在指向可执行内容的 hooks 时，拒绝 `add`/`commit`（filter 是 hooksPath 防护无法中和的静默篡改通道——设备实证）；
- 命令模板只用白名单子命令 + `--` 路径守卫；shell 模板避开 `$(...)`（guest ash 约束——设备实证）；
- 仓库完整性以 fsck **输出文本**为准（退出码不可信——设备实证）；
- 超 084 归档限额（128 MiB/4,096 文件/单文件 64 MiB）的仓库 fail-closed 拒绝；
- `clone/fetch/pull/push`/凭据/credential helper/SSH agent 全部不在首版（无 INTERNET 权限结构上排除联网 Git；远程 Git = 未来新的联网执行域 ADR）。
- **Tool schema/风险/审批示例（候选，非授权）**：`code.git.run`（Advanced-only，CODE_EXECUTION 同类，动态风险 L2：`status/diff/log` = L1 只读候选 / `init/add/commit` = L2 写仓库候选；scope = 选定 Workspace 根；审批 = 与 `code.linux.run` 相同的每次/规则/批量语义；审计记录 = 子命令 + 仓库根 + 输入快照 sha + 输出 diff 摘要）。Standard 不暴露任何 Git 配置（Helix 原生历史/diff/回收站不变）。

在 ADR 被接受前，Job-local Git 维持"单次离线 Job 内的 git 工具"定位，**不得描述为持久仓库管理**。

## Reconsider when

- HXA-088 证明某一方案可在预算内原子恢复并通过攻击测试。
- Android 存储/后台限制或 Runtime 更新模型使候选方案不可行。
- 产品明确要求多人协作或 remote Git；此时必须另建联网/凭据 ADR，而不是只修改本记录。
- 选定 Git 库停止维护、许可证或 ABI/体积不满足发布门禁。

## References

- [总体技术方案](../architecture/overview.md)
- [本地代码执行方案](../architecture/local-code-execution.md)
- [路线 HXA-088](../development/roadmap.md)
- [安全测试与发布门禁](../security/testing-and-release.md)
- [ADR-0005：Standard/Advanced 安全配置](0005-standard-advanced-safety-profiles.md)
- [ADR-0007：Companion Runtime 生命周期](0007-companion-runtime-lifecycle.md)
