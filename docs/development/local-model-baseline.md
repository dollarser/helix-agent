# 本地模型性能与任务质量基线

P5 的目的不是调参，而是冻结一个可重复的事实口径。它复用 HXA-227 evidence v1 和 HXA-222/P4 的真实设备证据，不建立第二套 Agent runtime 或综合评分。

## 固定执行条件

当前 baseline 使用 API36 consumer owned emulator，8 GiB RAM、4 vCPU、density 400；llama.cpp CPU backend 保持 **2 inference threads、greedy sampling、4096 context**。AVD 的 4 cores 不等于 native 使用 4 threads。正式 P5/P6 只保留 **Qwen3 4B Instruct 2507 Q4_K_M**；该模型独占一次 emulator run，app data 清理后注入已校验的 pinned GGUF，运行期间关闭网络。

模型身份只认文件 SHA/size，不认显示名。当前主动支持与基线模型只保留 **Qwen3 4B Instruct 2507 Q4_K_M**；0.6B/1.7B 的既有结果仅作为历史实验记录，不再进入后续设备测试或优化 A/B，也不会为了它们重新下载/保留主动测试路径。

## 可观测指标

| 指标 | 状态 | 定义 |
| --- | --- | --- |
| cold load wall | 可观测 | fresh app data、runtime 未加载时第一次 `LocalInferenceRuntimeClient.load` 的 wall time |
| warm handle reuse | 可观测 | 同一 client、同一 asset/config 再次 `load` 返回既有 handle 的 wall time；不是重新加载权重 |
| load + inspect + warm wall | 可观测 | 从第一次 load 开始直到 metadata inspect 与 warm-handle 验证完成的总 wall time；保留用于与 HXA-222 历史 `load-ms.txt` 对照 |
| generation wall | 可观测 | 一次 `generate()` 从 Harness/runtime 调用开始到最终 ModelEvent 序列返回的 wall time |
| input/output tokens | 可观测 | native 最终 JSON 报告的 tokenizer/generated token 计数 |
| effective end-to-end output tok/s | 可计算 | `outputTokens / generationWall`；**包含 prompt processing，不是 decode tok/s** |
| sampled PSS/RSS | 可观测 | `dumpsys meminfo <app>:model_runtime` 每 1 秒离散采样；只称 sampled peak，不冒称瞬时峰值 |

1 秒 `dumpsys meminfo` 采样本身会产生观察开销，因此 P5/P6 的 wall time 只在**相同采样设置**下比较；关闭采样得到的更快数字不能直接声明为模型优化收益。
| cancel → executor exit | 可观测 | 调用方取消 generation 到 runtime owner 证明 `EXITED` 且 active generation 清空的 wall time |
| unload/reload/terminate | 可观测 | 对应 runtime API 的 wall time及终态 |
| prefill time | **不可观测** | 当前 JNI 在完整 prompt decode + token generation 后才返回最终 JSON |
| true TTFT | **不可观测** | Kotlin 收到的第一个 ModelEvent 是 native 全部生成完成后的解码事件，不是第一个 native token |
| decode-only time / decode tok/s | **不可观测** | 当前协议没有 prefill/decode phase timestamps；不得从总 wall 反推 |

如果 P6 希望优化 prefill/KV/streaming，必须先增加独立 native timing/streaming contract，并重新建立 baseline；不能用本页的 effective rate 伪装对应指标。

## 固定任务集

机器可读定义见 `evals/local-model/p5-baseline-v1.json`，当前正式基线固定 5 类：

1. no-tool text：精确 `ok`，隔离最简单文本完成能力；
2. tool capability：生产 capability probe 的真实本地 tool-call round；
3. file aggregation：读取两份 CSV、聚合重复地区、写两份输出并在最后写入后回读；独立数值 oracle 不接受“Turn completed 即成功”；
4. minimal write/read + reopen：P4 固定字符串写入/回读、artifact ownership、真实进程死亡后不重放副作用；
5. cancel/exit lifecycle：取消、卸载、重新加载、runtime terminate 的终态与 latency。

其中 1/2/3/5 由当前 4B P5 run 采集；4 直接引用 P4 已保存的 4B first-success evidence。后续 A/B 不重复执行与 treatment 无关的历史事实，除非相应 contract 被修改。0.6B/1.7B 的旧 output-limit/OOM/质量失败继续保留在历史 evidence 中，但不属于正式 P5 task-set，也不再执行。

## Evidence identity

正式 P5 run 在开始时冻结：

- `gitCommit` 与 `dirty`；
- 对本地 runtime、Provider/local model、device fixture、P5 scripts/task-set 的 `sourceManifestSha`；
- task-set SHA；
- context/threads/sampling/emulator memory/cores/density；
- model SHA/size；
- app/test APK SHA、API/ABI/fingerprint/AVD；
- session config/tool surface/environment hashes。

`summarize-p5-local-model.py` 同时产生原始 `p5-summary.json` 和 HXA-227 `agent-eval-envelopes.json`。如果 run 启动时没有捕获这些 identity，仍允许生成 raw summary，但必须标为 `comparisonIdentity=INCOMPLETE`，不得进入 P6 A/B。

## 判定规则

- runtime load/probe PASS 与 task oracle PASS 独立显示；任何一个都不能替代另一个。
- `Turn=COMPLETED` 只证明 Harness terminal，不证明结果正确；反过来，文件内容 oracle 正确但 Turn 尚未 durable `COMPLETED` 也不能判 task PASS。两者必须同时满足。
- output-limit、OOM、protocol failure 等保留原始错误分类；不得通过扩大答案容差或改 oracle 获得 PASS。
- 性能对比只接受同模型 SHA、同 task-set、同 device/config/environment；候选源码/APK 可不同但必须分别保存 identity。
- 模拟器结果不能外推为手机温升、OEM 内存、续航或真机推荐。
