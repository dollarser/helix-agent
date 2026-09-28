# ADR-MCP-001: MCP Client、传输与工具接入

Status: accepted
Date: 2026-09-16; revised 2026-09-28
HXA: HXA-070
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

MCP 需要 Android 可运行的 Client 底座，同时保持 core 与外部 SDK 解耦。

## Decision

采用 io.modelcontextprotocol:kotlin-sdk-client，HTTP 使用 Ktor OkHttp engine；版本以依赖清单和锁文件为准，不依赖 umbrella/server artifact。extensions:mcp 对外只暴露 McpClientFacade、McpClientSession 与 Helix 值类型，SDK/Ktor/OkHttp/JSON-RPC DTO 保持内部。

Client 不声明未实现的 sampling、elicitation 或 roots。initialize result 是协商版本事实源，按 request ID 提取并同步会话快照和传输 header；缺失版本时失败并关闭资源，不猜服务端版本。

工具发现、搜索与会话曝光复用统一 Registry；启用/禁用在 schema、搜索、缓存和实际调用中一致，外部 hints 不能授予权限。配置与安装流程看 Skill 决策，操作授权看权限决策。

模型 schema 的硬上限继续为64，不把全部已注册工具按顺序塞满。默认保留 Files 核心读写/搜索、Goal 生命周期、工具结果读取、JavaScript、Plan/Todo、Skill 读取和时间；当前内置默认集合上界19个。用户已有有效自动化会话时加入完整9个 UI token/snapshot/action 契约，避免动作可见而快照被截断。模式、功能开关与用户禁用先于曝光选择，优先级不注册工具或扩大能力。

`tools.search` 复用同一注册表，覆盖已启用的内置、MCP、A2A 工具；精简关键词/完整名称匹配，搜索自身不进入结果。搜索后的最多16个 schema 替换当前会话加载窗口，其他会话不继承；下一次模型请求重新校验版本、当前模式和可用性。MCP 小目录（最多16项）保留直接曝光，大目录继续按需发现。基础集合、自动化组、小目录和加载窗口合计上界60，仍保留最终64 hard cap。进程重开不持久保留窗口，重新发现；搜索和加载从不授予执行许可。

依赖排除 ktor-server-websockets 必须由 Client fixture 和 Android/R8 检查证明可行；升级后若依赖链变化重新评估。仅对明确的可选 JVM TLS provider/日志 adapter 做定点 missing-class 处理，其他错误保持门禁。

## Alternatives considered

直接实现最小 HTTP/JSON-RPC 可减少 SDK 依赖，但需要自行维护 session、SSE、取消和恢复，仅在 SDK 无法满足 Android 条件时评估。采用 Server/umbrella 依赖扩大无关能力，不采用。固定失配的旧依赖不是兼容方案；需要升级时同步整个实际依赖链并验证。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)

## Decision history

- 2026-09-28：所有者明确授权优化工具数量与曝光。AndroidWorld 小样本发现64截断留下 `ui.click` 却丢掉 `ui.snapshot`，且旧搜索仅支持MCP。收敛为基础集合、已开启自动化组和统一按需发现；不增加上限，不改变 Dispatcher/approval/effect owner。证据见[公开评测试跑](../../evidence/development/public-agent-pilot-2026-09-28.md)。
