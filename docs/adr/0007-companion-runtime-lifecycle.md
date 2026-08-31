# ADR-0007: Companion Runtime 按需绑定与可中断恢复

Status: accepted
Date: 2026-08-31
HXA: HXA-083, HXA-084, HXA-085, HXA-086, HXA-103, HXA-110, HXA-111, HXA-112, HXA-113
Deciders: Project owner（要求主应用可直接调用独立 Runtime，并在后台限制或进程回收后保持可解释、可恢复）
Supersedes: none
Superseded by: none

## Context

PRoot 和 CLI 必须使用独立 applicationId/UID 才能隔离 RootFS、Job、网络权限和官方 CLI 凭据。独立 APK 不等于用户必须先打开第二个应用，也不等于 Runtime 进程必须常驻。Android 的 bound service 可以通过显式 Intent 和 `BIND_AUTO_CREATE` 在需要时创建目标 Service 进程；最后一个客户端解绑后，纯 bound service 可以被系统销毁。

只规定独立 UID、signature permission 和 Binder/PFD 数据流仍不足以指导实现：小模型可能要求用户先打开 Runtime、把“进程存活”当作可用条件、依赖普通后台 Service 长期执行、在 Binder 断连后自动重放命令，或用不匹配的 `dataSync` 前台服务类型为任意 Shell/CLI 计算保活。这些做法分别破坏产品体验、Android 生命周期约束或副作用恢复边界。

## Decision

PRoot/CLI Runtime 采用以下共同生命周期契约：

1. Runtime 是按需 companion，不是第二个主应用。用户必须独立安装经过签名和版本验证的 Runtime APK，但正常 Job 不要求手动打开 Runtime Activity，也不要求其进程预先存活。PRoot 不需要日常 launcher UI，但必须提供 Helix 可在用户点击后打开的最小设置/修复入口，以处理平台或 OEM 要求显式恢复已停止 package 的情况；CLI 还为首次登录、重新认证和退出提供有界 UI。
2. 切换 `ADVANCED`、应用启动或被动刷新 Tool Registry 不安装、不启动也不绑定 Runtime。只有两类动作允许主应用的进程级 `RuntimeSupervisor` 以 explicit `ComponentName`、signature permission 和 `BIND_AUTO_CREATE` 绑定目标 Service：用户明确点击安装后的“验证/修复/登录”（只做握手，不提交 Job/正文），或用户触发的 ToolCall 已通过 Registry、Policy、Approval 且输入快照已固定。首次验证得到的有界 execution target descriptor 可以持久化；进程是否存活不能作为 descriptor 或 Tool 可用性的条件，每次真实 Job 仍重新握手。
3. 绑定成功后必须先校验目标 package、启用状态、签名、`protocolVersion/runtimeVersion/ABI` 和能力，再提交 Job。未安装、被禁用、被强制停止、签名不符或版本不兼容均返回稳定“Runtime 不可用”状态，不得回退到主 App UID、QuickJS 或其他权限更大的执行器。
4. Runtime 进程空闲时不保持常驻：没有活动 Job、结果传输或登录交互后主动解绑并允许系统回收。进程是否存在不得进入 Tool 可用性判断；下一次调用重新绑定并冷启动。
5. 每个提交使用稳定 `executionId/jobId`。Runtime 在自己的私有目录原子维护有界 Job journal、输入 manifest hash、状态、deadline、结果 manifest/hash 和 terminal commit；主 App 的 Room/Agent 状态仍是用户流程与审批的权威事实。首版 journal metadata 上限为 128 条且总计 1 MiB：active 与未对账 terminal record 不因配额静默删除，配额不足时拒绝新 Job；主 App 确认结果/处置后立即删除 input/output payload，最小 tombstone 最多保留 7 天；未对账 terminal record 最多保留 30 天，之后只保留 evidence-expired marker，主 App 必须停泊 `INTERRUPTED`，仍不得重放。Runtime journal 只用于跨进程对账，不能创建审批、扩大 scope 或自行发起下一任务。
6. 主 App 监听 `ServiceConnection`、Binder death 和 `DeadObjectException`。断连后先按 `jobId` 重连并查询，不重新提交原命令。只有 Runtime 返回与原输入 hash 匹配、完整且可验证的 terminal record 时才能恢复对应结果；无法证明结果时将现有 `ExecutionState` 停泊为 `INTERRUPTED`，需要用户处理，绝不自动重放非幂等 Job。
7. 短任务使用 bound service，并受调用方生命周期、deadline 和取消约束。用户明确启动且确需在 Helix 退到后台后继续的任务，只有在存在与真实工作匹配的 Android foreground service type 时，才由 Runtime 自己进入 started + bound foreground service，显示所属 Runtime、任务摘要、耗时和停止动作。前台服务必须在 Helix 仍可见、用户动作仍新鲜时启动；等待审批、登录选择或人工输入时停止。
8. `dataSync` 只用于真实的数据传输或本地文件处理，不能覆盖任意 Shell/CLI 计算。找不到合法前台服务类型的任务必须保持前台有界执行，并在应用退后台时暂停或取消；不得用 `shortService`、`dataSync` 或循环重启规避平台限制。
9. 前台服务降低被回收概率，但不提供“不会被杀”的保证。若经 HXA-084/CLI Spike 证明屏幕关闭时执行必须保持 CPU 唤醒，Runtime 只能在已运行的用户可见前台服务中持有带硬超时的 `PARTIAL_WAKE_LOCK`，且 deadline 不晚于 Job deadline；完成、失败、取消和 `onTimeout()` 均在 `finally` 路径释放。Binder death 后若没有满足第 7 条的有效 FGS 继续路径，也必须终止 Job 并释放；不得持有无限 wake lock。
10. 用户强制停止/禁用 Runtime、设备重启或 OEM 后台限制后，不自动恢复或重放 Job。Helix 显示可操作的中断/不可用状态；恢复安装或启用后仍由用户明确重试。CLI 凭据可以继续存在于 CLI UID 私有存储，但主 App 不读取或复制。

