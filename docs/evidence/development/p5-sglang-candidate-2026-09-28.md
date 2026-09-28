# P5 SGLang Harness 候选验证（2026-09-28）

## 结论与边界

固定 15 case 完整采集：**14 PASS / 1 FAIL / 0 fixture ERROR**。不是全绿验收；runner 已以非零退出保留失败。剩余 `goal-001` 为 `OUTPUT_TOKEN_LIMIT`，正文为空，Goal 保持 PAUSED，没有误标完成。相同修正夹具的定向轮该项通过，说明输出稳定性仍未闭合；不通过增加预算或重复运行覆盖失败。

这是基于 `4e634c670a3540eddf04028d4affc0709e1bc1b2` 的 **uncommitted candidate**，不是 clean-worktree 正式 P5/P6 A/B 锚点。并发写入曾影响原 checkout，最终验证改用隔离 checkout；本记录仅采用隔离后的制品。未提交、推送、合并或运行远端 CI；真机 `not requested`。所有本轮 owned emulator 已退出。

原始 7 PASS / 7 FAIL / 1 ERROR 基线保留在原 checkout 的 `build/p5-sglang-harness-baseline-f40188f8/`。本轮调整了 oracle 和执行夹具，不能把两轮分数或耗时差直接当作 production 优化的因果 A/B。

## 修复归属与诊断

- **真实工具面缺陷**：接续另一端提交 `4e634c67`。先完成 mode、session disabled、Memory、MCP discovery 等过滤，再优先排列核心文件工具、Goal 生命周期、结果回读与发现入口，最后保持 `MAX_TOOLS = 64` 截断。未增加上限、放宽 permission 或改变 Dispatcher/effect owner。`file-003` 实际直接调用 `write`，经既有审批成功落盘。
- **file-001 证据缺陷**：同提交将无 Turn 时的 `.last()` 崩溃改为 `NO_TURN_CREATED` 失败证据。本轮实际正常路径通过；不声称设备运行覆盖了无 Turn 分支。
- **JS oracle 漂移**：timeout/cancel 后 executor 退出未被证明，保留 call/result/Turn 的 NEEDS_REVIEW，分别验证 audit `TIMEOUT`、`CANCELLED_AFTER_START`。测试等待边界同时接纳 NEEDS_REVIEW，避免非终态导致无效等待 180 秒。未改 Runtime/Dispatcher 安全语义。
- **Goal continuation 夹具竞态**：原 `continueGoal` 启动连续 Driver，prelude/正文可能创建多个 Turn；按固定第 1/第 2 个 Turn 等待会提前取到旧终态。本轮通过既有 `AgentRuntime.submit(continuousGoal=false)` 明确单轮，等待返回的具体 Turn ID。增加预算后若已 BLOCKED，必须先经过既有 `recheckGoalBlocker`。验证累计用量增加 1，并断言续跑 Turn 恰好有 1 条 model-call 记录。没有证据需要改生产账本。
- **Goal plan-only oracle**：数据集目标是“拆出检查点”，不是“完成审计”。无结构化完成报告的单轮应 PAUSED/RUN_FINISHED；仅当有效报告被正常结算时允许 COMPLETED/MODEL_COMPLETED。仍要求三份文档对应的三个 checkpoint、Turn 正常完成、恰好一个 Turn，且无业务工具执行。没有引入自然语言关键词硬门禁或放宽真实审计目标的完成条件。
- **Goal approval**：生命周期 metadata 查询不再被误当成业务写入；仍要求真实 approval pending、所有业务 call 未 RUNNING/COMPLETED、目标文件不存在、Goal 未 COMPLETED。完整轮观察到 `get_goal:RUNNING` 与 `write:AWAITING_APPROVAL`，无写入；不把等待审批的 Goal RUNNING 错记为 PAUSED。
- **证据与 runner**：工具版本清单跟随当前过滤/优先级；支持 `HELIX_P5_CASES` 定向子集，子集不标 baselineComplete；完整采集仍有失败时非零退出，不再把“收集齐”当 gate 通过。

## 设备与身份

API36 / arm64-v8a / developer debug，AVD `Helix_HXA210_API36`，独占 `emulator-5574`，4 GiB / 4 cores / 400 dpi。SGLang `Qwen3.8-27B`，host `localhost:30008`，emulator `10.0.2.2:30008/v1`，test-only OPENAI_CHAT_COMPLETIONS override。未运行设备内 4B 或物理设备。

