# Helix Android 平台能力架构

文档状态：Baseline 1.3
基线日期：2026-08-31
适用范围：Google Play、国内 Android 应用商店与官网直接分发；按渠道真实政策保留最大能力

## 1. 目标和边界

本方案定义 Helix 的内置浏览器、文件管理器、辅助功能自动化和可选 Root 能力。四类能力都必须通过统一的 Tool Registry、Policy、Approval 和 Audit 管线，不能因为用户授予了系统权限就直接暴露给模型。

关键边界：

- Android 系统权限只说明 App 获得了能力，不说明某次 Agent 调用已获用户授权。
- 用户不启用某项权限时，其余功能必须正常降级运行。
- Standard 以应用商店上架为产品目标，但不预先删除 `MANAGE_EXTERNAL_STORAGE`、Accessibility 或解释脚本：优先按核心用途、显著披露、用户同意和权限审核保留，只在目标渠道明确禁止或真实审核拒绝时做该渠道的最小差异。
- Google Play 当前允许文件/文档管理核心用途申请 All-files，但要求声明和审核；Accessibility 自动化必须是狭窄、用户可理解的确定性流程，不能由 Agent 自主发起、规划并执行。该 Play 限制不应扩展为其他合法渠道的永久产品限制。
- Root 不是 Android runtime permission。只有实际请求 `su` 才能确认 Root 管理器是否授权。
- 禁止把浏览器、辅助功能或 Root 变成绕过 Tool Policy 的“万能执行器”。

## 2. Capability 与访问作用域

```kotlin
// 定义于 core:model，ToolDescriptor 和平台 resolver 共用同一类型。
enum class Capability {
    WEB_BROWSING,
    SAF_DOCUMENT_TREE,
    MANAGE_ALL_FILES,
    ACCESSIBILITY_AUTOMATION,
    ROOT_SHELL,
    NOTIFICATION_READ,
    CALENDAR_WRITE,
}

data class CapabilityGrant(
    val capability: Capability,
    val state: GrantState,
    val grantedBySystem: Boolean,
    val userScope: UserScope?,
    val checkedAt: Instant,
)
```

`CapabilityGrant` 只能由平台适配层根据系统真实状态产生。模型不能构造、修改或缓存它。Tool 执行时必须再次检查；不能因为数据库里曾记录 `GRANTED` 就跳过系统状态检查。

文件、浏览器和 UI 自动化都使用显式作用域：

- `WorkspaceScope`：会话私有目录，默认作用域。
- `DocumentTreeScope`：用户通过 SAF 选择并持久授权的目录。
- `SharedStorageScope`：用户开启 All files access 后，再在 Helix 内选择的根目录；不是默认整个 `/storage/emulated/0`。
- `BrowserTabScope`：一个受 Helix 管理的标签页及其当前导航代次。
- `AutomationSessionScope`：允许的目标包、有效时间、最大步骤和禁止区域。
- `RootSessionScope`：单次用户开启、短时有效；默认只允许高层 Root 工具。

## 3. 内置浏览器

### 3.1 技术路线

使用 Android System WebView 作为渲染引擎，使用 `androidx.webkit:webkit` 访问跨系统版本的新能力。不要 fork Chromium，也不要依赖 X 浏览器的闭源实现。

参考边界：

- X 浏览器只作为轻量 UI、单手操作和标签管理的产品参考，不作为源码或技术依赖。
- EinkBro、Fulguris 可用于研究 WebView 生命周期、标签管理、下载和错误页；其许可证要求不适合不经审查直接复制。
- Helix 自己实现最小浏览器壳：地址栏、标签页、前进后退、刷新/停止、页面内查找、分享、下载、桌面模式和站点权限。

### 3.2 模块

```text
feature/browser/
├── api/                 # BrowserSession、TabId、Snapshot、BrowserError
├── engine-webview/      # WebView 生命周期、WebViewClient、WebChromeClient
├── automation/          # 固定脚本、DOM snapshot、动作解析
├── downloads/           # DownloadManager/SAF 目标
└── ui/                  # 地址栏、标签、权限卡、下载列表
```

