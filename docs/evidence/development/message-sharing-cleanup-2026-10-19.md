# 移除重复消息分享并整理交付入口（2026-10-19）

## 产品决定与实施

Owner 明确决定只移除与复制重叠的单条消息分享，同时检查并保留有独立用途的结构化导出及文件/产物交付。以 `4a740b1d` 的分支确认和普通包语音收尾为基线；不增加新导出格式、云端链接或知识库，不改版本、数据结构或运行依赖，不推送/发布。

消息操作栏现在保留复制、适用的编辑/重新生成和分支确认，删除 `ShareTextButton`、专用分享图标及三语言 `chat_share_message` 资源。复制仍得到完整 Markdown 原文，不更改消息持久化或分支历史。

| 保留入口 | 实际位置与命名 | 独立用途与取舍 |
| --- | --- | --- |
| 导出会话 JSONL | 会话顶部 → 会话详情 → 导出会话 JSONL | 保存整会话结构化记录，用于诊断/评测；是会话级的次要操作，留在详情而非每条消息工具栏 |
| 分享文件 | 导航 → 文件管理器 → 选文件 → 文件详情 → 分享文件 | 把实际文件交给其他应用；与复制文本不同，保留并明确命名 |
| 产物文件交付 | 导航 → 全部成果 → 文件详情的操作区：分享文件、适用的导出、分享预览文本 | 将原来在详情正文中的“分享文件”移入同一操作区，FlowRow 可换行；实际文件与截取的预览文本分开命名 |
| 分享任务结果 | 全部成果中的任务结果详情 → 分享任务结果 | 汇总整个任务的可读结果文本，并非单条消息原文；保留，替换笼统的“分享”标签 |

没有任何入口创建或上传云端会话链接。Android 建议用系统 Sharesheet 交付内容、用具体 MIME 与临时 URI 授权分享文件；本轮沿用该现有链路，不自建接收应用列表。参考：[发送内容](https://developer.android.com/develop/ui/compose/sharing/send)、[安全分享文件](https://developer.android.com/training/secure-file-sharing/share-file)。JSONL 仍使用系统保存位置选择器，格式/内容边界沿用 [ADR-AGENT-005](../../adr/agent/005-session-jsonl-export.md)。

## 已复现并修复的文件交付问题

新增普通包设备用例以工作区中的合成 `report.txt` 点击真正的文件分享按钮，首轮检测到 Intent MIME 是 `application/octet-stream`：原实现直接使用工具内容探测器的保守 MIME，虽然同一探测结果已明确识别为文本。工作区及 SAF 的临时副本又统一使用 `.bin`，使 FileProvider 按扩展名查询类型时也退化。

修复仅位于用户文件分享适配层：对正向识别的文本使用 `text/plain`；其余已知 magic 类型及未知二进制结果保持原判断，未修改工具侧 `ContentProbe`。临时副本保留受限 ASCII 扩展名，未知/不安全扩展名回退 `.bin`；文件详情分享通过 FileProvider 的显示名参数保留原文件名。未知 MIME 仍回退 `application/octet-stream`，不宣称推断所有格式或修正内容与扩展名不一致的文件。

两个文件分享调用点共用 `fileShareIntent`：`ACTION_SEND`、`EXTRA_STREAM`、具体 MIME、相同 URI 的 ClipData、仅 `FLAG_GRANT_READ_URI_PERMISSION`；拒绝 `file://`。不增加写授权、前缀授权或持久授权，不扩大 FileProvider 根目录。文件分享协程取消重新抛出，不把取消显示成一次普通 I/O 错误。

## 测试方法与证据边界

设备为已授权的日常 Helix_API_36、API36、ARM64 模拟器；测试普通 Standard/Advanced 包，未使用独立验证包或实验属性。使用 `adb install -r` 保留数据，并核验实际安装 APK 与成功构建产物一致。

两个普通渠道各 14 项、共 28 项通过：消息复制及消息分享消失 5，分支确认/取消/防重/会话切换及真实持久化 4，文件/任务文本分享、系统选择器、URI/MIME 边界 4，JSONL 导出 1。

- 文件分享由真实 UI 按钮产生系统 Chooser Intent；将其原始 `ACTION_SEND` 载荷交给本地测试 APK 的显式 Activity。该 Activity 位于另一 UID，没有注册系统分享过滤器，不会成为普通用户分享目标。回执核对完整合成内容、Intent MIME、FileProvider 查询 MIME 均为 `text/plain`，读取权限已授予、写权限被拒绝。没有以同 UID 读取冒充外部接收成功。
- 任务汇总文本使用保留的 `sharePlainText`，经 Chooser 封装后送到同一个测试接收器，核对完整合成文本。另实际打开系统 Sharesheet 并按返回取消，未选择真实联系人或第三方应用；URI 接收验证是显式测试接收器，不冒充人工在 Sharesheet 中完成选取。
- JSONL 使用独立测试数据库、唯一命名的偏好/缓存和测试文档 Provider。确认 `ACTION_CREATE_DOCUMENT`、`application/x-ndjson` 的选择器边界，取消不生成暂存输出；返回已授权合成目标后真实执行导出，读回消息正文与 `complete` footer。该用例通过 Instrumentation monitor 返回文档选择结果，不冒充用户实际浏览系统文件选择器。没有接触用户未完成导出或真实云盘。
- 两渠道均不调用会话/配置重置助手；用 `helix.preserveUserSettings=true` 保留语言选择。测试前后用户偏好文件哈希、原麦克风权限和标志相同，只有正常运行的进程诊断心跳可变化。自有 UUID 会话、数据库、文件、文档、偏好、缓存和测试 APK 均清理，正式普通包保留。测试复制会使用合成文本写入系统剪贴板。

本轮没有录音或新增真机运行。语音的普通包及真机公开 PCM 证据沿用[前轮收尾](session-actions-voice-closeout-2026-10-19.md)，不把它冒充本次新 APK 的真机验收；手机原有安装未在本轮改动。

## 主机检查与本地产物

新增 JVM 回归确认文本分享 MIME、暂存扩展名、原文件内容及原位身份不变。Consumer 1322 项、Developer 1457 项，零失败/错误，各 4 项既有可选外部输入检查跳过；源码门禁 279 项通过。两渠道主机源码门禁、格式、detekt、lint、JVM 测试、普通 Debug/Release 与 AndroidTest 编译按本轮日志记录。第一次格式化与静态分析并行执行时，detekt 读到尚未格式化的长行；没有抑制规则，随后以 `spotlessCheck` 和 detekt 重跑最终源码。首轮设备 MIME 失败保留原日志及 APK，最终通过使用修复后的成功构建。

本地原始证据在忽略的 `build/asr/sharing-closeout/`（每渠道 APK、安装记录、完整 instrumentation 流、设置/权限前后哈希、完整性记录），主机日志为 `build/asr/closeout/sharing-*.log`。临时运行器在忽略的 `scripts/local/debug/2026-10-19/sharing-closeout-device.py`。可安装普通 Debug 产物更新到 `build/asr/deliverables/Helix-Standard-debug.apk` 和 `Helix-Advanced-debug.apk`；不表示已签名发布、其他 OEM/ABI 或完整版本验收通过。
