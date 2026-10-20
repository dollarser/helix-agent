# OnePlus 候选收尾（2026-10-20 UTC）

Owner 明确要求“帮我收尾，没有的应用可以直接在真机安装”。本轮仅 OnePlus 6T / API34 / ARM64，源码候选 `0b689fb0`。保留工作区既有 `implementation-guide.md` 修改；不使用模拟器替代真人口述/试听，不推送或替换发行附件。

## 已完成

- 从上一轮成功构建的 `build/scheduled-budget/deliverables/` 安装 Advanced Debug 与测试 APK，包名/签名覆盖安装成功，未卸载旧应用。核对 provenance 中源码提交及 APK 摘要；实际已安装 APK 与归档字节相同。原 Standard 不更换。沿用该候选的完整主机 JVM/lint/R8/源码门禁，不将其记为本日重新执行。
- 真机实际 AlarmManager 触发一条六秒后到期的合成提醒，持久 outcome 为 POSTED，迟到约 60 ms。测试核对 NotificationManager 活跃通知并发送其原 contentIntent，验证打开对应编辑条目、仅一条事件与 reconcile 不重复投递。1/1，11.156 秒；这不是用户亲手点击或主观铃声/振动验收。合成提醒与事件在 finally 删除。
- 知识库/问答/提醒 50/50（35.051 秒）、定时 Agent 30/30（23.900 秒）、自然语言提醒 10/10（6.987 秒）通过。使用合成隔离库/工作区，无私人材料或真实模型请求。包含真实 Android 系统分享目标选择、临时 content URI 撤权后原件仍可读、文本文件提取、图片 original-only/INCOMPLETE 与搜索、失败恢复和引用；发起应用是测试夹具，不能替代微信/X 原生分享。
- 图像保存原件，无 OCR。当前文件提取实现支持文本、HTML、PDF、DOCX；本轮真机夹具验证文本文件与图片，不能推断所有实际 PDF/DOCX 排版/编码均通过。
- 前后 SharedPreferences 文件摘要除正常进程诊断外相同，通知/麦克风权限、时区和默认输入法相同；未改精确闹钟访问。未清数据库、重置设置、停止未知用户任务或录环境音。只卸载本轮新增的 Advanced 测试包，保留原 Standard 测试包及用户应用。
- 微信原先未安装。由 [微信官网](https://weixin.qq.com/) 列出的腾讯 `dldir1v6.qq.com` 地址下载 8.0.79 ARM64 / code 3200，apksigner 验证成功，Tencent 签名，安装成功。打开后实际停在 WelcomeActivity 登录/注册页，未输入账号、接受登录验证码或发送内容。APK 摘要和官方 URL 在本地 provenance，未提交 APK。
- README、中英文 v0.0.6 说明明确区分本地未发布候选与 2026-10-18 原发布附件。下方历史发行验收不迁移成新候选通过。版本仍 0.0.6 / code 1，工具声明 1。

## 失败与边界

- X 未安装，手机无 Google Play。读取 `https://help.x.com/en/using-x/download-the-x-app` 与 `https://twitter.com/download/android` 返回 HTTP 403，未取得可验证的官方 APK 安装来源，故未安装；没有改用第三方 APK 站。此为外部来源阻塞，非自动审批拒绝。
- 微信首屏的 uiautomator dump 因 cannot get idle state 失败；随后实际截图及 window focus 确认登录页。未把 UI dump 失败算作分享功能失败。
- 本轮无 Helix 测试断言失败；没有新的产品代码修复。不是全格式提取、Release 运行、息屏/Doze/重启/长稳或真人通知感知矩阵，之前未覆盖的边界继续保留。

## 未测及用户配合

- 微信原生公开内容→系统分享→Helix：pending，微信需要用户自行登录。微信内部转发未必暴露 Android 系统分享；没有入口时可复制公开文字/链接或保存合成文件再分享，不绕过登录。不向联系人发消息。
- X 原生分享：pending，需官方安装入口可用（或用户通过官方商店安装）及必要的用户登录。未以网页/夹具替代原生 X。
- 真实 ASR 口述：pending。用户在 Advanced 主动点麦克风，说“今天测试语音输入，项目预算是一百三十七元”，检查识别草稿后取消即可，不必发送。尚未启动录音。
- 真实 TTS 听感与停止：pending。用户对完整回复点“朗读此回复”，如提示首次资源下载由用户确认，听清后停止并反馈。未代点播放，也不以合成音频或模拟器推定听感。

已向用户给出上述最小配合步骤并等待结果。这些项尚未执行，不能宣布整体验收或发布就绪。无发布/推送操作；如下一步替换现有 v0.0.6 Release，需要明确确认仓库 `dollarser/helix-agent`、标签、两渠道附件、签名与目标提交范围。

原始日志、前后状态摘要及本轮 provenance 位于忽略目录 `build/physical-closeout/`；可安装候选仍为 `build/scheduled-budget/deliverables/Helix-Advanced-debug.apk`。本记录提交仅更新文档，不改变 APK 源码身份。

## 官方入口与独立原生分享补查

随后只读核查 [X 官方 Android 帮助页](https://help.x.com/en/using-x/x-for-android)，其下载链接指向 [Google Play 的 X Corp. / com.twitter.android](https://play.google.com/store/apps/details?id=com.twitter.android)。官方商店条目可读取；此前两个 URL 的 403 不代表所有官方网页均不可访问。当前手机没有 Google Play，也没有查到现有 Amazon/厂商应用商店；market URI 解析到 APKPure、酷安及百度下载管理，均未作为本任务的官方 X 安装来源使用；未新建 Google 账号、安装商店基础设施、绕过访问限制或使用第三方 APK 源。未找到可验证的官方直装 APK，本机安装仍 pending。

手机已有系统 `com.android.documentsui`，LauncherActivity 可解析，具备独立于微信/X 的文件管理器入口；`com.android.gallery3d` 包存在但 MAIN/LAUNCHER 入口未解析到，不能据此声称图库分享已执行。已在本机忽略目录 `build/physical-closeout/native-share/` 准备带唯一标记的合成 UTF-8 文件与合法 PNG，尚未推到手机或分享。

此时仅读窗口焦点，发现手机已由微信 WelcomeActivity 切换至 MMWebViewUI；没有读取网页、账号字段或截图。为避免切走可能正在进行的微信操作，本轮未接管前台启动 DocumentsUI。原生文件管理器→Helix 的独立真实分享仍 pending，等待设备空闲后只处理这两个自有夹具；原先夹具发送器的系统分享通过不冒充本项完成。没有重复提示用户、代登录或重跑 91 项。
