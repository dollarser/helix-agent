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
    MOBILE_USE,
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

`MOBILE_USE` 表示宿主 Mobile Use 插件已启用并发布工具，不等于无障碍、Root 或 Shizuku 授权。应用查询、精确点击、屏幕观察、截图和手势使用此入口能力，仍由原会话范围和具体后端的实时检查决定是否执行。语义节点、应用启动和系统动作保留 `ACCESSIBILITY_AUTOMATION` 系统能力检查；consumer 不提供 Mobile Use，解析为 unavailable。

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

| Tool | 操作类型 | 说明 |
| --- | --- | --- |
| `browser.open` | NETWORK | 新建标签并导航；展示目标 origin |
| `browser.navigate` | NETWORK | 当前标签导航；跨 origin 更新作用域 |
| `browser.back` / `browser.forward` / `browser.reload` | LOCAL_MUTATION / NETWORK | 只作用于 Helix 标签 |
| `browser.snapshot` | READ_ONLY | 返回裁剪后的语义树、URL、标题；可能含敏感页面内容 |
| `browser.find` | READ_ONLY | 当前 snapshot 内查找 |
| `browser.click` | EXTERNAL_ACTION | 依据 snapshot node ID 点击；要求页面代次一致 |
| `browser.type` | EXTERNAL_ACTION | 输入文字；密码框、支付框和验证码框默认拒绝 |
| `browser.scroll` | LOCAL_MUTATION | 有界滚动 |
| `browser.screenshot` | LOCAL_MUTATION | 只截当前 Helix WebView，保存到 Workspace |
| `browser.download` | NETWORK + 文件效果 | 显示 URL、文件名、大小上限和目标位置 |

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

| Tool | 操作类型 | 说明 |
| --- | --- | --- |
| `read` | READ_ONLY | 读取文本或有界二进制元数据 |
| `write` | LOCAL_MUTATION | 原子新建/覆盖，显示覆盖目标并按当前规则授权 |
| `edit` | LOCAL_MUTATION | 唯一匹配 patch 或带前置 hash 的 patch |
| `bash` | CODE_EXECUTION | 仅 PRoot Runtime；不是 Android 主进程 Shell |
| `files.list` | READ_ONLY | 有界目录枚举 |
| `files.search` | READ_ONLY | 文件名或正文搜索，有文件数/时间限制 |
| `files.stat` | READ_ONLY | size、mtime、MIME、hash 可选 |
| `files.mkdir` | LOCAL_MUTATION | 作用域内创建 |
| `files.copy` | LOCAL_MUTATION | 分类源/目标范围与覆盖效果后授权 |
| `files.move` | LOCAL_MUTATION | 显示源、目标和冲突策略 |
| `files.delete` | LOCAL_MUTATION | 默认进入 Helix 回收站 |
| `files.archive` / `files.extract` | LOCAL_MUTATION | 防 Zip Slip、文件数和膨胀比限制 |

短工具名和 namespaced 工具共享相同执行实现，不能出现两套 Policy。

## 5. Accessibility 自动化

### 5.1 定位与授权

Mobile Use 是 Advanced/developer 的 host-native 插件，复用现有 Dispatcher、工具授权、视觉输入和会话产物，不拥有另一套规划或任务引擎。Standard/consumer 不打包其无障碍服务、手势或截图实现。

用户为当前 Conversation 选择“全手机（包括系统界面）”或指定应用并保存，形成该会话的持久配置；系统无障碍尚未开启时也可先保存。全手机不是默认授予，模型参数不能打开或扩大范围。授权直到用户主动关闭/修改；锁屏、进程死亡、设备重启、服务断开/系统权限丢失、模型或工具失败均不删除配置。无额外 TTL/动作数设置；Android 服务可用后，下一个获准工具调用重建运行态。

持久配置使用 Conversation ID、完整范围和独立 grantId；不变的保存复用原配置，范围变更生成新身份。运行态另有 process-local id，用于窗口/frame/回调；切换正在操作的会话或重建连接会刷新运行态，不复用旧 token。Dispatcher 将原调用的会话和批准 Scope 传给 executor，执行时再次对照保存配置。旧通知只能关闭绑定的原会话/Scope，不能关闭另一会话。

