# BFCL 与 AndroidWorld 全量执行计划

> **原始运行计划，2026-09-29 从当前控制面移入历史验证计划。**下文“未开始运行”记录的是计划编写时状态，不能据此判断后来是否已跑。实际运行/未闭合条件需查对应 run 的 manifest、原始结果与当前 status；归档本身不宣称全量通过或关闭 ERROR/BLOCKED。
>
> 可复用的证据与比较原则统一看[Agent Eval](../../../development/agent-eval.md)、[系统基线](../../../development/harness-system-baseline.md)与[公共验收](../../../development/verification-matrix.md)。本文不构成新的设备、账号或执行授权。

---

日期：2026-09-29。状态：仅计划，未开始运行。供后续执行 agent 按检查点推进；不改变产品权限或重构排期，不授权提交、推送、付费服务或真实设备使用。

## 目标与成绩口径

- BFCL：固定官方 revision，完整运行其适用类别与全部 case ID，使用官方判分。主结果为模型直连成绩，不称为 Helix Harness 成绩。
- AndroidWorld：完整运行固定 revision 的 `android_world` suite 全部任务模板，每个模板先运行一个固定种子的实例。它有动态参数空间，“全量”不表示穷尽所有参数组合。官方当前介绍为 116 个模板、20 个应用，实际分母以锁定 registry 为准。
- AndroidWorld 主线评估 Helix 真实执行链，延续 Accessibility 动作空间，标记为 Helix 适配全量结果。API36、自定义动作、审批和预算不同于官方条件时，不称为官方可比排名。要做严格可比实验，另建遵守官方环境/动作/步数定义的 track，不混合计分。
- 过去 BFCL 20 题 × 3 次和 AndroidWorld 两个亮度任务仅作历史参照，不计入新全量结果。
- “小模型”默认指负责执行本计划的 agent，不擅自更换被测模型。若指被测小模型，先冻结其独立模型配置；不同模型不得混入同一结果集。

## P0：冻结输入与完整清单

1. 阅读仓库 AGENTS、status 和现有两份 public-agent/automation-recovery evidence。保留其他人的修改，优先使用空闲独立工作树，不复用正在重构的可变构建。
2. 固定 BFCL、AndroidWorld 的 commit、依赖环境、数据 hash；优先核对之前固定的 revisions 是否已包含所有目标类别，升级时单独记录差异。不要边跑边 pull。
3. 固定 Helix commit、APK/test APK SHA、模型 ID/权重或服务版本、量化、chat template、上下文容量、推理参数、工具模式。模型上下文不超其支持范围，不为个别失败临时增大预算。
4. 从官方类别展开和 registry 生成 `manifest.json`、`expected-cases.jsonl`，记录类别、唯一 ID、种子、依赖、是否计分。不要用手写题数或扫描到的全部 JSON 文件冒充官方 suite。
5. BFCL `all` 包含非计分 format sensitivity，`all_scoring` 才是计分类别集合。format sensitivity 对 native function-call 模式不适用时明确记 N/A；若另跑 prompting 模式，独立标记配置。Memory 等类别初始化/关联案例顺序由官方 runner 管理，不能随意按行打散。
6. 确认专属模拟器的当前授权、型号/API/架构、磁盘/内存与独占端口；只使用合成数据。主机上的现有模型服务须确认允许使用；Web search 所需 SerpAPI 等账号/额度由用户提供并授权，缺失记 BLOCKED，不能冒充完整完成。

退出条件：清单和所有配置已经写盘；分母可复算；依赖缺口逐项列出。设备未获当前授权时先完成主机和夹具准备。

## P1：完善 runner 并校准

已有 `scripts/debug/2026-09-28/run-bfcl-diagnostic.py` 和 `run-androidworld-pilot.py` 只作诊断参考，不直接把循环次数扩大后当官方全量 runner。理解代码前遵循仓库 CodeGraph 规则。

- BFCL 使用官方 generate/evaluate 和正式模型 handler。模型不在注册表时实现最小适配，验证请求/返回协议；不改问题、答案或 checker。依据锁定版本 `--help` 验证命令，不猜参数。
- AndroidWorld 接入官方 suite 初始化、任务生成、oracle、清理与 checkpoint。Agent 通过 Helix Provider → AgentLoop → 工具 → 持久化路径执行；ADB 仅供环境初始化/取证/判分，不代替 Agent 完成任务。oracle 答案不得进入模型上下文。
- 为每题持久保存状态：PENDING、RUNNING、PASS、FAIL、ERROR、BLOCKED、N/A。保存尝试编号、失败类型、预算、耗时、工具/模型次数、最终状态和证据路径。
- 先跑各 BFCL 类别少量题及 AndroidWorld 各主要应用代表任务，检查 reset、结果落盘、异常取证、进程中断后续跑。校准结果独立保存，不从中挑最好一次计入正式成绩。
- 工程失败可修夹具；模型失败不阻塞其余题。若发现生产缺陷，保留原结果，建立新版本修复轮；正式全量期间不边改生产代码边拼接成绩。
- 校准后根据实际每题耗时和 token 估算总时间/磁盘需求，写出中位数和长尾估算。不要把此前两道亮度题外推整个 suite 的准确耗时。

