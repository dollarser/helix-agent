# ADR-0006: 直接分发只提供一个可升级到 Advanced 的主应用

Status: superseded
Date: 2026-08-31
HXA: HXA-120, HXA-122, HXA-123
Deciders: Project owner（明确要求唯一可获取主包同时支持普通与高级用户）
Supersedes: none
Superseded by: [ADR-0013](0013-standard-store-capability-preserving-distribution.md)

## Context

[ADR-0005](0005-standard-advanced-safety-profiles.md)区分了编译期 `consumer/developer` 变体和运行时 `STANDARD/ADVANCED` Safety Profile。如果普通用户只能获得 consumer APK，而高级代码只存在于不可获取的 developer APK，那么产品声明的 Advanced 能力实际上不可达；如果要求用户更换 applicationId 不同的 APK，还会引入会话、Workspace、Provider 配置和授权迁移问题。

当前工程已经有两个主 App 变体：`consumer` 裁掉高级模块，`developer` 包含高级权限 UI 和 Runtime client。PRoot 与 CLI 本身已经是独立 applicationId/UID 的可选 Runtime APK。为了保持现有代码与已验证的变体门禁，本决定不在文档任务中重命名 Gradle flavor 或修改 applicationId，而是明确各变体的发布角色。

## Decision

当前 Android 直接分发只向最终用户提供一个主应用：工程内部的 `developer` 变体。产品 UI、下载页和用户文档统一称其为“Helix”，不向普通用户展示“developer 包”概念。

该直接分发主应用：

- 首次启动和重置后始终为 `STANDARD`。
- 同时服务普通和高级用户；普通用户无需更换 APK，高级用户在同一安装内显式进入 `ADVANCED`。
- Advanced 只显示进一步启用能力的入口，不自动申请权限、请求 Root、安装 Runtime、创建 LAN scope 或扩大 Tool Registry。
- PRoot/CLI 继续作为按需安装的独立 companion Runtime APK/UID；它们不是第二个主应用，也不复制主应用数据。

`consumer` 变体继续构建和测试，但定位为未来应用商店、企业策略、合规或其他严格受限渠道的裁剪产物，不是当前直接分发的默认下载，也不提供从 Standard 进入 Advanced 的路径。它保留编译期边界验证价值，避免未来为了受限渠道临时从主包删除 Root、Accessibility、All-files 或 Runtime client。

现有 applicationId 暂时保持：`consumer=com.helix.agent`、`developer=com.helix.agent.developer`。首次对外发布前，HXA-122 必须明确直接分发主应用的最终稳定 applicationId；若要将主应用迁移到 `com.helix.agent`、交换变体 ID 或重命名 flavor，必须在发布前新增迁移 ADR、更新代码/签名/升级测试，不能只改文档。未完成该决定前不得宣称已有跨 applicationId 原地升级路径。

## Alternatives considered

1. **同时公开 consumer 与 developer 两个主应用**：二进制边界最直观，但用户必须预先判断自己未来是否需要高级能力，升级时可能需要迁移不同 applicationId 的数据。未选择作为当前直接分发体验。
2. **只公开 consumer，不提供 Advanced**：发布面最小，但与高级用户、PRoot、Root 和 Accessibility 的产品范围冲突。未选择。
3. **删除 consumer，只保留单一 universal flavor**：工程更简单，但失去受限渠道的编译期裁剪和 APK 内容门禁。当前保留 consumer，待真实渠道需求稳定后再评估。
4. **把所有高级执行器都放入主 APK**：单文件下载更直接，但破坏 PRoot/CLI 独立 UID 和凭据隔离。未选择；companion Runtime 仍单独安装。

## Consequences

- 普通用户和高级用户共享同一主应用、数据和升级路径；普通用户只看到 Standard 的安全默认。
- 当前直接分发需要完整验证 developer APK 在 Standard 下的低配置、零副作用默认，而不能只验证 consumer。
- consumer 仍需 CI、依赖裁剪和发布级扫描，但其发布门禁属于受限渠道，不阻塞没有该渠道目标的直接分发功能验收。
- developer 主包在未来声明 `MANAGE_EXTERNAL_STORAGE` 或 Accessibility Service 后，Android 系统设置可能在 Standard 下仍列出 Helix。Standard 的承诺是默认关闭、不自动申请/启用且没有 Helix scope，不是从系统设置中隐藏 manifest 声明；权限说明和首次启动文案必须诚实解释这一点。
- 下载页必须把 Runtime APK 描述为按需 companion，不得让用户误以为需要选择另一个主 App。
- `.developer` applicationId 对最终产品命名不理想，但本轮不改代码；HXA-122 的首次外发身份决定成为真实发布阻塞项。
- 本 ADR 只决定发布角色，不表示 Standard/Advanced 或高级能力已经实现。

## Verification

当前证据：

- M0 已证明 consumer/developer 可以独立构建并具有不同 applicationId，developer-only 模块不会进入 consumer。
- 项目所有者明确选择“一个用户可获取主包，普通用户默认安全、高级用户原地开启能力”的产品路径。

首次直接分发前必须证明：

- 下载清单只有一个主 App artifact，标识其工程来源为 developer variant；可选 Runtime 明确标为 companion。
- 全新安装、升级、清除配置后的主应用均进入 Standard。
- Standard 完成普通用户核心流程，不要求理解 Advanced、Root、PRoot 或 Accessibility 设置。
- 同一安装进入/退出 Advanced 不丢失会话、Workspace 或 Provider 配置，也不产生权限/网络副作用。
- consumer 仍通过禁止内容扫描，并在受限渠道构建时只提供 Standard。
- HXA-122 记录最终 applicationId、签名和升级证据；在此之前不发布外部稳定版。

## Reconsider when

- 应用商店或企业渠道成为主要分发方式，并明确禁止直接分发主包中的某些 Manifest 能力。
- 用户研究表明 Advanced 入口即使默认隐藏也使普通用户困惑，且双包迁移可以可靠自动完成。
- 维护两个变体的成本长期超过受限渠道价值。
- 首次外部发布前决定重命名 flavor 或迁移 applicationId。

## References

- [ADR-0005：两级安全配置](0005-standard-advanced-safety-profiles.md)
- [产品需求](../product/requirements.md)
- [路线与发布任务](../development/roadmap.md)
- [安全与发布门禁](../security/testing-and-release.md)