指定应用使用搜索多选界面（全部/用户应用/系统应用/已选），名称与包名同时可见，无需手工输入。分类基于 ApplicationInfo 标记，更新后的预装应用仍为系统应用；无桌面入口或已停用组件显示实际状态而不隐藏。列表读取在后台线程执行且仅用于本地设置；Advanced app 独立声明 QUERY_ALL_PACKAGES 以包含非 launcher 系统组件，Standard/consumer 不包含该声明。安装范围仍受 Android 当前用户/资料夹可见性约束，不宣称可查询其他 Android 用户。

勾选系统设置/系统界面同时允许已有系统全局操作，界面明确提示，但不暗中补入其他系统包。确认选择只修改当前表单，点击保存才更新该 Conversation 的持久集合；取消、搜索、刷新不改变已存配置。大集合仍完整保存在 Scope/Codec 中，超过审计字段长度的引用以整个规范化 Scope 的 SHA-256 表示，不以长度上限限制可选应用数。

### 5.2 工具与操作类型

| Tool | 操作类型 | 实际能力 |
| --- | --- | --- |
| `ui.snapshot` / `ui.find` | READ_ONLY | 当前语义树、文本/属性匹配、token 与节点边界 |
| `ui.click` / `ui.click_match` / `ui.long_click` | EXTERNAL_ACTION | 操作当前有效节点；`click_match` 原子执行最新快照上的唯一语义匹配，歧义/不可点时零副作用拒绝 |
| `ui.set_text` / `ui.ime_enter` / `ui.set_progress` | EXTERNAL_ACTION | 原生文本输入、IME 提交与滑块；只在节点声明对应能力时执行，拒绝不存在的能力与越界数值 |
| `ui.scroll` | EXTERNAL_ACTION | 节点 forward/backward 滚动，屏幕手势另走 gesture |
| `ui.back` / `ui.home` | EXTERNAL_ACTION | 保留原便捷动作入口，可离开暂停目标 |
| `ui.wait` | READ_ONLY | 等待出现、消失、匹配内容改变、语义稳定；单次最多 60 秒 |
| `ui.device` | READ_ONLY | 当前 display/window、旋转、物理像素、frame 和实际系统动作 |
| `ui.apps` | READ_ONLY | Android 可见且已授权的可启动应用；列表截断明确标记 |
| `ui.launch` / `ui.system` | EXTERNAL_ACTION | 启动应用；返回/主屏/最近任务、通知/快捷设置、电源菜单/分屏/锁屏、方向键、应用抽屉、媒体/耳机及菜单动作；只调用平台实际支持项 |
| `ui.gesture` | EXTERNAL_ACTION | 单点点击/长按、路径滑动/拖动、多指缩放；一组 strokes 为一次原生手势 |
| `ui.screenshot` | LOCAL_MUTATION | 授权窗口/屏幕 PNG、会话/Turn 产物和既有视觉回填，不往 JSON 塞 Base64 |

两组共 18 个工具；`ui.system` 枚举 18 种动作，设备可用集合来自 `getSystemActions()`（API 29 使用平台已有基础动作）。`ui.ime_enter` 仅在 API 30+ 且当前可编辑节点实际声明 IME Enter action 时可用；枚举存在不是设备支持证明。`headset_hook` 可接听/挂断电话，`lock_screen` 会暂停物理操作而不删除 Conversation 授权，应由模型按任务意图选择。

`ui.click_match` v5 默认 `backend=auto`：对于精确 `packageName` + `viewId` + `text` 且无额外筛选参数的点击，按当前已授权且连接的 Root → Shizuku → Accessibility 选择一次。显式 `root`/`shizuku`/`accessibility` 固定路径；派发失败或 UNKNOWN 不跨后端重放。其他筛选、节点 token、截图、手势和输入仍使用已实现的 Accessibility 能力。`ui.device` v3 返回 `rootState`、`shizukuState` 和 `clickMatchBackend`，后者不代表所有操作的后端。