## P2：BFCL 全量

按官方类别依赖顺序覆盖 single-turn、live、multi-turn、memory、web search 和适用的 format sensitivity；每题一次正式尝试，不需要每题机械重复三次。

命令形状（执行时替换已注册模型名，并以锁定版本帮助校验）：

```sh
bfcl generate --model MODEL_NAME --test-category all --skip-server-setup
bfcl evaluate --model MODEL_NAME --test-category all
```

`--skip-server-setup` 仅适用于已配置并验证的兼容模型服务。先以低并发运行，根据服务负载调整；有状态类别按官方隔离与依赖执行。用官方 `--run-ids` 处理缺失项，最终完整判分不能使用掩盖缺失案例的 `--partial-eval`。默认全量选项若含不适用类别，用明确类别列表并保留排除原因。

每类结束保存 checker 输出，最终按官方计分汇总，同时公布覆盖率、ERROR/BLOCKED/N/A。依赖未齐时继续其余类别，但不得宣称全量完成。

## P3：AndroidWorld 全模板

1. 专属模拟器完成官方应用安装与初始化，冻结系统镜像、屏幕配置、应用版本和 Helix 配置。
2. 使用 `suite_family=android_world`，不设置任务子集；`n_task_combinations=1`，固定 `task_random_seed`，保存生成参数。具体 flag 以锁定 runner 为准。
3. 实现/注册 Helix adapter 后才能替换官方示例 agent；不能照抄 `t3a_gpt4` 然后把结果称为 Helix。
4. 串行跑一台设备，每题恢复官方初始状态、隔离会话/Workspace，并执行清理。fixture 可以按冻结策略调用正式授权/恢复入口，但不得看模型失败后临时加权限；报告哪些任务有人为确认或自动代确认，辅助条件与无人辅助结果分开。
5. 使用官方每题 step budget 作为可比 track 的预算；Helix 产品默认长程预算可以单独跑适配 track。预先定义 Helix 工具调用到 benchmark step 的映射，不能用内部调用粒度绕过步数限制。
6. 每题保存官方 oracle、Helix Turn 状态、预算停止原因、轨迹和脱敏截图。oracle 成功但 Turn 超限/未结束须双列报告，不能只写成功。
7. 使用官方 checkpoint 机制恢复；RUNNING 中断项先检查/重建 fixture，不能复用半完成环境直接重试。完成第一轮所有模板后结束本计划；多种子稳健性实验另列扩展，不阻塞全模板覆盖。

## P4：失败补齐与结案

- FAIL：模型或 Agent 任务失败，包括限额终止；它是有效测量结果，不要求修到全部通过才能结案。
- ERROR：环境、传输、夹具或判分故障。最多追加两次基础设施重试，所有尝试保留；不把任务失败包装成 ERROR 反复抽样。对存在副作用的重试先重置独立 fixture。
- BLOCKED：缺少外部服务、授权或环境；列出具体解除条件。全量完成要求无 PENDING/RUNNING/BLOCKED，ERROR 也应修复补跑至有效结果；仍有 ERROR 则标记全量尝试但验证未完。
- 每个 expected ID 恰有一个确定选择规则产生的正式结果。校验 expected/result 集合差、重复 ID、缺失 oracle、版本漂移，禁止 best-of 重试选择。
- 输出 BFCL 分类/官方聚合成绩、AndroidWorld 官方 oracle 与 Turn 正常结束双指标、覆盖率、失败归因、耗时/token 分布；不自行平均类别准确率替代官方权重。
- 只归档脱敏报告、manifest、摘要/hash 与必要轨迹。原始数据放 ignored `build/public-eval/<run-id>/`；脚本放日期化 debug 目录。不要提交密钥、模型权重、应用用户数据。
- 交付总结与按影响排序的修复清单；不提交榜单、不提交 Git、不推送。相关操作需要当前另行授权。

## 交接给执行 agent

按 P0 → P1 → P2 → P3 → P4 推进。每阶段在独立运行目录写 `progress.md`，包含已完成项、下一个具体动作、命令、证据位置和阻塞条件。每批结束或发生阻塞汇报一次；别逐题刷屏。进程中断依靠持久记录恢复，不依赖聊天上下文。先完成全量测量，再决定修复；不要以全绿为目标无限重试。没有权限或外部输入时继续不依赖它的部分，并准确报告未完成范围。

## 官方依据

- [BFCL runner 文档](https://github.com/ShishirPatil/gorilla/blob/main/berkeley-function-call-leaderboard/README.md)：生成、判分、模型服务与断点案例选择。
- [BFCL 类别定义](https://github.com/ShishirPatil/gorilla/blob/main/berkeley-function-call-leaderboard/TEST_CATEGORIES.md)：all/all_scoring、agentic 与 format sensitivity。
- [AndroidWorld 文档](https://github.com/google-research/android_world)：全 suite、动态实例、checkpoint、模拟器环境。
- [AndroidWorld runner](https://github.com/google-research/android_world/blob/main/run.py)：任务种子、实例数和套件选择。

以上链接于 2026-09-29 查询；执行时必须转换为实际锁定 revision 的证据，不能混用后续 main 变化。
