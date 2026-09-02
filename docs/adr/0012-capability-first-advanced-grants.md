# ADR-0012: 能力优先的 Advanced 与持久授权边界

Status: accepted
Date: 2026-09-02
HXA: HXA-020, HXA-028, HXA-033, HXA-066, HXA-068, future capability research
Deciders: Project owner（明确授权能力优先、长期授权与用户责任方向；仓库不可变安全内核继续约束可接受边界）
Supersedes: [ADR-0005](0005-standard-advanced-safety-profiles.md)
Superseded by: none

## Context

[ADR-0005](0005-standard-advanced-safety-profiles.md)建立了 `STANDARD`/`ADVANCED` 两级配置、consumer/developer 正交边界、精确出网规则和不可绕过的工具安全管线。项目随后把首要用户调整为开发者与 Android 自动化高级用户。项目所有者进一步要求评估长期工具授权、Trusted Workspace、自动批准、Full Access、模型自授权，以及未来兼容 Tasker/Auto.js、Shizuku/ADB 的 Android 可行性。

Android 可以持久保存 SAF tree grant、应用内 scope/rule，也可由用户长期启用 All-files、Accessibility 或 Root 能力；这些系统/应用状态使“减少重复确认”可实现。Tasker 有标准 Android 插件协议；Auto.js 类脚本依赖特定 JavaScript 引擎、Android API、Accessibility、屏幕捕获、Root 和第三方模块，只能通过独立兼容 Runtime 分阶段覆盖。Shizuku 可让普通应用经用户启动的高权限服务调用系统 API；Android 11+ 无线调试提供配对码/二维码流程。它们证明候选能力在 Android 上有实现路径，不证明任意设备、OEM 或任意脚本都能兼容。

同时，真实手机上的模型输入、网页、MCP、Skill 和脚本都可能是不可信内容。把模型声明、Advanced 开关或一个全局按钮当成未来任意 L2/L3 ToolCall 的批准，会失去调用参数、目标和副作用的用户授权来源，并违反仓库既定的不可变安全内核。因此本决定扩大可持久授权的范围表达，但不接受模型自授权或全局审批绕过。

## Decision

Helix 保留两个运行时配置：

1. `STANDARD` 是所有安装的默认配置，也是按 [ADR-0013](0013-standard-store-capability-preserving-distribution.md)面向 Google Play 与国内 Android 应用商店的完整产品形态。它保留完整核心任务能力；高敏出网、系统权限与专家执行域按需启用或确认，但不能仅以“安全默认”为由删除与商店政策兼容的能力。
2. `ADVANCED` 只在 developer 变体中由用户显式开启。它以任务完成能力为优先，允许用户启用 All-files、Accessibility、离线 PRoot、Root、精确 LAN origin 和下述持久授权。用户负责选择 Provider、启用能力、配置 scope、备份数据并承担已明确授权操作的后果。

Advanced 支持以下长期或集中授权形式：

- **Capability grant**：Android 系统授予并由 Helix 实时验证的 All-files、Accessibility、Root，以及未来可能接入的 Shizuku/ADB 能力状态。系统授权只证明能力可用，不等于某次 ToolCall 已批准。
- **Trusted Workspace**：用户选择一个或多个规范化文件根，并可为固定工具/版本、操作类别和固定目标配置持久规则。首版只允许自动执行 L0 和风险经动态计算仍不高于 L1 的操作；覆盖、删除、代码/命令执行、跨 scope 外发或动态风险达到 L2/L3 时不匹配该规则。规则必须可查看、暂停和撤销。
- **有界长期工具规则**：只允许固定 tool ID/version/contract hash、固定 capability、scope、execution target、origin/数据类别（如适用）和有效期；任何绑定字段、工具契约或动态风险变化都失效。实现任务必须定义期限上限、存储、重启恢复、时钟回拨和撤销语义，未实现前回退逐次确认。
- **精确批量批准**：一次用户操作可以批准界面中已完整披露的有限 ToolCall 列表；列表中的每个调用仍生成独立、一次性、精确绑定的 `APPROVED` proof。批准后追加或变更的调用不在批次内。
- **Full Workspace Access**：产品可以用此名称表示“用户选择的 Workspace 文件 scope 较宽”，但必须同时显示根目录、可用操作和撤销入口。它不是全局 `Full Access`，不授权系统设置、其他 App 私有数据、凭据、支付、认证、Root 命令或未来未知工具。
- **有界高敏出网规则**：延续 ADR-0005 的语义，Advanced 可保存精确绑定 Provider/MCP ID、规范 origin、数据类别、scope 和固定期限的规则；禁止发送的凭据类数据仍拒绝。现有 1h/24h/7d/30d、默认 24h、最大 30d、不可滑动续期和时钟回拨 fail closed 约束保持不变，除非未来 ADR 以实现与测试证据重新决定。

