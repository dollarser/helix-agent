# 上下文窗口与共享存储现状核实

核实日期：2026-09-09。下文保留 HXA-172 的基线与竞品分析；HXA-173/174/176 随后按所有者要求实现以下变化。

## HXA-173/174 实现

归档保留 App 私有数据库的 session/message 记录及原文件内容，仅设置 archivedAt。会话列表的“已归档会话”可查看、选中和恢复；恢复清除归档标记，标题、消息与工作目录归属不变。

共享存储入口直接进入 Android 共享存储根目录；没有权限则打开系统授权页面，返回后重新检查权限。两个 flavor 均声明手动文件管理所需权限；该根目录只接入用户文件管理 facade，未进入 Agent resolver。当前根目录浏览/预览/分享为只读，SAF 子目录入口保留。Android 私有目录限制与商店权限申报仍适用，见 [ADR-0036](../adr/0036-manual-shared-storage-root.md)。

Provider 为精确 endpoint/model 保存窗口设置：默认 200000，默认自动压缩 80%，比例 10～95%。模型列表明确声明的数字上限优先；本地单模型 SGLang 可读取 get_server_info。未知元数据使用默认值；用户设置更小窗口时采用较小值。窗口独立于 Turn/Goal 累计预算，服务端值不会从名字猜测。

模型与圆环共用一个选项胶囊。点击圆环查看最近输入、窗口与手动“立即压缩”；Provider 设置页可选择模型、调整窗口/阈值或关闭自动压缩。每次模型请求前同步检查压力，包括同一 Turn 工具批次结算后的下一步；不等待 Turn 结束或空闲窗口。优先压缩较早历史，必要时压缩当前 Turn 已结算步骤，保留当前用户输入、最新完整工具批次及未结束的调用。工具调用和全部结果整组处理。

压力使用最终请求估算、协议/图像余量、精确 endpoint/model 的最近输入下限和同 Turn usage 比例校准，另加输出预留；这仍是保守估算而非准确 tokenizer。发布摘要要求至少节省 max(32 tokens, 原请求估算的 5%)。两次普通模型调用间最多两次摘要尝试；可恢复失败仅重试一次，费用与调用仍计入 Turn/Goal 预算。无收益后按上下文增长抑制反复压缩，只有原文仍满足硬容量限制才继续。不可缩减的当前输入、最新批次或工具目录过大时返回容量限制，Goal 保留为可继续的 PAUSED。

Goal 正常 Turn 结束按 ADR-0004 暂停并等待显式 Continue；立即 Continue 的下一 Turn 同样先检查压缩。新轮尚无已结算步骤时，可摘要最近上一轮的较早步骤，保留其最新步骤与用户原文，避免只有一轮长历史就不能压缩。压缩不新增自动唤醒，不依赖两轮之间留出时间，也不在尚未结束的模型流或工具执行中打断压缩。

摘要输出基础额度为 2048 tokens，较大上下文可增至 4096，仍受用户输出预算和窗口限制；不使用过小的 512 基础额度。对探测确认支持推理的 Provider，摘要明确选 LOW，避免省略推理参数时服务按默认强度消耗摘要额度；普通对话推理设置不变。其他 Provider 保持 OFF 的既有协议行为，不能据此声称所有服务都关闭了内部推理。

摘要不带工具，使用当前 Provider 与既有出站/Turn/Goal 预算。完整摘要成功才原子写入独立 CONTEXT_CHECKPOINT_V1 消息并关闭对应模型调用，之后请求使用不可信历史摘要加未覆盖原文。可选 preservedMessageIds 保留覆盖边界之前的当前输入等必要原文；旧 checkpoint 缺少此字段时按空集合读取。原消息/附件不删除，UI 不暴露 checkpoint JSON；无需新增 Room schema。取消、拒绝、未完整结束、预算不足不发布半成品；恢复关闭未完调用，不自动重放。手动压缩后圆环用 ≈ 标记估算，下一次真实请求以实际 usage 更新。现行决策见 [ADR-0038](../adr/0038-step-boundary-context-compaction.md)，它取代 [ADR-0037](../adr/0037-context-window-and-compaction.md)。

## HXA-172 对话基线（历史状态）

ChatRequestAssembler.buildRequest/buildBackfillRequest 读取完整持久历史，没有调用 core/agent ContextBuilder，也没有自动摘要、摘要持久化或压缩恢复。ContextBuilder 本身是按预算保留/剔除上下文项的组件，不是已经接入的对话压缩器。

