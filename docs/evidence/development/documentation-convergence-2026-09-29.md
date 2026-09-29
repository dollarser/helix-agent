# 2026-09-29 文档命名、归档与导航收敛

## 范围与基线

用户要求梳理项目文档的改名、过时内容、汇总机会并实际优化。本次为文档整理，不新增 HXA、不接受候选功能、不修改生产代码或任务顺序，不执行设备/模型、commit/push。

开始时主目录 `main` / `742d93b635fbbefa2263da4234b8c7b8f79f7a6c` 加并行工作树；先建立独立文档快照再移动。结构扫描覆盖 `docs/` 630 份 Markdown，含 126 份缺陷记录、206 份完成记录、173 份证据材料；未发现字节完全相同的重复文件。内容审查聚焦当前入口、目标方案、日期化研究、参考材料和互相矛盾的状态，不声称逐字重新审核全部历史或所有竞品外部事实。

## 改名与归类（14 项）

路径均相对仓库；旧路径用于这份变更账目和 Git 溯源，不保留跳转空壳。

| 原路径 | 新路径 | 理由 |
| --- | --- | --- |
| `docs/architecture/agent-capability-refactor-plan-2026-09-28.md` | `docs/architecture/harness-refactor-plan.md` | 持续维护的唯一 Harness 方案，日期留正文 |
| `docs/architecture/plugin-platform-refactor-2026-09-28.md` | `docs/architecture/plugin-platform-plan.md` | 专项设计用稳定名称，通用契约归总方案 |
| `docs/development/remaining-work-plan-2026-09-28.md` | `docs/development/release-readiness.md` | 实际职责是非重构收口/内测/发行条件，避免与 status/策略竞争 |
| `docs/development/public-benchmark-full-run-plan-2026-09-29.md` | `docs/evidence/development/verification-plans/public-benchmark-full-run-plan-2026-09-29.md` | 原始运行计划不是当前排期或最终成绩 |
| `docs/references/goal-feature-guide.md` | `docs/references/deepseek-harness-goal.md` | 明示是 DSH 参考，不误读为 Helix 功能指南 |
| `docs/references/agent-image-reading.md` | `docs/product/image-reading.md` | Helix 用户使用/能力说明归 product |
| `docs/references/helix-linux-command-integration.md` | `docs/evidence/research-history/helix-linux-command-integration.md` | 旧部署/权限论断不再适用；不补造原日期 |
| `docs/references/background-task-completion-notify-mechanism.md` | `docs/evidence/research-history/background-task-completion-2026-09-10.md` | 原日期明确；混合开发宿主和旧产品阶段，不当现行接口 |
| `docs/research/agent-memory-and-activity-presentation-2026-09-26.md` | `docs/evidence/research-history/agent-memory-and-activity-presentation-2026-09-26.md` | 229/230 已承接；保留 Project 未启用边界 |
| `docs/research/conversation-first-session-workbench-2026-09-26.md` | `docs/evidence/research-history/conversation-first-session-workbench-2026-09-26.md` | 228 已交付，不重开原 UI 计划 |
| `docs/research/workspace-competitive-contracts-2026-09-27.md` | `docs/evidence/research-history/workspace-competitive-contracts-2026-09-27.md` | Workspace 已接受/交付，保留原竞争比较 |
| `docs/research/helix-agent-capability-architecture-convergence-2026-09-28.md` | `docs/evidence/research-history/helix-agent-capability-architecture-convergence-2026-09-28.md` | 早期横向路线由详细方案/策略承接 |
| `docs/research/agent-plugin-ecosystem-and-mobile-use-2026-09-28.md` | `docs/research/topics/plugin-ecosystem-and-mobile-use-2026-09-28.md` | 继续使用的专题比较，保留原研究窗口 |
| `docs/research/async-jobs-wait-and-background-execution-competitive-study-2026-09-28.md` | `docs/research/topics/async-jobs-and-background-execution-2026-09-28.md` | 专题与当前技术契约分开 |

## 已汇总与纠正的内容