Mobile Use 的 Root 连接由设置中的明确授权按钮创建，允许切换目标应用，并可主动断开；状态查询和模型调用不请求 Root、不冷绑定。复用 libsu 非 daemon 服务与同一有界特权点击协议，原 `root.*` 的后台失权规则不变。Root manager 撤销未来授权未必杀死已运行的 UID 0 服务；逐调用仍检查原会话范围、停止、锁屏和取消，不能将系统 Root 授权视为业务授权。验证与边界见 [Root 优先级证据](../evidence/development/mobile-use-root-priority-2026-10-04.md)。

### 5.3 观察、执行与真实结果

- 移除按应用类别、包名关键词或“发送/支付/安装”等文本的一刀切拒绝。实际副作用仍受当前会话工具授权与用户范围约束，不另外建立业务关键词审批器。密码/Android 标记的敏感节点只暴露脱敏占位，不隐藏整个页面。
- 新 snapshot/find、节点动作与授权变化会废止旧节点 token；package/window/fingerprint/generation 继续检查，取消任意 30 秒 token 时限。失效应重新观察，不自动重复点击。
- frame 绑定 grant、package/window/display、旋转、尺寸与窗口边界；坐标使用物理像素。指定应用模式核对路径和覆盖窗口，不能借坐标手势进入未授权 App。全手机模式允许跨应用/系统 UI，但不会授权 Android 不允许的行为。
- API 30–33 截图仅在全显示范围已授权时使用屏幕 API；API 34+ 指定应用可抓取目标窗口。API 29 没有这条截图后端。`FLAG_SECURE`/系统禁止、未连接和超时返回真实错误，不尝试绕过；无语义节点的 Canvas 可用画面观察与手势，但没有可识别目标窗口时不假装成功。
- PNG 的 width/height 与模型所见的 imageWidth/imageHeight 分开返回，通过 screenBounds 映射。先写入已绑定 session/turn 的产物，再准备规范化图片；无视觉能力仍保留文件，明确说明像素未回填。发送给模型继续使用原有视觉来源/披露机制。截图属于现有会话产物，保留/删除沿用产物机制，不能宣称从未存储。
- 不新增全局任务锁。仅不能重入的物理手势/截图回调各自拥有执行槽；取消等待不冒充 Android 已退出，迟到回调不能释放新任务的槽。平台已进入但结果不明返回 UNKNOWN，不作为安全自动重试依据。停止立即撤销新动作权限，但不宣称已撤回平台可能执行的动作。
- 指定范围越界时暂停；回到已授权目标并取得有效观察可恢复。同一授权外的新目标需用户修改范围。等待不暗中恢复过期/停止许可；截断观察不能证明元素不存在或页面稳定，返回结果数限制也不能掩盖其他匹配节点的变化。

### 图片交给模型处理的确认

系统无障碍许可、Mobile Use 操作范围、`ui.screenshot` 工具授权与网络图片确认是不同对象。`WorkspaceToolImagePublisher` 先登记本会话/Turn 的截图，`ToolImagePreparer` 产生可选的规范化视觉来源；工具返回的视觉来源不等于图片已经发送成功。

Mobile Use 设置显示当前接收 Provider/模型与服务 origin，点击“保存并开启”同时允许本会话当前及以后用户选择的模型处理后续原生截图，无需逐图或切换接收方确认。原生截图通过可信发布接口持久登记采集 Scope、会话/Turn、哈希和类型；`MobileUseScreenConsent` 使用该 Conversation 的保存配置校验，不从文件名、工具 JSON 或模型参数推导授权。每次模型请求的 proof 仍绑定实际内容和接收方，防止在途路由变化借用旧 proof，但重新绑定不制造新的用户审批。

未选模型也可以先保存配置，后续用户选择模型后直接按此授权分享；并发截图不创建图片确认卡。锁屏、服务断开和进程结束不清除共享配置，重建服务或应用无需再确认。用户关闭或更改 Scope 后，旧 proof 及旧采集 Scope 不能复用；已外发的数据不声称能撤回。普通附件、浏览器图片和其他文件读图仍使用原本的图片/内容与接收方确认。

真正 `ProviderTransport.OnDeviceLocal` 无需网络外发确认，仍检查来源、活动 Turn、哈希、视觉能力及真实配置绑定；Mobile Use 还检查采集许可与 live grant。经 HTTP/HTTPS 的自建、LAN 或 loopback Provider 仍为网络传输，但本会话原生截图已由持久配置覆盖，不逐图确认；普通图片不凭主机名称跳过确认。最终 `BoundImageAccess` 在读取字节前复核共享资格，用户关闭后不继续回填尚未外发的截图。会话工具权限始终独立：FULL_ACCESS 不加额外逐动作卡，APPROVAL_REQUIRED/READ_ONLY/CUSTOM 保留用户设置。

