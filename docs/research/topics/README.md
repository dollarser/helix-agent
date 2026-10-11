# 专题研究输入

本目录保存仍被目标设计引用的专项研究。日期是来源核验窗口，不是当前实现状态；专题中的接口草案和阶段顺序不独立维护为生产契约。

2026-10-11 AgentLoop/Core/UI/Turn/权限/工具的系统分层分析已补入[现有执行引擎综合研究 §9](../modules/01-architecture-and-execution-engine.md#9-2026-10-11-分层复核与优化承接)，不另建重复专题。HXA-274/277 的增量、已交付任务去重及宿主提取条件见该节，优先级以 [status](../../development/status.md#系统分层优化承接2026-10-11)为准。

| 专题 | 保留内容 | 设计与实施承接 |
| --- | --- | --- |
| [Agent 插件架构调研（2026-10-11）](agent-plugin-architecture-research-2026-10-11.md) | 八类 Codex 插件及 Codex 公开核心、Claude Code、Gemini CLI、OpenCode、DSH 与 Helix 对照；Computer Use 的 MCP 接入、原生与 MCP 混合路径、权限与证据边界 | [Helix 优化方案](helix-plugin-architecture-optimization-2026-10-11.md)；事实输入，不独立授权实现 |
| [Helix 插件架构优化方案（2026-10-11）](helix-plugin-architecture-optimization-2026-10-11.md) | Helix 差距、Skill 激活/调用规则、配置生效、兼容诊断、Browser 归属、共享资源；原生/IPC/CLI/MCP 选择、导入与导出边界及验收条件 | [Plugin 专项](../../architecture/plugin-platform-plan.md)、[Harness 方案](../../architecture/harness-refactor-plan.md)；HXA-272～279 已登记，范围映射见方案 §7，优先级由 status 维护；F/G 保留进入条件 |
| [架构及测试维护审查（2026-10-10）](architecture-and-test-maintenance-audit-2026-10-10.md) | 全系统模块/文件边界、应用层职责、测试入口与历史材料留存；桌面和移动参考 | [开发原则](../../development/feature-refactor-strategy.md)、[留存规则](../../development/README.md#git-跟踪与留存)；分切片建议，不独立授权新重构 |
| [Mobile Use：PackageInstaller 可见性、视觉回退与特权后端（2026-10-04）](mobile-use-packageinstaller-visibility-vision-and-privileged-backends-2026-10-04.md) | API 36 PackageInstaller Install 节点缺失、Accessibility/direct query/UiAutomation/vision/root 实测、历史状态污染说明与当时交接 | [平台能力 §5](../../architecture/android-platform-capabilities.md#5-accessibility-自动化)、[Mobile Use 设备可靠性](mobile-use-device-readiness-and-reliability-2026-09-29.md)；后续实现与有限验收以 [status](../../development/status.md) 为准，不将研究当时待办恢复为当前缺口 |
| [Mobile Use 设备就绪与可靠性（2026-09-29）](mobile-use-device-readiness-and-reliability-2026-09-29.md) | 钉钉场景、熄屏/安全锁定/后台区别、源码风险、接续建议与验证矩阵 | [平台能力 §5](../../architecture/android-platform-capabilities.md#5-accessibility-自动化)、[Plugin 专项](../../architecture/plugin-platform-plan.md)；新生命周期与权限策略仍待裁决 |
| [工具曝光、发现与扩展组织（2026-09-29）](tool-exposure-and-discovery-2026-09-29.md) | 数量口径、竞品机制、当前源码与历史评测、六个改进点及实验矩阵 | [工具综合研究 §2](../modules/04-tools-browser-and-extensions.md#2-tool-exposure)、[Harness §15–17](../../architecture/harness-refactor-plan.md)；策略仍需裁决 |
| [插件生态与 Mobile Use（2026-09-28）](plugin-ecosystem-and-mobile-use-2026-09-28.md) | portable package、Skill/MCP/native 区别、移动执行接入和竞品比较 | [Plugin 专项](../../architecture/plugin-platform-plan.md)、[Harness §17](../../architecture/harness-refactor-plan.md) |
| [异步 Job 与前后台执行（2026-09-28）](async-jobs-and-background-execution-2026-09-28.md) | launch/join、不同后台对象、等待与 promotion 的竞品输入 | [当前终端](../../architecture/terminal.md)、[Harness §8](../../architecture/harness-refactor-plan.md) |

通用结论在[综合模块](../modules/README.md)中索引，已被接受或替代的日期化设计在[历史研究](../../evidence/research-history/README.md)中保存。当前授权及缺失能力看[候选索引](../../development/candidate-decisions.md)和[状态](../../development/status.md)。
