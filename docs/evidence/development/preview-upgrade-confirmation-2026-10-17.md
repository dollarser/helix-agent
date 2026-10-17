# 预览升级确认与重构前追加验收

Date: 2026-10-17
Task: [HXA-304](../../development/tasks/HXA-304.md)
Status: 已完成；主机、指定 ARM64 设备与真实 Qwen 范围 passed；仅本地提交

## 范围与起点

Owner 授权旧测试版升级清库提示、独立 ARM64 模拟器与真实模型验收、审查并本地提交。HXA-303 的 162 个文件已提交为 `9e9b6912`；原 8 项样本/匿名公开 MCP 补验在其证据中完成，本轮没有重复执行。分支 main，不推送或发布；保持 0.0.6 / versionCode 1。CodeGraph 索引候选经当前源码核对，无本地技能目录。

## 实现与审查

- 当前 Room schema 在容器启动前只读检查。不兼容时先说明会话、任务、配置与数据库授权记录会丢失，取消退出；未确认不初始化容器、恢复或工具服务。仅确认后取消数据库所属的延迟提醒与已发布提醒，再重建数据库。数据库外文件保留，不迁移旧结构。
- 确认绑定所观察文件的身份与内容指纹，只能消费一次；重复、过期、清理失败和普通读取/腐败错误不自动删库。覆盖 Android 默认腐败删除回调，关闭 destructive fallback。确认准备前后均复核，异常报告失败，不宣称事务回滚了删除或提醒取消。
- 容器初始化仍在后台，同一尝试由各入口共享；取消页面等待不会取消工厂。OAuth/终端启动失败回到主启动界面，旧通知不能通过服务入口授权重建。
- 实测发现预算设置使用异步偏好写入，保存后结束进程会读回旧值。仅运行默认值改为确认落盘后发布状态，失败显示未保存；会话 Room 设置独立验证。
- PRoot collect 返回原已验证归档的有界 stdout/stderr，并说明不可用与截断；无输出文件时省略 artifactDirectory。保留原调用、授权、receipt、结算和 ACK，重复收集不重跑或重复导入。已将该行为加入 288 的后续替换验收，不保留旧实现兼容要求。
- 审查 HXA-303 的有效预算、Room 列与 JSON、调用者/夹具、配套闭包/同源撤销/发布不可变及原调用授权边界。当前新增改动不扩展能力目录，不自动启动后续大重构。

## 已执行验证与失败修复

原始证据均在忽略目录 `build/hxa304/`，不把历史包结果算作当前通过。

