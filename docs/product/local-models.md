# 设备内本地模型

在 Models 页面选择 **On-device** 后，默认可从精选目录安装固定版本的公开单文件 GGUF；当前目录包含 Qwen3 0.6B Q4_K_M 与 Qwen3 4B Instruct 2507 Q4_K_M，并固定来源 revision、文件名、大小、SHA-256 和许可证。ModelScope 与 Hugging Face 均可选。需要其它模型时使用“高级导入”，继续填写直接 HTTPS URL、准确 SHA-256 与字节数。模型权重不打包进 APK。

安装前会检查模型资产 quota/count 与 Android 可分配私有存储。下载支持 Range 续传：只有精确匹配 offset/total 的 `206` 才追加；服务器对 Range 返回 `200` 时从头覆盖。错误 Content-Range、大小、SHA-256 或 GGUF header 均 fail closed，不发布残缺资产；取消和普通 IO 中断保留当前 partial 以便相同资产恢复。精选来源仅允许各自可信 HTTPS 域族重定向；高级导入保持不跟随重定向，也不支持登录、Cookie 或认证 header URL。下载与原子发布在峰值时接近两份模型空间。

校验发布后 Helix 自动执行连接测试和能力探测。连接成功只说明资产能够由当前 runtime 加载；能力 probe 与固定任务质量仍是不同证据。安装不会自动切换任何会话：只有用户明确点击“用于当前会话”后，当前会话的后续 Turn 才使用该本地模型，其它会话与全局默认保持不变。设备内模型推理不经过网络 endpoint；模型发起的工具仍遵循会话权限、审批、effect 与审计。

首版限制：

- CPU backend，单个常驻模型、单个 generation；默认 4096 token 上下文、2 threads、greedy decoding。可在 Provider 上下文设置中手动选择 1024–32768，实际受模型上限和设备内存约束。模型内置 chat template 必须可用。
- 不支持图片、非零 temperature、自定义 stop sequences；输出在 native 生成结束后显示，暂非逐 token 流式输出。
- 单模型至多 8 GiB、总模型至多 12 GiB、最多 16 个。大小允许范围不等于设备一定能够加载；OOM/不兼容会失败，不自动改用远端。
- 内存预检失败时可降低上下文再试；不会自动吞掉用户配置。达到输出预算时保留用量并显示输出上限错误，不执行未完成的工具调用。工具格式约束不能保证计算正确，能力探测通过也不代表任务质量通过。
- “卸载”释放常驻权重，保留文件；“删除”卸载并删除资产及 Provider。模型运行中明确提示稍后重试，不删除活动模型。
- 开发期 Room v1 baseline 重建保留模型文件，启动时重新登记为未测试；名称和能力探测结果可能重置。卸载应用会删除私有文件。

已执行授权的 API36 模拟器真实 GGUF 推理、生命周期与 P3 Android HTTP 安装闭环验证。Qwen3-0.6B/1.7B 的固定 CSV 类任务存在错误计算；Qwen3-4B-Instruct-2507 Q4_K_M 在 8 GiB 模拟器、8K 上下文上完成固定任务并通过数值/回读断言，耗时约 14 分 46 秒。P3 另证明 0.6B 可由 Android 真 socket 下载、中断后精确 Range 续传、原子发布、真实 probe 并显式绑定当前会话。上述结果都不是手机性能或任意任务可靠性保证。真机温升、持续负载与 OEM 行为仍未验。当前验收边界见 [HXA-222](../completion-records/HXA-222.md)、[HXA-222 收口证据](../evidence/development/hxa222-closeout-2026-09-28.md)及[P3 安装证据](../evidence/development/p3-local-model-install-2026-09-28.md)。
