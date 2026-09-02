# ADR-0013: Standard 商店形态与能力保留型分发

Status: accepted
Date: 2026-09-02
HXA: HXA-120, HXA-121, HXA-122, HXA-123
Deciders: Project owner（明确要求 Standard 以 Google Play 和国内 Android 应用商店上架为产品边界，不因抽象安全偏好预先阉割能力）
Supersedes: [ADR-0006](0006-single-direct-main-package.md)
Superseded by: none

## Context

[ADR-0006](0006-single-direct-main-package.md)把 developer 变体定位为直接分发的唯一完整主应用，把 consumer 定位为未来商店、企业或合规渠道的严格裁剪产物。该做法容易把运行时 `STANDARD` 错误等同于“低能力版”，并让尚未核验的安全或审核假设提前决定产品能力。

项目所有者现明确要求：`STANDARD` 的产品边界应能面向 Google Play 和国内 Android 应用商店，同时保留尽可能完整的用户价值；不能只因为安全偏好、命名惯例或未经证实的审核猜测删除能力。`STANDARD`/`ADVANCED` 是运行时体验和授权模型，商店/官网是分发渠道，Gradle flavor 是打包机制，三者不能互相替代。

商店政策仍会形成真实外部约束。Google Play 当前允许符合核心用途并通过声明/审核的文件管理或文档管理应用申请 `MANAGE_EXTERNAL_STORAGE`；非辅助工具也可使用 Accessibility，但必须声明、披露并取得同意。与此同时，Google Play 明确禁止使用 Accessibility 自主发起、规划并执行操作，允许的是狭窄、用户可理解的确定性自动化；从 Play 外下载 DEX/JAR/native executable code 也受禁止。国内商店的权限、SDK、备案、隐私与动态能力规则必须按目标商店和提交日期分别核验，不能假设完全等同于 Google Play，也不能用其中最严格的一家永久限制所有渠道。

## Decision

Helix 采用“一个产品、Standard 默认、能力保留、渠道最小差异”的分发原则：

1. `STANDARD` 是完整的商店产品形态和所有安装的默认运行配置，目标渠道包括 Google Play 与选定的国内 Android 应用商店。它不是 demo、只读版、聊天版或安全阉割版。
2. Standard 至少保留 Provider/BYOK、会话与 Goal、Workspace/SAF、文件管理与可审阅修改、Browser、QuickJS 解释执行、MCP/Skills、Android Intent/通知/日历、任务模板、恢复和审计。已实现能力不得仅因“高级”“可能有风险”或笼统最佳实践被移除。
3. Standard 可以提供用户主动启用的 All-files、Accessibility、Tasker/确定性脚本互操作及其他商店允许能力。是否进入某个渠道 artifact 由该渠道提交时的明确政策条款、权限声明结果和真实审核证据决定，而不是由 Safety Profile 名称决定。
4. `ADVANCED` 继续承担 Root、PRoot/CLI、精确 LAN、自定义执行域、更宽开发者控制台和 ADR-0012 长期授权等专家体验。某能力进入 Advanced 是因为它需要不同执行域、依赖、授权表达或渠道不接受，不是为了把 Standard 人为做弱。
5. 渠道差异优先落在 manifest、依赖、companion 可用性、Capability Provider 和商店 listing，不分叉 Agent Core、数据模型、Workspace 格式或主要 UI。不可用能力显示准确原因和其他合法分发渠道，不伪装成尚未开发。
6. 当前 `consumer`/`developer` 名称和 applicationId 暂不机械修改。HXA-122 必须基于本 ADR 决定稳定主 applicationId、flavor/channel 命名和升级路径；目标形态应避免让用户为 Standard/Advanced 更换 applicationId。
7. Google Play 和国内商店都是明确发布目标，但“目标可上架”不等于保证第三方审核通过。只有真实提交、审核通过和可下载证据才能记录为已上架。

渠道裁剪必须满足“外部硬约束 + 最小差异”测试：

- 每个被移除或替换的能力都要记录目标商店、政策原文/版本或审核反馈、受影响 manifest/module/tool，以及为什么运行时关闭或声明流程不足。
- 若商店允许声明、显著披露、用户同意或权限审核后保留能力，优先完成这些流程，不先删除能力。
- 若 Google Play 不允许 Agent 通过 Accessibility 自主规划执行，则 Play artifact 只能提供政策允许的确定性、用户定义自动化；Agent 自动化保留在允许该能力的合法渠道。不能把这一渠道差异扩展成所有 Standard 构建的永久产品限制。
- 若 `MANAGE_EXTERNAL_STORAGE` 以文件/文档管理核心用途申报，必须让商店描述、产品首页和真实功能一致，并准备 SAF 不足的证据；审核未通过时保留 SAF/MediaStore 降级，不删除文件工作台。
- 解释型 JS/脚本能力可以保留，但不得从 Play 外下载 DEX/JAR/`.so` 自更新，也不能借解释器实施商店政策禁止行为。

