# HXA-236：统一观察上下文、可信进展与执行占用修复

日期：2026-10-01。入场基线 `main@4d449fb9`；本记录承接[基础等待阶段](hxa236-job-observation-2026-10-01.md)，不重写其历史失败或未交付状态。所有者明确要求完成 J1 剩余计划，并调查 v0.0.4 的 JS/Bash `EXECUTION_BUSY`。本次无指定设备、真实账号或模型使用授权；设备与真实服务为 **not requested**。

## 生产接线

### 同一请求中的观察事实

`DetachedJobObservationStore` 在原 QUERY/collect 路径记录经过完整身份验证的状态。`JobObservationJournal` 复用 audit_events，不引入任务表、执行状态机或第二聊天历史；仅状态/revision/结算变化写一条不可变事实，重复轮询不刷新记录时间。终态收据与观察记录同事务；晚到 RUNNING 不覆盖终态，已按原重启证据处理的丢失记录保留 UNKNOWN，不伪造成功或 Runtime 终态。

`JobObservationContext` 从当前会话读取最多 64 条已记录事实。宿主通过当前请求可见的原子工具绑定、禁用过滤器，以及 Dispatcher 现有 capability/session permission/policy 和原执行归属检查筛选；不提示新审批、不启动 Runtime 查询、不从模型名称或任意远端元数据授予读取权。fork 不继承原执行控制资格。

纯 Core `ContextCompiler.withJobObservations` 选取每个执行最新的相关快照，最多 8 项/8 KiB，并受剩余输入预算及模型消息数量限制。记录顺序以数据库插入顺序为准，不因系统时钟回拨选错旧状态。非终态的旧时间或未来时间明确标 stale；每条带 revision、观察时间和 `audit:` resultRef。该引用指向状态事实，不代表已导入的产物。

实际初始/后继请求都接入同一 `ChatRequestAssembler`。自动观察是在当前已获准请求中的数据投影，不启动新 ModelCall、不自动收取、不延长 lease。投影重复编译是替换而非叠加，用户输入和原工具结果保留；输入量由同一请求估计和预算计算。实际发送的快照引用通过原 ModelCall 审计记录，诊断只接受最多八个严格格式的引用，不记录脚本或完整产物。

### 可信类型的无进展判断

Dispatcher 仅对可信宿主创建的实际 `JobObservationExecutor`、成功且未截断的结果写 `jobObservation` 证据；同名普通工具和自报审计字段不能得到该待遇。App 读取对应 ToolCall 的持久 Dispatcher 审计，校验调用归属与结果类型，移除 `LIVE_OBSERVATIONS` 名称白名单。

指纹排除观测时间和耗时，时间变化不等于任务进展。完整、最新且健康的 RUNNING/WAIT_EXPIRED 重复观察仍可告警但不因等待本身误停任务；原执行 lease、Turn/Goal 预算与取消仍生效。重复来源不可用、终态查询、未知/过时记录和真实重复失败不获得该例外，沿用原有界 STOP。模型继续自主选择等待、查询、收取和其他获准工作。

### EXECUTION_BUSY

具体旧代码路径、修复与用户侧边界见[缺陷记录](../../bug-fixes/2026-10-01-execution-busy-pre-submit.md)。修复确定未发送 START 的终端启动失败遗留占用，不放宽真实执行、未知副作用或手动终端的互斥。

## 回归与联合覆盖

| 范围 | 主机/设备入口 | 本轮边界 |
| --- | --- | --- |
| 原身份、共享查询、取消/迟到回包、容量与唯一审计 | 原 JobObservationService/Dispatch/Isolation/Limits 测试；本轮追加实际 executor 审计与无查询的上下文准入测试 | 真实 Dispatcher/Scheduler fixture，不等于 Binder 设备运行 |
| 上下文容量、去重、时钟倒退、重编译与消息上限 | JobContextCompilerTest | 纯 Core，不依赖 Android/Room |
| 时间戳不当进展、等待与停滞、同名工具伪装、字段净化 | JobObservationEvidenceTest、JobProgressIntegrationTest、原 DurableToolLoopProgressTest | 两渠道共享场景不重复相加 |
| v0.0.4 保留占用反例、确定未提交与未知提交、第二终端/其他 owner、写入后抛错 | TerminalStartTransactionTest、原 ExecutionOwnership/NativeJavascriptOwnership 测试 | 复现旧本地算法，不声称运行旧 APK 或还原用户手机 |
| 真 Room 事实去重、重开、fork 隔离、终态与处置不回退 | JobObservationJournalDeviceTest、JobObservationReceiptDeviceTest | 新增五项设备用例仅编译 |
| Provider→Core→Dispatcher→Runtime→等待→收取→产物哈希及请求快照审计 | 扩展 JobAwaitLoopDeviceTest | 保留原端到端断言，仅编译；不把脚本模型当真实模型 |
| 换目录/取消/恢复/插件撤销及原输出导入 | 原 LinuxJobObservationPortTest、JobObservationIsolationTest、DetachedJobControlDeviceTest 等对应边界，新增上下文可见绑定撤销反例 | 主机覆盖与实际联合设备旅程分别记账，完整进程矩阵/OEM 仍需指定条件 |

