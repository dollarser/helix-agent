# ADR-CONNECTORS-005: 内置精选扩展市场与端侧安装契约

Status: accepted
Date: 2026-09-22
HXA: HXA-212
Deciders: project-owner

## Context

在已交付的扩展体系中（[ADR-CONNECTORS-001](001-portable-bundles.md)、[ADR-SKILLS-001](../skills/001-authoring-and-installation.md)），Helix 支持通过本地文件或 ZIP 导入 Connector 以及创作本地 Skill。但在实际使用中，新用户缺乏标准且易于发现的能力模板，需要自行构造 JSON 或编写 frontmatter。

此外，[ADR-CONNECTORS-004](004-signed-index.md) 提出的签名索引仍处于 proposed 状态且明确排除了动态市场网络运行时与 UI。为了让用户开箱即用体验典型的免鉴权公开 MCP 服务、受保护 Connector 及审查/数据库类 Skill，需要建立一个零额外网络依赖、编译期内嵌、完全遵循端侧安全边界的内置精选市场。

## Decision

接受在应用内实现本地静态内置精选扩展市场（Curated Marketplace），定义以下端侧架构与安装契约：

1. **编译期静态内嵌与零未授权网络外呼**：
   - 精选目录（`MarketplaceCatalog`）作为代码资源固化在应用内，用户在浏览市场、按类型过滤（All / Connector / MCP / Skill）以及查看说明时，不发起任何未授权的远程网络请求。
   - 内置项目包含典型代表：免鉴权公共文档检索（Cloudflare Docs）、结构化网页提炼（Web Research）、受保护代码与任务协作（GitHub Operations、Linear Workspace）以及本地代码审查与数据库只读助手（Code Review、SQL Assistant）。

2. **严格遵循安装默认未激活与凭据隔离原则**：
   - 一键安装产物直接交由 `ConnectorService` 与 `SkillRepository` 管理，安装后端点一律保持默认未激活状态（`endpoints` 需用户在配置中显式启用）。
   - 受保护 Connector 仅内置结构与占位声明，不内嵌任何硬编码真实 Token；由用户在管理页面通过系统安全存储注入凭据。

3. **幂等安装与生命周期可逆性**：
   - 安装过程为幂等事务：已安装项目重复触发时返回现有记录并确保必要组件处于正确可用状态；
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

## Reconsider when

- 远端签名索引与公钥基础设施（HXA-130）正式立项并需要支持动态第三方软件源订阅时。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [ADR-CONNECTORS-001](001-portable-bundles.md)
- [ADR-SKILLS-001](../skills/001-authoring-and-installation.md)
