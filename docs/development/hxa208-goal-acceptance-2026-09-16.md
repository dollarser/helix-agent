# HXA-208 Goal 连续运行与完整工具验收

基线：2026-09-16 `main` 的 `bcc2a6dc` 加本任务改动。决策见 [ADR-0053](../adr/0053-goal-continuation-activation.md)。状态：本任务范围验收通过，见[完成记录](../completion-records/HXA-208.md)。工作区另有独立审批 ADR 草案，仅保留，不纳入 Goal 提交。

## 已实现契约

- Goal 模式发送创建或继续本会话目标；用户启动后，同一 AgentRuntime 在前轮持久结算后发起下一轮；同会话有多个目标时优先沿用最近实际使用的未完成目标，Activity 退后台不解除激活，FGS 跨轮保持。正常完成、阻塞、预算、取消、输入等待及异常仍由原状态机处理。
- `GoalContinuationDriver` 只拥有进程内激活、前轮身份与交接；Room/GoalRunCoordinator 拥有状态和累计账本；ForegroundController/Service 拥有 Android 运行窗口。没有第二套执行循环或预算。
- 停止及新用户输入先解除激活；新输入等待旧轮结算再准入。重复/旧前轮请求、会话或 Provider 快照变化不能多启动一轮。系统拒绝启动/提升前台服务、服务丢失、Android 超时均收口，`SYSTEM_PAUSED(FGS_*)` 有可读说明。
- `create_goal`、`get_goal`、`update_goal` 是生产 Registry/Dispatcher 工具。读取包含目标、状态、armed、revision、待结算编辑、上一结果及累计预算。创建、目标/预算编辑和 active/paused 必须来自当前直接用户请求；自动轮/重试没有该来源。原文引用检查不宣称独立理解或证明用户语义。
- `update_goal` 要求 id/expected_revision；目标最长 16,384 字符，预算包含模型调用、工具调用、总 token、总时长、单轮时长及既有重试配置。完成/进度/阻塞报告有 summary，兼容 `goal.report`，仍由正常 Turn 结算消费，不能替代实际工作。
- Room19 的 `goal_controls` 保存归属、CAS 和待结算修改。目标创建、归属和首次 UI run 准入同事务；模型创建的首次 run 在创建轮结束后才开始。创建轮保留原 Turn 预算，之后各 Goal run 共享持久 Goal 额度。
- 编辑在当前轮成功结算后原子应用目标/预算/版本；失败、取消和恢复丢弃尚未生效修改。UI 目标与预算编辑同样检查版本，禁止覆盖待结算修改；Plan 绑定目标不绕过计划审阅。删除检查待结算修改，会话永久删除清理其所属 Goal。
- 进程恢复保持未激活状态，清理待生效编辑、结算预算预留、保留已执行结果；不自动重放未知副作用。暂停解除激活，READY/BLOCKED/INPUT_REQUIRED 保留实际状态，活跃轮结算后停在 PAUSED；显式取消的既有终态语义不变。

## 验证证据

统一可重跑命令：`bash scripts/debug/2026-09-16/run-goal-acceptance.sh`，需配置 JDK17 和 Android SDK。该脚本串行执行 Gradle 写入，避免多个 Gradle 进程争用同一 Kotlin 增量缓存。

| 验证 | 实际结果与证据 |
| --- | --- |
| 全量本地主机 | `./scripts/check-all.sh --all`：source、spotless、detekt、test、Debug/Release lint/build、锁文件和制品边界通过；`build/goal-acceptance-final.log`。按日志实际 test task 对应 XML 汇总为 4412 通过/8 既有条件跳过/0 失败，未扫描归档 XML 充数 |
| APK/存储测试编译 | 双 flavor AndroidTest 与 core/storage AndroidTest 通过 |
| Goal 与服务四象限 | API29/36 × consumer/developer，每组合 45 通过/0 失败/0 跳过，`build/pre-hxa-device-20260916-122117/` |
| 真实进程恢复 | 同四象限执行 control → prepare/SIGKILL → recover-prepare/SIGKILL → recover-final，8 次实际 SIGKILL；每组合两次成功断言阶段，PID/信号/原始 instrumentation 见上述目录各 `*-goal-kill/`。验证待编辑丢弃、累计消耗保留、重启未激活及预算耗尽不创建第三轮 |
| Android 超时回调 | API36 × 双 flavor，各 1 通过/0 跳过，`build/pre-hxa-device-20260916-122427/`。调用真实服务回调，不伪称等满系统六小时 |
| Room 迁移 | API29/36 各 32 通过/0 失败/0 跳过，`build/goal-storage-20260916-122513/`。覆盖18→19归属回填、无待编辑/无激活、当前schema drift以及完整历史迁移链 |
| 最终 FGS 拒绝补充 | 启动调用与后续服务提升共用拒绝收口；主机异常断言通过，四象限各11通过/0失败/0跳过，`build/pre-hxa-device-20260916-122825/` |
| 新输入与当前目标 | 用户新消息抢占、预算保留、多个目标按最近执行选取，连同创建/编辑/恢复专项四象限各10通过/0失败/0跳过，`build/pre-hxa-device-20260916-123958/`；新消息独立交互三场景四象限亦全过，`build/pre-hxa-device-20260916-123537/` |
| 最终完整主机门禁 | `build/goal-current-check-all.log` 再次完整通过；4414通过/8既有条件跳过/0失败。其后仅修正新增设备夹具遗漏的 beginModelStream 状态进入，修正后四象限实际重跑通过 |

所有模拟器由 runner 独占创建，拒绝已有 serial，在 finally 只关闭拥有的进程。环境中已有 `emulator-5554` 未使用或关闭。设备证据来自原始 instrumentation 状态，不合成“通过”XML。

## 已纠正的验收问题

- 首次 E2E 读取真实模型工具结果时，测试未去除 `[SUCCEEDED]` 包装；修正测试解析后验证真实 Registry/Room/模型回填。
- 后台测试用 Activity 状态切换接口尝试恢复后台任务，清理超时；改为验证真实 STOPPED 生命周期并正常关闭测试场景，不把 UI 可见性当 FGS 激活条件。
- developer 取消用例固定 10,000 输入 token，新增工具描述使其在 CONTEXT_WINDOW_LIMIT 被正确拦截；将该 socket 用例预算改为32,000，仍保留停止、超时、旋转及断连断言。生产上下文门禁未放宽。
- 并发 Gradle 写同一增量缓存造成 snapshot 缺失；最终门禁串行执行，不以关闭缓存检查掩盖失败。

## 验收边界

没有引入定时或外部 Channel 自动激活，也不增加任何执行期授权。系统强停、进程回收、Doze/服务限额仍可中断运行，用户打开应用后显式继续；不承诺永久后台驻留。工具完成报告不是独立质量认证。真实账号、OEM 真机、长稳与发布属于各自验收，loopback 设备通过不代表这些已通过。本次交付不复制 DSH 源码，也不把 Helix 改为 event sourcing。