## Alternatives considered

1. **继续把 consumer 定义为严格裁剪商店版**：审核准备直观，但在没有具体政策证据前损失文件、自动化和开发能力，也把 Standard 错误等同于低能力。拒绝。
2. **所有渠道发布完全相同的二进制**：维护最简单，但 manifest 权限、companion、Accessibility 自动化和动态执行政策确实可能不同。拒绝；采用能力保留、渠道最小差异。
3. **只做官网直接分发，不进入商店**：能力限制最少，但显著降低发现、更新和用户信任渠道。拒绝作为长期产品边界；官网仍保留完整能力渠道。
4. **为了保证审核通过先删除所有敏感权限和执行器**：短期申报工作少，但并不能保证审核通过，且破坏产品核心价值。拒绝。
5. **承诺所有国内商店与 Google Play 一定通过**：第三方政策、审核和地区要求会变化，项目无法控制。拒绝保证结果，接受“明确目标 + 逐渠道证据”。

## Consequences

- 收益：Standard 成为可独立完成真实任务的完整产品，商店用户不会只得到聊天壳或人为阉割版。
- 收益：产品、安全配置和分发渠道解耦；真实政策限制只影响必要渠道，不污染其他合法分发版本。
- 收益：文件管理核心定位可支持 All-files 申报，Accessibility/解释脚本也按官方允许范围保留，而不是先验删除。
- 代价：HXA-120～123 必须维护逐渠道 capability/manifest/listing 矩阵，并在政策变化时复核。
- 代价：Google Play 与官网/部分国内渠道可能存在少量能力差异，测试和用户说明更复杂。
- 约束：本 ADR 不证明任何商店已经批准 Helix，也不授权虚假申报、规避审核或远程下载可执行代码。
- 约束：现有 consumer/developer 构建和 applicationId 仍是工程事实；重命名、合并或迁移必须由 HXA-122 连同代码、签名和升级测试完成。

## Verification

当前已验证证据：

- Google Play 官方政策把文件管理、文档管理列为 `MANAGE_EXTERNAL_STORAGE` 的允许用途，但要求权限声明并通过审核。
- Google Play 官方政策允许广泛应用使用 Accessibility，但非辅助工具需声明、显著披露和肯定同意；Agent 自主规划执行 UI 动作被明确禁止，确定性用户脚本不在该禁止范围内。
- Google Play 官方政策禁止从 Play 外下载 DEX/JAR/native executable code，同时允许受政策约束的解释型语言运行。
- 项目当前只具备 consumer/developer 构建边界，没有任何 Google Play 或国内应用商店提交/审核通过证据。

HXA-120～123 后续必须证明：

- Standard 在每个候选渠道完成核心任务矩阵，不因缺少 Advanced/companion 变成聊天壳。
- 生成逐渠道 capability、manifest permission、SDK/依赖、数据安全声明、隐私披露、listing 和降级矩阵；每个差异均有具体外部依据。
- Google Play artifact 对 Accessibility 只暴露审核允许的确定性自动化，不把 Agent 自主 UI 规划描述或实现成合规能力。
- All-files 申报材料能证明文件/文档管理是核心用户功能，并保留 SAF 降级；审核结果按真实证据记录。
- applicationId、签名、升级/回滚和 Standard/Advanced 数据连续性经过 release artifact 与真机验证。
- 对每个国内目标商店，在提交当日重新核验官方规则并记录真实审核结果；不能用其他商店结论代替。

## Reconsider when

- Google Play 或主要国内渠道政策明确禁止 Helix 的核心 Provider、文件、Browser、解释执行或自动化组合。
- 真实审核反复证明能力保留型单产品无法通过，而独立渠道 applicationId 能显著改善分发且数据迁移可接受。
- 商店渠道用户使用数据证明某些 manifest 能力即使默认关闭也造成不可接受的安装或信任损失。
- Android 新增更适合 Agent/自动化的官方 API，使 Accessibility 或 All-files 渠道差异可以消除。

## References

- [ADR-0012：能力优先的 Advanced 与持久授权](0012-capability-first-advanced-grants.md)
- [产品需求](../product/requirements.md)
- [Android 平台能力](../architecture/android-platform-capabilities.md)
- [安全与发布门禁](../security/testing-and-release.md)
- [Google Play：All files access](https://support.google.com/googleplay/android-developer/answer/10467955)
- [Google Play：AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491)
- [Google Play：Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646)
- [Google Play：target API 要求](https://developer.android.com/google/play/requirements/target-sdk)
