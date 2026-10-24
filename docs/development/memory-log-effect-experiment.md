# Memory 选择与日志诊断：有界效果实验

日期：2026-10-24。基线为阶段 1 `ac6d8012`；本轮先复核语义边界，再开展 HXA-293-B 与既有日志诊断的固定样本实验。不更改默认 Memory 策略，不采用知识库 UI 提案，不新增检索/同步平台。

## 预先冻结的条件

- 模型只用现有测试账号对应的内置模型与原客户端鉴权。凭据从既有本地私有、忽略且未跟踪的 profile 经一次性 loopback 桥传入独立包；不记录凭据/请求头。无新账号、提供商或付费启用。
- 四个 Memory 样本各 baseline/index 一次，共八个任务：摘要第 1000 字后的关键事实；多主题与过期事实；另一项目不可访问；当前会话没有外部读取权限。相同任务、内容/版本、模型、参数和授权，条件间不共享模型回答。
- baseline 为实际生产完整 SYSTEM/目录与每域前 1000 UTF-16 字符。index 为仅替换已获准 Memory 内容块的诊断性出站对照：逻辑路径、类型、版本 hash 与受控读取提示，不预读/注入主题答案。其余必要指令、当前用户任务、schema、call/result 配对保留。
- index 属于实验传输投影，不冒充生产 ContextCompiler 已采用该策略；保存 original/sent 与差异。小窗口、截断、跨 scope、取消/失败先做确定性校验，不能用模拟结果证明模型质量。
- 日志两任务：完整生产上下文与最小上下文诊断对照。每个独立原始操作只执行一次合成 `stderr + exit 7`，模型读取原结果/日志；不复用另一会话的执行身份，不让先前答案污染另一条件。最小上下文不作为生产验收或默认方案。
- 保留自然工具选择；不写下一步工具、不自动批准、不注入答案，不把目录限制为目标三个工具。使用相同、明确的只读本机实验权限；不可用/未配置的外部服务不因实验启用。

## 预算与停止

十个任务每条件仅一次，不追加“试到成功”的轮次。每任务最多四次实际生成请求、90 秒、每次最多 1024 输出 token；整个效果批次最多四十次生成请求、十五分钟和 2 MiB 出站请求正文。账号准备的短合成连通调用另外计数并披露。失败、超时、预算未运行分别记录，不能从分母删除。首次服务/鉴权不满足时停止真实模型阶段，继续离线验证；不得绕过网关或更换模型凑通过。

模型最终任务正确性与 JUnit/Turn 完成分开统计。记录实际出站正文/可用 schema、发现查询与候选、实际工具、读取的正文/日志、错误归因、原操作提交数、实际 usage（若服务提供）、正文 UTF-8 字节与估算成本、整个任务耗时。只输出合成资料；日志视为数据，不是新指令。单次四对 Memory 样本不足以推断普遍收益或稳定性，不凭其自动改生产默认。

## 阶段 1 语义边界复核

Core 已拥有实际 driver、原执行身份、取消、round 与闭合持久端口；App 的提交准备、Session/Goal 下一工作选择与平台/Room 适配不因此成为跨平台产品。Room 必须在真实事务内重验事实；纯 JVM 内存 fixture 不代替真实 Room。

本轮发现一处确定残留：`GoalContinuationDriver.eligible()` 经 `GoalRoundAllowance.blockIfExhausted()` 间接写 Goal，终态 hook 因而并非纯本地交接。修正将资格查询改为只读，并在 Room 终态准备中对绑定 Goal 和本轮待结算 Goal edit 统一记录轮次耗尽；准入处的同一复检保留。额度、自动激活、权限不变。`GoalBlockerResolution` 是实际应用修复命令，按职责从 chat 移入 application/agent，无旧包兼容转发。

此前 C 记录关于“hook 纯本地”的结论需以上述更正限定；原实际通过的测试结果不撤销，也不据其隐藏该残留。新增真实 Room 回归覆盖查询不写、结算写、重复收尾与未绑定的待编辑 Goal。该修正验证与效果结果将在本记录续写。

## 当前状态