WebView 只能由浏览器 feature 持有。Agent Runtime 不持有 `WebView` 引用，只调用 `BrowserController`。

### 3.3 首批浏览器工具

| Tool | 风险 | 说明 |
| --- | --- | --- |
| `browser.open` | L1 | 新建标签并导航；展示目标 origin |
| `browser.navigate` | L1 | 当前标签导航；跨 origin 更新作用域 |
| `browser.back` / `browser.forward` / `browser.reload` | L0 | 只作用于 Helix 标签 |
| `browser.snapshot` | L1 | 返回裁剪后的语义树、URL、标题；可能含敏感页面内容 |
| `browser.find` | L0 | 当前 snapshot 内查找 |
| `browser.click` | L2 | 依据 snapshot node ID 点击；要求页面代次一致 |
| `browser.type` | L2 | 输入文字；密码框、支付框和验证码框默认拒绝 |
| `browser.scroll` | L1 | 有界滚动 |
| `browser.screenshot` | L1 | 只截当前 Helix WebView，保存到 Workspace |
| `browser.download` | L2 | 显示 URL、文件名、大小上限和目标位置 |

`browser.click/type` 只能使用最近一次 snapshot 返回的短期 node token。页面导航、刷新、DOM 大变化或超时都会使 token 失效，防止模型在页面变化后点击错误对象。

### 3.4 WebView 安全约束

- 不对不可信页面注册永久 `addJavascriptInterface`。Android 官方明确警告该接口会让所有 frame 访问原生对象。
- DOM 提取和动作通过 Helix 固定、版本化的 JavaScript 片段执行；模型不能提交任意脚本给 WebView。
- 禁止 `file://` 通用访问、通用 content URL 访问和 file URL 跨域访问。
- 保持 WebView Safe Browsing 开启；`onSafeBrowsingHit` 默认回到安全页或显示系统 interstitial，绝不由 Agent 自动 `proceed`。该机制不代替 origin 展示/跨 origin 重新确认和客户端 URL 策略；混合内容默认禁止。
- 站点相机、麦克风、位置、通知、剪贴板权限默认拒绝；未来启用时逐站点、逐次确认。
- 下载必须限制协议、重定向、文件大小、MIME 和目标；禁止把下载的 APK/DEX/JAR/SO 自动执行或安装。
- WebView Cookie、历史、缓存和站点授权提供独立清除入口；Cookie 不进入 Agent Context 和日志。
- 页面正文标记为 `UNTRUSTED_WEB_CONTENT`，其中的指令不构成 Tool 授权。
- 对支付、账号恢复、系统权限、验证码和生物识别页面禁止自主点击或输入。

## 4. 文件管理器和 All files access

### 4.1 三层访问模式

| 模式 | Android 能力 | 默认 | 能力范围 |
| --- | --- | --- | --- |
| Workspace | App 私有目录 | 是 | 当前会话 Workspace |
| 授权目录 | Storage Access Framework | 可选 | 用户选定 tree URI |
| 完整文件访问 | `MANAGE_EXTERNAL_STORAGE` | 关闭 | 共享存储，但仍受 Android 限制 |
| Root 文件 | libsu RootService | 关闭 | 仅经 Root Policy 的系统路径 |

All files access 的正确流程：

1. App 解释用途、可访问范围和风险。
2. 跳转 `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`。
3. 返回后调用 `Environment.isExternalStorageManager()` 验证。
4. 用户在 Helix 内选择一个或多个可供 Agent 使用的根目录。
5. 每次 ToolCall 仍做 scope、风险和审批检查。

该权限并不允许访问其他 App 的所有私有目录，`Android/data` 等位置仍受平台限制。文档和 UI 不得宣称“获得手机全部文件”。

### 4.2 文件管理 UI

