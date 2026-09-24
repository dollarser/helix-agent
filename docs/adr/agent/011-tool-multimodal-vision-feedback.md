# ADR-AGENT-011: 工具产出多模态视觉回流与生命周期管理

Status: proposed
Date: 2026-09-24
HXA: HXA-225
Deciders: Project owner

## Context

Helix 在 HXA-060～063 中构建了完备的 12 个 `browser.*` 浏览器控制工具，并通过 HXA-055 与 [ADR-AGENT-003](003-attachments.md) 实现了用户消息维度的图片附件摄入（`AttachmentPurpose.REFERENCE`）。

然而，当前执行架构在“感知—思考—行动”（Observe-Think-Act）闭环中存在一处关键视觉断层：
1. `browser.screenshot` 工具仅将截图以 PNG 文件形式写入工作区 `artifacts/`，并向模型返回纯文本路径引用（`scope:<id>:<path>`，受限于 `maxOutputBytes = 4096` 硬上限）；
2. 请求装配层 `ChatRequestAssembler` 存在严格的硬守卫（`require(userRows.size == userMessages.size)`），仅针对 `ModelRole.USER` 行加载并绑定 `images` 列表，工具消息（`ModelRole.TOOL`）没有多模态图像通路；
3. 这导致 Agent 在执行浏览器交互、UI 自动化测试、Canvas 渲染检查或排版验证时，无法“看见”页面真实渲染结果，成为视觉盲区。

调研业内前沿系统（OpenAI Codex、Computer-Using Agent / Operator、Anthropic Computer Use 与主流 Agent Harness）：
- **OpenAI Codex CLI（官方开源实现 `openai/codex`）**：
  - **工具实现与沙盒**：在 `codex-rs/core/src/tools/handlers/view_image.rs` 中实现了内置的 `view_image`（及 `view_image_tool`）工具，通过沙盒上下文 `file_system_sandbox_context` 与 `PathUri` 读取工作区图片；
  - **缩放与模式控制**：引入 `PromptImageMode`（`Original` 与自适应缩放至 2048x768 的 `ResizeToFit`），并将图片转换为 Base64 `image_url` data URL 传给 OpenAI Responses API；
  - **踩坑教训与上下文灾难**：社区与官方工单（#24676、#41338 等）暴露了严重痛点——如果将 Base64 data URL 直接持久化并随会话历史保留，多轮交互会导致 Token 爆炸、会话压实（Compaction）极度缓慢、内存暴涨及重载时的 400 Bad Request。官方后续必须在 `PreToolUse` 等日志审计钩子中将大 Base64 遮蔽，并转向“外部存储 + 轻量引用”与按需物化；
- **Anthropic Messages API**：在协议层原生允许 `tool_result` 包含 `image` content block；
- **OpenAI Responses / ChatCompletions API**：虽然 `tool` 角色原生 content 要求为纯文本，但成熟 Harness 均采用请求装配层的“合成多模态视觉注入”（Synthetic Vision Turn），在工具文本确认后紧随一条带有 `image_url` 的只读视觉消息，从而让所有主流多模态模型获得视觉输入；
- **移动端端侧资源与预算挑战**：连续浏览器交互往往触发十数次截图。如果不设生命周期治理与滑动窗口淘汰，图片将迅速撑爆上下文 Token 预算和移动端内存。

## Decision

采用以下设计，为 Helix Agent 建立工具产出的多模态视觉回流通道与预算自适应管理体系：

1. **工具多模态产物契约与外带存储**：
   - 保持工具执行结果主体的轻量纯文本规范（`maxOutputBytes = 4096`），工具返回紧凑的执行摘要、工作区相对路径及 SHA-256；
   - 扩展 `ToolExecutionResult` / `ToolDescriptor`，允许声明 `visualArtifact: ArtifactReference?`；
   - 截屏与图像生成工具只在本地工作区安全沙盒内生成图片文件，不直接在工具返回字串中内联 Base64，隔离内存开销。
2. **请求装配器自适应多模态回流（Assembler Multimodal Feedback）**：
   - `ChatRequestAssembler` 解除“图像只能绑定用户消息”的旧单向假设，引入工具视觉产物的延迟安全物化；
   - **Provider 协议自适应映射**：
     - **Anthropic Provider**：将产物图像直接编码为 `tool_result` 内嵌的 `image` content block；
     - **OpenAI / 通用多模态 Provider**：在 `tool` 文本回执之后，自动合成一条带视觉附件的受控上下文消息（标记为 `[Tool Output: <tool_name>]`，携带 `image_url`），确保在不修改远程不可控协议的前提下让模型实时观察页面。
