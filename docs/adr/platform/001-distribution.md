# ADR-PLATFORM-001: 产品完整性与渠道分发

Status: accepted
Date: 2026-09-16
HXA: HXA-120, HXA-121, HXA-122, HXA-123
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

能力优先的单设备产品需要最小渠道差异，同时如实表达当前制品与发布证据。

## Decision

首次启动的必要说明不依赖 AppContainer、Room 或浏览器就绪。恢复事务先完成文件替换／回滚协调，再读取已读状态和进行数据兼容性预检；所有旧数据库消费者及任务恢复在预检通过之后启动。说明可与预检并行展示；不兼容数据的具体范围、保留内容及独立确认／退出界面和初始化／恢复失败优先展示，确认说明不等于确认删除或授予能力。

确认说明且预检通过后，首次用户进入可跳过的精简系统权限列表，初始化完成不会打断阅读。通知、关闭电池优化和 Developer 悬浮预览入口以当前安装制品实际声明及系统版本为准；返回应用刷新真实状态，不自动启动服务或申请权限。通知和电池优化不能保证后台永驻，继续使用已有任务服务生命周期。电池优化使用系统列表入口，不新增直接豁免声明。已有已读用户兼容升级不重复首启引导；新用户的权限待办与说明已读一起持久化，进程重建继续待办。

Standard 是完整产品体验，不是只读或聊天试用版。consumer/developer 是构建能力集合，不能与审批模式混为一谈；会话授权预设两变体均可提供，实际能力仍以制品和系统授权为准。

能力只由构建渠道决定，不再提供应用内 Standard/Advanced 运行档位或“权限模式”选择。Developer 固定为 Advanced，包含该渠道的全部能力；Consumer 固定为 Standard，保留渠道允许的完整产品能力。渠道身份不从偏好、备份、模型或扩展元数据读取，不存在切换确认、退出 Advanced 或先切换再使用的前置步骤。

“全部能力”指可用的能力集合，不是授权全放行：会话审批预设、目录/文件范围、LAN origin、Android 权限、Root/Shizuku 授权、执行域准备及物理容量仍分别检查；不自动创建授权、安装运行环境、连接服务或消费账号。运行环境继续按已接受的首次使用准备流程执行。

developer 单 APK 包含私有进程 Runtime，consumer 排除对应组件。其他能力原则上保留在渠道允许范围，不因笼统“风险较高”删除。渠道差异落在 manifest、依赖、Capability 和 listing，不分叉 Agent Core、用户数据或主要 UI。

Google Play、国内商店与直接分发是产品目标；上架仅以真实审核/可下载证据声明。限制能力必须记录目标渠道、具体约束、受影响组件与不能通过披露/授权保留的理由；优先完成合法声明和审核，必要时保留 SAF/MediaStore 或确定性自动化替代。

脚本解释不等于可以下载 DEX/JAR/native 自更新或绕过渠道政策。applicationId 与数据升级由发布契约维护，不要求用户为切换会话权限重新安装应用。依赖版本可以为兼容性更新，须固定可复现版本并同步许可、锁文件与验证，不由 ADR 中的过期版本阻止修复。

Release 使用 R8 代码和资源优化，保留实际反射、JNI、组件与序列化契约；不以全包 keep 掩盖缺失规则。制品门禁结合该次构建的 mapping 与最终 DEX 定义核对渠道边界。默认开发包包含 arm64-v8a/x86_64，直接分发可分别构建单 ABI 包；不携带本地核心库不支持的 32 位 ABI。x86_64 包不宣称具有仅 ARM64 可用的 PRoot/FFmpeg 执行能力。

Developer 的 RootFS 随 APK 离线交付，以单流 XZ 存储；锁定身份仍是原始 tar。构建端验证往返一致性，安装器约束压缩输入、解压输出与解码内存，验证流尾及原始 SHA-256 后才激活，失败保留原安装。保留原生可执行文件的安装提取方式、语言切换资源和既有 Runtime 软件包。下载式 Runtime 不由体积优化自动引入。

开发预览版不承诺保留旧数据，不以旧会话/配置迁移或无损覆盖升级作为发布前置。不兼容数据库继续按现行开发 baseline 重建，数据库外文件按现有范围保留；发布说明和随包帮助必须明确这一限制。此决定不授权删除任意日常安装、用户文件或其他应用数据，也不等于正式发行身份和长期升级契约已经确定。

### ModelScope 更新发现

两渠道的「关于 Helix」提供用户主动的检查更新，以及 GitHub Releases / ModelScope 下载入口。检查只读取公开元数据，不随启动、恢复、进入设置或后台任务自动请求，不下载 APK、不申请安装权限，也不清理数据。发现新版本后仍需用户另一次点击才交给浏览器下载；浏览器下载完成和 Android 安装器的确认、包名/签名校验属于独立步骤，Helix 不声称已经验证 APK 或完成升级。

固定仓库为 `https://modelscope.cn/datasets/dollars/Helix`，清单入口为该仓库的 `resolve/master/update-manifest.json`。入口与实际发布可用性须在每次发布时独立验证；404 显示「尚未发布」，不能当作已是最新。读取最多 64 KiB、5 次 HTTPS 请求，总请求预算 20 秒，每次阻塞连接/读取最长 10 秒（取消后最多等待当前阻塞操作结束）；重定向仅限 ModelScope 自有域名。取消/关闭或离开界面后旧请求不得重新打开对话框或覆盖新结果。网络失败、格式不合法、未发布和没有匹配制品均给出明确结果与重试/手动下载入口。