- 保留原有指南两行和未采纳原型。
- 私有测试 profile 存在；未输出其内容。无凭据服务探测为 HTTP 403，只证明可达，不证明登录/模型可用。
- Goal 边界修正主机检查通过；独立 Advanced/API 36 模拟器 33 项通过、无跳过，实例已关闭。实验静态检查、Developer AndroidTest 编译和 Core 单元检查通过；固定夹具/架构检查共 12 项通过。
- 实际账号准备一次返回 HTTP 530，未进入任何生成调用；十个效果任务全部未运行，按预设停止条件不重试、不换服务。没有可报告的成功率、上下文成本或模型时延差异，不声称策略改善或日志失败已解决。

## 本轮结果与可继续条件

Goal 语义修正已单独提交为 `a9ac31c7`。Core 仍拥有完整 Turn 的运行、终态与持久端口；App 继续拥有提交准备、下一工作命令及实际 Room/平台适配。此次修正的是资格查询暗含持久写入，不把目录移动本身算作宿主中立化，也不删除正常 Queue/Goal 后续命令。后续命令仍须重新走同一准入。

账号准备使用原测试 profile 与现有账号客户端，明确收到 `AccountRequestFailure: HTTP 530`。该响应只能定位到账号访问失败，尚不足以判断网关、上游或账号服务的内部原因；不是模型任务失败、权限拒绝或工具发现失败。唯一准备调用未完成，生成请求为零；8 个 Memory 与 2 个日志任务均未运行，不能计算质量胜率或宣布候选优于 baseline。模拟器退出码为零且已关闭；准备测试的 finally 执行退出路径，但未据此宣称服务端已签发或撤销令牌。

本地证据集中于 `build/memory-log-experiment-2026-10-24/`，包括冻结 APK 身份、准备失败、关闭记录与 `effect-outcome.json`；凭据桥只用一次，未保存凭据和请求头。恢复服务后只需使用同一受控测试 profile 和冻结样本重做一轮有界批次，不需要新 Provider、权限或生产默认改动。若代码或模型条件改变，建立新的批次记录，不覆盖本次失败。

实验入口为 Developer AndroidTest 中 `MemoryLogEffectDeviceTest`（仅明确 opt-in 的独立 validation 包）与 `MemoryLogProjectionDeviceTest`；固定任务/预算在 `scripts/fixtures/memory-log-experiment-v1.json`。传输 ablation 保留 original/sent 以避免误认生产策略。临时准备/收取脚本位于忽略目录，证据仅收取合成实验结果，不复制账号存储。

## 离线与模拟器检查

- `python3 -m unittest scripts.tests.test_memory_log_experiment scripts.tests.test_agent_layer_boundaries`：12 项通过（固定任务/预算 4 项，依赖边界 8 项）。
- `spotlessKotlinApply detekt :app:compileDeveloperDebugAndroidTestKotlin :core:workspace:test :core:agent:test`：通过；前两次静态/导入失败保留在本地日志，修正后重跑，不跳过规则。
- 独立 Developer/API 36 ARM64 的 `MemoryLogProjectionDeviceTest`：5 项通过，无跳过；覆盖必要约束/来源/截断事实、无授权与其他 scope、重复标记、原字节保持与请求次数上限、取消不重发。其 Wire 为 fake，仅证明投影/传输边界。
- 真实账号准备：1 项执行失败（HTTP 530），不是设备/模型效果通过。独立实例关闭；十项效果任务未运行。
- 本轮没有真机、Release/R8、新发布或推送；没有以此前 Release 通过替代当前补丁验收。
- 最终 `./scripts/check-all.sh --debug`：退出 0。302 项主机夹具通过；文档/40 份 ADR/国际化/秘密扫描、Spotless/Detekt/Debug Lint、全部 JVM 测试、两渠道 Debug 构建、42 份依赖锁及变体/CLI 边界通过。App Standard 1467、Advanced 1629 项各无失败、保留原有 5 项跳过；Core Agent 354、Workspace 112 项均无失败或跳过。补记文档后 `./scripts/check-docs.sh` 再通过。
