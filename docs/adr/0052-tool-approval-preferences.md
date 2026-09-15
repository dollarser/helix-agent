# ADR-0052: 工具审批允许、询问、禁止偏好

Status: accepted
Date: 2026-09-14
HXA: HXA-200, HXA-201
Deciders: Project owner（明确要求支持允许/询问/禁止；本决定保留 ADR-0012 的精确高风险批准边界）
Supersedes: none
Superseded by: none

## Context

当前 StorageApprovalBroker 持久记录单次批准/拒绝并消费精确证明；PolicyEngine 与能力授权已有独立语义。单次 APPROVED/DENIED 不等于工具的长期偏好，缺少统一的用户设置面。

所有者要求三态审批与更完整产品体验。本决定增加偏好层，部分扩展 ADR-0012 的设置与询问语义，不放开它禁止的未来任意 L2/L3 自动批准。

## Decision

1. 提供 ALLOW（允许）、ASK（询问）、DENY（禁止）用户偏好。偏好与单次 ApprovalDecision 分开存储，只有用户应用服务可修改；模型、Skill、MCP/A2A 不能写偏好。既有工具未配置（UNSET）沿用原 Policy、不强制 ASK；明确 ASK 才新增本次询问限制；“新增工具默认 ASK”依赖可信的工具登记/升级基线判定“新增”（不把空记录当新工具、不依据模型自身声明判断），当前无此基线时空记录按 UNSET 解析。升级已有配置保留原行为，不批量创建永久允许。
2. ALLOW 表示在已授权范围和现行策略允许时免询问：L0 或满足既有低风险规则的 L1 可自动解决；L2/L3 仍需精确本次/有限批次证明，否则询问。UI 显示“允许范围内自动执行；高风险操作仍需确认”，不伪称永不询问。不支持“所有未来工具全允许”。
3. ASK 对原本可执行的调用要求本次确认，包括用户明确设 ASK 的低风险工具；不能让低风险自动路径吞掉用户询问偏好。已明确批准的当前调用/精确批次仍可复用，不二次询问；它不授权改变参数后的调用。
4. DENY 在模型工具曝光和执行边界都生效；已排队但尚未开始的调用也拒绝并持久结算。改成禁止不自动杀已执行进程，停止走独立的精确取消入口。系统权限和模式限制优先，偏好不能使不可用工具可执行。
5. 首版支持工具级基础偏好和选定会话/Workspace 的更窄限制。按可信工具来源/提供方稳定身份+工具名识别，不能只用可碰撞的展示名。命中的 DENY 优先于 ASK，ASK 优先于 ALLOW；窄范围不能覆盖外层禁止。恢复默认表示移除记录，不是第四种审批状态。
6. ALLOW 须绑定有效工具版本/契约指纹、执行目标和用户选择范围；契约/来源变化使允许失效并回退询问。保留 DENY/ASK 的限制，不把旧记录或未知 enum 迁移为 ALLOW。已有合法规则只在原范围复用，不能仅有偏好就创建 Capability、scope 或高风险 proof。
7. 模型请求前过滤，执行开始前重新解析偏好、能力和版本，避免审批等待/入队期间修改设置仍使用旧允许。偏好修改与执行开始定义线性化点和 revision；开始后变更只影响后续操作。取消、拒绝和撤销不能返回成功或丢失结算。
8. 审计保存生效偏好、来源、规则/修订、实际决定与原因；显示“设置允许，但当前需审批/能力不可用”的真实状态。持久记录使用现有 Room repository，不由 UI 直接访问 DAO。

手动终端仍按 ADR-0051 的人工输入契约，不把逐工具三态套到每个按键；外部工具内部的后续本地效应仍走工具管线。consumer/developer 均提供适用工具设置，consumer 不因此包含排除的 Runtime。

**2026-09-14 澄清（HXA-200 实现时补记，对第 1 点的精确修订）**：原第 1 点“新工具默认 ASK”易被读成“凡未配置即询问”，据此细化为——**既有工具 UNSET 沿用原 Policy**（范围内低风险免询问、L2/L3 仍按策略询问/拒绝），**明确 ASK 才新增询问限制**，**契约/来源变化使 ALLOW 失效回退 ASK**（第 6 点，且回退来源须与“未配置”可区分，不能塌缩成同一个 ASK）。“新增工具默认 ASK”需要一个**可信的工具登记/升级基线**判定“新增”：不能把所有空记录当新工具，也不能依据模型自身声明判断；当前尚无该基线，故空记录解析为 UNSET（沿用原 Policy）。本澄清只细化第 1 点，不改变第 2–8 点。

