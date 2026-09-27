# Agent Eval evidence v1

HXA-227 提供离线规范化与轨迹验收入口，不实现第二套 Agent runtime，不修改生产策略，不上传生产会话。可执行契约与字段校验在 `scripts/agent_eval.py`，CLI 为 `scripts/run-agent-eval.py`。

## 数据契约

一个 envelope 对应一个 suite/case，包含 `schemaVersion: 1`、identity、outcome、trajectory、cost。身份重复拒绝；同一 case 的不同 flavor/model/protocol 可分别报告，A/B 必须先选定一个 cohort。

| 字段组 | 契约 |
| --- | --- |
| identity | suite/caseId、gitCommit、dirty、sourceManifestSha、datasetSha、promptSha、verifierSha、evidenceKind、measurementScope 必需；config/tool/capability/fixture/environment hashes、Provider/model/protocol/version、flavor/API/device、APK hashes 按实际观测记录 |
| evidenceKind | `fixture`、`fake-provider`、`real-provider` 分开；固定脚本响应不证明模型推理能力 |
| measurementScope | host boundary、device Harness fixture、session、legacy last-turn 分开，不混算 |
| outcome | PASS/FAIL/BLOCKED/INVALID；verifierFacts 必须非空；artifacts 是证据根内相对路径 + SHA-256，读取时重新校验 |
| trajectory | turns/successorTurns/modelCalls/toolCalls/invalidToolCalls/repeatedFailures/approvalsRequired/approvalsAnswered/humanInterventions/unknownEffects/reviewsResolved/compactions/recoveryEvents/firstPassSucceeded；未测量写 null |
| cost | wallMillis/inputTokens/outputTokens/totalTokens，只有实测才填；不根据模型名估价 |

缺必需 identity/verifier 记 INVALID，不能计 PASS；未知扩展字段保留，可选指标缺失不变成 0。不支持的 schemaVersion、证据路径越界、文件缺失或 hash 不匹配拒绝输入。JSON 不接受 NaN/Infinity。只有受控测试 producer/verifier 才能提供这些断言；校验 hash 不是对任意导入文本的真实性认证。

源码 manifest 对显式源目录中的 tracked 与相关 untracked 文件记录相对路径/内容 hash，包含 tracked 删除；不扫描用户目录，不读取被忽略的 build 输出或凭据。实际 APK/test APK 单独标识。manifest 证明采集时 checkout 内容，不能单独证明已安装 APK 由该 checkout 编译。

## 指标与分母

- task success = PASS / (PASS + FAIL)，BLOCKED/INVALID 独立显示，不偷偷计成功。
- first-pass = `firstPassSucceeded` 已知记录中的 true 比例；定义为 verifier 成功且没有纠错重试、故障恢复或纠错性人工介入。正常多步调用和预期安全审批不自动算失败。
- recovery success = 有观测到 recoveryEvents > 0 的记录中最终 PASS 比例。这是有恢复样本的条件成功率，不是综合能力分数；与 first-pass 和覆盖数一起看。
- invalid tool、repeated failure、human intervention、UNKNOWN incidence 分别使用对应指标已知的记录作分母；同一工具 + canonical args + 失败原因的再次失败计 repeatedFailures，原始 evidence 不足时不猜测。
- approvalAnswered 仅计已有持久决策，不能等同人类纠错次数；UNKNOWN 指副作用未决事实，不从模型措辞推断。
- metrics/cost 报告 known/eligible 或 known，缺失不记 0。A/B cost/metric delta 只使用前后都已知的配对样本，报告 paired 数。

失败 taxonomy：MODEL_REASONING、TOOL_SELECTION、TOOL_ARGUMENTS、TOOL_EXECUTION、PERMISSION_OR_APPROVAL、ENVIRONMENT、CONTEXT_LOSS、RECOVERY、UNKNOWN_EFFECT、BUDGET、PROVIDER_PROTOCOL、VERIFIER、HARNESS_INVARIANT。可有 contributingCauses；原因不明保持未归因，不凭异常字符串自动定责。

## 三套 fixed eval adapter

