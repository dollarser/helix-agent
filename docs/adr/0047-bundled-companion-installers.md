# ADR-0047: 主包内置独立组件安装包

Status: accepted
Date: 2026-09-10
HXA: HXA-190
Deciders: Project owner（明确要求主 App 内置 Subscriptions 和 PRoot 安装包，点击安装）
Supersedes: none
Superseded by: none

## Context

现有 Subscriptions 与 PRoot 是独立 APK/UID。用户要求主 App 提供其安装包及点击安装入口，降低首次安装的分发成本。当前 developer 已接入两类客户端，consumer 没有对应客户端和能力接线；本轮不改变渠道能力矩阵。

## Decision

developer 构建先生成同 buildType 的两个 companion APK，再复制到生成 assets 并嵌入主包。APK 不提交源码仓库，不使用手工拷贝的旧制品。设置提供两个用户安装/更新按钮，按固定标识选包，校验预期 applicationId、非空签名集合与主包签名一致后，通过仅覆盖专用缓存目录的 FileProvider 交给 Android 系统安装器。用户授权安装来源及系统安装确认均保留，安装行为不暴露为 Agent 工具。

组件继续独立安装和隔离，不在主 UID 中执行；不自动绑定、登录、启用 Advanced 或初始化 RootFS。PRoot 安装后使用原修复/验证入口。consumer 当前不嵌入两包或增加安装权限，也不据此宣称所有商店禁止该能力；渠道扩展仍由 ADR-0013/HXA-122 处理。

## Alternatives considered

- 仅链接下载安装：体积较小，但不满足本轮离线随包交付要求。
- 合并 Runtime 代码至主进程：减少图标和 APK，但破坏既定 UID/凭据隔离，拒绝。
- 静默安装：普通应用不能以本轮授权取代 Android 安装确认，不采用 Root 安装。

## Consequences

主包增加两个 APK 的体积，当前 Debug 原始组件合计约 66 MiB。临时拷贝原子替换为每组件一个缓存 APK，可由系统清理缓存；不覆盖用户工作区。更新仍受系统签名、版本、ABI 和存储检查约束，取消不视作成功。

本轮验证 Debug 同签名构建。Release 当前尚无发行签名配置：必须先给两个 companion 配置同一发行签名，再构建包含它们的主包；仅对最终主包补签不能让内置 unsigned APK 可安装。安装器拒绝空签名和异签名，不将 Debug 包冒充 Release 组件。发行签名/渠道验收仍属于 HXA-122。

## Verification

用户本轮需求是决定授权依据。实际构建、签名策略回归和嵌入字节核验结果记入 [实现记录](../development/bundled-runtime-installers-2026-09-10.md)。系统安装确认、来源授权允许/拒绝和升级数据保留尚待独占设备人工验收，不以主机构建代替。

## Reconsider when

发行包体积成为实际分发限制、目标渠道提出不同安装要求，或需要独立组件更新服务时，重新评估按需下载及签名/版本目录方案。

## References

- [Runtime 生命周期与隔离](0007-companion-runtime-lifecycle.md)
- [渠道能力保留](0013-standard-store-capability-preserving-distribution.md)
- [Android 安装来源授权](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())
- [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller)
