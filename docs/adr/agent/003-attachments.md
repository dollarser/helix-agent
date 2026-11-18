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
   pipeline，把字节流式复制到应用私有 app 文件域的
   `input/attachments/<attachment-id>/`，校验大小、探测 MIME 并计算 SHA-256。一次性
   文件导入不依赖 persisted tree grant；`DocumentTreeScope` 只用于用户另行授权的
   长期目录访问。原始 `content://` URI 只在平台 import adapter 的短生命周期内使用；
   消息、模型 Context、审计和诊断只保留来源类别、净化后的显示名、大小和内容哈希。
2. Room 新增 `message_attachments` 关系，把 `messageId`、`artifactId`、顺序、用途和
   绑定时哈希关联起来。附件绑定后的源 Artifact 视为不可变快照；发送、重试和恢复前
   复核哈希，变化或缺失时失败关闭。大型正文和二进制仍只存文件，不进入 Room。
3. 普通非图片附件默认只向模型提供文件名、类型、大小、哈希与可执行的来源引用，不自动注入全文或 8 KiB 预览，不按大小另设注入档位。正文由 Agent 按需调用现有文件工具读取，读取范围、截断和错误以实际工具结果为准。图片继续按模型能力发送校验后的规范化图像。PDF/DOCX 通过现有 Documents 插件按页或结构块读取，具体格式、页数/字节限制、失败与来源绑定见 [文档 ADR](../workspace/006-pdf-reading.md)。其他格式明确返回 `UNSUPPORTED_ATTACHMENT_TYPE`，保留类型化原因，不通过通用 base64 或 Provider file upload 绕过支持矩阵。文档预览、正文提取、图片视觉输入和模型确实消费分别是事实；文件管理器能保存/分享不代表模型能解析。
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

### 来源、执行路径与恢复

附件使用 Artifact ID、绑定时哈希和 `FileScopePath` 表达身份；构造 Context 不接受把完整 `scope:` 引用再次当成相对路径拼接。导入的原件及图片派生件属于会话快照，不是知识库条目或可编辑项目文件。项目绑定/切换不移动文件，也不授予额外权限。原生工具解析文件引用；终端/Python/Git 使用当前绑定目录，跨目录附件通过显式 `files_copy` 或既有 `snapshot` 输入进入执行环境，不能把 `scope:` 字符串当 OS 路径。

项目复用沿现有工具入口：用户明确要求复制后，Agent 调用 `files_copy` 将附件复制到选定项目的目录，经过原有发现、权限及执行链路；目标已存在时报错，不隐式覆盖。新会话选择该项目后可以读取项目副本；给已有会话改项目归属不自动改变其工作目录。项目副本可编辑，原附件继续作为历史快照。这里不新增附件库或专用复制按钮。Runtime 的 `workspaceMode=snapshot` 配合 `files` 将附件暂存到执行环境的 `/workspace`，按工具 schema 的文件名规则形成 shell/Python 可用路径；修改执行副本不写回附件原件，输出按既有成果导入规则发布。

备份恢复保留受限导入/派生目录内的逻辑引用与字节，沿现有恢复事务进行替换与回滚；项目及外部来源仍需重新绑定。待发附件移除、迟到导入和永久会话删除只释放无持久消息/输入或共享 Artifact 引用的快照，字节进入可恢复回收站，不自动永久清除。普通归档和项目删除不回收附件。成功拍照导入后，仅在原始字节已持久登记且哈希一致时清理相同的相机运输副本；失败时保留，不误删其他来源。

主应用启动、导入消费者开放之前，扫描封闭的附件原件与图片派生目录，将无 Artifact 登记的完整快照可恢复地回收，覆盖文件发布后登记前的进程死亡窗口及同类历史残留。不跟随符号链接，不扫描项目或任意目录；有登记的文件保留，未知结构和多文件异常目录保留并记录失败。没有可靠归属的相机源文件可能仍是待完成拍摄，不能仅因时间较早便清除。此策略不承诺全盘所有残留都已删除。

### 调研依据与取舍（2026-11-18）

