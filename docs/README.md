# Helix 文档中心

本目录按“当前事实、设计规范、开发治理、决策、交付证据、历史记录”分层。目录层级表达文档职责，文件名不再使用 `01`～`12` 的人工顺序编号。

## 从哪里开始

| 你要做什么 | 先读 |
| --- | --- |
| 判断当前做到哪里、下一项是什么 | [实施状态](development/status.md) |
| 开始或继续一个 HXA | [开发路线](development/roadmap.md)、[验收矩阵](development/verification-matrix.md) |
| 配置开发机或设备 | [开发环境](development/environment.md) |
| 理解产品边界 | [产品需求](product/requirements.md) |
| 判断目标用户、首发场景和商业模式 | [市场、用户与商业化](product/market-users-and-commercialization.md) |
| 理解系统与安全边界 | [总体架构](architecture/overview.md)、[安全与发布门禁](security/testing-and-release.md) |
| 判断是否需要架构决定 | [ADR 约定](adr/README.md) |
| 查某项交付、Bug 或事故证据 | [完成记录](completion-records/README.md)、[Bug 修复](bug-fixes/README.md)、[Postmortem](postmortems/README.md) |

编码 Agent 还必须遵守仓库根目录的 [`AGENTS.md`](../AGENTS.md)。

## 目录职责

```text
docs/
├── product/             产品需求、定位与竞品研究
├── architecture/        当前规范性架构与专项设计
├── development/         当前状态、路线、环境、实施与验收
├── security/            威胁模型、测试和发布门禁
├── references/          外部项目、依赖和许可证边界
├── adr/                 架构决定及其理由
├── completion-records/  已完成 HXA 的不可变交付证据
├── bug-fixes/           非平凡缺陷的根因与回归证据
├── postmortems/         越过既有安全网的系统性事故复盘
└── history/             不再充当当前规范、但仍有追溯价值的记录
```

## 产品

- [产品需求](product/requirements.md)：用户、场景、能力边界和产品验收指标。
- [移动端 Agent 竞品与定位](product/competitive-landscape.md)：竞品快照、差异化定位和后续跟踪指标。
- [市场、用户与商业化](product/market-users-and-commercialization.md)：目标用户、购买理由、能力包装、分发与商业化假设。

## 架构

- [总体架构](architecture/overview.md)：模块、状态机、核心接口、存储和安全不变式。
- [本地代码执行](architecture/local-code-execution.md)：QuickJS、PRoot 与执行域边界。
- [Android 平台能力](architecture/android-platform-capabilities.md)：Browser、Files、Accessibility、Root 与 Android 工具。
- [Provider、MCP、Skills 与模式](architecture/provider-mcp-skills-modes.md)：Provider、扩展协议、Plan/Goal 和订阅边界。
- [手机端 Tool 编排](architecture/mobile-tool-orchestration.md)：确定性调度、取消、恢复与受限委托。

## 开发治理

- [实施状态](development/status.md)：唯一当前状态源，只维护已验证范围、当前任务、接口和限制。
- [开发路线](development/roadmap.md)：HXA 的依赖顺序、范围和验收要求。
- [验收矩阵](development/verification-matrix.md)：每个 HXA 的真实命令、设备与证据。
- [开发环境](development/environment.md)：JDK、Android SDK、AVD、真机和依赖基线。
- [小模型实施指南](development/implementation-guide.md)：受限上下文下的任务读取、实现与交接规则。

## 安全与外部参考

- [安全、测试与发布门禁](security/testing-and-release.md)：威胁模型、测试矩阵和发布阻断条件。
- [开源依赖与参考项目](references/open-source-projects.md)：直接依赖、设计参考、禁止复制和许可证复核边界。

## 决策与证据

- [ADR](adr/README.md) 记录决定及理由；`accepted` 不等于已经实现。
- [完成记录](completion-records/README.md) 记录 HXA 的实际命令、结果、设备和限制。
- [Bug 修复记录](bug-fixes/README.md) 记录交付后非平凡缺陷的根因、不变式和回归证据。
- [Postmortem](postmortems/README.md) 仅用于已经越过安全网的系统性事故。
- [文档复核历史](history/documentation-review.md) 保存跨里程碑复核与取舍，不作为当前状态源。

## 维护规则

1. 同一事实只有一个权威位置；其他文档使用链接，不复制易变化的状态快照。
2. `development/status.md` 是唯一当前状态源；路线是计划，ADR 是决定，完成记录是证据，三者都不能替代当前状态。
3. 规范变化同步修复所有相对链接，并运行 `./scripts/check-docs.sh` 与 `git diff --check`。
4. 历史文件只有在确认没有独有决定、根因或验收证据后才能删除；被取代的 ADR 仍保留并标注 superseded。
5. 新文件使用小写 kebab-case；HXA、ADR、M0 编号和日期型 Bug 文件保留各自约定格式。
6. 产品需求和架构文档只定义长期边界，不复制“当前 HXA/已完成范围/下一任务”；需要表达实时实现状态时链接 `development/status.md`。
7. 分发渠道、构建 flavor 与运行时安全配置是三个独立维度；任何文档不得再把 consumer/developer 直接等同于商店版/完整版。
