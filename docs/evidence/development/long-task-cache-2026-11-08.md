# 长任务缓存前缀与计量验证

2026-11-08。以下原缓存阶段延续[网页与客户端交付](continue-delivery-2026-11-08.md)当时未提交的工作树，保护原有网页、MCP Resources、UI 与 SAF 修改；该阶段未提交、推送、发布或升级版本，也未关闭原自然 stderr 诊断失败。后续日志修复与独立 Debug 收尾见该证据接续，不能将诊断改善归因于缓存优化。

## 诊断与选择

- 留存的最终自然修复 10 个真实请求：系统文本 10,612 字符逐轮相同，历史持续追加；第 3 个请求发现新工具，工具表从 6 项变为 7 项。不能把该样本的问题归因于动态时间戳，也不能从 HTTP 200 或相同文本推导命中。旧证据未保留原始服务端缓存 usage，历史命中率未知。
- 发现可避免的变化：工具曝光在同一优先级按发现顺序排序；语义相同 schema 的对象键沿来源插入顺序发送；长固定工具说明位于插件动态内容之后，文件说明开头含目录事实。
- 改为同优先级按工具名排序、最终 schema 使用已有规范化器（仅对象键排序，数组和历史参数不改）；固定说明前移，目录独立为每次请求新读的 section。工具成员、模式、权限、版本、选择预算保持现行逻辑。
- 工作目录、项目/Memory/Skill 变动、Job 最新观察、工具撤权、图片披露/窗口与压缩仍会改变模型输入。当前恢复身份与安全优先，不以缓存为理由保留过期能力或改写原历史。新增工具本身也可能导致服务端 tools 前缀失效，本轮不冒充原生 deferred tools，也不把全部工具常驻。
- ModelEvent 与原有 ModelCall usage JSON 保存可选缓存读取/写入数。缺失、非法和明确 0 分开；OpenAI 不重复加缓存输入，Anthropic 聚合只计一次。服务端数值本身不是独立计费账单。

## 主流方案与协议差异

资料于本日核验，设计参考而非复制实现：

