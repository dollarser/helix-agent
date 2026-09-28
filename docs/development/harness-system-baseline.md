# Harness 系统基线

P5 之后把“设备内本地模型最低可用”与“Helix Harness 能否解决问题”分开验证。

## 模型分工

- **Qwen3 4B Instruct 2507 Q4_K_M**：只承担设备内最低能力证明。P3 已覆盖安装/校验/Provider/probe，P4 已覆盖真实 `write`→`read`、artifact 与进程重开。后续不再用设备内模型跑长程系统能力或优化 A/B。
- **SGLang `Qwen3.8-27B`**：`http://localhost:30008/` 是当前 Harness 系统测试的模型服务。Android emulator 通过 `10.0.2.2:30008/v1` 使用同一服务，走 Helix 正式 Provider、Agent loop、permission/effect、Tool Dispatcher 与 durable storage。

本分工不表示 4B 是最终推荐手机模型，也不把 SGLang 结果当成设备内推理性能。两类证据回答不同问题。

## P5 固定范围

P5 复用 `evals/m10/fixed-evals.tsv`，不建立第二套 Eval。为了只测 Harness 解题能力而不把 SGLang 的多协议兼容性混入结果，当前系统基线通过 test-only override **统一使用 `OPENAI_CHAT_COMPLETIONS`**；原 HXA-100 仍按数据集中的 Responses/Chat/Anthropic 协议做 adapter 专项。当前 Harness 系统基线固定四组、15 个 case：

1. `files` 4 项：scope read/list、写审批、越界拒绝；
2. `javascript` 4 项：受控执行、超时、能力缺失、取消不重放；
3. `skills` 4 项：不可信 hints、未曝光工具、archive traversal、snapshot invalidation；
4. `goal` 3 项：bounded goal、预算 continuation、写审批暂停。

MCP/A2A/Browser/Accessibility/Root 与多协议兼容等完整 45 项仍属于更广回归，不要求每次 P5/P6 A/B 重跑。

## 性能口径

Files/JavaScript/Skills 的 12 个 fixed case 已持久化 `elapsedMs`；Goal 三项目前只有其他用量与 host wall time，不混入同口径 elapsed。P5 汇总这 12 项的 mean/median/p95/max，并保存每个 suite 的 host wall time。这里的 elapsed 是 **Harness 端到端任务时间**，包含模型请求、Tool、审批、Room 与恢复等待，适合比较 Harness 改动前后是否让同一任务更快/更慢。

它不是 SGLang server 的纯推理性能，因此本基线不声称：

- prefill time；
- true TTFT；
- decode-only tokens/s；
- GPU 显存或服务端吞吐。

如果未来需要优化 SGLang server 本身，另建服务端 profiler；不要把 Harness `elapsedMs` 混成模型 decode 指标。

## 可比较身份

正式 P5 run 必须满足：同一 `gitCommit`、clean worktree、同一 `fixed-evals.tsv` SHA、同一 developer app/test APK、同一 source manifest、同一 SGLang model id。各 suite 继续输出既有 HXA-227 envelope/原始 device record；P5 汇总不另建 oracle；旧 fixed-eval 与当前冻结 contract 不一致时，先显式修正并记录 verifier/fixture 身份，不把跨 oracle 的分数差当作优化 A/B。未提交 candidate 可用于诊断，但不能替代 clean 正式锚点。

复现入口：

```bash
python3 scripts/debug/2026-09-28/run-p5-sglang-harness-baseline.py
```

runner 会先执行真实 SGLang UI/provider smoke，再在同一 owned API36 emulator 上依次执行 `files/javascript/skills/goal`，最后关闭自己创建的 emulator。任一 case 失败都保留原始证据并使 P5 gate 失败，不通过重试直到成功来篡改基线。

当前候选结果与 oracle/fixture 修正见 [2026-09-28 P5 验证](../evidence/development/p5-sglang-candidate-2026-09-28.md)。`HELIX_P5_CASES` 可指定逗号分隔的固定 case 子集；子集完成不表示完整基线通过。

v1 正式锚点为 clean `99b7bee7`：15/15 通过；前一 clean `b436247f` 为 14/15，skill-003 的冗余执行请求失败。两者同 fixture/oracle、同 test APK；只改变 base prompt 的充分证据报告指导。完整对照与局限见 [P5/P6 证据](../evidence/development/p5-clean-baseline-and-p6-2026-09-28.md)。单次通过不消除历史截断或证明统计稳定性。

后续所有者授权修正提示词与 skill-003 最终请求矛盾，形成独立 v2 锚点 clean `fce488ce`，15/15 通过；`fixtureVersion=report-refused-import-v2`、实际 prompt hash 与数据集原始 prompt hash 分别记录，安全 oracle 不变。当前继续评测使用 v2；上述 v1 A/B 证据保留，不跨版本比较完成率或声称提示词因果收益。见 [v2 证据](../evidence/development/prompt-fixture-v2-2026-09-28.md)。
