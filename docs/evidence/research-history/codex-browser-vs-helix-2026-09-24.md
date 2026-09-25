# Codex 浏览器能力 vs Helix：差距分析

**日期**：2026-09-24
**基线**：Helix HEAD `3cf89027`
**问题**：Helix 有没有操作浏览器的能力？与 Codex 的 `control-in-app-browser` / `mcp__node_repl__js` 差什么？

---

## 〇、结论摘要

**Helix 有浏览器操作能力，而且相当完整**——12 个 `browser.*` 工具，全部在生产容器注册，HXA-060/061/062/063 已交付。

**真实差距只有一条值得补**：**Agent 看不见页面渲染**。`browser.screenshot` 把 PNG 存进 Workspace 后只回传一个引用字符串，模型拿不到图像。

另外两条"差距"不是缺陷：
- **没有"控制用户真实浏览器"这条线** —— 应保留（Android 无等价物，且项目明令禁止抽取 Cookie）
- **没有通用 JS 脚本底座** —— 架构取舍，不该补（QuickJS 是离线 + isolated UID + 无宿主桥）

---

## 一、Codex 侧：两条浏览器线 + 一个脚本底座

Codex 的浏览器能力不是一个工具，是**三条独立路径**，很容易被混为一谈：

| 线 | 控制对象 | 依赖 | 典型用途 |
|---|---|---|---|
| **in-app browser**（`control-in-app-browser`） | Codex 自己开的浏览器 | 无（桌面 App 内置） | 本地 dev server 布局验收、文档链接 404 检查、设计与实现对照 |
| **Control Chrome** | **用户自己的真实 Chrome** | Chrome 扩展 + `mcp__node_repl__js` | 用用户登录态操作、读已有标签页 |
| Playwright / Chrome DevTools MCP（备选） | 第三方浏览器 | 额外 MCP server | 替代方案 |

### 1.1 in-app browser 能做什么

读 DOM、点击、输入、检查渲染状态、**截图**、下载页面资源、运行只读页面检查。按站点授权（per-site approval）。文档同时明确它不是 E2E 测试框架的替代，且默认能访问"机器能访问的一切"（含内网管理页），所以需要在任务里显式声明 `localhost only` 或域名白名单。

### 1.2 `mcp__node_repl__js` 到底是什么（关键澄清）

**它不是浏览器工具。** 它是 MCP server `node_repl` 暴露的一个通用 JS 执行工具，兄弟工具是 `js_reset`、`js_add_node_module_dir`。它能跑任意 Node 代码（`await import("...")`、`nodeRepl.write(...)`）。

它的角色是 **Control Chrome 这条链路的"手"**：

```
Codex 线程
  → 工具发现 / MCP 工具注册
  → Node REPL 的 js 执行工具（mcp__node_repl__js）
  → Control Chrome skill
  → scripts/browser-client.mjs
  → Chrome 扩展
  → 用户自己的 Chrome 标签页
```

这解释了那个著名报错「当前线程没有 `mcp__node_repl__js`」：**Chrome 没坏，是工具路由没接上**——`js` 工具没暴露给当前线程，Control Chrome 就缺少启动链路的手。`js_reset` 只是重置 REPL 状态，`js_add_node_module_dir` 只是加模块搜索路径，**都不能代替 `js`**。

**为什么必须走这条线**：要控制**用户真实 Chrome**（登录态、Cookie、已有标签页、Chrome Profile）只能用扩展桥。Playwright 和 in-app browser 控制的是另一个浏览器环境，替代不了。

> 命名注：`mcp__node_repl__js` 与界面上显示的 `mcp__node_repl.js` 指同一工具（前者是 `mcp__服务名__工具名` 的严格写法）。

---

## 二、Helix 侧：12 个类型化工具，生产已接线

### 2.1 工具清单（`tools/browser/BrowserTools.kt:17-28` 注册 12 个）

| 工具 | 能力 | 对应 Codex |
|---|---|---|
| `browser.open` | 新建标签页打开 URL | ✅ |
| `browser.navigate` | 当前标签页导航 | ✅ |
| `browser.back` / `browser.forward` / `browser.reload` | 历史导航 | ✅ |
| `browser.snapshot` | **结构化 DOM 快照**（≤400 节点、每节点文本 ≤200 字符） | ✅ 读 DOM |
| `browser.find` | 页内查找 | ✅ |
| `browser.click` | 按 node token 点击 | ✅ |
| `browser.type` | 按 node token 输入（敏感字段默认拒绝） | ✅ |
| `browser.scroll` | 滚动 | ✅ |
| `browser.screenshot` | 截图存 Workspace | ⚠️ **见 §三** |
| `browser.download` | 下载页面资源到工作区 | ✅ |