现有 File、Browser、Goal device producer 保留原始 per-case JSON，增加真实 session 的 Turn、ModelCall、ToolCall、审批与 UNKNOWN 计数。`run-hxa100-provider-evals.py` 在原有已授权评测执行完后额外产出 `envelopes.json`、`eval-summary.json`、`eval-summary.md`；不改变原 verifier。该 runner 原本需要设备与真实模型，本 HXA 不自动运行它。

离线导入也可执行：

```bash
python3 scripts/run-agent-eval.py fixed --root build/my-eval --context build/my-eval/context.json --input build/my-eval/device/file-001.json --output build/my-eval/envelopes.json
python3 scripts/run-agent-eval.py report --root build/my-eval --input build/my-eval/envelopes.json --output build/my-eval/summary.json
```

context 必须提供采集的 dirty/sourceManifestSha/verifierSha/evidenceKind/flavor 等身份，不能用当前源码 hash 冒充旧制品 provenance。旧格式只有最后一轮 calls 时仅记录 last-turn toolCalls，其余未知；新增 session 计数不沿用旧 elapsedMs 冒充整个 session 的耗时。原 raw text/args/tool result body 不复制到统一报告。

目前 fixed runner 未采集完整 session config/Provider capability/environment 对照条件时，相应字段保持未知，A/B 会明确拒绝可比 delta；Provider 未报告版本也不猜测。可以做单次结果/覆盖报告，不作因果结论。

## 固定轨迹与主机 baseline

`evals/trajectory/core-device.json` 将八类 case 绑定到真实 Harness/Room 或脚本 Provider 的精确测试方法：Queue、Steer、Cancel + UNKNOWN、review receipt、process-death recovery、Goal、compaction、工具失败换路。它复用现有测试而不是复制状态机；新工具恢复反例使用生产 ChatService/Dispatcher/Provider decoder。

设备 suite 的 recovery case 是数据库 reopen + recovery，不冒称 OS SIGKILL；host RecoverySummary 测试补充有界摘要和旧协议不回放。已有正常进程 kill runner 是另一个专项。Review case 验证旧 attempt 结算、不重放；不把这些 fixture 解释为真实模型能正确推理或任意进程死亡均恢复。

`evals/trajectory/host-boundaries.json` 是 8 个较小的 host 断言组，共用 envelope，但覆盖明确标为 `host-boundary-not-device-trajectory`；它不把 Queue/Steer 的设备轨迹冒充为 host 通过。运行新 baseline：

```bash
python3 scripts/run-agent-eval-host.py --output build/eval-baseline-unique
```

runner 对确定的 JVM 类强制重新执行，保存新生成 XML、源码 manifest、运行条件、JSON/Markdown 报告。输出目录必须不存在；Gradle 失败、XML 缺失/跳过、源码执行期间变化都不会生成 PASS。没有设备/网络/模型访问。

设备授权后可按 core-device manifest 的 `class#method` 选择器交给现有 owned acceptance runner，保留独占设备规则和 APK hash；只有实际执行结果才可导入：

```bash
python3 scripts/run-agent-eval.py junit --root build/device-eval --context build/device-eval/context.json --manifest evals/trajectory/core-device.json --input build/device-eval/results.xml --output build/device-eval/envelopes.json
```

JUnit adapter 读取真实 testcase failure/error/skipped，不从 `BUILD SUCCESSFUL` 或空日志推定通过。指标不足时为空；断言覆盖和轨迹计数是不同证据。

## A/B

```bash
python3 scripts/run-agent-eval.py compare --baseline build/baseline/envelopes.json --baseline-root build/baseline --candidate build/candidate/envelopes.json --candidate-root build/candidate --output build/comparison.json
```

比较要求同 case 集合、dataset/verifier/fixture/environment、已声明的执行条件和证据层级。未知控制变量或不匹配条件返回 exit 2，保留原因，delta 为 null。源码/制品本就可因候选修改而不同，仍分别保存身份。

`--treatments` 接受明确字段与前后值的 JSON，例如 `{"sessionConfigHash":{"before":"<sha256>","after":"<sha256>"}}`。只允许指定 Prompt/config/tool/capability/Provider 字段作为实验变量，dataset/verifier/fixture/environment 不可通过 treatment 绕过。改变 Workspace 资源环境时重新建立对应 baseline，不能误称纯 Harness 改善。JSON/Markdown 分组结果没有一个合并的“Agent 总分”。
