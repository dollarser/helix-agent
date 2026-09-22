# 小模型批次执行进度记录

> 历史材料（2026-09-22 已核对）：此页只记录 2026-09-20 准备批次及当时命令；后续 198、206 已交付，199 剩物理专项。当前顺序见[工作计划](../../development/next-work-plan.md)，不沿用下文分工与待办。

- **执行基线**：`d5645774`
- **执行分支**：`codex/small-model-preparation`
- **工作树**：`Helix-small-model-preparation`
- **开始时间**：2026-09-20
- **执行指导**：`docs/development/small-model-handoff.md`

---

## 当前整合状态

2026-09-21：返修提交至 `14cfbb44` 已按所有者授权快进合入 main。51 项 Python 测试已重新通过；双终端普通页面补验发现同目录新建被拒，协调者已完成修复与双API补验。当前事实见[整合记录](hxa-198-main-integration-2026-09-21.md)。下列命令/数量是各切片提交时的历史记录，不替代最终验收。199/206 的 fixture 报告均不是产品或真机通过证据。

## 原切片进度总览

| 工作包 | 内容概要 | 状态 | 交付提交 SHA | 验证结果 |
| :--- | :--- | :--- | :--- | :--- |
| **A** | HXA-206 场景映射、报告契约、统计脚本及测试 | **READY_FOR_REVIEW** | `3e3c6040` | 12/12 单元测试通过，CLI 验证通过 |
| **B** | HXA-199 证据汇总与报告准备 | **READY_FOR_REVIEW** | `fcaa190d` | 8/8 单元测试通过，CLI 验证通过 |
| **C** | 过期交接文档收敛、用户帮助草稿 | **READY_FOR_REVIEW** | `beb59c95` | 源码门禁通过，引用核对无孤岛 |
| **D** | HXA-198 UI 设计、状态/操作表、双会话闭环实现与测试映射 | **INTEGRATED** | `14cfbb44`、`5d97fc01`及本次收口 | 最终证据见整合记录 |





---

## 工作包 A 执行详情

- **文件修改与新增**：
  - 新增 `scripts/acceptance_reports.py`（共享报告与验证库）
  - 新增 `scripts/verify-product-journeys.py`（HXA-206 场景校验与报告生成）
  - 新增 `scripts/fixtures/acceptance/valid_fixture_manifest.json`（测试夹具）
  - 新增 `scripts/tests/test_product_journeys.py`（12 项单元测试）
  - 新增 `docs/evidence/development/hxa-206-preparation.md`（场景映射与契约文档）
  - 新增 `docs/evidence/development/small-model-batch-progress.md`（进度跟踪文档）
- **验证命令与结果**：
  - `python3 scripts/verify-product-journeys.py --help` -> Exit Code 0
  - `python3 -m unittest discover -s scripts/tests -p 'test_product_journeys.py'` -> 12 tests passed, Exit Code 0
  - `python3 scripts/verify-product-journeys.py --manifest scripts/fixtures/acceptance/valid_fixture_manifest.json --output build/test_206_out` -> Exit Code 0, 生成 `report.json` 与 `report.md`
- **边界说明**：
  - 仅包含 HXA-206 场景映射、校验脚本与测试夹具；不包含真实 Android 设备测试执行，不标记 HXA-206 整体完成。

---

## 工作包 B 执行详情

- **文件修改与新增**：
  - 新增 `scripts/verify-terminal-runtime.py`（终端专项场景与压力边界验证脚本）
  - 新增 `scripts/fixtures/acceptance/valid_terminal_fixture_manifest.json`（终端夹具）
  - 新增 `scripts/tests/test_terminal_reports.py`（8 项单元测试）
  - 新增 `docs/evidence/development/hxa-199-preparation.md`（终端证据与长稳边界契约文档）
- **验证命令与结果**：
  - `python3 scripts/verify-terminal-runtime.py --help` -> Exit Code 0
  - `python3 -m unittest discover -s scripts/tests -p 'test_terminal_reports.py'` -> 8 tests passed, Exit Code 0
  - `python3 scripts/verify-terminal-runtime.py --manifest scripts/fixtures/acceptance/valid_terminal_fixture_manifest.json --output build/test_199_out` -> Exit Code 0, 生成 `report.json` 与 `report.md`
- **边界说明**：
  - 双会话场景绑定 HXA-198 的 `ProotMultiSessionDeviceTest`，8 项合成夹具终端场景校验通过；5 项物理硬件与长稳压力测试显式列为待验并保持 `FIXTURE_INCOMPLETE` 报告状态；不触发两小时长任务，不关闭 HXA-199 整体验收。

---

## 工作包 C 执行详情

- **文件修改与新增**：
  - 修改 `docs/development/claude-handoff-207-191-206.md`（澄清 207/191 已交付合入，防止重复开发，链接到 small-model-handoff.md）
  - 修改 `docs/development/implementation-guide.md`（将交接 prompt 更新为通用当前模板，注明既有批次均已交付合入）
  - 新增 `docs/evidence/development/small-model-docs-review.md`（记录 status/roadmap 事实冲突与协调者最小修正建议，撰写面向用户的单终端帮助与双终端待交付草稿）
  - 更新 `docs/evidence/development/small-model-batch-progress.md`（更新进度跟踪）
- **验证命令与结果**：
  - 检查引用关联，被更新入口均有明确去向与历史说明
  - `./scripts/check-all.sh --source` -> Exit Code 0 (486 Markdown files, 194 HXA tasks, 31 ADRs, 1369 i18n keys)
  - `git diff --check` -> Clean
- **边界说明**：
  - 不擅自修改 status.md 与 roadmap.md 的完成状态或任务总数（保持 13 项未闭合义务），将建议整理并交协调者统驭。

---

## 工作包 D 执行详情

- **文件修改与新增**：
  - 新增 `docs/evidence/development/hxa-198-ui-preparation.md`（UI 设计、状态/操作表、核心接口需求及测试映射）
  - 更新 `docs/evidence/development/small-model-batch-progress.md`（记录批次总揽与各包状态）
- **核心与 UI 实现**：
  - `ProotTerminalHost` 支持最多 2 个 live 会话管理与 `CAPACITY_EXHAUSTED` 容量阻断。
  - `ProotTerminalEndpoint` 与 `ManualTerminalConnection` 实现单写互斥与只读观察模式降级。
  - `DeveloperManualTerminal` 实现双绑定仲裁、执行所有权维持与首会话先结算时的平滑提升。
  - `ManualTerminalViewModel` 与 `ManualTerminalScreen` 完成双会话多标签切换（零 Shell 重启）、新建与只读横幅展示。
  - 补充完整三语言字符串资源（base / en / zh-rCN）。
  - 新增 `ProotMultiSessionDeviceTest` 覆盖多会话隔离、容量超限拒绝、单写互斥与结算提权。
- **验证命令与结果**：
  - `./scripts/check-all.sh --source` -> Exit Code 0 (487 Markdown files, 31 ADRs, 632 源码, 1369 i18n keys)
  - `:runtime:proot-core:test :app:testDeveloperDebugUnitTest` -> 4/4 单元测试通过
  - `:app:compileDeveloperDebugAndroidTestKotlin` -> 编译通过
  - `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` -> 47/47 测试通过
  - `git diff --check` -> Clean
- **交付文档**：
  - 正式交付见[HXA-198完成记录](../../completion-records/HXA-198.md)。