**生产装配证据**：`app/src/main/kotlin/com/helix/app/DefaultAppContainer.kt:425-428`

```kotlin
BrowserTools.registerAll(
    ...,
    BrowserToolBridgeImpl(browser, workspaceStore, APP_SCOPE_ID),
)
```
配合 `:339` `override val browser: BrowserController = BrowserController(context)`。**不是桩，不是测试专用。**

**路线图状态**：HXA-060（最小 WebView 浏览器）、HXA-061（snapshot）、HXA-062（actions）、HXA-063（download）**全部"已交付"**，另有 HXA-152/154/155/159/160/188 六项浏览器相关加固（原生引用短时归因、生产路径引用核实、网络取消与 Activity 生命周期、Activity 级宿主、真机冻结与后台恢复）。

### 2.2 在五个点上 Helix 比 Codex 更严格

**1. 模型不能向 WebView 提交任意脚本**（doc 09 §3.4 明文禁止）

页面上只跑**固定、版本化、编译期常量**的 JS 片段：

- `BrowserSnapshotScript.EXTRACT` —— 源码注释称它是「the ONLY JavaScript the browser feature ever evaluates on a page」，是 compile-time constant，**没有任何页面值、用户输入或模型输入被插值进去**
- `BrowserActionScript.click/type/scroll` —— 模型值只以**已验证的自包含字面量**方式插值：nodeIndex 是宿主校验过的 Int，type 文本作为 JSON 字符串字面量注入（JSON 字符串字面量即合法 JS 字符串字面量，无法越出脚本）

Codex 走的是**完全相反的路线**：agent 通过 `node_repl` 写任意 JS。

**2. node token 失效语义（Codex 没有这层结构保证）**

`SnapshotToken` 绑定 7 个字段：`nodeIndex` / `tabId` / `origin` / `navigationGeneration` / `fingerprint` / `mintedAtMillis` / `ttlMillis`，默认 TTL 60 秒。校验按序执行、**首个失败即拒**，6 种 fail-closed 判定：

```
MalformedToken  WrongTab  StaleOrigin  StaleGeneration  StaleFingerprint  Expired
```

宿主是唯一签发者，token 自己声称的一切都不被信任——页面或模型可以读取并重放 token，但一旦导航、跨 origin、DOM 变化（指纹不匹配）或超过 TTL，立即失效。

**3. URL 准入策略 fail-closed**（`BrowserUrlPolicy`）

只放行 `http`/`https`（非空 host）、`about:blank`、`data:text/html`；`file`/`content`/`intent`/`javascript`/`view-source`/其他 `about:` 全部拒绝。无法解析的 URL 直接判 INVALID，不交给平台解析器。且策略只在**一个**导航咽喉点应用（`BrowserTabController.navigate` + 下载路径），没有调用点能绕过。

**4. 敏感字段双重判定**

`SensitiveFieldClassifier` 对 password / payment / one-time-code 默认拒绝，且 **JS 侧与 Kotlin 侧各自独立判定，两边都同意才执行**（fail-closed）。两侧实现由单测 + 设备拒绝测试双向钉住。

**5. 用户脚本是用户侧能力，不是模型能力**

`UserScriptEngine`（Tampermonkey 风格）通过 `evaluateJavascript` 注入用户自己写的脚本——这是**唯一**让任意 JS 到达 WebView 的地方。但它**没有暴露为任何工具**（`grep userscript|user_script|userScript` 在 `tools/browser/` 零命中）。Agent 无法注册或触发用户脚本。

---

## 三、差距逐项对照

| 能力 | Codex | Helix | 差距性质 |
|---|---|---|---|
| 打开 / 导航 / 前进后退 / 重载 | ✅ | ✅ | 无 |
| 读 DOM | ✅ | ✅ snapshot（结构化 + 有界 + 指纹） | 无，Helix 更规范 |
| 点击 / 输入 / 滚动 | ✅ | ✅ token 化 + 敏感字段拒绝 | 无，Helix 更严格 |
| 下载页面资源 | ✅ | ✅ `browser.download` | 无 |
| **截图给模型看** | ✅ 多模态回传图像 | ⚠️ **只存 Workspace，模型拿引用** | **真差距（可补）** |
| **控制用户真实浏览器** | ✅ Control Chrome | ❌ 无 | 差距（**应保留**） |
| **任意 JS 脚本底座** | ✅ `node_repl` | ❌ 刻意没有 | 架构取舍（**不该补**） |
| 模型提交任意页面脚本 | ✅（即 node_repl） | ❌ 明令禁止 | **Helix 更强** |
| 按站点授权 | ✅ | ✅ URL 策略 + 敏感字段分类器 | 相当 |
| 令牌失效语义 | 无（CDP 直连） | ✅ 6 种 fail-closed | **Helix 更强** |