本轮没有执行设备或付费账号，没有削弱原权限/副作用/超时断言。旧名称白名单测试改为证明普通同名工具不获例外，原真正重复观察 STOP 仍保留。中间构建、格式与新回归失败保存在 `build/hxa236-followup/`：缺字段解析、诊断白名单接线、HelixStorage 测试关闭方式和类型/格式问题已逐项修正，不隐藏旧失败。

## 验证记录

定向复验 `focused-r4.log` 已退出 0，包含 Framework/Core/双渠道 App 单测、两渠道 AndroidTest Kotlin 编译；368 tasks，21 executed / 347 up-to-date。这是中间候选验证；后续追加处置快照保护和对应测试，完整最终结果另记，不自动继承。

最终执行 `scripts/debug/2026-10-01/validate-hxa236-followup.sh` 已退出 0：989 tasks，128 executed / 861 up-to-date；包含全仓 test、detekt、spotlessCheck、双渠道 lint/Debug APK/AndroidTest APK。原完整整合的复杂度/格式失败保留为 `host-r1.log`，按原门槛拆分函数后复验，没有放宽规则。增量 UP-TO-DATE 不等于全量强制重跑，设备编译不等于设备通过。

新增独立 JVM 场景 29 项通过：Core 上下文 7、证据编解码/进展 5、真实 Dispatcher 联合新增 2（套件共 8）、App 进展/过滤 6、终端启动事务 9。均无失败/错误/跳过；App 共享两渠道场景只计一次。既有条件性跳过不改成通过。新增五项 Room 设备用例及扩展原任务旅程均仅编译。

主机前后源码 provenance 清单逐字一致，sourceManifestSha 为 `dfb37396d1e6105fa06bcfbf90446f559a19641cdc9fc025f37389689a0462aa`。精确源码范围与原始清单见 `build/hxa236-followup/source-before.json` / `source-after.json`；文档和调试脚本不在这一源码指纹范围内。

`summarize-hxa236-followup.py` 校验定向 XML 并保存报告指纹、APK 大小/SHA 和 terminal.xml 三语言同名键一致性。Consumer Debug SHA-256 为 `2a0d2eec37146824717f25417ca8ba6abdc2e5aeb9926464d025eb126c9db6e5`；Developer Debug 为 `26269ae5834f8d427f9b7e46604f61d702446527fccfe6c161d38ec976c56ecd`。两个测试 APK 的指纹也在 `build/hxa236-followup/summary.json`。随后 `check-all.sh --source` 与 `--artifacts`、`git diff --check` 联合退出 0：689 Markdown、220 HXA、35 现行 ADR、多语言与秘密扫描通过；双渠道组件/进程/UID/制品边界及 Consumer 订阅排除检查通过。terminal.xml 的 22 个键三语言一致，含此次两项新提示。最后文档格式与暂存检查另行复验；早期 Bug Fix 文档模板/章节顺序失败已修正，检查器未放宽。

## 剩余验收与范围

本地衔接及完整适用主机验证已交付，HXA-236 转收尾验收；设备端仍需指定版本、设备/API/渠道执行真实存储、后台任务、取消/恢复和用户操作旅程，真实模型应验证正确等待/收取/完成而非只看 Turn 结束。本轮不使用 v0.0.4 或早期 P5 的绿色替代新代码验收。J2 AUTO/后台按钮、Project Memory、完整 Runtime/OEM 故障矩阵和发行仍属原后续阶段。

## 实践参照

本轮核对 [Codex App Server](https://developers.openai.com/codex/app-server) 的进程身份与独立观察/终止接口、[Claude 后台命令](https://code.claude.com/docs/en/interactive-mode#background-bash-commands) 的任务句柄与结果读取，以及 [Anthropic 长任务 Harness](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents) 的可追溯增量与端到端验证。复用职责与证据方法，不复制桌面无限后台策略，不将外部文档当作 Helix 设备验证。
