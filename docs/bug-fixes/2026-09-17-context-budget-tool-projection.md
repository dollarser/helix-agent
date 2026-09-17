# Bug Fix: 工具协议重复回显与预算停止后的上下文保留

Status: fixed
Date: 2026-09-17
Related HXA: HXA-099, HXA-174, HXA-176
Affected modules: app（agent、chat、runcontrol、UI），无数据库迁移

## Problem

聊天投影曾把非 USER 的持久消息一并作为助手气泡，导致 TOOL 的结果信封显示成长段转义 JSON。上下文预检包含消息/工具协议与图片余量，Turn/Goal 准入却只按正文与 schema 字节估算。单次输入、输出和累计额度又共享模糊预算错误。普通失败重试使用原输入所在 Turn 的边界，可能不把该失败 Turn 内已经结算的工具结果送到新的请求。

## Impact

用户会看到重复的机器协议；模型在每轮反复消费大结果。人工设置过小输入上限后，用户难以区分模型窗口、自己的输入上限、输出上限与累计费用额度。错误地把执行成功理解为任务验证通过、或者重新要求已完成的操作，会放大恢复摩擦。

现场历史只证明发生过 `TOKEN_BUDGET_LIMIT` 与 `CONTEXT_WINDOW_LIMIT`；旧记录没有完整的当时预算快照与压缩失败原因，不能据此断言某一次停止必然由哪个维度触发。本次新增诊断用于消除未来同类证据缺口，不回填猜测的历史。

## Root cause

- 持久消息角色/种类与 UI 消息类型边界不完整。
- 对工具结果没有按需读取的模型投影，原始 QuickJS 字符串化结果会形成额外转义层。
- 三个预算入口各自估算；输出缺 usage 时也未计入工具参数字节。
- 预算停止复用普通“重试原输入”，与“继续已完成的工作”语义不同。
- 缺少关联到 modelCallId 的预算配置、实际获准请求和压缩结算诊断。

## Fix and invariants

1. 只把 USER/ASSISTANT 正文投影为聊天气泡，隐藏协议/checkpoint；真实正文中的 JSON 不受影响。模型调用/结果配对与持久原文保留。
2. 已知 QuickJS envelope 仅解码一层。超过 16 KiB 的成功输出，在会话已启用读取工具时生成带截断标记、短预览、稳定引用与偏移的投影；原结果保存，`tool.result.read` 只读同会话已验证成功结果，每页 2048 个 code point，不执行原工具。
3. 输入估算统一，Provider usage 优先，缺失输出计入工具参数；明确区分预算维度。诊断同时保留原始估算、校准后的准入输入与上下文窗口，防止“估算未超限但实际输入校准已超限”被误读。摘要请求有独立获准记录。
4. 推荐设置不静默覆盖已有自定义值；高级选项可手动调整，保存只影响新 Turn。总额度、调用/轮数、工具资源和 Goal 持久预算仍强制。
5. 明确继续仅适用于最新的普通预算失败 Turn，且会话工具已结算。新 Turn 保留历史并发送继续提示，附件复核仍生效，不登记为模型可借用的新 Goal 用户授权。Goal 沿原独立路径处理。
6. 每条预算审计为有版本的有界数值元数据，不携带正文、凭据或外部地址。旧记录缺诊断时不伪造数据。

## Alternatives considered

移除模型工具结果会损坏任务事实链；直接截断 JSON 会产生不可解析或不可补足的结果；放开所有预算会失去端侧资源与费用边界；自动扩额/重启会模糊用户意图。均未采用。

## Regression verification

隔离分支 `codex/context-budget-ux` 基于 `73e574f6`。主分支并行 HXA-194 工作未纳入此修复；测试使用自建临时 Room/内容目录、离线 WireClient 与专属模拟器。

