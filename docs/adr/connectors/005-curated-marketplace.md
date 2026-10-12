# ADR-CONNECTORS-005: 内置精选扩展市场与端侧安装契约

Status: accepted
Date: 2026-09-22; revised 2026-10-13
HXA: HXA-212, HXA-281
Deciders: project-owner

## Context

在已交付的扩展体系中（[ADR-CONNECTORS-001](001-portable-bundles.md)、[ADR-SKILLS-001](../skills/001-authoring-and-installation.md)），Helix 支持通过本地文件或 ZIP 导入 Connector 以及创作本地 Skill。但在实际使用中，新用户缺乏标准且易于发现的能力模板，需要自行构造 JSON 或编写 frontmatter。

此外，[ADR-CONNECTORS-004](004-signed-index.md) 虽定义了离线签名索引与验证规则，但明确排除了动态市场网络运行时与在线中心化市场 UI。为了让用户开箱即用体验典型的免鉴权公开 MCP 服务、受保护 Connector 及审查/数据库类 Skill，需要建立一个零额外网络依赖、编译期内嵌、完全遵循端侧安全边界的内置精选市场。

## Decision

接受在应用内实现本地静态内置精选扩展市场（Curated Marketplace），定义以下端侧架构与安装契约：

### 用户导入的发现目录（HXA-281）

Owner要求复用Codex、Claude Code和WorkBuddy插件市场，明确先支持导入、Skill和可运行MCP，不增加MCP App页面。增加用户选取的市场ZIP/JSON发现源，复用有界归档/目录及JSON校验；相对来源从同一快照解析，市场不能当作一个插件整体安装。添加、浏览、移除目录不安装/卸载插件，不自动联网、授权或连接服务。

相对来源包含的实际插件可逐项预览与安装；远程Git/archive/npm/command等来源保留发现信息，用户下载原包后在条目内选择，校验名称及当前目录快照再安装。不提供后台抓取、账号克隆、自动更新或桌面运行时安装。市场名/格式与条目名组成稳定安装身份，不按同名或URL合并不同来源。目录更新与已安装版本分别反馈，安装/update仍走原PluginService及Room提交、revision、取消和归属规则；移除发现源保留已安装记录及会话选择。

发现源以本机私有文件的准备后投影保存，原输入hash作来源事实，投影checksum只检测意外损坏，不证明可信来源。只保存解析后的端点/凭据需求/不可逆绑定指纹和Skill资源，不存原MCP认证头/Token或复制账号。候选发布用同卷原子替换，提交前取消或失败保留旧源，重开读取已发布投影；不声称断电持久性或Shared UID隔离。遵循原16MiB输入/展开及单文件、路径、深度等边界，本机发现数据有64MiB存储预算，非整个请求工具计数上限。

市场不展示插件全局启停。安装不代表已连接/已选择/本请求已加载；“在会话中使用”走现有来源面板，组件配置/登录走原管理入口。所选版本与已安装版本如实展示，其他宿主App映射/MCP App UI/hook/stdio等限制可展开查看。固定精选项不保留未发布服务的占位入口；既有安装仍可在管理页处理。

1. **编译期静态内嵌与零未授权网络外呼**：
   - 精选目录（`MarketplaceCatalog`）作为代码资源固化在应用内，用户在浏览市场、按类型过滤（All / Connector / MCP / Skill）以及查看说明时，不发起任何未授权的远程网络请求。
   - 内置项目包含免鉴权公共文档检索（Cloudflare Docs）、受保护代码与任务协作（GitHub Operations、Linear Workspace）以及本地代码审查与数据库只读助手（Code Review、SQL Assistant）；不将没有发布服务的占位域名当作可用精选项。

2. **严格遵循安装默认未激活与凭据隔离原则**：
   - 一键安装产物直接交由 `ConnectorService` 与 `SkillRepository` 管理，安装后端点一律保持默认未激活状态（`endpoints` 需用户在配置中显式启用）。
   - 受保护 Connector 仅内置结构与占位声明，不内嵌任何硬编码真实 Token；由用户在管理页面通过系统安全存储注入凭据。

3. **幂等安装与用户主动版本替换**：
   - 安装过程保证相同哈希幂等：相同版本重复触发时返回现有记录；
   - 系统不限制降级安装：支持用户自由安装旧版本或不同版本；当目标条目哈希与已安装版本不一致时执行版本替换，成功后展示目标版本；已安装卡片展开后提供「重新安装」操作支持原地覆盖/降级；
   - 卸载 Connector 联动清理对应端点与启用状态；在底层 Connector 记录被移除后，市场卡片状态精准恢复为 `NOT_INSTALLED`，保证用户可重新安装。

4. **交互联动与防抖保护**：
   - 扩展管理页（`ExtensionsScreen`）提供「发现市场」与「已安装与自定义」双 Tab，未激活项提供一键跳转管理配置入口；
   - 安装动作具备状态锁防重（Debounce）与安装中状态反馈，杜绝并发重入。

## Alternatives considered

- **远端动态拉取市场 JSON**：因引入未经用户明确授权的不可信网络依赖和动态解析风险，暂不采用；
- **安装时自动激活全部端点**：违反 Helix 权限与能力准入底线（未授权端点不得隐式连接网络），不采用；
- **将 Skill 直接作为内置固定指令（BuiltInSkills）**：破坏 M7/M9 内置核心 Skill 基线断言，且失去按需安装/卸载的灵活性，不采用。

## Consequences

- 用户获得开箱即用的典型 MCP、Connector 与 Skill 发现和一键安装能力；
- 安装后的扩展完全复用现有的安全治理、审批哈希与审计追踪管线；
- 不影响 consumer/developer 变体隔离，不新增未授权的网络执行域。

## Verification

- 单元测试：`MarketplaceCatalogTest` 验证全部精选目录项的 JSON 有效性、字段完整性、资源 ID 及保护凭据标记；
- 设备测试：`MarketplaceDeviceTest` 验证在 Android 环境下 MCP 与 Skill 项的安装、激活状态联动、幂等重装及卸载后的生命周期恢复；
- 静态与门禁：`./scripts/check-i18n.sh` 三语资源对齐与 0 硬编码 CJK 扫描，`spotlessCheck`、`detekt` 与 `./scripts/check-all.sh` 全量通过。

实现边界澄清：212初始交付的先卸载再安装已由[HXA-129](../../completion-records/HXA-129.md)替换为准备后一次发布、引用保护及可重试清理，并通过规定本地验收，现已整合到 main。契约见 accepted [ADR-CONNECTORS-003](003-ownership-and-installation.md)；主动重装及降级能力保持。

## Reconsider when

- 远端签名索引与公钥基础设施（HXA-130）正式立项并需要支持动态第三方软件源订阅时。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [ADR-CONNECTORS-001](001-portable-bundles.md)
- [ADR-SKILLS-001](../skills/001-authoring-and-installation.md)

## Decision history

- 2026-10-13：Owner明确要求复用主流插件与市场，并选择先支持内容/可运行MCP、显示其他组件限制。接受用户主动导入的离线发现源和逐项原包安装，维持无静默网络、Room安装权威及原权限链；不引入在线签名市场、依赖执行器或MCP Apps宿主。源删除不卸载实际能力是Helix的保留数据选择，与参考宿主行为明确区分。
