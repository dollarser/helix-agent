# ADR-RUNTIME-001: 执行域、打包、生命周期与结果对账

Status: accepted
Date: 2026-09-16
HXA: HXA-083, HXA-084, HXA-085, HXA-086, HXA-103, HXA-110, HXA-111, HXA-112, HXA-113, HXA-117, HXA-190, HXA-193
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

单应用体验需要清晰区分进程崩溃边界、UID 权限边界和模块凭据所有权。

## Decision

- developer 是单 APK，Subscriptions 与 PRoot 为 Android library，分别在 non-exported :subscriptions/:proot 私有进程中按需运行。consumer 的依赖、manifest、dex、assets/native 制品排除两模块。模块名不决定是否独立 APK。
- 私有进程共享应用 UID、文件系统权限和网络能力。PRoot 是可信开发者执行环境，不是恶意代码沙箱，不保证离线、只读工作区或订阅凭据隔离。QuickJS 使用 isolated UID；生成代码不在主进程执行。
- 凭据由订阅模块管理，正常 API 不返回 token；同 UID 代码仍可能访问应用数据。可用模式不能虚构运行时隔离，限制写/网络须有真实机制或拒绝无法约束的调用。
- 冷绑定只由用户验证、修复、登录或获准执行发起，启动应用、切换 Profile、被动 Registry 不启动执行。Runtime 进程不执行主应用 AppContainer/Room 的启动恢复。
- 私有 Binder/PFD 传输有界、校验完整性并绑定 Job ID、generation、目标与 owner。主应用拥有任务和授权，Runtime 拥有执行及退出事实，UI 仅发出用户意图与观察。
- 同步 Job 在 owner 丢失后遵守取消与结算；明确获准的 detached Job 由有期限 Runtime owner 管理，不把所有命令默认为持久后台任务。仅在 Android 实际允许的运行窗口启动/保活，系统拒绝、超时、停止后如实释放。
- Binder 断开、超时和进程死亡查询原 Job；未知副作用进入待核查，不盲目重放。终态、取消、失败、EOF 和未知结果都持久化，导回输出经身份、哈希、scope 与冲突检查。
- 安装/修复/更新的资源与执行锚点必须对应真实当前环境，不沿用不可验证的凭据或运行证明。数据转换保留用户文件和结果；无需维护跨 APK 的安装与兼容执行路径。

## Alternatives considered

多个独立 Runtime APK 增加安装与升级成本，不作为当前形态；把 PRoot 改成 isolatedProcess 不能自动提供可用 RootFS/文件访问；把生成代码放主进程不符合执行边界。官方 CLI 原生/PRoot 候选不单独保留为当前必做要求。

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