| 问题 | 处理 | 依据 |
| --- | --- | --- |
| 文档中心同一引擎研究出现多个同义入口 | 按用户问题汇总主入口；README 固定文档职责/命名/归档规则 | 原各目录职责与现有 AGENTS |
| status 堆积旧表数、旧设备测试数和“本轮未提交” | 已交付历史汇为领域表，保留完成索引与当前例外；Next task 不再复制 P0～P7 全过程 | [完成索引](../../completion-records/index.md)、[最终收口](final-closeout-2026-09-29.md) |
| HXA-225 初次 host 范围与后来真实模型结果混读 | 明确先后与证据边界；不把 1/1 工具看图扩大为所有视觉已验 | [HXA-225](../../completion-records/HXA-225.md)、最终收口 |
| overview 仍画 ChatService 持有 live driver | 对齐有效 ADR：TurnEngine 已有 live driver；headless Core 的后续目标仍未冒充实现 | [Agent ADR](../../adr/agent/001-turn-coordination.md) |
| 终端页仍暗示独立终端/199 尚未交付 | 当前已有功能与剩余 OEM/资源验证分开 | [HXA-196](../../completion-records/HXA-196.md)～[199](../../completion-records/HXA-199.md) |
| 旧 Linux 参考中的 companion APK/UID、QuickJS、风险等级 | 原文归档，不用旧描述覆盖运行时；有效的手动/工具入口区别汇入当前执行域 | [Runtime ADR](../../adr/runtime/001-execution-domains.md) |
| 研究页仍将 Workspace/本地模型/视觉/UI 写为待开发 | 更新承接说明，保留旧详细比较而非删除全文 | HXA-210/222/225/228/229 与候选索引 |
| 输入“必须持久化”容易误含未发送草稿 | 区分 accepted input/receipt 与每会话临时输入缓存 | [输入缓存修复](../../bug-fixes/2026-09-29-conversation-input-cache.md) |
| 执行指南泛化允许自动本地提交 | 对齐根 AGENTS：当前任务明确授权才提交 | 根 [AGENTS.md](../../../AGENTS.md) |
| Benchmark 日期计划混入 current 目录 | 原计划归档，可复用计分/分母/比较规则汇入 Agent Eval | [原计划](verification-plans/public-benchmark-full-run-plan-2026-09-29.md)、[Agent Eval](../../development/agent-eval.md) |
| DSH 参考自称本文件永不进入 Git | 明确该说法仅属原笔记存放环境，当前副本实际在 Helix docs | 原文及[重命名参考](../../references/deepseek-harness-goal.md) |
| 能力研究有重复标题及未经实测的绝对化例子 | 删除重复标题，保留研究 framing 并标明例子不构成测量 | 原模块 07，与本次无外部复验的范围一致 |
| 三处旧章节链接失效 | 完成记录模板改指实施指南；Termux 与开源参考改指当前有效正文 | 全量相对锚点复查 |
| 竞品替代页仍称 Helix 使用独立 UID companion | 标明原比较时间，Helix 状态部分对齐当前 Runtime/Provider ADR | 只修 Helix 的过时描述，不新增竞品核验 |

统一正文而非合并所有文件：Harness 目标方案保留完整卡片与契约；开发策略保留顺序/停止原则；实施指南保留执行流程；release-readiness 保留发行依赖；专题保留独有研究。它们各有目的，不能合成一份同时承担现状、契约、历史和排期的巨型计划。

## 不做机械清理的内容

不重编 ADR/HXA，不接受 proposed，不删除历史失败，不把原先未请求的设备验证改成通过。完整完成记录、缺陷记录、历史报告和来源保留。大文件如环境指南、产品需求与 Harness 方案先改善导航/身份，不仅因行数长就拆分或删除。

历史正文只修链接并增加页首适用说明；引用文字、旧实验数字和 original status 保留原时间含义。部分长期文档仍含旧基准实例，本轮不逐段重新证明所有技术论断；以后按相关领域任务核验，不把本次整理称为全仓语义零错误。

## 检查与审计

改名前 `./scripts/check-docs.sh` 已通过：633 Markdown、214 HXA。所有重命名 SHA 守卫通过后才移动；机械入链/出链修复共涉及 26 份文档，之后另做内容与导航收敛。

一次性脚本为 `scripts/debug/2026-09-29/rebase-document-links.py`；原文备份/哈希、移动映射和修复清单位于 ignored `build/docs-convergence-20260929-75506fbe/`。快照用于本次校验，不成为第二套版本管理或用户数据备份。

本轮实际检查：

| 检查 | 结果与边界 |
| --- | --- |
| `./scripts/check-docs.sh` | 整理后通过：635 Markdown、214 HXA；最终正文再次复核 |
| 移动与历史正文校验 | 14 项原路径不存在/新路径存在；9 份归档和专题正文完整保留，仅修相对链接和新增页首说明 |
| 相对章节锚点 | `docs/` 632 份 Markdown 中识别的 36 个本地章节链接均有效；修复 3 个既有失效锚点，无新增断链 |
| `git diff --check` | 修复本轮 Agent Eval 尾部空行后通过；并行 Provider ADR 曾短暂产生尾部空行，SHA 冲突阻止本任务覆盖，该并行变更未纳入成果 |
| 当前页压缩 | status 从 24,978 UTF-8 字节收敛为 15,553 字节，减少约 38%；历史数字与失败保留在原证据 |

机械迁移覆盖文档和根入口；另有一个历史 debug 脚本仅修正生成 Markdown 时使用的 Goal 链接，没有执行该旧脚本。主 Harness 方案的技术内容、开发原则和 HXA-231 范围只修路径引用，没有借此扩大接受范围。

文档通过不等于代码、设备或模型功能通过。发现并行 App/ADR 内容继续变化时保留其工作，不将它们计为本次实现。本次没有执行新的构建、设备、模型、账号、提交或推送操作。