## Alternatives considered

1. **要求用户先打开并保持 Runtime 应用在前台**：实现简单，但把内部进程生命周期暴露成用户步骤，且仍不能保证进程不被系统回收。未选择。
2. **把 PRoot/CLI 放回主 App 进程或 UID**：调用最直接，但会让生成代码、RootFS 或 CLI 凭据接触主 App 权限和数据，破坏既定隔离边界。明确拒绝。
3. **让 Runtime 永久 started service 常驻**：减少冷启动，但耗电、易受后台限制，且诱使实现依赖进程存活。未选择。
4. **所有任务统一使用 `dataSync` 前台服务**：表面上便于后台执行，但 service type 与任意计算不匹配，并受平台时限和启动规则约束。未选择。
5. **Binder 断开后以同参数自动重试**：对只读计算可能方便，但无法区分“未执行、执行中、已产生副作用但结果未送达”。未选择；统一采用 query/reconcile，未知结果停泊。

## Consequences

- 用户只需打开 Helix；安装完成且状态正常的 Runtime 由主 App 按需冷启动。CLI 首次登录是唯一常见的 companion UI 例外。
- Runtime 冷启动、重连和进程死亡成为正常协议路径，需要 Job journal、状态查询和幂等提交保护，不能只实现一次 Binder RPC。
- 空闲 Runtime 不耗用常驻进程；活跃后台任务必须用户可见、可停止、有硬 deadline，并接受 Android/OEM 仍可能终止进程的事实。
- Tool Registry 必须区分“代码已编译”“Runtime 已安装且曾由用户验证”“用户已启用能力”“本次已批准”，不能用一个 Advanced 布尔值替代，也不能为刷新 Registry 被动启动 Runtime。
- 本 ADR 只接受生命周期和恢复契约，不表示 PRoot/CLI、前台服务或 wake lock 已实现。

## Verification

当前依据：

- 项目所有者明确要求独立 Runtime 不增加“先手动打开应用”的使用步骤，同时要求覆盖电池优化、后台限制和进程回收。
- Android 官方 bound service 文档规定 `BIND_AUTO_CREATE` 可在 Service 未存活时创建它，且最后一个客户端解绑后纯 bound service 可销毁。
- Android 官方说明前台服务仍受启动条件和类型限制；Android 15 对 `dataSync` 等类型设置后台累计时限。

后续 HXA 必须证明：

- Runtime 未运行且从未手动打开时，用户点击“验证 Runtime”或已批准 Job 可通过显式绑定冷启动并完成握手；应用启动、切换 Advanced 和被动 Registry 刷新不会启动进程。
- 未安装、禁用、强制停止、签名/协议/ABI 不匹配均 fail closed，且不会落入主 App shell。
- 空闲解绑后 Runtime 可被系统回收；下一 Job 冷启动成功。
- 在提交前、RUNNING、terminal commit 前后分别杀主 App/Runtime，重连查询结果正确；未知结果进入 `INTERRUPTED`，同一 Job 不被重复执行。
- journal 达到 128 条或 1 MiB metadata 时拒绝新 Job而不删除 active/未对账记录；已对账 payload/tombstone 和 30 天未对账过期按第 5 条清理，过期证据只会停泊、不会触发重放。
- 后台、锁屏、Doze、低内存和 OEM 限制下，前台服务/通知/停止/timeout 行为符合声明；没有合法 FGS 类型时任务按契约暂停或取消。
- 若使用 wake lock，所有成功、失败、取消、超时和崩溃可观测路径均释放，持有时间不超过 Job deadline。

## Reconsider when

- Android 引入适合本地长计算的稳定调度/隔离 API，能够替代 started + bound Runtime Service。
- PRoot/CLI Spike 证明目标执行形态无法提供可查询、可持久化的 Job 生命周期。
- 真机数据表明冷启动成本无法接受，且已有不削弱隔离与恢复语义的受控进程池方案。
- 分发渠道或 Android 平台禁止当前跨 APK Service、前台服务类型或 companion 安装方式。

## References

- [总体架构](../02-architecture-design.md)
- [本地代码执行方案](../03-local-code-execution.md)
- [安全、测试与发布门禁](../07-security-testing-release.md)
- [ADR-0005：两级安全配置](0005-standard-advanced-safety-profiles.md)
- [ADR-0006：单一直接分发主应用](0006-single-direct-main-package.md)
- [Android bound services](https://developer.android.com/develop/background-work/services/bound-services)
- [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Android foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
- [Android wake lock best practices](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/best-practices)
