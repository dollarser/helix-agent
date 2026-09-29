# ADR-AGENT-011: 工具产出多模态视觉回流与生命周期管理

Status: accepted
Date: 2026-09-24
Accepted: 2026-09-29
HXA: HXA-225
Deciders: Project owner

## Context

Helix 在 HXA-060～063 中构建了完备的 12 个 `browser.*` 浏览器控制工具，并通过 HXA-055 与 [ADR-AGENT-003](003-attachments.md) 实现了用户消息维度的图片附件摄入（`AttachmentPurpose.REFERENCE`）。

本提案创建时的执行架构在“感知—思考—行动”（Observe-Think-Act）闭环中存在以下视觉断层；这段是历史问题描述，不替代 HXA-225 的当前实现与验证状态：
1. `browser.screenshot` 工具仅将截图以 PNG 文件形式写入工作区 `artifacts/`，并向模型返回纯文本路径引用（`scope:<id>:<path>`，受限于 `maxOutputBytes = 4096` 硬上限）；
2. 请求装配层 `ChatRequestAssembler` 存在严格的硬守卫（`require(userRows.size == userMessages.size)`），仅针对 `ModelRole.USER` 行加载并绑定 `images` 列表，工具消息（`ModelRole.TOOL`）没有多模态图像通路；
3. 这导致 Agent 在执行浏览器交互、UI 自动化测试、Canvas 渲染检查或排版验证时，无法“看见”页面真实渲染结果，成为视觉盲区。

2026-09-29 根据所有者接受与实施要求重新核验一手资料：Codex `view_image` 提供专门像素读取；Claude Code Read 可读取图片；OpenCode V2 的 read 有图片归一化与限制，而其直接附件按当前文档绕过该归一化。Helix 复用自己的附件预处理器属于本项目选择，不是照抄竞品两条入口的行为。这些支持“发现文件/产物 → 受控读取 → 真正视觉输入”的能力，不证明任意文件工具返回 Base64 就等于看图。

原提案将 Responses 与 Chat Completions 一概视为纯文本工具结果并不准确：当前 Responses 官方 SDK 的 `FunctionCallOutput.output` 类型接受文字或图片/文件项列表，Codex 的 view_image 也返回原生 InputImage；Anthropic 支持 `tool_result.content` 内图像；Chat Completions 使用独立 wire 投影。删除未经本次独立核验的具体社区工单、统一协议断言和“仅最新图片一定足够”的结论。

## Decision

1. **通用入口与共享管线**：增加 `view_image(path)`，支持已授权 Workspace 中的照片、图表、截图；相对路径沿用请求工作目录冻结与 FileToolArguments 规范化，绝对路径、越界和任意 URL 不放行。`browser.screenshot` 复用相同本地准备过程；生成图表可随后调用 view_image。普通 read 仍是文本/字节读取，不伪装为视觉工具。
2. **工具 sidecar 与持久绑定**：可信 executor 的 Completed/BoundToolResult 携带类型化 `VisualArtifact`，不扫描任意 MCP JSON 寻找上传指令。消息历史仅保留 ID、哈希、尺寸、类型等事实，TOOL 消息与 `message_attachments` 的 `TOOL_OBSERVATION` 绑定同事务提交。复用 Artifact、内容存储与现有引用回收，不新增数据库表；原执行结果不被后续披露或模型文本改写。
3. **像素准备**：源流读取在大小门内完成，受 scope、取消与期限控制；归一化复用 ImageNormalizer（EXIF/方向/解码/像素/字节上限），串行限制大图解码峰值。快照写入宿主管理的独立产物，不修改源文件。沿用已验证 VisionLimits，不在本次凭经验强降到 1280px/500KiB，也不声称能识别任意小字。
4. **协议映射**：Responses 原生 `function_call_output` 图像数组；Anthropic 原生 tool_result 图像块；Chat Completions 在同一批全部 tool receipt 之后追加带原 tool/call 标记的只读视觉观察。后者只存在于请求编码，不成为新的持久 USER 消息、用户输入或权限。协议失败明确暴露，不暗中切协议。
5. **窗口与预算**：首版仅物化当前 Turn 最新两张工具图像；更旧或跨 Turn 观察保留引用与“本次未展示像素”提示，可再次 view_image。用户参考图不被工具窗口淘汰，但所有图片共用总请求字节与模型上下文预算。窗口大小是当前策略，不是证明两张永远足够；无图、超限或不支持不能宣称看过。 未展示原因用封闭的 `ToolImageOmission` 请求投影表示，canonical TOOL 文本和调用身份保持原样；编码及容量估算使用带提示的 modelText，压缩匹配仅忽略类型化视觉投影，不忽略任意正文变化。
6. **独立数据披露**：读文件/截图权限不授权发送像素。对将发送的具体图像、hash、Provider endpoint/配置、实际模型、session/turn/message 建立现有 InteractionReceipt 的专用披露身份，用户明确允许才发送。回执不进入工具 Approval、不放大后续权限；同一短期有效绑定复用，不每轮重复询问；拒绝/超时/旧 pending 不自动重问或上传。
7. **发送前再验证**：ImageReference 携带逐请求来源而非依赖全局 session 指针。物化时读取确切消息关系，复核会话、Turn、目标配置、视觉能力、有效披露、长度、MIME 与同一份实际字节的 hash；取消/终态和被篡改图像禁止出网。拒绝/窗口淘汰可带显式无像素说明继续，完整性损坏则失败关闭，不偷偷当作已观察。
8. **恢复、渠道与边界**：consumer/developer 共享本机能力；没有图片的请求不产生额外视觉工作。进程死亡终结旧 Turn，新 Turn 不继承工具图片披露，按需重新读取。内置本地 Runtime 本次仍不支持图片，不能通过手工勾选获得编码能力；MCP 自动图像接入、Mobile Use 截屏获取、视频/OCR服务不在本次范围。 现有订阅图像传输也必须使用相同的逐请求解析器；进入 Runtime 的是校验后的不可变字节快照，不传递本机授权对象，同内容可去重但相同引用的不同字节必须拒绝。