| 身份 | SHA-256 |
| --- | --- |
| fixed-evals.tsv | `f27bf8b51e61be248a6e642c22cefc3e5045d0d35e37518377eb9b8cdf85e795` |
| source manifest | `e086e16fdf4832888ec09749bbcc998d9a4054fcfbd09948eb1c0bc75c21f22a` |
| app APK | `099c3e7c1e620dccccb31184e4f7d9469c069969fbd61c13b74fcbc4609821a1` |
| AndroidTest APK | `e9ad95124d6ae0994a6732c06033982f89188906f70869bfa18cad9a00f76124` |
| 验证时源码补丁 | `23aa94b54e7743aad31e79ea2ed5968793ee5099d8cc4cb25ab6eb76b50b433c` |

完整摘要与每项原始 record 已归档：[summary.json](p5-sglang-candidate-2026-09-28/summary.json)、[cases.json](p5-sglang-candidate-2026-09-28/cases.json)。完整 APK、日志、source manifest 与补丁在本 checkout 的 `build/p5-full-candidate/`、`build/p5-candidate-source.patch`。

## 结果

| Suite | 结果 | 关键事实 |
| --- | --- | --- |
| Files | 4/4 PASS | read/list、审批 write、scope escape refusal |
| JavaScript | 4/4 PASS | 常规与隔离；timeout/cancel 均保留 NEEDS_REVIEW |
| Skills | 4/4 PASS | traversal host 拒绝与模型报告通过；未放宽危险操作 |
| Goal | 2/3 PASS | 预算与审批通过；goal-001 OUTPUT_TOKEN_LIMIT |

`goal-002`：modelCalls **1→2**，两 Turn 共两条真实 model-call、无工具执行，Goal BLOCKED，outcome `BUDGET_EXHAUSTED(maxModelCalls)`。`goal-003`：一条 pending write approval、0 answered、`writeOccurred=false`。

定向历史：隔离首轮 7 项中 Files/JS/goal-003 共 5 PASS，goal-001/002 FAIL，暴露连续 Driver 夹具竞态；修正为单轮后 Goal 3/3 PASS（`build/p5-goal-targeted/`），其中 goal-001 一次 model call、无工具、PAUSED。随后只运行一次完整 15-case，保留上述失败。原 checkout 中断、端口尚未释放及并发污染的运行均不计有效验收。

12 个提供 elapsedMs 的 Files/JS/Skills case：median **3.751 s**，mean **8.670 s**，p95/max **37.021 s**；Goal 3 项未提供同口径 elapsedMs，不计入这些统计。JS timeout 11.553 s、cancel 3.831 s。主要去除了 fixture 的无效等待，不是模型推理加速。未测 prefill、TTFT、decode tok/s 或 GPU。

## 主机验证

以下在隔离 checkout 通过；最后的 Goal fixture 调整后重新完成 AndroidTest 构建、双渠道 lint、spotless/detekt。共读取相关 JVM XML：2,576 pass、8 条件 skip、0 failure/error；跳过不计通过。

```bash
./gradlew :core:model:test :core:agent:test :core:storage:testDebugUnitTest \
  :tools:framework:test :runtime:quickjs:testDebugUnitTest \
  :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest \
  :app:lintConsumerDebug :app:lintDeveloperDebug spotlessCheck detekt
./gradlew :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest
scripts/check-all.sh --source
git diff --check
```

原 checkout 的并发 QuickJS/Dispatcher 改动导致过 4 个 QuickJS 测试失败；该组改动不属于本候选，保留现场/补丁而未带入隔离 checkout。不把上述通过外推到原 checkout 后续提交。

复现完整候选：

```bash
python3 scripts/debug/2026-09-28/run-p5-sglang-harness-baseline.py \
  --output build/p5-full-candidate --emulator-port 5574
```

输出目录必须不存在；定向调用在相同命令前设置 `HELIX_P5_CASES=goal-001,goal-002,goal-003` 并使用独立输出目录。

## 后续

1. 先审查并整合此候选，取得 clean commit，再冻结同 oracle/fixture/source/APK 的正式 P5/P6 锚点。
2. 对 `goal-001` 的输出截断做独立、有限的原始模型输出与提示分析；检查 system Goal 报告要求与 plan-only 用户约束的交互。此次没有原始 reasoning 证据，不宣称唯一根因；不直接提高预算、放宽权限或更改完成 owner。
3. 本记录不覆盖连续 Driver 的全面回归、不证明模型普遍可靠，也不替代 OEM/真实账号/发行验收。