**2026-09-15 基线机制（HXA-200 Gap 2 实现补记，落实第 1 点“可信登记/升级基线”；不改变第 2–8 点，仅为第 1 点补上此前“尚无”的机制）**：新增两张加性 Room 表，迁移 17→18，升级后均为空（不 seed 任何 ALLOW、不 seed 任何“新工具”标记，未配置用户保留原行为）：
- `tool_registration_baseline`（每工具一行，主键 `sourceRef + toolName`，即与偏好同一可信身份）存 `firstSeenVersionCode`：该工具**首次被可信登记**时的应用 versionCode。
- `tool_baseline_meta`（单行）存 `foundingVersionCode`：本设备上基线**首次建立**（首次安装/首次登记）时的 versionCode。
**可信登记**只由应用自身在内置工具登记/升级时驱动（`ToolApprovalPreferenceService.reconcile`），模型、Skill、MCP/A2A 与 UI 都**不能**写这张基线表——判定“新增”绝不依据模型自身声明，也绝不依据偏好表是否为空。
**“新增”判定（纯函数）**：`NEW_DEFAULT ⟺ firstSeenVersionCode == currentVersionCode && currentVersionCode > foundingVersionCode`，且该工具当前无任何偏好记录（未配置）。据此：首次安装走“建立基线”路径（`foundingVersionCode == currentVersionCode`，所有工具 `firstSeenVersionCode` 也等于它）→ `currentVersionCode > foundingVersionCode` 不成立 → 全部判为**旧工具 → UNSET**（沿用原 Policy）；一次升级到更高 versionCode 后，首次出现的工具 `firstSeenVersionCode == currentVersionCode > foundingVersionCode` → 判为**新增 → 默认 ASK**（`ToolApprovalReason.NEW_DEFAULT`），而既有工具 `firstSeenVersionCode < currentVersionCode` → 仍为旧工具；该“新增”状态在**同版本重启**中保持稳定（`firstSeenVersionCode` 不变），到下一次升级自动“变旧”。用户一旦显式设置 ALLOW/ASK/DENY，其显式选择优先于新增默认；**恢复默认 = 删除偏好行**（第 5 点，非第四态），使工具回到“未配置 → 按基线判新增/UNSET”。`NEW_DEFAULT` 是 [EffectiveToolPreference.Ask] 的一个来源，走与显式 ASK 相同的运行时卡片路径（仍暴露给模型，只在执行前询问），不新增任何能力/范围/高风险批准。

## Alternatives considered

- 只做三按钮 UI：不改变实际执行偏好，会误导用户，未选择。
- 同名工具永久自动批准所有参数：简单但破坏 ADR-0012 的精确高风险边界，未选择。
- 每次都询问：忽略用户明确允许和既有授权，摩擦过大，未选择。

## Consequences

用户可持久控制工具行为；新增存储、优先级、版本失效和竞态测试成本。ASK 是额外用户限制，ALLOW 不是权限来源；产品文案必须说明两者。接受仅授权实现，不代表目前存在三态产品。

## Verification

2026-09-14 读取 PolicyEngine、ApprovalBinding/Proof、StorageApprovalBroker、审批卡、当前状态与 ADR-0012；未运行新增功能测试。

Required before completion：HXA-200/201 的 Policy/Dispatcher/Room 真实集成，三态与 L0～L3/模式/能力/规则组合，重启/迁移/版本变化、排队撤销、精确批次复用、外部来源碰撞、双 flavor UI 和设备验收；命令见开发计划。

## Reconsider when

要求未来任意高风险永久免确认、窄范围覆盖外层禁止、全局 ALLOW、动态插件修改偏好或自动停止已运行工具时，另行明确改变的契约。

## References

- [ADR-0012](0012-capability-first-advanced-grants.md)
- [手动终端](0051-terminal-runtime-enablement.md)
- [产品闭环任务包](../development/product-completion-and-approval-plan.md)