### 3.1 差距 1（唯一值得补）：Agent 看不见页面渲染

`browser.screenshot` 的工具描述原文：

> "Capture the current page of a browser tab as a PNG and save it to the Workspace. **Returns the model-safe Workspace reference plus the file size and SHA-256** (never a raw filesystem path)."

`maxOutputBytes = 4096`，`baseRisk = RiskLevel.L1`。**模型收到的是 `scope:<id>:<path>` + 字节数 + SHA-256，不是图像。**

而 Helix 的图像进入模型上下文**只有一条路**：

`app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt:293-300`
```kotlin
val restored = messages.map { message ->
    if (message.role == ModelRole.USER) {
        message.copy(images = imageVerifier.imageReferencesFor(userRows[userRow++].messageId.orEmpty()))
    } else { message }
}
```

只给 **USER 消息**挂 `images`，来源是该用户消息的图片绑定。`imageReferencesFor(messageId)` 全仓**唯一调用点**就是这里。且 `AttachmentPurpose` 只有 `REFERENCE` 一个角色，注释写明是「reference content **the user wants the model to read**」——附件在设计上就是用户提供的。

**结论：没有任何"工具结果 → 图像"的通路。**

**影响**：
- 布局/样式/CSS 问题（如"375px 下登录表单溢出"）——结构化快照表达不了，Agent 无法判断
- 视觉回归、设计稿对照、渲染异常排查不可做
- 图片内容理解（canvas、图表、验证码、图片内文字）不可做
- `browser.screenshot` 目前的定位是**给用户看**，不是给模型看

**根因**：不是遗漏，而是 HXA-055 视觉附件的设计把图像绑定在用户消息上，工具侧没有开口。

**修法**：给工具结果开图像通道——最小改动是让 `browser.screenshot` 能产出 `ImageReference` 并被请求装配纳入。

**但要注意**：这会打破"图像只绑定用户消息"的现有不变量——`ChatRequestAssembler.kt:289-291` 有硬守卫：
```kotlin
require(userRows.size == userMessages.size) {
    "history USER rows and USER messages diverge — image binding refused"
}
```
所以这不是一个局部改动，**需要一次 ADR 级别的裁决**（图像来源从"用户提供"扩展为"用户提供 + 工具产出"），并同步 `VisionLimits` 的预算口径、`ContextSegments` 的变更检测（`:112` 有 `copy(images = emptyList()) != ...` 的比较逻辑）与审计。

### 3.2 差距 2（应保留，不该补）：没有"控制用户真实浏览器"这条线

Helix 是**自己进程内的 WebView**，无外部浏览器接管：

- `grep externalBrowser|Intent.ACTION_VIEW|openExternal|customTabs|CustomTabs` → **0 命中**
- AGENTS.md 明文禁止：`no implementation may extract browser cookies or import credentials from another App/CLI`

**为什么不该补**：
1. Android 上没有 Codex 那种"用户 Chrome Profile"的等价物——Chrome for Android **不提供扩展机制**，Control Chrome 那条链在 Android 上不存在
2. 抽取浏览器 Cookie 是项目**已接受的禁止项**
3. Helix 的定位是单机 Agent，不是桌面 IDE 的浏览器副驾

**但有一个值得在文档里说清的中间态**：如果用户已在 **Helix 自己的 WebView** 里登录了某站点，那个 Cookie jar 本来就在 Helix 进程内。所以"复用用户已登录的会话"在 Helix 内是**自然成立**的——这不是能力缺失，而是它比 Codex 更简单的地方。目前文档没有明确说明，容易被读成"Helix 不能用登录态"。

### 3.3 差距 3（架构取舍，不该补）：没有通用脚本底座

| | Codex `mcp__node_repl__js` | Helix `code.javascript.run`（QuickJS） |
|---|---|---|
| 本质 | 宿主进程内任意 Node JS | 隔离的 JS 运行时 |
| 网络 | 有 | **离线**（源码注释：`isolated, offline QuickJS backend`） |
| 宿主桥 | 有（可加载 `browser-client.mjs`、连扩展） | **无特权主机桥接**（ADR-RUNTIME-003） |
| 进程 | 宿主进程 | 非导出 isolated process，isolated UID |
| 授权 | — | `CODE_EXECUTION`、`RiskLevel.L2`、**每次调用需批准**、无自动放行 |
| 并发 | — | **单并发**（串行，`ExecutionTargetType.LOCAL_QUICKJS` 独占） |

