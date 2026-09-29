# 专题研究输入

本目录保存仍被目标设计引用的专项研究。日期是来源核验窗口，不是当前实现状态；专题中的接口草案和阶段顺序不独立维护为生产契约。

| 专题 | 保留内容 | 设计与实施承接 |
| --- | --- | --- |
| [Mobile Use 设备就绪与可靠性（2026-09-29）](mobile-use-device-readiness-and-reliability-2026-09-29.md) | 钉钉场景、熄屏/安全锁定/后台区别、源码风险、接续建议与验证矩阵 | [平台能力 §5](../../architecture/android-platform-capabilities.md#5-accessibility-自动化)、[Plugin 专项](../../architecture/plugin-platform-plan.md)；新生命周期与权限策略仍待裁决 |
| [工具曝光、发现与扩展组织（2026-09-29）](tool-exposure-and-discovery-2026-09-29.md) | 数量口径、竞品机制、当前源码与历史评测、六个改进点及实验矩阵 | [工具综合研究 §2](../modules/04-tools-browser-and-extensions.md#2-tool-exposure)、[Harness §15–17](../../architecture/harness-refactor-plan.md)；策略仍需裁决 |
| [插件生态与 Mobile Use（2026-09-28）](plugin-ecosystem-and-mobile-use-2026-09-28.md) | portable package、Skill/MCP/native 区别、移动执行接入和竞品比较 | [Plugin 专项](../../architecture/plugin-platform-plan.md)、[Harness §17](../../architecture/harness-refactor-plan.md) |
| [异步 Job 与前后台执行（2026-09-28）](async-jobs-and-background-execution-2026-09-28.md) | launch/join、不同后台对象、等待与 promotion 的竞品输入 | [当前终端](../../architecture/terminal.md)、[Harness §8](../../architecture/harness-refactor-plan.md) |

通用结论在[综合模块](../modules/README.md)中索引，已被接受或替代的日期化设计在[历史研究](../../evidence/research-history/README.md)中保存。当前授权及缺失能力看[候选索引](../../development/candidate-decisions.md)和[状态](../../development/status.md)。
