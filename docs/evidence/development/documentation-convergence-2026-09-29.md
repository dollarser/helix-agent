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

## 2026-09-30 内容、时效与按需阅读收敛

本节是后续独立的文档整理记录，不覆盖上文 2026-09-29 的统计和结果。用户要求“总体优化文档”；本轮以 `main/b51687e0635dc33713ff86e52c49044049575668` 加已有工作树为起点，先快照全部文档和 AGENTS，再按当前 status、有效 ADR、完成记录及原方案核对。范围扫描覆盖根目录与 docs 的 653 份 Markdown，其中 docs 650 份；不是逐字重新审查全部历史和竞品。

### 实际改动

| 范围 | 处理与来源 |
| --- | --- |
| 当前状态 | status 的已完成流水账改为领域基线，开放任务、Next task、Blocked 和限制保留；移除需靠历史时点解释的提交/测试句，详细证据保留原路径 |
| 过期声明 | 按 ADR-TOOLS-001 与 HXA-231 完成记录修正主方案的 proposed/待批、candidate/roadmap 的仅主机范围和重复 R1 待办；不授予新实现范围 |
| Goal ADR | 只合并已接受的 HXA-232 增量：局部额度与总额度分别处理，无进展收尾回到状态正文，自动只读核查不等于解除 UNKNOWN；预算数值、四次模型/八轮核查、FAILED 收尾及原用户/平台边界不变 |
| 主重构方案 | 保留 22 章、职责准则、结果反馈、Core/Job/Context/Plugin 契约与 26 组测试；R1-1～R1-3 退出待执行清单，保留完成入口；通用交接/验证改引用实施指南 |
| Plugin 专项 | 保留 manifest 示例、host-known native、Mobile Use、工具接口演进理由与专项回归；通用安装/更新/选择规则只引用 Harness §17；删除平行 P0/P1/P2 排期和第二种 canonical 哈希描述 |
| 开发策略/实施 | 保留十项原则、六类样本、五项停止条件、候选接入取舍；去掉重复依赖表/交接模板与旧“R1 下一步”。明确 CodeGraph/按需读取及文档任务不默认构建 APK |
| 归档 | 两份已承接研究移动到 evidence/research-history，修复入链/出链并添加时效说明；HXA-232 不因此关闭，原发现和备选方案正文保留 |
| 索引 | docs/README、research/README、research-history/README 对齐单一正文和新位置；不新增平行计划、Markdown 文件或文档管理系统 |

移动映射：

- `docs/research/harness-human-intervention-audit-2026-09-29.md` → `docs/evidence/research-history/harness-human-intervention-audit-2026-09-29.md`
- `docs/research/quickjs-access-and-autonomous-recovery-2026-09-29.md` → `docs/evidence/research-history/quickjs-access-and-autonomous-recovery-2026-09-29.md`

### 阅读体积与保留检查

| 当前入口 | 之前行数 | 整理后行数 | 之前/之后 UTF-8 字节 |
| --- | ---: | ---: | ---: |
| status.md | 119 | 84 | 20,800 / 11,367 |
| feature-refactor-strategy.md | 354 | 204 | 28,121 / 15,189 |
| implementation-guide.md | 73 | 62 | 8,415 / 5,667 |
| harness-refactor-plan.md | 1,205 | 1,151 | 135,063 / 131,547 |
| plugin-platform-plan.md | 661 | 202 | 20,182 / 14,889 |

这五页合计减少 709 行、33,922 字节；不是全仓 token、计费或开发效率的实测收益。没有通过删掉完整技术条款来强求主方案变短：对比快照确认 §8、§13、§14、§15.6、§16、§17、§19 七个契约/验证范围逐字不变，未完成的 R2/R3/J1/J2 卡片亦保留。

一次性只读核验：`scripts/debug/2026-09-30/verify-document-convergence-509ac5c9.py`。它依赖本轮 ignored 快照 `build/documentation-convergence-20260930-509ac5c9/before.tar.gz`，仅输出同目录 `verification.json`，不接入常驻门禁，不是替代 CodeGraph 的工具。

已执行的专项检查通过：Markdown 集合除两次移动外一致、根 AGENTS 未改、两份历史正文在相对链接归一后完全一致、七个技术范围及未完成卡保留、T01～T26 和十个 FUT ID 保留、全量可识别本地链接/章节锚点和改动空白检查。数目与最终状态由 `verification.json` 记录，不复制第二份滚动统计。

中间核验两次停在快照读取：macOS tar 包含 `docs/._README.md` 等 AppleDouble 元数据。确认 `00051607` 魔数后仅排除这类元数据成员，继续严格解码真正 Markdown；没有忽略文档编码错误或跳过原文保留断言。修正后的完整专项检查通过。

后续文档门禁发现实施指南改写后缺少四个按字面校验的契约表述，已恢复 status 完整路径、不得重做已完成 HXA、持久 Goal 须明确要求及 verification matrix 原称；没有放宽门禁。整理记录追加后发现尾部多余空行，已删除。一度专项 JSON 先报告内容检查通过、随后 Git 空白检查失败；现将 Git 结果纳入最终报告后再写出总状态，不据该中间 JSON 宣称全部通过。

最终仓库门禁使用 `./scripts/check-docs.sh`、`./scripts/verify-adr.sh` 及相应状态声明检查；命令结果单独留证。仅更新文档、路径和一次性核验材料，不改变 ADR 的接受状态、生产行为、任务优先级或设备/账号授权；不执行构建、设备、真实模型、Git 提交或推送。