3. **视觉滑动窗口与预算治理（Sliding Window Budget）**：
   - 为避免自动化循环中多张截图导致上下文 Token 与显存爆炸，实施**工具视觉滑动窗口策略**（默认窗口大小 $W = 1 \sim 2$）；
   - 仅当前 Turn 最新产出的 $W$ 张工具截图在请求装配时物化为 Base64 图片；更早轮次的工具截图自动退化为纯文本引用（路径及 SHA-256，`images = emptyList()`）；
   - 用户显式上传的参考附件（`AttachmentPurpose.REFERENCE`）遵循原有 `VisionLimits` 长期有效，不与工具截图竞争滑动窗口。
4. **端侧归一化与尺寸准入**：
   - 复用 HXA-055 的端侧图片预处理管线：对截图进行分辨率约束（限制最长边不超过 1280px，推荐 WXGA/XGA 标准）、去除 Alpha 冗余通道、压缩控制单张图像体积（通常 $\le 500\text{ KiB}$）；
   - 在装配阶段对工具产物重验 SHA-256、文件存在性与 MIME 类型，任何不匹配或损坏立即 fail-closed 降级为文本，不向远端发送损坏载荷。
5. **审计与回放一致性**：
   - 数据库 Room 层只记录工具调用的产物外键引用，不持久化大尺寸图片 Base64；
   - 会话导出为 JSONL（ADR-AGENT-005）时，如实记录多模态工具产物元数据与路径，确保离线回放与评测的一致性。

## Alternatives considered

- **在工具返回值中直接内联 Base64 文本**：直接打破 `maxOutputBytes = 4096` 限制，严重污染工具调用历史、日志与数据库，且模型将其视作纯文本字符而非视觉特征，坚决拒绝。
- **强制要求所有 Provider 均支持原生工具图片**：OpenAI ChatCompletions 等成熟规范短期内并未开放 `tool` 消息嵌入图片；若强求原生支持，将导致大量流行 Provider 无法使用视觉能力。采用装配层自适应合成策略兼容性最优。
- **上下文全量保留所有历史截图**：几步交互后 Token 将迅速累积数万，导致请求变慢且端侧极易 OOM；事实证明当前决策仅需最新视觉状态即可完成闭环，滑动窗口退化机制不可或缺。

## Consequences

- **能力提升**：Agent 获得真实的页面视觉理解能力，能够识别 CSS 溢出、图表、布局异常及不可点击的视觉遮挡。
- **模块影响**：
  - 需要在 `app/chat` 中微调 `ChatRequestAssembler` 与 `ImageReferenceVerifier` 的装配拓扑；
  - 需要在 `provider/openai-chat` 与 `provider/anthropic` 编码层支持工具视觉载荷映射；
  - 需要保持变体隔离，consumer 构建零多余开销。
- **向后兼容**：不改变现有纯文本工具及既有会话数据库的存储结构。

## Verification

1. **单元测试**：
   - 装配器在多轮工具调用下仅物化最新截图、历史截图退化为纯文本引用的滑动窗口单测；
   - OpenAI 与 Anthropic 两种编码器正确输出视觉消息体的单测。
2. **集成与设备验证**：
   - 模拟器/真机端到端执行 `browser.screenshot`，断言模型后续回复能够针对页面具体视觉特征（如按钮颜色、文字排版）做出准确推断；
   - 验证滑动窗口在长交互场景下 Token 预算稳定、无内存泄漏。

## Reconsider when

- 业界对多模态工具交互形成完全统一的 API 标准协议；
- 端侧专用小型视觉模型能够在本地毫秒级输出高质量语义描述，从而免去将高分辨率图像回传云端大模型的开销。

## References

- [ADR-AGENT-003: 附件快照与请求物化](003-attachments.md)
- [ADR-AGENT-002: 模型请求上下文与步骤边界压缩](002-context-compaction.md)
- [ADR-AGENT-006: 模型结果投影、预算诊断与明确继续](006-model-data-budget-boundaries.md)
- [ADR-AGENT-010: 请求上下文清单与轻量记录](010-request-context-manifest.md)
- `reviews/2026-09-24/2026-09-24-codex-browser-vs-helix.md`
- OpenAI Computer-Using Agent (CUA) / Anthropic Computer Use API Specification