所有者选择元数据加按需读取；以下是公开入口的差异，不能推断所有产品版本或未公开实现具有相同行为，也不是行业统一标准。仅借鉴设计，没有复制参考代码。

| 公开依据 | 可确认的行为与本项目取舍 |
| --- | --- |
| [DSH content.ts](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/llm/llm/src/content.ts)、[附件子系统](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/attachment.md) | `fileHandleText` 输出名称、字节数、身份摘要和当前执行环境可读路径，要求按需读取；不可达时明确报告。采用其“持久身份与执行路径分离、普通文件不默认注入正文”的思路，不照搬桌面路径、配额或存储格式。 |
| [Claude Code 文件引用](https://code.claude.com/docs/en/common-workflows#reference-files-and-directories)、[工具说明](https://code.claude.com/docs/en/tools-reference)、[hooks](https://code.claude.com/docs/en/hooks#pretooluse) | 当前公开说明的本地 @ 引用会在读取限制内加入文件内容，拒绝的二进制等只给路径；不是一律元数据。指定 hooks 页面未独立核实“统一路径模式配置”的具体契约，因此不以该说法作实现前提。 |
| [Pi CLI file-processor.ts](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/src/cli/file-processor.ts) | 非图片 @file 读取 UTF-8 并加入内容，图片独立处理。该入口偏显式全文，不作为 Helix 默认自动注入的理由。 |
| [Codex SDK README](https://github.com/openai/codex/blob/main/sdk/typescript/README.md) | `workingDirectory` 与结构化 `local_image` 分开；工作目录设置不等于通用附件入库或全文注入，不据此推断其他文件的内部处理。 |

取舍：少占用默认上下文、让读取范围成为真实工具结果；代价是需要工具可达且模型实际发起读取。没有读取结果不能声称已读全文。现有支持格式、单文件 10 MiB、每消息 4 件、图片规范化及权限/出网校验暂不扩大；没有新增自动云上传、OCR 或解析运行时。历史内联正文是历史发送证据，不改写成新策略下的请求。

### 工具图片补充（2026-09-29）

所有者接受的 [ADR-AGENT-011](011-tool-multimodal-vision-feedback.md) 扩展图片来源到可信工具产物：复用同一附件关系，以 `TOOL_OBSERVATION` 绑定 TOOL 消息，和用户 `REFERENCE` 分开。工具读取授权不等于图片出网授权；请求级哈希、目标与披露复核适用于两类来源。既有用户图片输入不是通用工具看图已交付的证明。

## Decision history

- **2026-11-18 收尾**：在消费者启动前回收封闭附件目录内无登记快照；采用可恢复移动、保留未知结构与引用，不增加全盘永久清理或相机未完成源文件的猜测删除。

- **2026-11-18**：所有者采用普通附件元数据加按需读取，取消默认 8 KiB 注入；保留图片视觉链路、项目与附件分离，修正路径构造并保持导入快照恢复身份。实现与当轮验证状态见 HXA-049 追加记录，accepted 不代表设备验收完成。

- **2026-09-29**：接受工具视觉来源补充，细则与交付范围由 ADR-AGENT-011/HXA-225 维护；不增加数据库表，不扩展文档/视频/本地多模态 Runtime 范围。

## Alternatives considered

- **保留临时 `content://` URI，发送时再读取**：实现较少，但 URI 授权、来源内容和
  进程生命周期不可控，无法保证重试与审计对应同一字节，拒绝。
- **把所有附件直接内联为 base64 文本**：会膨胀 Context，绕过媒体能力与出网分类，
  且二进制文档/图片 base64 不等于语义输入，拒绝。
- **先要求用户授予整个 SAF tree**：适合长期目录 Tool scope，但对单文件/Photo Picker
  是不必要的权限和摩擦，拒绝作为附件前置条件。
- **在附件管线内加入所有文档解析、视频抽帧或原生上传**：会扩大格式、许可证、APK/内存和恶意媒体解析边界。当前 PDF/DOCX 按已接受的[文档读取契约](../workspace/006-pdf-reading.md)由独立 Documents 插件处理；PPT/DOC、视频和通用 OCR 不因附件导入而获得支持。

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