ProviderCapabilities 可以保存 maxContextTokens，但 CapabilityProbe 当前明确返回 null。Provider UI 没有手动窗口设置；不会自动从模型名、模型列表或连接测试推断窗口。默认输出上限 4096 与 TurnBudgetBounds 的 128000 输入预算分别属于请求输出和轮次预算，均不是模型上下文长度。

本轮只读查询已配置 SGLang 的 get_server_info，返回 context_length=262144、max_req_input_len=262138；这是真实服务此刻的部署配置，不代表其他 Provider 或同名模型的默认值。主 App 目前不读取该非通用接口，因此不能声称已自动发现这个上限，也没有把本机调研值硬编码进产品。

新增圆环使用当前 endpoint/model 最近一次持久 ModelCall 的 inputTokens，绝不累计整个 Turn 的多次模型调用。缺少 usage 或窗口上限显示问号；异常、缺字段、负数、切换模型/endpoint 不借用旧记录。进度条最多填满，百分比保留超过 100 的情况。点击显示来源和限制：不包括后续输出、未发送输入，不能等同于下一次请求精确 token 数。它是最近一次请求的上下文输入快照，不是实时完整会话 tokenizer。

## 可参考的生产机制

- [Codex 配置参考](https://learn.chatgpt.com/docs/config-file/config-reference)：区分 model_context_window 与 model_auto_compact_token_limit，未设置压缩阈值时使用模型默认值。Helix 应同样区分服务上限、用户使用上限、输出预留和触发阈值，而不是复用轮次预算。
- [Claude Code 上下文管理](https://code.claude.com/docs/en/how-claude-code-works)：接近上限时先清理旧工具输出，再摘要；支持查看上下文和手动定向压缩。说明中明确指出早期细节可能丢失以及巨大单文件可能导致反复压缩。
- [DeepSeek Harness 压缩子系统](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/compaction.md)：以实际请求压力触发，保留必要片段，持久化摘要替代范围，保证同会话串行并处理取消与恢复。

建议后续独立实现：按 Provider 与 model 精确绑定窗口配置；服务声明优先并允许用户设置更小使用上限；按最终请求（消息、工具 schema、图像、输出预留）估算压力，结合 usage 校准；接近阈值先压缩旧工具内容，再生成可追溯摘要。保留原始历史、当前用户意图、工具调用/结果配对和审批事实；摘要永远不是权限来源。摘要写入与替换范围要原子化，取消、进程死亡和预算不足不损坏历史；不可压缩的单项过大要明确返回。该方案会改变模型可见历史及持久化契约，应单独形成 ADR 与故障/恢复验收，不能为了显示圆环静默接入。

## HXA-172 共享存储基线与分发约束（历史状态）

文件首页的新“共享存储”位置集中展示现有已授权来源，并提供选择 SAF 文件夹和跳转权限的入口。原“共享存储访问设置”只是权限页快捷链接，没有第二套权限。本轮将它放入共享存储位置内，两个 flavor 都有此入口。

[Android SAF 文档](https://developer.android.com/training/data-storage/shared/documents-files)明确限制 Android 11+ 的存储根目录、Download 根目录和 Android/data、Android/obb 等授权。选择可授权的文件夹可以访问其子树；不等于授权整个 sdcard。当前共享文件 facade 支持浏览、预览和分享，直接修改/删除尚未实现。

[Android 所有文件访问](https://developer.android.com/training/data-storage/manage-all-files)允许更广泛共享存储访问，但仍不能访问其他 App 私有目录。[Google Play 政策](https://support.google.com/googleplay/android-developer/answer/10467955?hl=en-GB)将文件管理器列为可能符合条件的核心用途，要求提交权限声明并通过审核。因此不能承诺“两包加此权限完全不影响上架”。按 accepted ADR-0013 也不能仅因存在审核就永久排除 consumer 的文件管理能力。

本轮没有扩大 MANAGE_EXTERNAL_STORAGE 的分发面、Advanced 门禁或 Agent 根目录。若推进整个共享存储的独立读写，须明确手动文件管理 facade 与 Agent scope 的隔离、两个渠道的申报方案以及实际权限拒绝/撤销、冲突/恢复测试；保留 SAF 替代路径。当前所有文件访问仍按原有 developer 能力提供，不宣称 consumer 已获得全盘访问。
