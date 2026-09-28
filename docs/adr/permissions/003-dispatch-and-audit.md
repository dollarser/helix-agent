# ADR-PERMISSIONS-003: 工具执行准入、精确审批与持久审计

Status: accepted
Date: 2026-09-16
HXA: HXA-020, HXA-028, HXA-033, HXA-066, HXA-068, HXA-200, HXA-201
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

免确认是一种用户授权来源，不是跳过执行准入、取消结算或恢复的理由。

## Decision

- 每个调用经 schema、可信工具身份、Capability、当前会话规则、执行限制和审计。系统权限只满足实际能力，不让模型或外部内容修改用户授权。
- 原生、别名、MCP、A2A 以平台登记的 origin/identity/版本识别，不能凭同名工具共享授权。曝光过滤与执行时复检同时存在；当前调用绑定规范参数、scope、执行目标、会话与版本。
- 需要确认的调用呈现实际参数、效果范围与原因；只消费对应调用的 APPROVED proof，DENIED 仅为拒绝事实。proof 在执行开始的线性化点一次性消费，不在出卡或点击时提前消费。精确批次为每个调用生成独立绑定，不能授予通配写权限。
- 等待卡不持有设置写锁。取消、工具禁用、scope/契约变化及当前操作 DENY 在执行开始前复检；迟到决定不重复执行，旧批准不能覆盖新禁止。
- 自动授权记录具体模式/规则来源，不能伪造用户点击。每个排队槽位均持久结算成功、失败、开始前取消/拒绝或未知副作用；一个调用的异常不能替代同批兄弟调用的结果。
- 进程死亡恢复历史、审计与实际结果。已失去 live wait 的卡不伪装成可继续执行；用户恢复必须经当前准入，不自动重放未知副作用。审批记录、执行 audit 与用户可见恢复摘要需可关联。
- UI 经应用服务读写设置，Room 写入在合适 dispatcher 串行执行；全局/Workspace/会话来源清楚显示。工具只有 ENABLED/DISABLED，ASK 只属于操作规则，不建立第二套有效偏好来源。

## Alternatives considered

仅隐藏 schema 不足以阻止历史调用；点击即消费 proof 无法处理停止竞态；给自动放行伪造 APPROVED 会破坏审计。均不采用。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

复用已有 HXA-200/201 的身份、审计与恢复证据；HXA-209 已重新验证新授权矩阵与竞态（见 HXA-209 完成记录），未沿用工具三态断言作为新模式验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history

- 2026-09-28：owner 明确要求正式系统设置授权入口。Advanced Accessibility 权限中心在启动自动化会话前提供默认关闭的“系统设置和快捷设置”选择，只在该 process-local session 内允许 `com.android.settings` / `com.android.systemui` 越过默认 package 拒绝。仍须包含在用户目标 allowlist；停止、到期、服务断开或进程重建不继承授权。没有模型可调用的授权接口，不改变 Dispatcher、审批或 effect 分类。其他敏感包、密码/敏感节点、危险动作语义及锁屏检查仍生效。此选择允许设备设置更改，不声称能逐页识别 Settings 内全部安全设置；OEM 包名不做通配放行。

- 2026-09-28 后续修复：owner 要求解决已授权 Settings 任务的跨目标/暂停失败。正式授权选项列出本机已安装且标记为系统应用的精确 Settings/SystemUI/Settings Intelligence 包（`com.android.settings.intelligence`、`com.google.android.settings.intelligence`），用户勾选并启动时一并纳入 allowlist；不做包名前缀或任意 Intent 跟随授权。权限中心增加暂停确认：用户选择当前 session 已授权且非禁止的目标，确认只绑定当前 pause/session；返回该包后再次检查 live snapshot、敏感节点和锁屏，再消费确认。停止/到期/恢复会清除待恢复确认，不预存下次 checkpoint 授权。snapshot/find/wait 增加 pause/recovery metadata，wait 遇授权阻塞立即返回，不自动批准或盲目重试。

- 2026-09-28 同轮滑块修复：授权/恢复修复后的真实轨迹已到达滑块，但节点不支持 click/set-text/container-scroll。新增 `ui.set_progress(token,value)`，仅接受节点声明支持 `ACTION_SET_PROGRESS`、有限且在原生 range 内的数值；范围/能力纳入 token fingerprint，执行前重新核验。该动作按 EXTERNAL_ACTION 走原有 capability、scope、审批与敏感语义检查，不提供坐标、盲拖或系统 settings 写 API；执行返回成功仅表示平台动作接受，任务效果仍须独立观察。

- 2026-09-28：owner 授权按操作类型收敛权限。移除注册、Policy、审批与新审计中的 baseRisk/dynamicRisk/L0–L3；展示 READ_ONLY、METADATA、LOCAL_MUTATION、NETWORK、EXTERNAL_ACTION、CODE_EXECUTION、PRIVILEGED 对应操作及实际范围/询问原因。等级不再是权限来源或默认拒绝依据。已接入会话的调用按可信 effect footprint 与当前 ALLOW/ASK/DENY 解析；未接入会话时，非只读/闭合元数据操作要求精确批准。凭据、SSRF、Capability、模式、未知副作用与执行前复检不变。旧审计缺少 operationClass 时保留其他事实，不从旧等级推断新类别；工具契约 hash 变化使旧批准失效。
