# HXA-222 失败会话分析（2026-09-28）

## 结论

已取回真实 Provider 请求、native 归一化事件、Room 轨迹与最终文件。1.7B/8K 的这次失败起点是模型第三轮生成的错误写入参数：它把每个地区的第一条订单当作地区合计。Provider 输入包含完整四条订单，未发生 Harness 历史截断；本轮也没有 compaction、OOM、输出上限或协议失败。

这些证据强烈指向模型/推理配置的任务质量问题，但还不足以唯一归因到模型权重：现有回放停在 JNI 输入与解析后输出，没有直接记录模板渲染后的 token 序列，不能据此完全排除模板/backend 的影响。继续用相同链路、更强的指令模型作对照。

## 可复查产物

隔离模拟器的合成会话位于 ignored `build/hxa222-closeout/qwen17-8g-api36/`：

- `session-replay.md`：按轮次展开的请求/工具事实/模型输出/最终文件，由 `scripts/debug/2026-09-28/render-local-session.py` 生成。
- `requests.txt`：实际 `ModelRequest`，包含 system/user/history/tools、reasoning 与输出预算。
- `events.txt`：每次 generation 的归一化事件、usage、terminal 与耗时。
- `trajectory.txt`：实际持久化 Turn / ModelCall / ToolCall，含 canonical args 和状态。
- `summary.json`、`totals.csv`、`report.md`、APK SHA、设备与 ownership 文件。

后续 fixture 另保存 `request-<generationId>.json`，即送往 JNI 的 bounded codec payload，便于精确重放；只在 opt-in 合成测试里保存，不增加生产 prompt 日志。

重新生成可读回放：

```bash
python3 scripts/debug/2026-09-28/render-local-session.py build/hxa222-closeout/qwen17-8g-api36
python3 scripts/debug/2026-09-28/summarize-closeout.py build/hxa222-closeout/qwen17-8g-api36
```

这些原始文件仅保存在 ignored build 目录，仓库中的本记录保存关键事实与来源。测试结束后 owned runner 会关闭模拟器，因此以已拉回的证据为准；它不是所有用户会话默认记录完整 prompt 的产品功能，也不包含未输出的隐藏推理。

## 原始 1.7B / 8K / 8 GiB 会话

文本和 echo probe 通过后，任务走了以下 6 轮。总计 519,132 ms，包含 setup/probe；耗时受并行主机/模拟器工作影响，不是独占 benchmark。

| 轮次 | 输入/输出 tokens | 模型动作 | 核查 |
|---|---:|---|---|
| 1 | 1352 / 39 | 相对路径读取 orders、returns | 两次成功；orders 为完整 47 bytes，`eof=true` |
| 2 | 1699 / 115 | 改为 scope 引用重复读取同两文件 | 没有需要修复的读取失败，属于冗余调用 |
| 3 | 2046 / 98 | 写 totals.csv | **第一次错误**：East=120/10/110，West=80/20/60 |
| 4 | 2276 / 154 | 写 report.md | 重复错误数字，grand net=170 |
| 5 | 2565 / 115 | 回读 totals、report | 证明磁盘与写入一致，没有重新验证源数据聚合 |
| 6 | 2995 / 52 | 文本结束 | 模型宣告结束，Turn=COMPLETED；外部数值断言 failed |

第二轮 Provider 收到的真实订单内容为：

```csv
region,amount
East,120
West,80
East,30
West,40
```

退货为 East=10、West=20。正确值一直是 East=150/10/140、West=120/20/100、grand=240。模型没有漏收后两行，而是在生成写入内容时没有把它们累加进去。工具忠实写入了模型给定的错误内容，文件回读也忠实返回了这些内容。

`COMPLETED` 表示执行协议正常结束，不表示 Harness 能自动证明任意业务计算正确；本任务的独立 oracle 正是为了检查这一区别。不能用模型自己的“已完成”代替 oracle。

## 对照与已排除项

- 1.7B/4K 的第一次错误也出现在写文件时，早于后续两次 compaction；压缩增加延迟，但不是该次漏加的最初原因。
- 8 GiB/8K 保持相同错误，说明增加可用内存没有修复聚合质量；资源不足与内容错误是两个问题。
- 明确“累加每一行、包含重复地区”的对照已生成正确订单合计，但又把 West 的退货写成 60；不能当作正确性通过。
- 第一轮相对路径已被正常规范化并成功读取，后续重复读不是路径权限修复。fixture 的目录引用末尾额外句号会把 `:.` 显示成 `:..`，已移除这一歧义；实际工具参数和返回没有使用逃逸路径。
- OFF + greedy 是当前实测配置。[Qwen 官方模型卡](https://huggingface.co/Qwen/Qwen3-1.7B#best-practices)建议非 thinking 使用 temperature 0.7 / top-p 0.8 / top-k 20；这提供了后续采样对照方向，并不证明改采样一定解决当前错误。未按模型名字给 Provider 自动授予 reasoning/tool 权限，也未将该建议强行套到所有 GGUF。

本轮先保留固定链路与固定数值答案，以 4B 指令模型对照；不通过删减订单、改答案、忽略断言或让第二个 LLM 宣告通过来关闭 HXA。

## 4B 首轮：任务内容正确，完成时限未通过

`qwen4-api36` 中 4B Instruct 生成了正确 CSV 与 grand=240 的报告，6 次工具执行均 COMPLETED，最终请求包含两个文件的实际回读内容。最后一轮 generation 未收到 terminal，fixture 在约 900 秒任务时限后进入清理，durable 采样仍为 RECEIVING_MODEL，不能判定完整通过。

JUnit 还暴露出夹具重复删除 Provider 的异常，覆盖了原始失败：inner finally 已经删除，outer finally 又删除一次。现改为仅删除仍存在的记录，并把轨迹和文件导出放入 finally，避免失败路径漏证据。15 分钟时限触发是由代码、918.546 秒总耗时与未结束轨迹共同支持的推断；原始超时异常未保留。新一轮显式给定 30 分钟 CPU 测试时限，数值与完整 terminal 断言不变。

`audit-local-requests.py` 对该轮 8 份 JNI JSON 进行核查：累计 20 条历史 ToolCall（跨请求重复计数）和 20 条工具结果；所有历史 business args 均不含 `__helix_intent`，没有把 presentation metadata 当业务参数重放。检查结果位于 `request-audit.json`；该计数不是 20 次独立执行。

## 4B 复验：完整通过

`qwen4-final-api36` 保留同一模型、8K/2 threads/greedy、相同 CSV 与数值断言；任务时限显式设置 1800 秒。实际 instrumentation 885.723 秒，6 轮模型调用、6 次工具执行后正常 stop，Room Turn=COMPLETED。两个文件均在最后写入后回读，East net=140、West net=100、grand=240。JUnit 1/1 passed；修正后的清理没有覆盖结果。

这进一步支持“1.7B 模型/配置任务质量不足”的判断，但模型权重与模板都随资产变化，并非严格隔离单一变量的实验。也不能说扩大测试时限必然修复了性能：两轮 host 负载及生成轨迹存在差异，本轮实际低于原 900 秒任务时限。运行时边界、模型业务质量与测试夹具缺陷必须分别记录。

最终回放位于 `build/hxa222-closeout/qwen4-final-api36/session-replay.md`；8 份 JNI JSON 和 Room 轨迹均已取回。完整资源与验收边界见[收口证据](hxa222-closeout-2026-09-28.md)。
