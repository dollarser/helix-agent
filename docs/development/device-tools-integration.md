# 本机工具接入

Owner 授权参考 WorkBuddy 移动版迁移工具名、描述和常见操作方式，并明确不兼容旧工具入口。本实现保留原 Android/Agent 架构、Standard 能力与统一权限链路；未接入的第三方服务不显示为可用工具。

## 当前工具目录

以下为实际注册的 `device.*` 工具，而非计划或模拟工具。

| 工具 | 参数要点 | 实际效果 |
| --- | --- | --- |
| `device.open_url` | `url` | 仅打开 HTTP/HTTPS 系统处理器 |
| `device.open_settings` | `action`, `packageName` | 打开现有白名单系统设置页，不修改设置 |
| `device.clipboard_read` | 无 | 前台受限、长度有界的剪贴板读取 |
| `device.clipboard_write` | `text` | 前台受限的文本写入 |
| `device.share_text` | `text`, 可选 `title` | 启动系统分享面板 |
| `device.create_note` | `text`, 可选 `title` | 与 WorkBuddy 一致，交给用户选择备忘录 App 保存 |
| `device.share_files` | `paths`，1–8 个 | 分享作用域文件，图片也走此入口 |
| `device.open_file` | `paths`，恰好 1 个 | 通过系统面板选择外部打开应用 |
| `device.notifications_query` | 应用白名单、起止 epoch 毫秒 | 在通知访问授权下查询有界快照 |
| `device.prepare_calendar_event` | 标题、起止 epoch 毫秒、可选时区/地点/说明 | 校验并持有结构化草稿，不写日历 |
| `device.create_calendar_event` | `draftId` | 授权及日历权限校验后写入系统 Calendar Provider |
| `device.create_reminder` | `time`（含 UTC offset 的 ISO 8601）, `label` | 保存 Helix 一次性近似通知提醒，返回实际调度状态 |
| `device.list_reminders` | 无 | 查询最多 50 个活动定时提醒及 ID/revision，`hasMore` 表示截断 |
| `device.cancel_reminder` | `id`, `revision` | 必须先在同一会话查询，原子重检持久修订后取消 |
| `device.open_page` | `route` 枚举 | 向原生 UI 请求导航；账号路由进入设置顶部账号区 |

页面枚举为 `home/settings/permissions/session/account/models/memoryLibrary/reminders/tasks/plugins`。不接受任意路由和任意 params，不提供不存在的 WorkBuddy 页面。`navigation-requested` 表示已排入应用内导航请求，不是渲染验收。

## 与参考产品的有意差异

- 不创建名为 `mcp__workbuddy_device__` 的伪 MCP 服务；内置工具统一 `device.*`，外部 MCP 继续遵循自身来源/命名空间。
- 不保留旧工具入口或双目录。旧名称只在一次性用户可用性选择迁移及拒绝旧入口的测试中出现；历史审计保留原调用身份。原工具禁用选择不会因改名失效，单次批准不能转授新绑定。
- 不把 Helix 通知提醒冒充系统 Clock 的响铃闹钟。不提供无法可靠管理准确 ID 的 `list_alarms/cancel_alarm`；手动重复提醒、精确权限及恢复规则保持原有实现。
- 日历保留已有草稿→写入边界，不为靠近参考签名而绕过审阅、权限或实际写入结果。
- 图片分享包含在 `share_files` 中，不再另设重复执行路径。分享和备忘录交接返回 `chooser-opened`，不能当作已保存/已发送。
- IMA/腾讯文档尚无本仓库已接入的专用服务契约，未注册其 authorize/save/revoke 假入口。将来直接保存必须走真实连接器，不得调用分享工具冒充直接落库；授权仍由用户完成并受 Helix 权限链路约束。

## 文件与取消边界

文件只接受 `scope:<scopeId>:<relativePath>`，不接受任意绝对路径、file URL、外部 content URI 或 HTTP URL。通过现有 WorkspaceArtifactStore 读取，保留根目录/SAF/路径治理；文件读取效果与设备交接效果同时进入权限分类。复制为独立缓存快照后通过限定 FileProvider 路径、ClipData 和临时只读 URI 授权交接，不能直接向接收应用暴露可变源文件。

每次最多 8 个文件、合计 20 MiB；读取过程中及启动面板前检查取消。失败/启动前取消清理本次暂存；已启动面板后不宣称交接回滚，不撤销正在交给用户处理的快照，缓存由 Android 管理。文件来自已授权作用域不代表已授权外发，仍需当前操作规则许可。

提醒取消不是删除用户数据；历史、修订与实际通知事实保留在现有 Room 服务。先查询 ID/revision，服务锁内复检，持久取消先于撤销系统投影。通知关闭或调度降级如实返回，保存不是送达证明。进程死亡、取消或异常不能自动重放未知副作用。

## 验证范围

本轮完整主机门禁与 AndroidTest APK 编译通过。新增测试覆盖目录移除旧入口、外部备忘录 title 与 chooser 语义、文件引用/数量/取消、文件读取和交接效果分类、导航白名单/取消、提醒时间/身份/跨会话/修订/失败以及可用性选择迁移。模拟后端测试不证明实际系统面板、日历或通知交付通过。

本轮迁移没有请求设备验证，不启动模拟器、不安装、不使用真机，不使用真实账号或第三方云保存。后续设备验收需单独授权，重点为分享接收/URI 读取、原生页面导航、通知权限/恢复与取消，以及真实日历 Provider。

### 本轮主机结果

- `GRADLE_OPTS='-Dorg.gradle.workers.max=1 -Dorg.gradle.parallel=false' scripts/check-all.sh --all`：通过，涵盖主机脚本测试、文档/ADR/i18n/秘密扫描、格式、detekt、Debug/Release lint、全量 JVM 测试、两渠道 Debug/Release APK、release-smoke 测试 APK 编译、依赖锁及渠道/运行时边界检查。
- `./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest --max-workers=1 -Dorg.gradle.parallel=false`：通过，仅编译测试 APK，没有运行 instrumentation。
- 相关 JVM 结果：Consumer Debug 1417 项、Developer Debug 1552 项，各有 5 项既有条件跳过，均零失败/错误；Android 工具模块 63 项、存储模块 243 项，均零失败/错误/跳过。新增 24 个测试方法，应用层方法在两个渠道分别执行；不把渠道重复执行算作不同测试。
- 原先错误使用 JVM 模块的 `testDebugUnitTest` 命令已改为其真实 `test` 任务；新增代码的格式/静态检查问题已修复后重跑。没有删除、跳过或放宽边界测试。
- 详细原始日志与构建/源码绑定保存在本地忽略的 `build/device-tools-*` 证据文件中。源码尚未提交，没有推送或发布；本记录仅覆盖此工具迁移，不代表设备效果或第三方服务通过。