必须支持：

- 路径面包屑、收藏位置、最近文件、名称/时间/大小排序。
- 列表和网格视图、文本预览、图片预览、MIME/大小/哈希信息。
- 多选、复制、移动、重命名、删除到回收站、恢复、分享和 SAF 导出。
- 冲突策略：询问、跳过、重命名、覆盖；禁止默认覆盖。
- 长任务进度、取消和部分失败清单。
- 显示当前访问来源：Workspace、SAF、All files 或 Root。

Material Files 和 Amaze File Manager 只作为路径、冲突、长任务和 Root UX 的设计参考。两者为 GPL 项目，不能把代码复制进许可证不兼容的 Helix。

### 4.3 文件工具

为了提高模型兼容性，保留 Pi 风格的四个短工具名，并用 `scopeId` 限定 Android 访问范围：

| Tool | 默认风险 | 说明 |
| --- | --- | --- |
| `read` | L0/L1 | 读取文本或有界二进制元数据 |
| `write` | L1/L2 | 原子新建/覆盖，覆盖时升为 L2 |
| `edit` | L1/L2 | 唯一匹配 patch 或带前置 hash 的 patch |
| `bash` | L2 | 仅 PRoot Runtime；不是 Android 主进程 Shell |
| `files.list` | L0/L1 | 有界目录枚举 |
| `files.search` | L0/L1 | 文件名或正文搜索，有文件数/时间限制 |
| `files.stat` | L0 | size、mtime、MIME、hash 可选 |
| `files.mkdir` | L1 | 作用域内创建 |
| `files.copy` | L1/L2 | 跨作用域或覆盖时提升风险 |
| `files.move` | L2 | 显示源、目标和冲突策略 |
| `files.delete` | L2 | 默认进入 Helix 回收站 |
| `files.archive` / `files.extract` | L2 | 防 Zip Slip、文件数和膨胀比限制 |

短工具名和 namespaced 工具共享相同执行实现，不能出现两套 Policy。

## 5. Accessibility 自动化

### 5.1 定位

Accessibility Service 用于用户主动开启的跨 App UI 自动化。参考 Auto.js/AutoJs6 的节点查找、动作和等待模型，但 Helix 不内置可绕过 Policy 的通用 JavaScript 自动化环境，也不复制其源码。

启用流程：

1. 用户阅读屏幕内容可见性、自动点击和潜在误操作说明。
2. 跳转系统辅助功能设置，由用户手动开启 Helix 服务。
3. Helix 验证 service connection，并让用户选择允许自动化的 App 包名。
4. 每次运行创建有时限的 `AutomationSessionScope`，状态栏/前台通知提供立即停止入口。

### 5.2 工具和风险

| Tool | 风险 | 说明 |
| --- | --- | --- |
| `ui.snapshot` | L1 | 获取当前窗口的裁剪节点树和包名 |
| `ui.find` | L0 | 在已有 snapshot 中匹配文本/属性 |
| `ui.click` | L2 | node token + 预期包名/窗口 ID |
| `ui.long_click` | L2 | 每次审批 |
| `ui.set_text` | L2 | 密码/验证码/支付字段拒绝 |
| `ui.scroll` | L1 | 有方向和次数限制 |
| `ui.back` / `ui.home` | L2 | 系统全局动作 |
| `ui.wait` | L0 | 有界等待 UI 条件，不得无限轮询 |

MVP 不提供坐标盲点。节点动作失败时可以把截图和节点树返回给模型，但必须在发送前显示包含当前屏幕内容的隐私提示，Context 标记 `UNTRUSTED_ACCESSIBILITY_CONTENT`，并且截图/完整节点文本默认不长期保存。若未来增加坐标点击，必须作为 L3 单独设计。

### 5.3 强制防护

