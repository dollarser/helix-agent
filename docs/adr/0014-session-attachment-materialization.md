# ADR-0014: 会话附件归档与模型请求物化

Status: accepted
Date: 2026-09-03
HXA: HXA-049, HXA-055～056
Deciders: Helix project owner
Supersedes: none
Superseded by: none

## Context

Helix 当前聊天 UI 和生产 `ChatService` 只产生纯文本用户消息，三套生产
`ImageResolver` 也显式拒绝图片。另一方面，`ModelRequest` 已有
`ImageReference` 契约，OpenAI Responses、OpenAI Chat Completions 和 Anthropic
Messages adapter 已经实现并测试图片 wire encoding；HXA-044 也已经提供把一次性
`content://` 流安全复制进 Workspace 的 SAF import pipeline。因此缺口不是重新实现
协议编码，而是建立从用户选择、持久化、消息绑定、请求物化到出网审查的完整链路。

附件同时跨越 Android URI、Workspace 文件、Room 消息、Context Builder、Provider
请求和出网 Policy。若只在 Compose 中保存临时 URI，进程死亡、授权失效、重试或历史
恢复会导致请求不可重现；若把图片、视频或 PDF/PPT/DOC 等二进制文档的 base64 当作
普通 `read` 结果直接回填，模型也不会因此获得视觉、时序或文档语义。该能力因此需要
明确的持久化与物化边界。

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

- 聊天附件具备可重现、可恢复、可审计的内容快照，且三协议共用一个内部模型，不在 UI
  或 adapter 中重复 Android 文件访问。
- 需要 Room migration、消息历史恢复、孤儿 Artifact 清理和发送失败/取消的生命周期
  测试；图片归一化也会消耗 CPU、内存和磁盘。
- PDF、PPT/PPTX、DOC/DOCX 在本阶段不是受支持的聊天输入。UI 可以显示“暂不支持”，
  但不能展示上传/解析进度、创建派生内容或暗示模型已读取；未来实现时不得复用当前
  `unsupported` 结果冒充成功。
- 视频在本阶段同样不是受支持的聊天输入；不得启动 Android 媒体解码、抽帧、音频提取、
  转码或 Provider 原生视频上传，也不得暗示模型理解了时间轴。
- persisted SAF tree scope 接线仍是独立能力，不能因为单文件附件完成就宣称 Agent 已能
  浏览用户长期授权目录。

## Verification

当前已执行的仓库审查确认：聊天只产生文本；生产 image resolver 拒绝图片；三协议
encoder 已有图片序列化；HXA-044 import pipeline 与 `read` 的二进制 base64 分支已存在。
这些是规划依据，不是附件功能完成证据。

决定证据：Helix project owner 于 2026-09-03 在本项目会话中明确接受 ADR-0014。该接受
只批准本记录的架构和范围，不表示 HXA-049/HXA-055～058 已实现或通过验收。

实现与完成声明前必须完成：

- Room schema export 与 migration instrumentation test；消息/附件重启恢复和哈希变化
  fail-closed fixture。
- 恶意 ContentProvider、伪造 MIME、超大/截断流、图片解压炸弹、EXIF、取消和孤儿清理
  测试。
- 三协议 image request golden tests，以及不支持 vision、能力变化和请求大小边界测试。
- API 29、API 36 与至少一台低内存真机上的图片归一化、取消和进程回收测试。
- UTF-16、PDF、PPT/PPTX、DOC/DOCX、音频、视频和未知二进制类型的统一稳定拒绝测试；
  分类必须正确，且证明字节/base64 不进入 Context、不启动解析/解码/派生任务，也不创建
  Provider upload 或模型请求。
- 出网 disclosure/binding 测试，证明内容或 Provider/origin 变化后旧决定不可复用。

## Reconsider when

- 主流 Provider 提供稳定、可验证且适合 BYOK 的原生 file upload/file ID API，并能满足
  删除、保留期、数据去向和多 Provider 可移植性要求。
- 产品所有者把 PDF/PPT/DOC 阅读或视频理解提升为明确任务，并且独立 Spike 给出文档
  解析或视频抽帧/原生上传方案的许可证、体积、质量、攻击面和 Provider 可移植性证据。
- Android Photo Picker/SAF 生命周期或应用商店政策改变，使 app-private copy 不再是可靠
  的单文件导入路径。

## References

- [总体架构](../architecture/overview.md)
- [产品需求](../product/requirements.md)
- [开发路线图](../development/roadmap.md)
- [ADR-0012](0012-capability-first-advanced-grants.md)
