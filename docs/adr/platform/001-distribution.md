# ADR-PLATFORM-001: 产品完整性与渠道分发

Status: accepted
Date: 2026-09-16
HXA: HXA-120, HXA-121, HXA-122, HXA-123
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

能力优先的单设备产品需要最小渠道差异，同时如实表达当前制品与发布证据。

## Decision

Standard 是完整产品默认体验，不是只读或聊天试用版。consumer/developer 是构建能力集合，不能与审批模式混为一谈；会话授权预设两变体均可提供，实际能力仍以制品和系统授权为准。

developer 单 APK 包含私有进程 Runtime，consumer 排除对应组件。其他能力原则上保留在渠道允许范围，不因笼统“风险较高”删除。渠道差异落在 manifest、依赖、Capability 和 listing，不分叉 Agent Core、用户数据或主要 UI。

Google Play、国内商店与直接分发是产品目标；上架仅以真实审核/可下载证据声明。限制能力必须记录目标渠道、具体约束、受影响组件与不能通过披露/授权保留的理由；优先完成合法声明和审核，必要时保留 SAF/MediaStore 或确定性自动化替代。

脚本解释不等于可以下载 DEX/JAR/native 自更新或绕过渠道政策。applicationId 与数据升级由发布契约维护，不要求用户为切换会话权限重新安装应用。依赖版本可以为兼容性更新，须固定可复现版本并同步许可、锁文件与验证，不由 ADR 中的过期版本阻止修复。

Release 使用 R8 代码和资源优化，保留实际反射、JNI、组件与序列化契约；不以全包 keep 掩盖缺失规则。制品门禁结合该次构建的 mapping 与最终 DEX 定义核对渠道边界。默认开发包包含 arm64-v8a/x86_64，直接分发可分别构建单 ABI 包；不携带本地核心库不支持的 32 位 ABI。x86_64 包不宣称具有仅 ARM64 可用的 PRoot/FFmpeg 执行能力。

Developer 的 RootFS 随 APK 离线交付，以单流 XZ 存储；锁定身份仍是原始 tar。构建端验证往返一致性，安装器约束压缩输入、解压输出与解码内存，验证流尾及原始 SHA-256 后才激活，失败保留原安装。保留原生可执行文件的安装提取方式、语言切换资源和既有 Runtime 软件包。下载式 Runtime 不由体积优化自动引入。

开发预览版不承诺保留旧数据，不以旧会话/配置迁移或无损覆盖升级作为发布前置。不兼容数据库继续按现行开发 baseline 重建，数据库外文件按现有范围保留；发布说明和随包帮助必须明确这一限制。此决定不授权删除任意日常安装、用户文件或其他应用数据，也不等于正式发行身份和长期升级契约已经确定。

## Alternatives considered

把 Standard 人为裁成弱产品不能满足目标；为所有渠道统一删除一个渠道不允许的能力扩大了限制；独立 Runtime 安装包增加使用成本。

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

## Decision history

- 2026-10-15：Owner 明确“预览版不需要数据保留”。取消预览发布的旧数据迁移前置，保留真实数据重建说明、签名/版本校验、当前版本恢复正确性及独立测试安装边界；正式发行的升级承诺仍由 HXA-122 决定。

- 2026-10-15：Owner 要求落实 APK 体积优化及测试。采用同进程架构不变的 Release R8、独立 ABI 制品与 RootFS 存储压缩；没有改变能力目录、权限、执行域或发布身份。参考 [Android R8 配置](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)、[ABI 分发](https://developer.android.com/build/configure-apk-splits)与 [XZ for Java](https://tukaani.org/xz/java.html)。同源码基线、失败修复及实际验证边界见[实施记录](../../evidence/development/apk-size-optimization-2026-10-15.md)，不视为 HXA-122 正式发行验收。
