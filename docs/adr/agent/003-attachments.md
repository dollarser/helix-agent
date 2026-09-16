# ADR-AGENT-003: 附件快照与请求物化

Status: accepted
Date: 2026-09-16
HXA: HXA-049, HXA-055
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

附件需要从短生命周期平台 URI 转为稳定、可验证的会话资源，再按 Provider 实际能力进入请求。

## Decision

采用以下设计，由对应 HXA 分阶段实现：

1. 用户通过系统文件选择器或 Photo Picker 选择附件后，Helix 立即复用 SAF import
   pipeline，把字节流式复制到当前会话 app-private Workspace 的
   `input/attachments/<attachment-id>/`，校验大小、探测 MIME 并计算 SHA-256。一次性
   文件导入不依赖 persisted tree grant；`DocumentTreeScope` 只用于用户另行授权的
   长期目录访问。原始 `content://` URI 只在平台 import adapter 的短生命周期内使用；
   消息、模型 Context、审计和诊断只保留来源类别、净化后的显示名、大小和内容哈希。
2. Room 新增 `message_attachments` 关系，把 `messageId`、`artifactId`、顺序、用途和
   绑定时哈希关联起来。附件绑定后的源 Artifact 视为不可变快照；发送、重试和恢复前
   复核哈希，变化或缺失时失败关闭。大型正文和二进制仍只存文件，不进入 Room。
3. Provider-neutral 请求物化第一阶段只支持两类：通过 MIME、扩展名和有界字节 probe
   一致确认的 UTF-8 文本（首批 txt/md/csv/json）变成带来源、信任和哈希的有界
   `UNTRUSTED` context item，完整内容仍通过 `read(offset,maxBytes)` 分块；图片变成
   现有 `ImageReference`。UTF-16、PDF、PPT/PPTX、DOC/DOCX、音频、视频及其他未支持
   类型统一返回 `UNSUPPORTED_ATTACHMENT_TYPE`，并携带封闭的
   `category=TEXT_ENCODING|DOCUMENT|AUDIO|VIDEO|OTHER`，避免为每种格式扩张错误码。
   不实现文档解析、渲染、OCR、视频抽帧、音轨提取、转码、内容提取或 Provider file
   upload，也不把其 base64 放进模型 Context。文件管理器仍可把这些类型作为普通文件
   保存/分享，但聊天发送必须明确拒绝。
4. 第一版只把 app-private Artifact 解析为有界 base64/data URL，不上传到公共对象存储，
   也不让 adapter 读取 Android URI。图片在端上完成 bounds-only probe、像素/尺寸/请求
   字节上限、方向修正和元数据剥离；具体上限由 HXA-055 的 API 29/36 与真机内存测试
   固化，并取 Helix 上限与 Provider capability 中更严者。
5. 附件在用户点击发送前只保留在本地。发送动作触发既有出网 Policy，而不是自动放行：
   disclosure 必须展示 Provider、规范 origin、附件名称、类型、大小、数据类别和 scope，
   并绑定 Provider ID、origin、消息、Artifact SHA-256 与本次 Turn。附件变化、Provider
   变化或 origin 变化都重新评估；凭据类内容仍拒绝出网。该确认是模型请求的数据出网
   决策，不是 Tool Approval Proof。
6. `ProviderCapabilities.vision` 必须来自真实 probe 或用户可见的精确 Provider 配置，
   并随 Turn 保存快照。未确认视觉能力时允许本地导入/预览，但不得静默丢图、伪装成
   已发送或猜测式换协议。

## Alternatives considered

- **保留临时 `content://` URI，发送时再读取**：实现较少，但 URI 授权、来源内容和
  进程生命周期不可控，无法保证重试与审计对应同一字节，拒绝。
- **把所有附件直接内联为 base64 文本**：会膨胀 Context，绕过媒体能力与出网分类，
  且二进制文档/图片 base64 不等于语义输入，拒绝。
- **先要求用户授予整个 SAF tree**：适合长期目录 Tool scope，但对单文件/Photo Picker
  是不必要的权限和摩擦，拒绝作为附件前置条件。
- **第一版实现 PDF/PPT/DOC 解析，或视频抽帧/原生上传**：会扩大格式、许可证、
  APK/内存、恶意媒体解析、时序采样和质量验收范围。当前只预留类型和错误合同；任何
  文档或视频读取方案都必须由未来独立任务重新立项并给出依赖、体积、质量与安全证据。

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