清单格式固定 `schemaVersion: 1`，`releases` 为 1–32 项数组。每项包含：

- `versionName`：语义版本（主/次/补丁与可选预发布、构建标签）；检测按其优先级比较，忽略构建标签，不以字符串字典序或固定的 Android versionCode 代替。
- `versionCode`：APK 的真实正整数版本号，当前仍为 1；清单和实现不得擅自递增。新版本名而 versionCode 不大于当前安装时，提示系统可能拒绝覆盖，不引导卸载/清除数据。
- `packageName`：当前支持的 `com.helix.agent`（Standard）或 `com.helix.agent.developer`（Advanced），只匹配本机安装身份。
- `abis`：非空且不重复的 `arm64-v8a` / `x86_64` 列表，按本机支持的 ABI 选择。包名、版本名和 ABI 不允许重复歧义制品；优先最高版本，再按设备 ABI 优先级选择。
- `apkUrl`：固定仓库 `resolve/<revision>/<path>.apk` 的 HTTPS 直链；禁止其他仓库、查询/片段、用户信息、编码路径、路径穿越或非 APK 目标。发布使用 `releases/v<versionName>/` 下不可覆盖的制品路径，清单只在 APK 上传并核验后最后发布。入口不锁定某个版本，不把 README 等非 APK 文件的上传时间作为更新依据。
- `sha256` / `sizeBytes`：从实际最终 APK 计算的 64 位小写十六进制摘要和字节大小，用于发布校验及显示；清单本身不是 APK 签名证明，当前浏览器下载方案不冒充应用内哈希/签名验证。

正式商店渠道必须在上架前按该商店更新政策审查/接入其更新机制，不能把当前直接分发入口视为 Google Play 已合规的证明；不为预览功能提前引入新的构建渠道或 `REQUEST_INSTALL_PACKAGES` 权限。

## Alternatives considered

把 Standard 人为裁成弱产品不能满足目标；为所有渠道统一删除一个渠道不允许的能力扩大了限制；独立 Runtime 安装包增加使用成本。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

本次渠道固定能力改动已通过双渠道 Debug 门禁及 Helix_API_36（API 36、Developer）8 项本地检查：固定 Advanced、设置/侧栏移除档位入口、运行环境按渠道显示、权限页不申请系统授权，以及真实待审批调用在活动重建后仍可拒绝。仅 Developer 进行了本轮设备检查；Consumer 的证据为主机测试、测试源码编译和 APK 渠道边界检查。没有运行 Release、真机、真实 Root/LAN/账号授权或全套执行域设备回归。详细源码/制品绑定与原始输出保留于 ignored 本地收据。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
- [Android 版本规则](https://developer.android.com/studio/publish/versioning)
- [Android 应用签名与更新](https://developer.android.com/studio/publish/app-signing)
- [Google Play 安装包权限政策](https://support.google.com/googleplay/android-developer/answer/12085295)
- [Google Play 自更新约束](https://support.google.com/googleplay/android-developer/answer/16559646)

## Decision history

- 2026-11-13：Owner 要求增加 ModelScope 下载源与 Helix 更新检测。采用手动元数据检查、渠道/ABI 精确选择和浏览器下载交接，保留 versionName / versionCode=1 规则；不引入 APK 安装权限、静默安装、自动后台检查或数据清理。公开源可达、制品签名与发布一致性和主机/设备 UI 验证分别验收。

- 2026-10-15：Owner 明确“预览版不需要数据保留”。取消预览发布的旧数据迁移前置，保留真实数据重建说明、签名/版本校验、当前版本恢复正确性及独立测试安装边界；正式发行的升级承诺仍由 HXA-122 决定。

- 2026-10-15：Owner 要求落实 APK 体积优化及测试。采用同进程架构不变的 Release R8、独立 ABI 制品与 RootFS 存储压缩；没有改变能力目录、权限、执行域或发布身份。参考 [Android R8 配置](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)、[ABI 分发](https://developer.android.com/build/configure-apk-splits)与 [XZ for Java](https://tukaani.org/xz/java.html)。同源码基线、失败修复及实际验证边界见[实施记录](../../evidence/development/apk-size-optimization-2026-10-15.md)，不视为 HXA-122 正式发行验收。

- 2026-11-05：Owner 要求 Developer 不再区分权限模式，只保留 Advanced 全部渠道能力，并取消用户选择。将运行时可切换档位改为只读渠道身份，移除侧栏、权限设置、运行环境与手动终端的档位开关和风险确认；不更改会话审批、具体范围授权、系统授权或首次使用准备边界。Consumer 继续固定 Standard；已有偏好不参与能力决策，不增加迁移/兼容层。验证以本轮实际 Debug 结果为准，不据此宣称 Release/真机通过。

- 2026-11-06：Owner 要求首次打开期间先阅读必要说明，再自主配置可选权限，以利用初始化等待时间；数据不兼容与恢复失败始终优先，重置确认独立。保留旧已读状态，不新增保活服务。参考 [Tasker 后台运行说明](https://tasker.joaoapps.com/userguide/en/faqs/faq-problem.html)及 [Android Doze / App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)、[前台服务启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)，仅借鉴按用途引导和真实状态，不承诺绕过系统限制。
