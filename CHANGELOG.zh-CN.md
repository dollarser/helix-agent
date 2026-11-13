# 更新日志

**简体中文** | [English](CHANGELOG.md)

面向用户整理重要变化，最新版本在前，不直接堆叠 Git 提交记录；分类方式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。开发预览不承诺稳定的数据或 API 兼容性。下列已发布日期采用 UTC；未发布候选不填写发布日期。

## 0.0.11（未发布的开发预览）

[相对 v0.0.10 的变化](https://github.com/dollarser/helix-agent/compare/v0.0.10...main) · [版本说明](docs/product/releases/v0.0.11.md) · [English notes](docs/product/releases/v0.0.11.en.md)

### 新增

- 设置 → 关于 Helix 中的手动检查更新：读取 ModelScope 元数据，按当前包名和设备 ABI 选择最高语义版本；另一次点击才交给浏览器下载，不后台检查、不自动安装、不清理数据。
- 跨会话历史读取、管理、创建与任务投递的独立权限，支持任务排队及调整活动 Turn。
- 有界公开网页读取、浏览器搜索与表单操作，以及需要显式启用的搜索服务。
- 二进制 MCP Resources 以受管理的文件产物交付；技能按插件归属管理，输入框支持技能标记及斜杠发现。
- Advanced 通过已有 PRoot/Job Runtime 提供 FFmpeg 媒体工作流。
- 电脑 ADB helper 支持 Android 12+ 的非 debuggable Advanced 构建，使用应用签发的一次性、进程绑定交接票据。Standard 不包含该入口；未新增无线配对或跨进程任务恢复。

### 调整

- API 设置集中在一页，统一保存/取消，连接和模型检查分开；模型选择按服务分组。
- 工具调用使用简洁摘要，详情分组且可复制；业务内容与内部诊断分开展示，不覆盖底层工具、模型或审计原值。
- 上下文压缩、有界工具发现、缓存计量和 Goal 协调保留持久化执行与恢复边界。
- 本地发版验收必须实跑会话、成功工具结果回填、进程重启后的历史、取消、不重放和继续；非核心体验优化另行按变更检查。
- FFmpeg 随包说明反映当前 Runtime 接入及有界 Android 验证。原生文件字节和原始许可证保留，精确对应源码材料见版本说明。
- 中英文 README 聚焦核心功能、真实界面预览和 GitHub/ModelScope 下载入口；版本变化集中到双语更新日志，保留已核实的历史发布日期及明确的预览版升级风险。

### 修复

- 生产 Room 拒绝有界 `tools.cost` 诊断，导致普通会话在模型请求前失败的问题。未知诊断类型和载荷长度限制仍保留。
- 真实 SAF 搜索、授权撤销、目录交接，以及刷新和布局处理。
- MediaCodec 编码初始化调用 flush 的问题，该操作的行为仅对解码器有定义。实际支持仍取决于设备编解码器、尺寸及像素格式。

### 升级与验证说明

versionName 为 0.0.11，versionCode 和 Helix 自有内部版本保持 1。数据库结构与 v0.0.10 不同，不保证可覆盖安装或保留历史数据；不兼容主库重建须独立确认。Android 可能拒绝覆盖历史 versionCode 更高的预览包；不要仅为绕过限制而卸载，卸载会删除应用私有数据。已发布的 v0.0.10 标签和附件不变。

整合候选的双渠道已通过真实 Room Debug 核心批次、更新/关于页专项、隔离非 debuggable R8 会话检查及完整主机门禁。具体方法、设备和边界见[版本说明](docs/product/releases/v0.0.11.md#最终核心验收与历史证据)，较早的真机入口/媒体检查另行标注。签名、提交、CI 和下载身份以实际发行记录为准；本条目不表示应用或更新清单已经发布。

## [0.0.10](https://github.com/dollarser/helix-agent/releases/tag/v0.0.10) - 2026-11-06（开发预览）

### 新增

- 会话草稿关联项目，工作目录范围、来源检索及文件/结果预览更完整。
- Root 任务屏、可调预览、本地应用备份和可选的首次权限设置。
- 行内公式、离线 Mermaid/SVG 预览，以及连续听写和回复朗读改进。

### 调整

- 插件、技能和专家管理统一；会话菜单精简，需要配置时在选择能力后明确引导。
- Mobile Use 的 token/帧恢复、后台任务保留及有界诊断历史更可操作。
- 快速暂存检查和精确推送快照 hooks 与既有 CI 门禁配合。

### 修复

- 浏览器输入与归属恢复、账号变化后的模型选择、SAF 授权保留及有界完整文本读取。
- 备份中断清理和启动时数据库复检失败。

### 升级说明

versionCode 保持 1，开发签名证书不变。Room schema 兼容不覆盖旧专家载荷：升级前另存专家指令和重要内容。现有纯文本条目需要对离线数据库副本做外部一次性转换；v0.0.9 没有对应删除入口。也可暂缓升级，或明确接受私有数据丢失后全新安装。没有自动迁移或自动清理。电脑 ADB helper 在此已发布版本中仅支持 Developer Debug，不属于公开 Release APK 功能。

[中文版本说明](docs/product/releases/v0.0.10.md) · [English release notes](docs/product/releases/v0.0.10.en.md) · [完整源码比较](https://github.com/dollarser/helix-agent/compare/v0.0.9...v0.0.10)

## 更早的预览说明

即使旧下载已不再列出，历史说明仍予保留。只有发布记录能够证实时才列日期，不拿构建或提交时间代替。

- **[0.0.9](https://github.com/dollarser/helix-agent/releases/tag/v0.0.9) - 2026-10-29：**[中文](docs/product/releases/v0.0.9.md) / [English](docs/product/releases/v0.0.9.en.md)。
- **0.0.8：**[中文](docs/product/releases/v0.0.8.md) / [English](docs/product/releases/v0.0.8.en.md)。
- **0.0.7 - 2026-10-24：**[中文](docs/product/releases/v0.0.7.md) / [English](docs/product/releases/v0.0.7.en.md) / [历史发布记录](docs/evidence/development/v0.0.7-publication-2026-10-24.md)。
- **0.0.6：**[中文](docs/product/releases/v0.0.6.md) / [English](docs/product/releases/v0.0.6.en.md)。说明分别保留原预览与后续重发的事实。