以下请求作为替代方案记录，但不被接受：

- **模型自授权**：模型、MCP、Skill、网页或脚本不能生成、扩大或消费用户批准，也不能切换 Profile、启用 Android 权限或创建 Trusted Workspace。
- **全局自动批准**：不存在覆盖所有工具、未来调用或所有目标的 wildcard 规则。自动执行只能来自 L0、当前动态风险不高于 L1 的已匹配规则，或当前精确批次中的一次性 proof。
- **全局 Full Access**：Advanced、Android 权限、Root/Shizuku/ADB 状态均不能关闭 schema、Policy、Approval、执行限制、隔离、验证、审计、停止和恢复管线。
- **L2/L3 长期放行**：通用 L2/L3 仍按精确 ToolCall 由用户批准；不能永久放行生成代码、Shell/Root 命令、删除覆盖、外部写入、敏感 UI 操作或权限边界变化。

未来能力候选的决定边界：

- Tasker：研究官方插件 action/event/state 和调用命名 Task 的桥接；首阶段不承诺导入 Tasker profile 后保持完整语义。
- Auto.js/AutoJs6：允许导入任意来源脚本并输出版本/权限/API/模块兼容报告；“兼容任意脚本”是长期方向，不作为零差异承诺。执行必须位于独立 Runtime 应用/UID，使用受控 IPC，并完成许可证审计；不得把 Auto.js API 直接暴露给主进程 QuickJS，也不得复制不兼容许可证代码。
- Shizuku/无线 ADB：研究用户显式启动/配对、状态和 Binder death、撤销、OEM 差异及本机 client 供应链。它们仍不在当前 HXA 实现范围，也不是其他 Capability 或 ToolCall 的批准证明。

`consumer/developer` 是当前编译期机制，`STANDARD/ADVANCED` 是运行时 Policy/体验边界，商店/官网是分发渠道；三者不能合并成一个“受限/完整”布尔值。所有主应用首次启动仍为 Standard；进入 Advanced 不自动申请权限、启动 Runtime/companion、请求 Root/Shizuku 或连接 ADB/网络。

## Alternatives considered

1. **完全采用模型自授权和全局 Full Access**：交互最少，但不再能证明真实手机上的高影响动作来自用户授权，也让 prompt injection 可直接变成权限提升。与不可变安全内核冲突，拒绝。
2. **所有工具永远逐次确认**：实现简单且保守，但重复的 L0/L1 操作会造成严重摩擦，无法满足能力优先的开发者工作流。由 Trusted Workspace、有界长期规则和精确批量批准替代。
3. **长期授权所有 L2/L3 工具**：比全局放开更窄，但目标、命令、页面和副作用会随时间变化，持久规则无法保持用户对精确调用的知情。拒绝。
4. **直接宣称兼容任意 Auto.js/Tasker 脚本**：营销表述强，但运行时版本、插件、Java bridge、Root、OEM 和脚本依赖无法统一，无法形成可验收合同。改为“任意来源可导入并诊断、按兼容矩阵逐步覆盖”。
5. **立即把 Shizuku/ADB 纳入当前路线**：Android 上可行，但会新增配对、服务生命周期、native/client 供应链和 OEM 验收面，打断当前 M4～M12 路线。保留为未排期研究候选。

