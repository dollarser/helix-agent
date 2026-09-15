# HXA-200 收尾复核与 HXA-201 交接

最终更新：**HXA-200 已验收关闭**，见[完成记录](../completion-records/HXA-200.md)。下方1～3节是修复前审查历史，C1/C2、审计/恢复和JGit阻塞均已解决；第4节为当前HXA-201交接，不重新实施200。

2026-09-15 修复更新：下方C1/C2为发现时的历史问题，现已按既有ADR实现修复及针对性回归，见[修复与证据](../bug-fixes/2026-09-15-tool-preference-start-boundary.md)。HXA-201不再需要重做这两项；接手先核对该记录的最终验证范围。其余HXA-200验收缺口仍单独保留，不以两项修复冒充整体完成。

日期：2026-09-15。复核基线 `fa01dde6`，工作树含并行未提交修改。本轮为源码与契约复核，不是新的设备验收。结论：**HXA-200 不能关闭，剩余问题不只是共享文档提交和 JGit lint。** 六个 gap 有提交记录，但其中两个测试把与 accepted ADR-0052 相反的行为写成了通过条件。

## 1. 已确认的关闭阻断

### C1：跨范围 ASK 被 ALLOW 覆盖

`core/policy/.../ToolApprovalResolver.kt` 的 `effectivePreference` 在检查 DENY 后选最窄 scope；`ToolApprovalResolverTest.aNarrowerSessionAllowOverridesAGlobalAsk`、`ToolApprovalPreferenceEffectiveTest` 及设备 `ToolApprovalPreferenceDeviceTest.aNarrowerSessionAllowOverridesAGlobalAskCardFree` 明确要求全局 ASK + 会话 ALLOW 免卡。

[ADR-0052](../adr/0052-tool-approval-preferences.md) 第 5 条要求命中规则 DENY > ASK > ALLOW，第 6 条要求保留 ASK 限制。2026-09-14 澄清明确不改变第 2～8 条。现有测试不能作为符合该契约的证据。

修复验收：按已接受优先级合并适用记录；任一命中 DENY 拒绝，否则显式 ASK 保留询问，再解析允许及失效来源。覆盖 GLOBAL ASK + SESSION ALLOW、WORKSPACE ASK + SESSION ALLOW、反向组合和 DENY；保留真实 Room/broker/dispatcher，断言一张卡、未经确认零执行。更新错误测试及注释，不通过修改 ADR 追认错误实现。

### C2：等待审批后没有重新检查偏好

`ToolDispatcher.policyStage` 在 `acquireApproval` 前读取偏好；`executeStage` 只检查取消，然后记录开始时间、消费证明并执行。`aPreferenceFlipWhileTheCardIsPendingCannotRewriteThePresentedDecision` 在 acquire hook 中将 ASK 改为 DENY，却断言执行成功、source 只读一次、证明被消费。

这违反 ADR-0052 第 7 条“避免审批等待期间修改设置仍使用旧允许”。卡片内容不可被改写与执行前检查当前限制是两件事；旧卡批准不覆盖后来设置的 DENY。

修复验收：呈现事实保持不变；执行开始前重新解析偏好、能力与契约。偏好写入和执行开始须有明确线性化边界及 revision 证据，不能仅在执行前增加一次无同步查询便声称竞态已解决。不得在等待用户审批或执行长任务期间持有全局锁。覆盖：等待期间改 DENY 后批准旧卡仍拒绝、不消费proof、零执行、持久PREFERENCE_DENIED；变更在开始前/后分别遵循顺序；新增 ASK 需要确认，已有相同精确proof不重复询问；取消优先且兄弟调用结算不丢失。

`ToolApprovalPreferenceRepository` 存有 revision，但当前 service 的有效偏好返回值没有带出该修订，也未与 Dispatcher 开始边界协调。修复需梳理这条实际生产路径，不能仅修改测试 fake。

## 2. 仍需核查的证据，不能先判为实现缺陷