- `./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest`：consumer 584 项（580 passed、4 条件跳过），developer 618 项（614 passed、4 条件跳过）。覆盖统一估算、预算原因、整数边界、缺 usage 的工具参数、Unicode 多页无损恢复与未知格式保留。
- `./gradlew :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`：双 flavor 应用与测试 APK 构建通过。
- 定向设备类：`ToolResultReadDeviceTest`、`ChatScreenProjectionDeviceTest`、`ChatServiceAttachmentRetryDeviceTest`、`RunControlSettingsUiDeviceTest`、`LongTurnCompactionDeviceTest`、`ContextCompactionDeviceTest`。覆盖读取归属/取消/期限/缺失/重开、未知副作用阻止继续、真实 ChatService 保留历史出站、附件复核、设置保存与重建、工具配对、摘要失败和 Goal 用量。
- 最终设备结果：API29 consumer **30/30**、API36 developer **30/30**，无跳过；专属串号 `emulator-5566` / `emulator-5564`，只启动和关闭本次拥有的进程，`closed.json` 均记录 exit 0。使用 `scripts/debug/2026-09-09/run-owned-emulator.py`，分别指定 `Helix_API_29` / `Helix_API_36_test`、对应 flavor 的 app/test APK、上述六个 `--classes`、正确包名下的 `HelixAndroidJUnitRunner` 与 `--timeout 300`；证据为 `build/context-budget/device-api29-final/` 和 `device-api36-final/`。
- `./scripts/check-all.sh --source` 与 `git diff --check` 通过。全量 `./gradlew spotlessCheck detekt lintDebug lintConsumerDebug lintDeveloperDebug --continue` 未通过：分支基线的 6 个未修改文件共 12 条 Detekt，5 个未修改文件存在格式问题，`CommandResultDetailScreen.streamSection` 的 Compose 命名检查在两个 flavor 各失败 1 项。没有新增 lint baseline、跳过测试或关闭规则；本次所改文件已完成格式检查和问题修复。
- 首轮新增读取夹具错误地混用工具行主键与 call ID，及直接将 CREATED 改成 FAILED；修正为合法外键和 BUILDING_CONTEXT→FAILED 后重跑，未放宽生产状态机。首次设备命令使用了错误的长 Turn 测试包名，也已纠正。
- 继承的 `ArtifactVisionImageSourceTest` fake DAO 缺 `listByTurn`，导致初始 app 测试无法编译；仅补齐该夹具。PRoot 资产使用忽略目录内的已有本地制品并通过仓库校验，不提交制品。

私有日志位于忽略的 `build/context-budget/`；统计脚本为 `scripts/debug/2026-09-17/summarize-context-budget.py`，同时比较继承问题文件与分支起点的字节一致性。未运行或宣称完整产品 `--all` / 真机 P0 通过。

## Residual risk

- 本分支基线的 HXA-194 文件已有 Detekt/Spotless/Compose 命名问题，不能宣称全量门禁通过；这些文件保持与分支起点一致，不在本次用户授权切片中重做并行工作。完整主机/真机 P0 收尾仍查[实施状态](../development/status.md)。
- 未安装覆盖用户真机，未调用付费模型，未做 OEM/Doze/长稳验收。模拟器与离线生产请求测试不是实际模型任务成功证明。
- 继续是有界新 Turn，不是同 Turn 指令 checkpoint，也不保证模型语义层绝不重复动作。输入估算仍有 tokenizer/视觉差异；用户已关闭自动压缩或设定过小容量时仍可能停止。
- 原工具输出最大 8 MiB；分页读取每次校验原内容，不是流式随机读取。禁用读取工具后已存在的引用可能无法继续读取；不绕过用户禁用。

## Related records

- [模型数据与预算边界决定](../adr/agent/006-model-data-budget-boundaries.md)
- [模式与上下文说明](../architecture/agent-modes.md)
- [上下文压缩](../adr/agent/002-context-compaction.md)
- [HXA-176](../completion-records/HXA-176.md)