## Consequences

- 收益：Advanced 用户能以 Trusted Workspace、有界规则和精确批量批准减少低风险重复交互，同时保留高影响调用的精确授权。
- 收益：`Full Workspace Access`、Android Capability 和 Tool Approval 被拆成不同状态，UI 与审计可以准确解释“能访问什么”和“获准做什么”。
- 收益：Tasker/Auto.js 与 Shizuku/ADB 获得可实现但不虚假的未来边界。
- 代价：Policy 必须在每次调用时重新计算动态风险；规则存储、迁移、撤销、过期、contract hash 和恢复测试更复杂。
- 代价：不能满足“让模型自行批准一切”的零摩擦诉求；高风险动作仍需要用户参与。
- 约束：本 ADR 是产品与架构决定，不表示 Trusted Workspace 自动规则、批量审批、脚本兼容 Runtime、Shizuku 或 ADB 已实现。
- 约束：已经完成的 HXA 仍按其当时完成记录验收；后续实现不得反向把本决定写成既有代码证据。

## Verification

本决定已核验的可行性证据：

- Android 已支持 SAF 持久 URI grant、All-files、Accessibility 等可撤销能力；仓库已有 scope、Capability、Policy、Approval、contract hash 和审计骨架，但这不等于本 ADR 新增规则 UI 已实现。
- Tasker 官方提供 Android automation plugin developer API；Shizuku 官方说明服务由用户通过 Root 或 ADB 启动；Android 官方文档说明 Android 11+ 支持无线调试配对。
- AutoJs6 的公开说明显示脚本依赖 Accessibility、屏幕捕获、Root 和特定 JavaScript/Android API，因此独立兼容 Runtime 可行，任意脚本零差异不可由当前证据保证。

后续实现验收必须证明：

- Trusted Workspace 规则只能命中固定绑定且动态风险不高于 L1；参数、scope、target、origin、contract hash 或风险变化后重新门控。
- 精确批量批准不能追加调用，每个 proof 最多消费一次；`DENIED`、模型输出、MCP/Skill annotation 和导入脚本都不能生成 proof。
- 规则在重启、到期、时钟回拨、撤销、Profile 切换和存储损坏时 fail closed；用户能一处查看并立即停止。
- Tasker/Auto.js 兼容性以公开版本矩阵和 fixture 套件验收，分别报告解析、API、权限、执行与行为结果，不用“成功导入”替代执行兼容。
- Shizuku/ADB 以 Android 版本/OEM/启动方式/断连/撤销矩阵验收；未运行或失联时相关工具不进入 Registry，且不盲目重放。

## Reconsider when

- Android 提供可验证的、由用户签发且可绑定未来调用集合的系统级授权原语。
- 真机评测证明特定 L2 操作可以被稳定收窄为可恢复、固定目标且动态风险不高于 L1，并有清晰撤销与审计。
- Tasker、Auto.js/AutoJs6、Shizuku 或 Android 无线调试协议/许可证发生变化，当前兼容边界不再成立。
- 用户研究证明 Trusted Workspace 与精确批量批准仍无法满足高级用户任务完成率，需要新的授权表达。

## References

- [产品需求](../product/requirements.md)
- [Android 平台能力](../architecture/android-platform-capabilities.md)
- [市场、用户与商业化分析](../product/market-users-and-commercialization.md)
- [安全、测试与发布门禁](../security/testing-and-release.md)
- [ADR-0013：Standard 商店形态与能力保留型分发](0013-standard-store-capability-preserving-distribution.md)
- [Tasker 插件开发文档](https://tasker.joaoapps.com/plugins.html)
- [Android Debug Bridge](https://developer.android.com/tools/adb)
- [Shizuku 简介](https://shizuku.rikka.app/introduction/)
- [AutoJs6 项目说明](https://github.com/SuperMonster003/AutoJs6/blob/master/.readme/README-en.md)