### 5.4 验证与设备边界

当前授权与选择器交付以 HXA-244 的主机/实际 APK 证据为准，不继承 HXA-241 模拟器通过结论。界面后台运行不等于绕过锁屏；ACTION_SCREEN_OFF 或 Keyguard 锁定只终止物理运行态，保存授权不变，不自动输入解锁密码。真实 UI、OEM、无障碍窗口截图和手势兼容性需要单独设备验收。

API 来源：[AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、[应用 ID 规则](https://developer.android.com/build/configure-app-module)。关于设备就绪的历史研究见 [Mobile Use 设备就绪与可靠性](../research/topics/mobile-use-device-readiness-and-reliability-2026-09-29.md)，不把旧限制作为当前契约。


## 6. Root 能力

### 6.1 技术选型

采用 [topjohnwu/libsu](https://github.com/topjohnwu/libsu) `core` + `service`，基线 `6.0.0`。它提供 Root Shell 和基于 Binder 的 RootService。不要自行解析不同 Root 管理器协议，也不要把 `su` 字符串散落在业务代码中。

libsu 通过 JitPack 获取；当前生产依赖已由 HXA-094 按 [ADR-RUNTIME-004](../adr/runtime/004-root-service.md) 验收为 `core` + `service` 6.0.0，仅允许 `com.github.topjohnwu.libsu` 的 exclusive content，并纳入固定版本、checksum/dependency verification 与许可证闭包。后续若改变 libsu 版本、仓库来源或 Root 依赖方案，必须重新做供应链/设备边界评审，不能把替代源作为隐式 fallback。

### 6.2 产品流程

1. 默认不触发 Root 弹窗。
2. 用户进入“高级能力 → Root”，查看用途和禁止事项。
3. 用户点击“请求 Root”；此时才创建 Root Shell，让 Root 管理器展示授权界面。
4. Helix 显示 `Unavailable / Denied / Granted / Lost`，不使用“检测到 su”冒充已授权。
5. Root session 默认 10 分钟无操作失效；用户可立即断开。

### 6.3 工具分层

优先提供高层、参数化工具：

| Tool | 操作类型 | 说明 |
| --- | --- | --- |
| `root.status` | READ_ONLY | 当前真实授权和 RootService 状态 |
| `root.file.read` | READ_ONLY + Root 能力 | 用户选择的 RootScope 路径 |
| `root.file.copy` | 特权文件修改（规划） | Root 与普通存储间复制，显示方向和 hash |
| `root.package.info` | READ_ONLY + Root 能力 | 只读包/UID/路径信息 |
| `root.process.list` | READ_ONLY + Root 能力 | 有界进程快照 |
| `root.log.read` | READ_ONLY + Root 能力 | 有界、脱敏、仅用户主动请求 |
| `root.exec` | 特权执行（规划） | 仅开发者控制台；默认不提供给 Agent 自动选择 |

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
| Root / Shizuku 点击 | Advanced Mobile Use 设置中显式授权；精确 `ui.click_match` 自动按 Root → Shizuku → Accessibility 选择，也可显式指定 | 原会话范围、无障碍租约、默认显示与执行限制仍有效；封闭点击协议，不开放 shell；断连/结果不确定不重放 |
| 无线 ADB | Android 11+ 由用户启用无线调试并通过配对码/二维码建立连接；本机 client 需要单独评估 native 依赖、密钥和前台生命周期 | 不自动打开开发者选项、不静默配对；按 Android 版本/OEM 实测，用户可撤销，配对不等于全局 Full Access |

“兼容任意 Tasker/Auto.js 脚本”只能作为长期方向。可验收合同必须拆为导入、解析、API、权限、执行和行为六层；某脚本可导入不代表它能在当前 Runtime、ROM 与目标 App 上正确执行。授权边界遵循 [ADR-PERMISSIONS-003](../adr/permissions/003-dispatch-and-audit.md)。

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