- 默认拒绝目标：系统权限设置、辅助功能设置、设备管理、Root 管理器、软件安装器、支付/银行、密码管理器、认证器、生物识别和锁屏。
- 默认拒绝点击语义：支付、转账、购买、发送、发布、删除账号、授权、允许安装、开启 Root。
- 单次 session 默认 5 分钟、30 个动作；每 10 个动作强制检查点。
- 检查点同时考虑经过时间、目标 package/window 和敏感语义变化；连续快速批准不会升级为自动允许。Advanced 只能在发布硬上限内调整 session/动作预算，不能关闭敏感目标或敏感语义拒绝。
- snapshot token 绑定 package、windowId、node fingerprint 和 generation。
- 目标包变化时暂停并请求用户确认；不得自动跟随 Intent 进入新 App。
- `FLAG_SECURE`、无法读取的 WebView/Canvas 和 OEM 自定义界面必须返回 `UNSUPPORTED_UI`，不能假装识别成功。
- 用户按停止、锁屏、服务断开、前台通知被关闭或 App 强制停止时立即中止。
- 任务审计只保存必要的结构化节点摘要；截图和完整文本默认不长期保存。

## 6. Root 能力

### 6.1 技术选型

采用 [topjohnwu/libsu](https://github.com/topjohnwu/libsu) `core` + `service`，基线 `6.0.0`。它提供 Root Shell 和基于 Binder 的 RootService。不要自行解析不同 Root 管理器协议，也不要把 `su` 字符串散落在业务代码中。

libsu 通过 JitPack 发布。当前 HXA-094 的计划路径是只给 `com.github.topjohnwu.libsu` 使用 exclusive content、固定 tag，并启用 Gradle dependency verification；M9/HXA-094 前不得提前加入 JitPack。HXA-094 必须通过依赖 ADR 记录 Spike、校验和与供应链接受结论。如果项目所有者届时不能接受 JitPack，则改为在仓库内维护经过审查的源码镜像和对应 notice，而不是替换成低维护度 Root 库；该替代方案不是与当前路径并行的隐式选择，必须先获得授权并更新 ADR/路线。

### 6.2 产品流程

1. 默认不触发 Root 弹窗。
2. 用户进入“高级能力 → Root”，查看用途和禁止事项。
3. 用户点击“请求 Root”；此时才创建 Root Shell，让 Root 管理器展示授权界面。
4. Helix 显示 `Unavailable / Denied / Granted / Lost`，不使用“检测到 su”冒充已授权。
5. Root session 默认 10 分钟无操作失效；用户可立即断开。

### 6.3 工具分层

优先提供高层、参数化工具：

| Tool | 风险 | 说明 |
| --- | --- | --- |
| `root.status` | L0 | 当前真实授权和 RootService 状态 |
| `root.file.read` | L2 | 用户选择的 RootScope 路径 |
| `root.file.copy` | L3 | Root 与普通存储间复制，显示方向和 hash |
| `root.package.info` | L1 | 只读包/UID/路径信息 |
| `root.process.list` | L1 | 有界进程快照 |
| `root.log.read` | L2 | 有界、脱敏、仅用户主动请求 |
| `root.exec` | L3 | 仅开发者控制台；默认不提供给 Agent 自动选择 |

禁止内置或自动执行：关闭 SELinux、修改 boot/vendor/system 分区、刷写镜像、安装 Root 模块、提取其他 App 凭据、绕过锁屏、隐藏 Helix、修改金融/认证 App、静默安装 APK、静默授权自身权限。

RootService 是更高权限的执行域，不是更强沙箱。它不得持有 Provider API Key；请求只包含已审批的结构化参数，结果有大小限制和脱敏。

## 7. Android 基础工具最小集合

本节是完整工具目录的权威名称/分组清单；具体产品优先级和实现里程碑以 PRD/backlog 为准：

```text
P0 核心：time.now, read, write, edit, files.list, files.search, files.stat,
         files.mkdir, code.javascript.run

P1 文件：files.copy/move/delete/archive/extract

P1 浏览器：browser.open/navigate/back/forward/reload/snapshot/find,
           browser.click/type/scroll/screenshot/download

P1 系统：android.open_uri, clipboard.read/write, notifications.query,
         calendar.prepare_event/commit_event, android.share, android.app_info

P1 网络：http.fetch

P1 开发者：bash

P2 高权限：ui.*, root.*
```

工具是否可见取决于 Capability、当前 Agent Mode、用户设置和执行目标。把不可用工具从模型工具表中移除，比让模型反复调用后报错更可靠；但会话审计仍记录能力为何不可用。

## 8. 实施顺序和验收

1. 先实现 Capability Center 和 scope 类型。
2. 扩展文件管理 UI 与 `read/write/edit`，再申请 All files access。
3. 实现无 Agent 控制的最小浏览器，再开放 snapshot，最后开放 click/type。
4. Accessibility 先做人工调试页和固定测试 App，再接入 Tool Registry。
5. Root 先做 status/RootService spike，再做只读高层工具；`root.exec` 最后且默认隐藏。

每类能力必须在无权限、拒绝、撤销、进程重启、目标变化和输出超限条件下返回稳定错误。构建成功或一次演示不构成验收。

### 8.1 未排期的自动化兼容候选

这些候选只说明 Android 上存在可研究的实现路径；没有当前 HXA、代码或发布承诺：

| 候选 | Android 实现路径 | 必须保留的边界 |
| --- | --- | --- |
| Tasker | 实现官方 Android automation plugin 的 action/event/state；允许 Tasker 调用 Helix 动作，或 Helix 触发用户命名 Task | 首阶段不导入完整 profile；插件输入仍经 schema/Policy/Approval，Tasker 不是授权主体 |
| Auto.js/AutoJs6 | 独立 Runtime 应用/UID 提供特定版本的 JavaScript/Android API、Accessibility、屏幕捕获和可选 Root 适配，经 signature-protected IPC 返回有界结果 | 任意来源脚本可导入和诊断，但执行兼容按版本/API/权限/模块矩阵声明；不在主进程或 QuickJS 暴露 Java bridge，不复制许可证不兼容源码 |
| Shizuku | 用户使用 Root 或 ADB 启动 Shizuku 服务，Helix 作为 client 绑定并跟踪 Binder 生命周期 | unavailable/denied/granted/lost 分开；服务状态不授予 ToolCall，断连不盲目重放 |
| 无线 ADB | Android 11+ 由用户启用无线调试并通过配对码/二维码建立连接；本机 client 需要单独评估 native 依赖、密钥和前台生命周期 | 不自动打开开发者选项、不静默配对；按 Android 版本/OEM 实测，用户可撤销，配对不等于全局 Full Access |

“兼容任意 Tasker/Auto.js 脚本”只能作为长期方向。可验收合同必须拆为导入、解析、API、权限、执行和行为六层；某脚本可导入不代表它能在当前 Runtime、ROM 与目标 App 上正确执行。授权边界遵循 [ADR-0012](../adr/0012-capability-first-advanced-grants.md)。

## 9. 主要官方依据

- [AndroidX WebKit](https://developer.android.com/jetpack/androidx/releases/webkit)
- [Android WebView 指南](https://developer.android.com/develop/ui/views/layout/webapps/webview)
- [管理所有文件](https://developer.android.com/training/data-storage/manage-all-files)
- [Accessibility Service 指南](https://developer.android.com/guide/topics/ui/accessibility/service)
- [Android Debug Bridge 与无线调试](https://developer.android.com/tools/adb)
- [Tasker 插件开发](https://tasker.joaoapps.com/plugins.html)
- [Shizuku 简介](https://shizuku.rikka.app/introduction/)
- [AutoJs6 项目说明](https://github.com/SuperMonster003/AutoJs6/blob/master/.readme/README-en.md)
- [Google Play：All files access](https://support.google.com/googleplay/android-developer/answer/10467955)
- [Google Play：AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491)
- [Google Play：Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646)