- 首轮 SQLite 10 项有 5 项失败：只读打开可创建空 WAL，文件时间/大小也不能识别快速写入。改为旧库确认阶段才计算 DB/非空 WAL 内容指纹，普通兼容启动不全库哈希；10 项复测通过。随后增加 WAL-only 写入与清理失败，最终 `storage-final` **12/12** 通过。
- 双渠道应用回归 `app-device-final`：Standard 14 项功能/UI + 两阶段预算进程重启 2 项；Advanced 同组 14 项 + Mobile Use 注册 2 项 + PRoot 实际 Job 1 项 + 预算重启 2 项，共 **35/35**、零跳过。含真实 Room、历史路径、声明式发现、权限原子性、插件会话隔离及设置失败 UI。
- 旧提醒取消的追加设备方法在 `storage-final` 跟随脚本中双渠道各 **1/1** 通过。新 SQLite/提醒夹具均使用合成数据。
- 初次 Standard 配套夹具误要求该渠道不存在的 `ui.*`，按当前权限 ADR 改为验证 Standard 的不可用边界，Advanced 继续验证实际 Mobile Use/PRoot 配套；没有为使测试通过删除产品能力。
- 预算进程重启曾从 2048 读回 2000，保留失败输出；同步落盘修复后两渠道均通过。新增 JVM 写入失败不发布状态、启动确认顺序与恢复边界回归。
- 两次较早完整门禁在发现后续修复后停止其过时 R8 构建，不记整轮通过；升级/预算阶段的通过留在 `all-gates-verified.log`，随后 PRoot 修复的最终门禁为 `all-gates-completed.log`。初期测试编译/格式错误均保留并修正，未放宽门槛。
- 升级/预算阶段 `./scripts/check-all.sh --all` exit 0：Python 275 项通过；当时 Gradle 模块的 JVM XML 共 6,081 项、零失败/错误、8 项 opt-in 跳过（包含本轮 up-to-date/from-cache 的有效结果）。这 8 项已由 HXA-303 独立启用全部通过，不冒充默认门禁执行。格式、detekt、双渠道 lint、Debug/Release/R8、独立测试驱动编译、依赖锁、渠道/运行时边界均通过。
- 最后中文提示措辞调整后重新执行 i18n、文档与秘密扫描并通过；`./scripts/build-release-apks.sh arm64-v8a` 成功，两个实际应用 ID 的 R8 包均验证 non-debuggable、ARM64、0.0.6 / 1，使用现有本机 debug 签名，仅用于本地验收。制品及源码输入清单在 `build/hxa304/candidate/`。
- 首批 R8 的 `r8-device-retry` 已执行两渠道损坏库拒绝重置、旧库取消后内容不变、进程重启再次提示、明确确认后 schema 重建、正常重开，以及 UI 保存 2048 后进程重启仍显示 2048。升级警告截图已目视检查；清空前后分别核对合成标记和 Room identity。
- 同一轮真实 Qwen 在两渠道分别完成写入、读回及重启后再读，原始 Turn 均 COMPLETED、write/read 工具结果 SUCCEEDED，实际文件内容与预期合成标记完全一致。未凭模型叙述判定文件效果。
- R8 首次跟随脚本读取私有目录缺少测试 root 身份，第二次在 Advanced 切换后误认为导航菜单已关闭；均是夹具错误，保留原始失败，未改产品或降低断言。第二次已完成的升级/预算/文件证据保留，Mobile Use/PRoot 由独立 `r8-extras` 实例补跑。
- `r8-extras` 的真实 Qwen Mobile Use 已完成 tools.search → ui.launch → ui.snapshot → ui.click → ui.snapshot；观察到 `HXA304_CLICKED`，独立测试应用的持久标记为 true，只有一次点击。模型另尝试返回不在 allowlist 的 Helix，得到 TARGET_NOT_ALLOWLISTED，未发生越界导航；这不记为成功工具调用。目标结果与返回失败分别保留，未扩大应用范围。
- 同轮只读模型能力探测遇到一次 RemoteDisconnected，UI 正确显示服务端错误且保持连接状态；保留截图后点击一次能力检测重试，通过。Runtime 页面已显示就绪，后续脚本的返回/导航滚动位置假设导致停止；按实际页面直接返回原会话，PRoot 原作业单独在 `r8-proot-final` 补验，不重跑已通过的 Mobile Use。
- `r8-proot-final` 证明 BACKGROUND 原作业 start/status/collect 与归档 stdout 正确，但发现模型拿不到 stdout/stderr，随后读取了不存在的 artifactDirectory/result.txt。这一轮仅证明执行和收集，**不算模型完整拿到结果通过**。修复 collect 返回原已验证归档的有界 streams、available/truncated 标记，以及无输出文件时省略不存在的目录；原 receipt、重试、权限及 ACK 逻辑保持。补测导入失败重试与重复收集、失败执行不伪造流、Unicode 截断和包含转义字符的输出预算；旧候选包保留，修复后的制品另存。
- 修复后重新完整执行 `./scripts/check-all.sh --all`，`all-gates-completed.log` exit 0：JVM **6,084 项、零失败/错误、8 项 opt-in 跳过**，其他全仓门禁通过。AndroidTest 再单独成功构建后冻结，`collection-final-device` 原作业 **7/7**、零跳过通过。较早格式检查失败后遗留的旧 APK 曾被启动，来源复核后停止该实例并标记 INVALID，不作为本次修复验收；当前通过来自重新成功构建的包。
- 最终修订制品在 `build/hxa304/candidate-revised/`：Standard 与已验收签名包字节完全相同，Advanced 为 PRoot 输出修复重新构建。版本仍为 0.0.6 / 1，渠道、运行时与签名静态核验通过；最终实际运行结果按下节记录。
- `collection-final-device` 修订 Advanced R8 再次通过升级取消/确认；真实 Qwen 仅一次 start，status/collect 均绑定原 originalCallId，exitCode 0、stdout 为 `HXA304_PROOT`。实际 ZIP 与工具结果一致，后续模型请求中也确认包含该 stdout；全部工具调用 SUCCEEDED，未再猜测不存在的输出路径。

## 最终验收与交付

最终修订 Advanced R8 的 `revised-mobile-device` 再次验证预算 2048 保存/进程重启、真实 Qwen 文件写入/读回/重启后再读，以及 Mobile Use 的发现、仅测试应用授权、启动、观察、一次点击和再次观察。实际文件与测试应用持久标记分别核对；各 Turn COMPLETED，全部工具调用 SUCCEEDED。Mobile Use 明确保持测试界面，未再尝试超出范围的返回。Standard 使用字节相同的已验收最终包；Advanced 的原 Job/模型 stdout 见 `collection-final-device`，不是仅凭编译或模型结论判定。

全部指定门槛完成，审查与修复以本地提交交付；制品、源码输入与提交关联清单位于 `build/hxa304/candidate-revised/`。主机 275 项 Python 与 6,084 项 JVM 记录、12 项 SQLite、35 项应用回归、2 项旧提醒追加检查、7 项原作业回归及上述 R8/真实模型旅程分别记账，不把重复辅助步骤累加成独立覆盖。

本轮真实手机、x86 设备和长稳为 not requested；未执行其他真实 Provider、第三方账号业务或 FFmpeg/PRoot 媒体端到端。所有设备写入限新建自有 API36 ARM64 实例，日常模拟器和真机数据不变。临时代理已关闭，API 凭据仅在代理内存中使用；测试实例已关闭，未推送或发布。无本任务剩余阻塞。
