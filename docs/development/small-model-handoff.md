# 小模型执行指导：206 准备、199 报告、文档与 198 界面

更新：2026-09-20。所有者已授权将下列工作包交给 Claude Code 中的小模型执行。本文件把上一轮“仅规划”推进为**有明确范围的准备与实现切片**，不授权把整个 198／199／206 标记完成。总体优先级见[后续计划](next-work-plan.md)，完整验收要求仍以[206](tasks/HXA-206.md)、[198](tasks/HXA-198.md)、[199](tasks/HXA-199.md)为准。

## 1. 当前基础与职责

基线 main `ede73fb9` 包含已交付的 191、197、207、211 及 196 当前实现；CI 实现提交 `eeb5f04d` 的[完整远端门禁](https://github.com/dollarser/helix-agent/actions/runs/35516975534)通过，`ede73fb9` 的[文档门禁](https://github.com/dollarser/helix-agent/actions/runs/35517466739)通过。后者不是另一次 Android 验证。运行时仍应记录实际 HEAD 和差异，不用历史状态覆盖新失败。

- **小模型**：完成 A、B、C 及 D 的可执行部分，具名本地提交，提供可复核结果。一个后台任务、一个工作树，顺序推进；不用四个并行任务争抢共同脚本。
- **协调者**：审查报告语义、核心授权与恢复边界，提供 198 已验证的核心接口提交，复核 Android 变更与最终验收，负责 main 整合和推送。
- 196 真机 HOME/锁屏/Doze 本次仍跳过但未通过；125／190 真实账号、199 OEM/热压/16 KiB 长稳和发行决策不在本批自动执行范围。
- 198 核心实现尚未交付。D 的设计与映射可立即完成；生产 UI 实现必须等到本节后述的核心交接条件满足。不能用页面内的假会话列表补出第二套 Runtime。

## 2. 启动与执行顺序

先读 `AGENTS.md`、README、status、roadmap、implementation-guide、verification-matrix、当前工作包对应任务和 ADR。运行 `git status --short`、`git rev-parse HEAD`、`git log -5 --oneline`；工作树已有非本人变更时保留并报告，不 reset、stash 或清目录。存在 `.codegraph/` 时按 AGENTS 先使用 CodeGraph 定位代码，无索引则跳过，不自行索引。

依次执行：**A → B → C → D 设计/映射**。每个包验证通过并具名提交后自动进入下一包，不把一次提交或一次模型回复作为停工条件。准备切片不是多个 HXA 同时进入集成验收，也不改变一个执行者只推进一个 HXA 的规则。

A/B/C 不改 Android 生产代码，不启动设备或真实账号。它们先执行源码门禁和各自脚本测试；核对最新完整主机及相关设备基线，发现真实强制失败立即报告，不以准备工作冒称已满足整个 HXA 门禁。进入 D 的实际 Android 实现前，必须满足第 6 节核心交接条件及任务的完整主机/设备基线。

## 3. A：206 场景映射与结果统计

### 交付物和文件范围

允许新增 `scripts/verify-product-journeys.py`、一个简单共享 Python 模块 `scripts/acceptance_reports.py`、`scripts/tests/test_product_journeys.py`、`scripts/fixtures/acceptance/` 下的合成输入，以及 `docs/evidence/development/hxa-206-preparation.md`。已有适用解析代码应优先复用，不引入新依赖或通用插件框架。源码门禁无需为本切片修改，新增测试用明确命令执行。

先逐项列出 206 的“场景 → 实际源文件/测试类/方法 → 已有证据 → 缺失断言 → 将来执行入口”。只使用确实存在的符号；不存在的类标 planned，不伪造可执行命令。至少覆盖：

| 场景组 | 必须保留的判断 |
| --- | --- |
| 读取资料、修改文件、打开产物、返回来源 | 验证最终结果可使用，不能只有工具返回成功 |
| ENABLED/DISABLED、三预设、CUSTOM | ALLOW 无多余卡，ASK 正确审批，DENY/DISABLED 无副作用；显式 rm 规则不扩大 |
| 取消、真实重启对账、能力修复后继续 | 取消不自动继续；未知副作用不重放；记录副作用次数与执行身份 |
| Plan 审阅、搜索/主题、MCP 添加到禁用 | 区分纯应用入口与 Agent 工具，纯应用路径不产生模型调用或扩大授权 |
| JSONL 导出 | 真实会话入口、系统选择器、独立回读；取消/失败与大内容引用不遗漏 |
| Git R1 | 只读 status/diff 的恶意配置/filter/transport 验收仍缺什么；不新增 Git 写操作或替代最终复核 |

优先阅读现有 `TaskJourneyDeviceTest`、`RecoveryJourneyDeviceTest`、`ExtensionJourneyDeviceTest`、`SessionExportJourneyDeviceTest` 及各完成记录。历史通过只能写引用，不能改造成当前运行结果。

### 最小报告契约

实现的是**已有结果的校验/汇总**，本切片不制造新的设备调度器或应用状态机。与已有 owned runner 的输出做显式适配；在说明中区分“可导入的真实格式”和“合成 fixture 格式”。建议固定入口：

```bash
python3 scripts/verify-product-journeys.py --help
python3 scripts/verify-product-journeys.py --manifest <manifest.json> --output <new-output-directory>
python3 -m unittest discover -s scripts/tests -p 'test_product_journeys.py'
```

路径是调用者参数，不能提交机器绝对路径。输出为易读 Markdown 与可机器解析 JSON；版本从 1 开始。具体字段可以按既有 runner 调整，但必须满足以下语义：

- 批次身份：schema version、HXA/范围、完整提交 SHA、fixture 或 real 标记、API/flavor、主/测试 APK SHA-256；未运行时对应值缺失并标 incomplete，不编造摘要。
- 场景身份：稳定 scene ID、具体测试类/方法、结果来源引用；唯一键至少包含 scene/API/flavor，重复结果不重复计数。
- 测试结果：expected、executed、passed、failed、skipped 非负且一致；缺失类、空结果、零执行、错误退出码、结果文件缺失、重复或冲突记录不能变绿。解析原始结果，不只信任调用者给出的 PASS。
- 旅程指标：用户步骤、重复审批、产物可打开性、恢复副作用次数；没有测量就写 null/未测，绝不填 0 当作没有问题。
- 设备生命周期：真实设备结果引用 owned runner 的 owner/closed 记录。缺少结束证明就是证据不完整，不是资源已释放。
- 跳过：必须附条件与原因，缺失必跑场景不可借 optional/skip 变成通过；optional 由已审查的场景要求决定，不能由待验结果自行决定。
- 制品一致性：每个证据组对应明确 SHA/API/flavor，拒绝未经显式分组的混合源码/制品；不同版本可并列报告，不合算成同版整体验收。
- 输入有界：先检查文件大小、条目数量和路径范围再读入。可采用 10 MiB/10000 项的报告上限；超限明确失败。引用不能逃出调用者指定证据目录，不执行输入中的命令、不请求网络。
- 输出缺失、解析错误和未知版本不得 catch-all 成功。建议退出码：0=本次声明范围有效且满足要求，1=实际失败，2=证据不完整或输入错误；即使 fixture 自检 exit 0，也必须写 `fixture_only`，不能生成产品验收完成声明。

用少量直观函数和标准库实现。不要引入签名体系、事件总线、任务数据库或可执行配置 DSL。

### 验收

单测必须覆盖有效输入、失败、跳过与缺失原因、必跑场景遗漏、零执行、重复、计数不符、版本/SHA 混用、owner 未关闭、坏 JSON、路径逃逸、超限和 fixture 不冒充 real。测试使用临时目录和合成内容，不读取开发者真实会话。若现有真实产物可合法复用，另做只读导入并注明原提交；没有真实输入不影响解析器切片交付，但整体 206 仍未验收。

## 4. B：199 证据汇总与报告准备

A 的报告契约和测试通过后再做 B，复用同一个共享模块，不并行复制两套解析规则。

允许新增 `scripts/verify-terminal-runtime.py`、`scripts/tests/test_terminal_reports.py`、本包 fixture 和 `docs/evidence/development/hxa-199-preparation.md`。该脚本当前只实现 report/check 部分，owned-device 调度接线仍为后续集成职责，明确写在 help 和交付报告中。

映射 195～198 场景：实时日志与停止、后台回收、单终端、双会话、切页重连、主/Runtime 死亡、旧数据升级和 consumer 排除。记录首包、取消延迟、冷启动、包体及 PID/FD/线程变化的单位、采样窗口和来源；没有实际测量不估算通过值。

必须把 30 分钟 detach idle、两小时完整租期、运行中到期、OEM/Doze/热压和真实 16 KiB 分开列为待验，不用 197 的短测或 ELF 静态对齐顶替。198 尚未实现时，双会话场景报告 incomplete；不得删除场景换取汇总 PASS。

执行 `python3 scripts/verify-terminal-runtime.py --help` 和 `python3 -m unittest discover -s scripts/tests -p 'test_terminal_reports.py'`；加入跨版本、缺少双会话、短时间冒充长租期、页大小缺证据等反例。B 完成只表示报告基础就绪，不关闭 199、不触发两小时任务、不自动补做所有者已跳过的 196 真机项。

## 5. C：文档收敛与帮助准备

允许修改 `docs/development/implementation-guide.md`、`docs/development/claude-handoff-207-191-206.md`，新增 `docs/evidence/development/small-model-docs-review.md`。其他现有文档先列出建议和引用，不扩大文件所有权。

- 清理仍要求重新开发 207/191 的过期交接模板，替换成指向当前任务/本指导的通用入口；保留有效历史证据，删除前检查全部引用。
- 列出 status/roadmap/完成记录的事实冲突及最小修正建议，交给协调者统一更新。不要改它们的完成状态或任务总数。
- 在评审文档中给出面向用户的终端帮助草稿：进入/退出、重连/重新创建、停止、输出缺口、租期与后台限制。单终端按当前实现写，双终端明确待交付，不写成当前可用。
- 不修改 accepted ADR、历史测试数字、授权语义或发行承诺；不增加 Agent 工具。

执行 `./scripts/check-all.sh --source`、搜索被替换入口的全部引用及 `git diff --check`。不为纯文档修改重复启动 Android 构建。

## 6. D：198 UI 设计、测试映射与有条件实现

**现在可交付** `docs/evidence/development/hxa-198-ui-preparation.md`：核对下列入口和单会话假设，画出状态/操作表，列出需要协调者提供的核心接口与测试。不得添加占位生产代码、未接线按钮或伪装双会话的本地集合。

- `app/src/main/kotlin/com/helix/app/terminal/ManualTerminal.kt`
- developer 下 `DeveloperManualTerminal.kt`、`ManualTerminalViewModel.kt`、`ManualTerminalConnection.kt`、`ManualTerminalScreen.kt`、`ManualTerminalViewport.kt`
- 对照 `PtySessionClient`、`PtySessionProtocol`、`PtySessionStore` 和 `TerminalHostJourneyDeviceTest`，仅阅读核心模块。

**实际 UI 实现的依赖交接条件**：协调者提供具名核心提交、验证命令/结果和明确入口，证明最多两 live 会话、创建失败释放名额、每会话单写连接、generation 失效、按身份关闭、Agent admission 和取消/恢复行为。仅有 ADR 或 proposed 接口草案不满足条件；小模型不能自行宣布该条件成立。

条件满足后，另一次续接在包含核心提交的干净工作树完成：列表/选择、标题和状态、切换保留原 shell、观察端只读、失联保留历史、明确重新创建、第三会话拒绝反馈；禁止把 sessionId 当凭据或按复用 PID 操作。允许范围是协调者点名的 terminal UI/ViewModel 文件、对应测试及三语言新增文案；不自行修改 Runtime/IPC/storage/policy/Goal。

测试至少包含会话身份和输出不串线、关闭 A 不影响 B、快速切换不重建 shell、只读无写入口、失败恢复、窄屏/大字体和主题。运行 P1/P3、任务相关 G2、独占 API29/36 developer UI 旅程及 consumer 排除；新增类必须非零执行。198 整体仍需协调者完成核心/资源矩阵和最终复核。

若本轮没有核心交接，完成设计文档后把 D 实现登记为 `WAITING_CORE`，记录具体缺失项并结束后台任务，不空转、不自动改核心、也不丢弃其他已完成工作包。

## 7. 验证、资源与提交纪律

```bash
./scripts/check-all.sh --source
python3 -m unittest discover -s scripts/tests -p 'test_product_journeys.py'
python3 -m unittest discover -s scripts/tests -p 'test_terminal_reports.py'
git diff --check
```

尚未创建对应测试时不能先跑一个“0 tests”作为验收。每包只执行相关命令；最后合并跑两套测试及源码门禁。阶段复核是防止错误传播，不要求为每个小改动重复全量构建。

所有重型主机/设备运行使用：

```bash
python3 scripts/debug/2026-09-18/with-host-slot.py -- <one-command-and-arguments>
```

占用 slot 的命令不得再嵌套获取同一锁。设备必须新建独占进程、拒绝已有 serial、finally 清理自己的进程；不得停止别人的 Gradle、模拟器或 Claude。调试脚本先保存到 `scripts/debug/YYYY-MM-DD/`，生成物置于 ignored `build/`。准备阶段不需要 ADB、网络业务账号或新增 Maven/Python 依赖。

每包具名 `git add`，审查 cached diff 后本地提交，不 `git add .`，不 push、合并、删除工作树或改其他分支。不复制本机 secret、会话原文、签名材料、RootFS 或绝对机器路径进提交。不自行创建 Goal、新模型任务、轮询自动化或子 Agent。

失败先定位并修复本包范围内原因；涉及授权、跨进程身份、预算、持久结算、数据库迁移或发行决定时，保存证据并交协调者处理。不要为了继续而放宽测试或恢复旧行为。文件超出允许范围时提交具体变更建议，不顺手修改。

## 8. 交接结果与协调者复核

每包写明：实际改动文件、提交 SHA、执行命令/退出码/非零测试数、fixture/真实输入边界、未覆盖项和下一包依赖。进度统一写入 `docs/evidence/development/small-model-batch-progress.md`（本批创建），不要分散创建新的状态系统。

结束时报告 A/B/C/D 各自状态：`READY_FOR_REVIEW`、`WAITING_CORE` 或 `BLOCKED`，并列出剩余具体条件。小模型自测通过不等于已合入 main；协调者按实际 diff 复核、必要时补测，才更新 status/roadmap 或完成记录。

可直接用于后台启动的 Prompt：

```text
在当前独立工作树执行 docs/development/small-model-handoff.md 的全部小模型工作包。
这份文档已获所有者授权：按 A→B→C→D 设计/映射顺序持续执行，每包自测并具名本地提交。
先读 AGENTS/README/status/roadmap/相关任务和 ADR，遵循文件所有权、报告契约与验收边界。
你负责准备脚本、报告、文档和 D 的准备，不负责 Runtime 核心、授权、数据库迁移或最终验收结论。
D 的实际 UI 接线等待协调者给出已验证核心提交；没有该提交则完成 D 设计，明确 WAITING_CORE。
不得通过猜测核心接口、减少场景或将 fixture 当成真实验收来绕过依赖。
不要 push/merge/删除分支，不创建其他模型任务，不启动账号调用或物理设备长稳。
记录进度到文档指定位置。A/B/C 中发生局部错误先修复；真实跨范围阻塞写明证据。
不要只回复计划，也不要完成 A 后提前结束；完成所有可执行工作包后输出逐包交接报告。
```