## Alternatives considered

- **在工具返回值中直接内联 Base64 文本**：直接打破 `maxOutputBytes = 4096` 限制，严重污染工具调用历史、日志与数据库，且模型将其视作纯文本字符而非视觉特征，坚决拒绝。
- **要求所有 Provider 同一种图片工具格式**：拒绝；独立适配协议，不承诺所有兼容端点支持相同扩展。
- **全历史图片常驻**：拒绝；有界窗口与全请求预算，旧引用可再次显式读取。
- **为了看图等待完整 Observation/Node/R2 平台**：拒绝；按当前稳定管线完成纵向能力，未来保留类型化引用接口。
- **取消图片出网确认以减少点击**：拒绝；当前接受的数据披露要求必须保持，权限预设不等于数据上传授权。

## Consequences

- 从“产物文件存在”提升到“像素能够进入模型请求”；实际识别质量由模型与输入决定，代码接线不等于真实模型验收。
- 修改 model/tool result DTO、三个协议编码、工具准备、消息绑定、窗口、披露及字节解析；不新建 AgentLoop/权限系统或 schema 表。
- 共享归一化、窗口和控制规则有额外资源成本，按实际测试记录，不承诺 consumer 零开销。

## Verification

1. **单元测试**：
   - 装配器在多轮工具调用下仅物化最新截图、历史截图退化为纯文本引用的滑动窗口单测；
   - Responses、Chat Completions、Anthropic 三种编码器正确输出视觉消息体，保持完整工具配对；订阅 IPC 和本地纯文本路径不丢失无像素说明。
   - 覆盖源流变大/截短/同尺寸篡改/MIME 伪装/取消、上下文压缩匹配、拒绝/过期披露、目标变化和请求级绑定。
2. **集成与设备验证**：
   - 模拟器/真机端到端执行 `browser.screenshot`，断言模型后续回复能够针对页面具体视觉特征（如按钮颜色、文字排版）做出准确推断；
   - 验证滑动窗口在长交互场景下 Token 预算稳定、无内存泄漏。

## Reconsider when

- 业界对多模态工具交互形成完全统一的 API 标准协议；
- 端侧专用小型视觉模型能够在本地毫秒级输出高质量语义描述，从而免去将高分辨率图像回传云端大模型的开销。

## Decision history

- **2026-09-29**：所有者明确接受并授权完善需求与实现；从浏览器截图扩为通用 view_image＋共享工具观察，修正 Responses 原生输出假设，增加数据披露和逐请求绑定。实施与证据见 [HXA-225 主机完成记录](../../completion-records/HXA-225.md)；设备和真实识别仍需独立证据，不是接受即完成。
- **2026-09-24**：初始 proposed 提案；当时未形成正式任务规格。

## References

- [ADR-AGENT-003: 附件快照与请求物化](003-attachments.md)
- [ADR-AGENT-002: 模型请求上下文与步骤边界压缩](002-context-compaction.md)
- [ADR-AGENT-006: 模型结果投影、预算诊断与明确继续](006-model-data-budget-boundaries.md)
- [ADR-AGENT-005: 请求上下文清单与轻量记录](005-session-jsonl-export.md)
- [Codex 浏览器能力 vs Helix](../../research/modules/04-tools-browser-and-extensions.md)
- [Codex view_image](https://github.com/openai/codex/blob/main/codex-rs/core/src/tools/handlers/view_image.rs)
- [Claude Code Tools](https://code.claude.com/docs/en/tools-reference)
- [OpenCode attachments/read](https://opencode.ai/v2/docs/attachments)
- [Responses function calling 流程](https://developers.openai.com/api/docs/guides/function-calling)
- [Responses 官方 SDK 输出类型](https://github.com/openai/openai-python/blob/main/src/openai/types/responses/response_input_param.py)
- [Anthropic 工具结果内容](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)

本次一手资料核验日期 2026-09-29；只借鉴契约，不复制竞争项目实现。