Helix 的 QuickJS **不能**当 `node_repl` 用：不能联网、不能碰宿主、不能做浏览器胶水。PRoot/CLI 是 developer-only 且 job-scoped（冷绑定，仅用户触发的验证/修复/登录或已批准 Job）。

**这是设计取舍，不是缺陷。** Codex 的 `node_repl` 是能力极大、结构保证极小的东西（agent 能跑任意 Node 代码 = 任意文件/网络/进程访问）；Helix 用"固定脚本 + 类型化工具 + 令牌失效"换掉了这份灵活性，这是 `docs/research/project-structure-and-engine-review.md:19` 已接受的方向。

**但代价要认**：Helix 的 Agent 无法写"胶水代码"把多个能力串起来。Codex 可以说"跑一段 JS 把这 20 个页面轮一遍、抽表格、汇总成 JSON"；Helix 只能让模型逐步调 12 个类型化工具，**每一步都可能撞上 token 失效**（60s TTL + DOM 指纹 + 导航代际）。多步浏览器任务的**调用次数与失败率会显著更高**——这是当前设计下最实际的可用性成本，且没有被任何文档量化过。

---

## 四、建议

| 优先级 | 动作 | 说明 |
|---|---|---|
| **P1** | 给工具结果开图像通道，让 Agent 能看见自己的截图 | §3.1。唯一真正的能力差距。**但需 ADR 裁决**（打破"图像只绑定用户消息"的不变量），并同步 `VisionLimits` / `ContextSegments` / 审计 |
| **P2** | 在 `docs/architecture/` 补一段浏览器能力边界说明 | §3.2。说清"Helix 的浏览器是自有 WebView、复用其自身会话；不存在也不打算提供对外部浏览器的接管"，避免把设计取舍读成能力缺失 |
| **P2** | 量化多步浏览器任务的成本 | §3.3。当前无人测过"完成一个 N 步浏览器任务需要多少次工具调用、token 失效导致多少次重试"。这是 QuickJS 取舍的真实代价，建议用一条 androidTest 或 evals 度量 |
| **不做** | 补 Control Chrome 等价物 | 与 AGENTS.md 禁止项冲突，且 Android 无技术路径 |
| **不做** | 把 QuickJS 改成通用宿主脚本底座 | 与 ADR-RUNTIME-003（isolated UID、无特权主机桥接）直接冲突 |

---

## 五、证据与置信度

| 结论 | 证据 | 置信度 |
|---|---|---|
| Helix 有 12 个 browser 工具且生产已注册 | `tools/browser/BrowserTools.kt:17-28`；`DefaultAppContainer.kt:425-428`、`:339` | **高** |
| 模型不能向 WebView 提交任意脚本 | `BrowserSnapshotScript.kt:6-9`、`BrowserActionScript.kt:6-16` 注释引 doc 09 §3.4 | **高** |
| node token 6 种失效判定 + 60s TTL | `SnapshotToken.kt`（全文 139 行） | **高** |
| URL 策略 fail-closed 且单一咽喉点 | `BrowserUrlPolicy.kt:8-19` | **高** |
| 截图不回传图像 | `BrowserOutputTools.kt:26-35`（描述 + `maxOutputBytes = 4096`） | **高** |
| 图像只绑定 USER 消息 | `ChatRequestAssembler.kt:293-300`；`imageReferencesFor` 全仓唯一调用点 | **高** |
| 附件在设计上是用户提供 | `AttachmentPurpose.kt:9-14` | **高** |
| 无外部浏览器接管 | `grep externalBrowser\|Intent.ACTION_VIEW\|customTabs` → 0 命中；AGENTS.md 禁止项 | **高** |
| QuickJS 离线 + 无宿主桥 + 单并发 + L2 | `CodeJavascriptRunTool.kt:47-60`；`docs/architecture/local-code-execution.md:7` | **高** |
| Codex in-app browser 能力集 | Codex Handbook（自述核验依据为 OpenAI Help Center，2026-07-26 核验） | 中高 |
| Codex Control Chrome 链路与 `node_repl` 角色 | 第三方排障文（Linux.do 线索 + 4SAPI 第 132 期）；**非 OpenAI 官方文档** | **中**——链路描述自洽且与官方 issue #31533（node_repl 未暴露）互证，但具体实现细节未经官方确认 |

---

*本文对 Codex 侧的描述来自公开文档与第三方排障资料（已在置信度中标注），Helix 侧结论全部来自本仓源码。未修改任何项目文件。*