- [OpenAI Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)：早期模型的隐式缓存与新模型 Responses 的 `prompt_cache_options`、显式断点不同；缓存读写和不同 TTL 的成本也不同。只读官方 usage 的 `cached_tokens` 与 `cache_write_tokens`，此次不注入请求参数或改变留存策略。
- [Anthropic Prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)：tools → system → messages 的前缀、显式块/自动移动断点、最低长度与有效期均有模型/服务边界。兼容 Messages 不证明支持其缓存参数；此次保留完整输入聚合并支持服务端累计字段更新。
- [Codex agent loop](https://openai.com/index/unrolling-the-codex-agent-loop/) 与 [Claude Code caching](https://code.claude.com/docs/en/prompt-caching)：确定性工具顺序、稳定指令层、追加历史与阈值压缩是可借鉴基础；不能直接迁移各自服务端专用机制。
- [Manus 上下文工程](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus)：稳定前缀、确定性序列化和追加结果；其工具 masking 依赖运行环境能力，不用于绕开 Helix 撤权或模式过滤。
- [vLLM 自动前缀缓存](https://github.com/vllm-project/vllm/blob/main/docs/features/automatic_prefix_caching.md) 与 [SGLang session radix cache](https://github.com/sgl-project/sglang/blob/main/docs/docs/advanced_features/session_radix_cache.mdx)：引擎缓存由实际部署决定；兼容端点的会话标签、统计开关或专用参数不能通用发送，session 标签也不替代完整请求历史。

## 已执行验证与边界

- 独立只读审查未发现阻塞项，确认实时筛选、原 descriptor/binding、数组顺序、预算总量与未知计量语义保留；审查不是额外测试通过。
- `full-host-2` 的 `scripts/check-all.sh --all` 退出 0，输入在门禁期间未变，双渠道 Debug/Release 候选分别绑定。App XML 为 Consumer 1,645 项、Developer 1,843 项，各 0 failure/error、5 项既有 skip；Core model 179、agent 366，Anthropic 91、OpenAI Chat 74、Responses 73 项各无失败/错误/跳过。门禁可复用未变的 Gradle 结果，不把 UP-TO-DATE 记成重新执行全部测试。
- 已跑通 64 轮工具顺序扰动与三种真实 encoder、64 轮新鲜目录/插件/Skill 内容、64 轮追加历史，以及服务端 usage 缺失、0、非法字段与 Anthropic 累计合并。既有压缩、恢复、披露与授权测试仍由完整门禁覆盖，不新增强制压缩流程。
- 可复现 ACT/file-tools/单插件合成字节对照：目录变化的公共 UTF-8 前缀由 4,299 到 7,339 字节；插件说明变化由 7,005 到 7,472 字节。该结果仅衡量组装文本，不是模型 token 或服务器命中率。
- `isolated-build-1` 成功且输入未变；API36 `real-sequence-1` 使用隔离包和现有授权账号，对 Qwen3.8-27B / OpenAI Chat Completions 执行 4 次真实合成请求（两次相同、两次追加）。输入数依次为 10,434、10,434、10,461、10,488；缓存读取依次为未知、10,432、10,432、10,432。全部返回可见回复且无模型错误，缓存写入数四次均未知，不当 0。账号生命周期 setup 另有一次原有验证调用。
- 以上真实请求直接经过生产 Provider/encoder，不是 ChatService 工具循环的前后 A/B，也不是所有长任务、官方 OpenAI/Anthropic 或全部自托管部署的命中收益证明。没有原始服务端 usage 的历史请求不回填命中数字；本轮不推断费用。
- 隔离账号退出、加密会话 absent、测试阶段日常包不变均通过，cleanupErrors 为空。随后 `daily-install-1` 将完整门禁的 Developer Debug 保数据覆盖到 `Helix_API_36`，安装字节核验和 firstInstallTime 保留通过；没有卸载、清数据或真机操作。隔离测试包保留且已登出。
- 最初定向测试的缺失 import、受限 loopback 运行失败、无效测试请求/MCP origin/旧目录断言，以及 `full-host-1` 的两项静态错误均保留原日志；修复后复验，不将失败改写为通过。最终源码未再变动，之后仅补录文档事实并重跑 source 门禁。Release APK 构建/边界通过不代替 Release 设备行为验收。

保留的本地输入清单、失败日志、候选哈希和完整回执位于 ignored `build/validation/long-context-cache/`。不把 JSON 前缀字节数当 token 命中率，不外推未测试的官方付费模型、其他部署、OEM 或真机。

## 2026-11-08 真实 ChatService 连续任务观测

后续 `build/validation/closeout-2026-11-08/live-resources-5-trace/` 使用 Qwen3.8-27B 完成四轮 ChatService 网页/MCP 文本任务，六次真实 HTTP 200，正文、来源、下一请求消费与答案等严格断言整轮通过。它补充了上文直接 Provider 合成序列之外的工具循环证据；六份已交付 SSE 均保留，终止原因为两次 tool_calls、四次 stop。

后续三轮服务端报告输入 token 为 12,417 / 12,537 / 12,657，缓存读取为 12,096 / 12,416 / 12,480，分别约占 97.41% / 99.03% / 98.60%。缓存写入均未知，不当零；这些是该服务报告的计量，不是独立账单，也不是所有请求的汇总命中率。

这是优化后单次四轮工具任务的实际缓存复用，不是优化前后 A/B，不能证明因果收益、费用节省或稳定延迟改善；没有据此声称覆盖强制压缩或任意长任务。较早 `live-resources-3` 也有真实内容及缓存观测，但整体 JUnit 失败保留，不拿它替代最后严格验收。`live-resources-4` 的真实早停仍开放；带已交付 SSE 留痕的新成功不能倒推无 SSE 失败轮的原因，也不保证自主完成可靠性。

最终 Debug 门禁、隔离授权/账号清理、日常保数据覆盖及历史前置/验收器失败统一见[收尾证据](continue-delivery-2026-11-08.md)。本轮不继承原缓存阶段的 Release 通过结论，无真机、推送或发布。