- 取消留下 PENDING：`StorageApprovalBroker.cancel` 唤醒并终止活跃等待，记录保持未决。需证明有效审批列表过滤已终止调用、迟到决定无执行、重启不复活；保留未决历史本身不是错误，不必强制改成用户 DENIED。
- 最新 gap 记录主要为 API29；早期 API36 的4+9用例不能替代后来8+13及Room18迁移用例。按当前矩阵补 API36 和受修复影响的 API29 设备证据，记录实际XML数量和零skip。
- NEW_DEFAULT 当前在下一次 versionCode 升级后自动变为 UNSET，且只登记 built-in 工具。这是新增默认的实际适用边界；HXA-201不能宣称所有扩展默认ASK或永久保持ASK。是否改成持续等待用户配置需单独明确契约，不在UI中暗改。
- 两项 JGit lint 是此前记录，本轮未重跑。继续保留独立门禁状态，不推断已解决、不全局suppress，不把P1或局部测试通过称为P3通过。

## 3. 收口与提交规则

当前[状态](status.md)、[路线](roadmap.md)和[矩阵](verification-matrix.md)中的六个gap条目是历史提交证据。本文纠正“只剩文档协调”的解释；不删除这些历史记录，不创建 HXA-200 完成记录。

先修C1/C2并运行产品包P1/P2/P3与对应设备矩阵，再根据结果逐项关闭。完整命令见[产品计划](product-completion-and-approval-plan.md#5-精确验证命令)。JGit或其他未达门禁明确保留。可以提交验证通过的独立修复，未通过整项门禁不关闭HXA。

共享文档不是技术阻塞：本任务新增独立记录可单独暂存；已有文档只有归属清楚的hunk才能纳入。禁止整文件夹带其他HXA、git add .、reset/stash、push/merge。文档可引用本复核作为当前判定，不需要重复拷贝整张验收表。

## 4. HXA-201 可直接复制的 Prompt

```text
继续 Helix HXA-201 工具设置与审批卡。先确认当前是 Harness 工作树，读取 AGENTS.md、README、status、roadmap 的 HXA-200/201、verification-matrix、ADR-0012/0052，以及 docs/development/hxa200-closeout-review-and-hxa201-handoff.md 和 product-completion-and-approval-plan.md。

先核对当前HEAD与已有变更，不假定交接hash仍最新。HXA-200已在本工作树完成验收；先读docs/completion-records/HXA-200.md与实际提交，区分验收源码中的并行WIP和本任务提交，不重做已验证修复。原始问题和错误期望仅为历史证据，不通过修改ADR恢复它们。

201现在可推进UI与真实执行集成。复用200的执行开始重验、三阶段审计快照与取消/迟到批准拦截，不用UI假逻辑代替后端。保持共享未提交文档及并行任务归属；不得把200的后端验收当作201界面已完成。

复用现有ToolApprovalPreferenceService与Registry，UI不访问DAO，不另建偏好存储。实现按工具名称/提供方搜索、允许/询问/禁止设置、真实作用域/来源展示和恢复默认。未设置展示遵循默认策略，不能伪称用户已允许；失效ALLOW说明需要重新确认。按实际合并结果显示外层限制，不承诺窄scope可覆盖ASK/DENY。consumer不出现无法兑现的Runtime能力。

审批卡首屏显示动作、目标、范围、变更或外发摘要，完整参数可展开。本次批准/拒绝与保存未来偏好分开。ALLOW不创建Capability、文件范围或通配高风险proof；高风险不显示“永远允许”。卡片呈现事实保持稳定，设置变化后的实际可执行性由后端重新判断，过期/取消/旧卡不批准新调用。恢复默认移除记录并显示实际解析结果。

验证用户保存设置后的真实工具行为，覆盖低风险ASK/UNSET、DENY、ALLOW高风险仍确认、跨scope、失效版本、等待期间修改、取消、重启、迟到批准。新增ToolApprovalSettingsDeviceTest按计划API29/36双flavor独占运行，并覆盖三语言、深色、大字体、小屏与旋转。截图不能替代执行证据，fixture不能替代真实账号验收。执行产品包P1/P2/P3，不把编译/dry-run/零测试/skip记为通过。

每次一个独立切片，验证后允许具名路径/hunk本地commit，检查cached diff/stat/check，保留并行改动，不git add .、不reset/stash、不push/merge/release。状态、源码、测试与提交分别记账，只有任务矩阵全部满足才关闭对应HXA。最终报告用户变化、真实命令/数量、commit、未达门禁及下一项。
```
