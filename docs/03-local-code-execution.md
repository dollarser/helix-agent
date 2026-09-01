# Helix 本地代码执行方案

## 1. 目标与分层

Helix 需要允许 Agent 临时生成代码解决长尾任务，但不同代码需要不同执行环境。

| 层级 | 环境 | 用途 | 当前计划 |
| --- | --- | --- | --- |
| E0 | 原生 Tool | 稳定手机能力和常见文件操作 | P0 |
| E1 | QuickJS isolated process | JSON/CSV/文本/算法/格式转换 | P0 |
| E2 | PRoot + Alpine | Python/Node/Git/Shell/stdio MCP | P1，独立 Runtime APK |
| E2C | CLI Runtime | 官方 Codex/Claude CLI 订阅会话 | P2 实验，独立网络 Runtime APK |
| E3 | Remote Worker | 重型编译、远程浏览器、长任务 | 当前不实现 |

路由原则：能用 E0 就不用代码；能用 E1 就不用 Linux；只有依赖完整 CLI/包生态或 stdio MCP 时才使用 E2。内置浏览器属于 E0 平台工具，不属于远程浏览器。

这里的 E0/E1/E2/E2C 都在用户手机的物理 CPU 和 Android/Linux 内核上执行；区别是进程、UID、权限、网络和数据入口，而不是“本机/云端”二选一。模型推理可以来自公网、LAN 或设备内 Provider，但 Provider 只返回文本/ToolCall，真正 Tool 执行仍由 Helix 的本机管线完成。当前没有远程 shell/Worker，也不能把“isolated process”“PRoot”宣传成 VM：前者是 Android isolated UID 边界，后者是独立 UID 中的 Linux 用户态兼容层。

## 2. QuickJS 技术选型

### 2.1 选型

使用 [Cash App Zipline](https://github.com/cashapp/zipline) 暴露的 `app.cash.zipline.QuickJs` Android/JNI 实现，基线版本 `1.27.0`，许可证 Apache-2.0。

选它而不是自行维护 QuickJS JNI 的原因：

- 提供 Android 可用的 QuickJS 封装。
- 有 `memoryLimit`。
- 有可中断执行的 `InterruptHandler`。
- 支持直接 `evaluate(source, fileName)`。
- 已处理 Android 15 16 KiB page size 等兼容问题。

重要限制：Zipline README 明确说明它不提供 sandbox 或进程隔离，不能单独作为不可信代码安全边界。因此 Helix 必须再加 Android `isolatedProcess`，并且不能给 JavaScript 注册有权限的 Host Bridge。

### 2.2 进程模型

```text
主应用进程                         :helix_js 隔离进程
┌────────────────────┐ Binder   ┌────────────────────────┐
│ CodeExecutionTool  │─────────►│ JsExecutionService     │
│ Policy + Approval  │ request  │ isolatedProcess=true   │
│ Workspace Broker   │◄─────────│ QuickJs instance       │
│ Audit              │ result   │ no Android permissions │
└────────────────────┘          └────────────────────────┘
```

Manifest：

```xml
<service
    android:name=".quickjs.JsExecutionService"
    android:process=":helix_js"
    android:isolatedProcess="true"
    android:exported="false"
    android:stopWithTask="false" />
```

隔离 Service 没有自己的 Android 权限，只通过显式 Binder API 接收请求和返回结果。不要给它传入主进程的 API Key、Keystore handle、ContentResolver 或可写外部 URI。

`:helix_js` 是 manifest 为该组件声明的相对 process label，完整名称会分别依附 `com.helix.agent` / `com.helix.agent.developer`；`bindIsolatedService` 的唯一 `instanceName` 是 Android API 用于标识每个隔离 Service 实例的键。实现不得把实际 Linux 进程名当作协议 ID、生命周期证据或安全边界；这些分别使用 `executionId`/`instanceName`、Binder death/PID 观测和系统分配的 isolated UID。

## 3. QuickJS 执行协议

### 3.1 请求

```kotlin
@Parcelize
data class JsExecutionRequest(
    val executionId: String,
    val sourceUtf8: ByteArray,
    val inputJsonUtf8: ByteArray,
    val limits: JsExecutionLimits,
) : Parcelable

@Parcelize
data class JsExecutionLimits(
    val timeoutMs: Long = 10_000,
    val memoryBytes: Long = 64L * 1024 * 1024,
    val maxSourceBytes: Int = 256 * 1024,
    val maxInputBytes: Int = 2 * 1024 * 1024,
    val maxOutputBytes: Int = 256 * 1024,
) : Parcelable
```

Binder 有事务大小限制。超过安全阈值的数据使用只读 `ParcelFileDescriptor`，不要把大文件塞进 Parcelable。

### 3.2 JavaScript ABI

每段代码包裹为严格模式 IIFE。输入是 host 编码的参数/局部 `const`，不是可被生成代码覆盖的 global：

```javascript
((__helixInputJson) => {
  "use strict";
  const input = JSON.parse(__helixInputJson);

  function helixMain(input) {
    // Agent 生成的代码只能读取 input 并返回 JSON 可序列化值。
    return { ok: true, value: input.value * 2 };
  }

  return JSON.stringify(helixMain(input));
})(/* host 生成的 JSON string literal */);
```

第一版只允许 JSON 输入和 JSON 输出。文件内容由主进程的 Workspace Tool 在审批后通过 `read(offset, maxBytes)` 分块读取、做大小限制，再作为 JSON 字符串传入；JS 不直接获得路径和文件描述符。Workspace 10 MiB 单文件上限不等于 JS 可整块接收 10 MiB，超过 2 MiB input 必须由 Agent 分块/汇总或改用 PRoot。

### 3.3 不提供的全局对象

- `fetch`、WebSocket、XMLHttpRequest。
- `require`、Node.js modules、npm。
- `java`、Android Context、JNI 任意调用。
- 任意文件读写。
- `eval`、`Function` 动态二次编译；Zipline 已禁用 `eval`，HXA-050 还必须验证 `Function`/constructor 变体，若仍可用则在 wrapper 执行生成代码前显式禁用并用攻击测试锁定。
- 系统时间以外的设备信息。
- 随机 secret 或环境变量。

## 4. QuickJS 执行控制

1. 创建全新 QuickJs 实例；不跨任务复用全局状态。
2. 在 evaluate 前设置 `memoryLimit`。
3. 安装 `InterruptHandler`，使用 monotonic clock 检查 deadline 与取消标记。
4. 在专用单线程执行，该线程的 native stack 必须大于 Zipline 默认 6 MiB 限制；HXA-050 在目标 ABI/API 上验证实际线程创建方式。主进程设额外 watchdog。
5. API 29+ 为每个 `executionId` 生成唯一 instance name 并调用 `bindIsolatedService`，每次执行获得独立 service/process 实例。instance name 不直接使用 UUID；固定编码为 `js_` + 32 位小写 hex，只包含 Android 允许的 ASCII 字母、数字和下划线，并验证长度、非法字符与碰撞。超时先触发 interrupt；1 秒内未返回则主进程取消本次 Binder 交互并 `unbindService`，由 Android 系统回收无绑定的 isolated service 进程。不调用针对自身的 `killProcess` 或 `System.exit`；测试必须用 PID/Binder death 证明超时实例已回收，下次请求使用新实例。
6. 结果只允许 JSON primitive/object/array；拒绝循环引用和超限字符串。
7. 关闭 QuickJs，清理临时输入输出。
8. 主进程记录源码 SHA-256、输入摘要、限制、退出状态和输出 SHA-256。

### 4.1 默认限制

| 项目 | 默认值 | 可否由模型提高 |
| --- | ---: | --- |
| Source | 256 KiB | 否 |
| Input JSON | 2 MiB | 否 |
| QuickJS heap | 64 MiB | 否；用户设置可降 |
| Wall time | 10 s | 模型不能；用户单次可到 30 s |
| Output | 256 KiB | 否 |
| 并发数 | 1 | 否 |

## 5. QuickJS 审批 UI

审批卡必须展示：

- 完整代码，可复制和搜索。
- 输入来源和文件名，不默认展开敏感正文。
- 是否联网：固定为否。
- 超时、内存、输出限制。
- 预期输出说明。
- 代码 SHA-256 的短摘要。

“修改代码后重试”必须创建新的 ToolCall 和 approval hash。

## 6. PRoot Linux 方案

### 6.1 定位

PRoot 模式用于必须依赖 Linux 用户态、Python/Node/Git/Shell 的任务。它不是强安全虚拟机：PRoot 主要模拟路径、身份和系统调用行为，不能提供与 VM 等价的内核隔离。

因此：

- PRoot 不得运行在主 App UID 中。普通 `android:process` 仍共享 UID、权限和私有文件，不是安全边界。
- PRoot、RootFS 和 Job 目录属于独立 `com.helix.runtime.proot` APK/UID。
- 主 App 只在 `developer` product flavor 编译 Runtime Client 和设置入口。
- 首次启用需单独风险确认。
- Runtime APK 无主 App 权限，基线版本不声明 `INTERNET`。
- 不共享主 App 的 API Key、Keystore、Room、通知、联系人或 Workspace 目录。
- 每个 Job 通过 Binder/PFD 接收经过审批的输入快照，输出也以快照返回。
- Linux 命令仍经过 Policy Engine，每次执行属于 L2。

PRoot 离线是 [ADR-0005](adr/0005-standard-advanced-safety-profiles.md)接受的产品边界，不是等待实现者选择的临时默认。即使用户进入 Advanced、授权 LAN/All-files/Root，PRoot Runtime 仍不得声明或继承 `INTERNET`。未来联网 Shell 必须使用新的执行域并单独完成威胁模型、ADR 和发布门禁，不能在 HXA-084/085 中增加网络开关。

### 6.2 Termux 与 PRoot 对比及集成结论

Termux 与 PRoot 不是同一层的替代品：Termux 是 Android Terminal App、原生命令行用户空间和软件仓库；PRoot 是通过 `ptrace` 模拟路径、身份和部分系统调用行为的无特权兼容层。Termux 也可以安装 PRoot/PRoot-Distro，因此“Termux 路线”内部仍可能包含 PRoot。

| 维度 | Termux 原生环境 | PRoot + Linux RootFS |
| --- | --- | --- |
| 执行方式 | 包使用 Android NDK 构建，直接在 Android/Linux 内核上运行并链接 `bionic` | guest 程序仍使用手机内核，但 syscall 经 `ptrace` 拦截和转换 |
| 产品形态 | 完整 App：终端、bootstrap、`pkg/apt`、插件和用户 home | 底层组件；集成方还需 RootFS、loader、安装器、进程/Job 管理和 UI |
| Linux 兼容性 | 不是标准 GNU/Linux；使用自有 `$PREFIX`，桌面二进制通常需重新编译/打补丁 | 可提供 Alpine/Debian/Ubuntu 等标准目录和 musl/glibc 用户空间，现成 Linux arm64 程序兼容概率更高 |
| 软件生态 | Termux 自有仓库，移动端适配好 | 可使用 RootFS 自带 `apk`/`apt`，但包依赖的 kernel feature 仍可能失败 |
| Root/容器能力 | 普通 Android App UID，不是真 Root | UID/GID 0 是模拟值；没有 namespace、cgroup、seccomp、真实 mount、iptables 或内核模块 |
| systemd/后台服务 | 不提供完整 Linux init | 一般不能可靠运行完整 init/systemd；只能受 Android 生命周期约束地启动有界进程 |
| Android 集成 | 可通过 Termux:API、插件或 `RUN_COMMAND` 接入，但环境由用户管理 | 本身没有 Android API；可由 Helix 在独立 Runtime APK 中提供窄 Binder/PFD 协议 |
| 网络与更新 | 通常可联网，用户能任意安装/升级包 | 由宿主 APK 权限决定；Helix 基线可固定为无 `INTERNET` 且只随签名 APK 更新 |
| 可重复性 | 用户 package source、签名来源、home 和版本均可变化 | 可固定 PRoot、RootFS、包、hash、ABI、许可证和 rollback 版本 |
| 安全边界 | Android App UID 是边界；同一 Termux home 中的命令共享其权限和网络 | PRoot 自身不是安全边界；Helix 依赖独立 Runtime applicationId/UID、最小权限和快照 IPC |

#### 功能与性能

Termux 官方说明其 package 默认不经过 VM、容器或 PRoot，直接使用 Android host kernel；代价是包必须适配 `bionic`、Termux 固定安装前缀和 Android executable 限制。PRoot-Distro 可以运行多种标准 Linux/OCI RootFS，但官方同时明确：PRoot 拦截每个 syscall，文件系统密集型任务、包管理和编译会明显慢于原生执行，并且不提供真正的 namespace/cgroup/seccomp 隔离。

性能预期按工作负载区分：

- `git status`、`ripgrep` 扫描、解压、`npm/pip/apk` 安装、编译和大量短进程 shell 管线，Termux 原生通常明显更快。
- 长时间纯 CPU、较少 syscall 的 Python/Node 计算，PRoot 相对损失可能较小，但仍需真机测量。
- PRoot 还有 RootFS 解压、冷启动、路径转换和 Job snapshot 传输成本；Alpine 比完整 Ubuntu/Debian 更适合作为 Helix 固定基线。
- 2026-05 的一份 Termux 社区 UnixBench 单机测试报告 PRoot-Distro 综合分数低约 44%。该测试运行在 x86 Linux 主机而非 Android 手机，只有单一样本，只能作为“损耗可能显著”的风险信号，不能直接用作 Helix 性能预算。

HXA-084/086 必须在目标手机重新测量冷绑定、RootFS 首次/重复启动、Python/Node 启动、Git/ripgrep 小文件负载、snapshot 归档、取消和进程回收；4 KiB/16 KiB 页、锁屏、Doze、低内存和热限制均须覆盖。不能仅凭桌面 benchmark 宣称性能达标。

#### 开源社区、用户量和维护风险

2026-09-01 GitHub/API 快照只能作为社区规模代理，不等于安装量或活跃用户：

| 项目 | Stars 约数 | 当期维护信号 | 对 Helix 的意义 |
| --- | ---: | --- | --- |
| [`termux/termux-app`](https://github.com/termux/termux-app) | 60.1k | 2026-08 仍有提交；App、终端和插件生态规模最大 | 适合参考 Android 进程、终端和包环境；完整 fork 的维护与许可证成本高 |
| [`termux/termux-packages`](https://github.com/termux/termux-packages) | 16.9k | 2026-09 仍持续更新 packages | Android 原生 CLI 适配资产丰富，但二进制与路径/package name 强绑定，不能直接搬入 Helix |
| [`termux/proot-distro`](https://github.com/termux/proot-distro) | 3.4k | `v5.8.0` 于 2026-08-22 发布 | PRoot 管理层活跃；功能与用户量仍依附 Termux/其他宿主 |
| [`termux/proot`](https://github.com/termux/proot) | 1.1k | Android 定制 tag 在 2026-08 持续更新 | Helix 计划固定使用的 Android 分支，应锁 tag/source/patch/hash |
| [`proot-me/proot`](https://github.com/proot-me/proot) | 2.6k | 上游仍有维护活动，但正式 release 节奏较慢 | 上游原理和许可证来源；Android 集成优先验证 Termux 分支 |

Google Play 的 Termux 页面显示累计 `10M+` 下载和约 `170K` 评论，但 Play、F-Droid 与 GitHub 构建的签名、功能和维护路径并不完全相同，累计下载也不是活跃用户数。PRoot 没有可靠的独立用户统计，因为大量使用发生在 Termux、PRoot-Distro 或其他集成产品内部。因此只能确认 Termux 的终端/包生态和用户社区显著更大，不能用两组商店数字直接计算 PRoot 市占率。

#### 集成路径比较

| 路径 | 优势 | 主要问题 | 结论 |
| --- | --- | --- | --- |
| 依赖用户安装 Termux，通过外部命令桥调用 | 原型最快、性能好、包丰富，适合高级用户自行维护 | 多一次安装/初始化；F-Droid/GitHub/Play 来源和签名不同；home、包、网络和版本可变；不能天然满足 Helix jobId、快照、逐调用审批和恢复契约 | 可用于研发 Spike 或未来专家自带 Runtime；不作为默认产品依赖 |
| Fork/嵌入完整 Termux | 理论上兼得原生性能和生态 | package name/安装前缀进入二进制和 bootstrap；需重编 packages、维护仓库与安全更新；Termux App GPL-3.0，包许可证各异；等同维护一个自己的 Termux 发行版 | 首版不选 |
| 自有 PRoot companion APK | 固定资产、RootFS、ABI、hash 和许可证；独立 UID、可无网、可实现 signature Binder/PFD、Job journal 和 rollback | 性能较低、体积和安装峰值较大；需自建安装/升级/恢复；Linux 包仍有兼容性缺口 | **Helix E2 正式集成路径** |
| 原生 E0/E1 + 按需 PRoot | 常用能力保持低延迟；只为标准 Linux 兼容性支付 PRoot 成本 | 需要清晰路由和两套执行器验收 | **总体推荐** |

最终选择按目标区分：快速验证 CLI/包兼容性或给专家提供自由终端时，Termux 更合适；作为 Helix 可重复、离线、可审计的正式执行后端，独立 PRoot companion 更合适。PRoot 只负责兼容性，真正隔离来自独立 Android UID；性能敏感和稳定手机能力应继续优先落在 E0 原生 Tool，文本/数据计算优先 E1 QuickJS，不能把所有任务都路由到 PRoot。

该比较是现有 PRoot 路线的工程依据，不新增能力承诺，也不把外部 Termux bridge 加入当前 HXA。若未来要把 Termux 作为可选 execution target，必须单独定义安装来源、签名/版本握手、命令协议、数据/网络边界、取消恢复和许可证方案，并通过 ADR/HXA 授权。

主要依据：[Termux execution environment](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)、[Termux App 与分发限制](https://github.com/termux/termux-app)、[第三方复用与 package name 限制](https://github.com/termux/termux-app/discussions/4564)、[PRoot-Distro 功能与限制](https://github.com/termux/proot-distro)、[PRoot benchmark 样本](https://github.com/termux/termux-packages/discussions/29944)、[Google Play Termux 累计下载](https://play.google.com/store/apps/details?id=com.termux)。

### 6.3 运行时组成

```text
com.helix.runtime.proot 私有 filesDir/
└── runtime/<install-id>/
    ├── manifest.json
    ├── bin/
    │   ├── proot
    │   └── loader
    ├── rootfs/                 # Alpine minirootfs
    ├── home/
    ├── tmp/
    └── state/
        ├── install.lock
        ├── active.json
        └── rollback.json
```

基线建议：

- ABI：第一阶段 `arm64-v8a`，CI/模拟器再支持 `x86_64`。
- RootFS：固定 Alpine minirootfs，不使用 `latest` URL。
- PRoot：基于 `termux/proot` 的固定 tag/package。
- 基础包：`busybox`/Alpine base、`bash`、`git`、`python3`、`nodejs`、`ripgrep`；全部在构建期锁定并打包。
- 16 KiB 页是完整 Runtime 验收维度，不只检查主 APK 内的 QuickJS `.so`。`proot`、loader 及 RootFS 内所有 ELF 先做 `LOAD` segment alignment 扫描，再在 16 KiB 真机运行 python/node/git/ripgrep smoke。任一组件不兼容时阻断对该设备的分发，不得只用 APK Analyzer 结果声称 Runtime 兼容。
- 默认 Shell：`/bin/sh`；需要 bash 时显式调用。

具体版本不在文档中写死为“永远版本”。实施 HXA-080 时生成 `runtime-lock.json`，记录 URL、版本、大小、SHA-256、许可证和源码 URL；该文件是唯一版本真相。

### 6.4 获取和激活

参考 AndCode 的可靠做法，但不得直接复制其代码：

1. CI/构建期获取 PRoot/loader，固定 package 版本与 SHA-256。
2. CI/构建期从官方 Alpine CDN 获取固定 RootFS，验证 SHA-256/上游签名。
3. PRoot native component、压缩 RootFS、`runtime-lock.json` 一同进入独立 Runtime APK。
4. 用户设备上只从已签名 APK asset 安装，不在线下载 executable、RootFS 或 package。
5. 解压到 `.partial`，并再次验证 embedded archive SHA-256。
6. 防 Zip Slip 解压：拒绝绝对路径、`..`、设备文件和越界 symlink。
7. 写入新版本目录并运行 smoke test。
8. 原子切换 `active.json`，保留一个可回滚版本。
9. Runtime 更新通过新的同签名 APK 完成，不在 App 内自更新 native code。

### 6.5 命令请求

```kotlin
data class LinuxExecutionRequest(
    val executionId: ExecutionId,
    val argv: List<String>,
    val relativeWorkingDirectory: String,
    val environment: Map<String, String>,
    val timeout: Duration,
    val maxOutputBytes: Long,
    val inputManifestSha256: String,
)
```

禁止把命令拼为未经转义的单个字符串。Runner 接收 argv；只有用户明确请求 Shell 语法时才使用 `/bin/sh -lc <script>`，审批 UI 必须展示完整 script。

环境变量采用名称白名单：`HOME`、`PATH`、`LANG`、`TMPDIR`、任务自定义非 secret 变量。自定义名称命中 `KEY|TOKEN|SECRET|PASSWORD|AUTH|COOKIE|CREDENTIAL` 等模式、值与 SecretStore 已知 secret 匹配，或来自 Provider/MCP/CLI 认证结构时必须拒绝。筛查在主进程建立 snapshot 前完成，Runtime 不接收 SecretStore 访问能力。主进程环境、Provider key、Android 路径不继承。基线 Runtime 没有 INTERNET 权限，因此请求中也不提供可切换的网络开关。

### 6.6 独立 UID 与 IPC

Runtime APK 声明独立 `applicationId`，跨 App Service 必须 `exported=true` 才能绑定，但同时设置 `signature` 级权限：

```xml
<permission
    android:name="com.helix.permission.BIND_PROOT_RUNTIME"
    android:protectionLevel="signature" />

<service
    android:name=".ProotRuntimeService"
    android:exported="true"
    android:permission="com.helix.permission.BIND_PROOT_RUNTIME" />
```

主 App 使用同名 `uses-permission`，并以 explicit ComponentName 绑定。Service 再校验调用 UID 对应包签名，完成 `protocolVersion/runtimeVersion/ABI` 握手。

跨 APK 不等于用户要先打开 Runtime。PRoot 不需要日常 launcher UI，但要提供 Helix 可在用户点击后打开的最小设置/修复入口；正常 Job 在安装且校验通过后，由主 App 使用 `BIND_AUTO_CREATE` 按需创建 Service 进程。不得把 `ActivityManager` 观察到进程、用户“打开过一次”或后台常驻作为 Tool 可用条件。CLI Runtime 也使用相同的冷绑定规则，只有首次登录、重新认证或退出需要可见 UI。完整契约见 [ADR-0007](adr/0007-companion-runtime-lifecycle.md)。

Job 数据流：

```text
主 App Workspace
  → 生成允许文件清单、hash 和只读 input archive/PFD
  → Runtime 安全解压到自己的 jobs/<id>/workspace
  → PRoot 只挂载 Runtime 自己的 job copy 到 /workspace
  → 执行后生成 output manifest + archive
  → 通过调用方提供的 write PFD 返回
  → 主 App 验证大小/hash/diff，等待用户审批后导入 Workspace
```

始终不共享：

- `/sdcard` 全盘。
- 主 App `dataDir`、Keystore、数据库、SharedPreferences。
- 主 App 或其他会话的真实 Workspace 目录。
- 外部 Content URI。

输入/输出 archive 同样执行路径穿越、symlink、文件数、单文件和总大小检查。Runtime 崩溃只能损坏自己的 Job 副本，不能直接修改主 App Workspace。

### 6.7 Job 生命周期、断连与后台执行

主 App 的 `RuntimeSupervisor` 与 Runtime Service 必须共同实现以下状态协议：

1. 主 App 先检查 package/启用状态/签名。用户点击“验证/修复/登录”可以冷绑定做零 Job 握手；真实执行则必须在批准和输入快照固定后冷绑定，并重新完成协议、版本、ABI 和 capability 握手。应用启动、切换 Advanced 和被动 Registry 刷新不绑定。
2. 每个请求携带稳定 `executionId/jobId`、input manifest hash、deadline 和 output limit。Runtime 原子写入私有 Job journal 后才能回报 accepted；重复提交同一 jobId 只能返回已有状态，不能启动第二份进程树。
3. Runtime 的 journal 至少能证明 `PENDING/RUNNING`、已有 `ExecutionState` 的 terminal outcome、input hash、结果 manifest/hash 和 terminal commit。它不保存 approval proof/Secret，也不能自行创建后续 Job。
4. 主 App 监听 `ServiceConnection`、Binder death 和 `DeadObjectException`。断连后只允许重连并 `query(jobId)`；匹配的 terminal record 可继续结果校验，其他情况将执行停泊为 `INTERRUPTED`。禁止因 Binder 超时/断连直接重新提交 argv/script。
5. 无活动 Job、结果传输或登录交互后解绑；Runtime 进程可被 Android 回收。下一 Job 重新冷启动，不能依赖进程内缓存恢复授权或 scope。
6. 用户强制停止/禁用 Runtime、设备重启或 OEM 后台限制后，Helix 显示不可用/中断，不自动唤醒或重放。若平台要求用户显式恢复 stopped package，只有用户点击“修复 Runtime”后才能打开 companion 的最小设置/修复 Activity；恢复后重试产生新的 ToolCall/approval/jobId。

短 Job 只需 bound service。确需在主 App 退后台后继续的用户主动 Job，必须在 Helix 仍可见时启动由 Runtime 所有的 started + bound foreground service，通知展示 Runtime、任务摘要、耗时和停止动作。前台服务类型必须匹配真实工作：快照传输/本地文件处理可以使用 `dataSync`，任意 Python/Node/Shell/CLI 计算不能仅为保活冒充 `dataSync`；没有合法类型时保持前台有界执行，退后台即暂停或取消。等待审批、登录选择或人工输入时停止前台服务。

前台服务只降低回收概率。若 HXA-084/CLI Spike 的锁屏真机测试证明必须阻止 CPU suspend，只能在用户可见前台服务的 `RUNNING` 窗口持有带硬超时的 `PARTIAL_WAKE_LOCK`，deadline 不晚于 Job deadline，并在成功、失败、取消、timeout、`onTimeout()` 和异常清理的 `finally` 路径释放。不得用无限 wake lock、循环重启 Service 或请求忽略电池优化来代替正确恢复。

## 7. 包安装策略

### 7.1 默认包

RootFS manifest 中的基础包由 Helix 维护，CI 构建时锁定，设备安装时再次验证 embedded lock。

### 7.2 pip/npm

第一版 PRoot Runtime 不声明 INTERNET，因此不支持 `pip install`、`npm install`、`apk add`、`git clone/pull/push`。Python、Node 和 Git 仅使用预装标准能力与用户输入副本。

需要新 package 时必须通过 Helix 的 Runtime 构建流程加入锁文件、许可证清单和下一版 Runtime APK，不能让 Agent 在用户设备上临时联网安装。需要联网模型订阅 CLI 时，不给现有 Runtime 静默加 `INTERNET`，而是使用下一节的独立 CLI Runtime。

### 7.3 Git 的当前边界与决策入口

RootFS 中预装 `git` 的当前含义仅是：HXA-081 固定并审计二进制，HXA-086 在离线 Job 副本中执行版本/smoke。它不等于 Helix 已具备“内置 Git 仓库管理”：

- PRoot 每个 Job 只看到输入快照，不能挂载主 App 的真实 Workspace；Job 结束只返回经 hash/diff 验证的输出快照。
- 第一版 Runtime 无 INTERNET，因此 `clone/fetch/pull/push`、远程凭据、credential helper 和 SSH agent 均不可用。
- 不得把 Job 输出中的零散 `.git` 文件覆盖回 Workspace。对象库、index、refs 和工作树必须作为同一一致性边界处理，否则崩溃或部分导入会损坏仓库。
- Git hooks、alias、外部 diff/merge、pager、filter、submodule、worktree 和仓库级 config 都是不可信执行/配置输入；在形成专门 Policy 和测试前不得由结构化 Git UI 隐式触发。
- 普通用户优先获得不要求理解 Git 的 Helix 原生历史、diff、回收站和恢复；Advanced 的结构化离线 Git 是否以及如何出现，由 HXA-088 Spike 和 [ADR-0008](adr/0008-git-workspace-management.md) 决定。该 ADR 接受前，`git` 只能是每次已批准 `bash` Job 内的离线工具，不能声称持久仓库可用。

HXA-088 必须在“主 App Workspace 为权威”“Runtime 私有仓库为权威”“主 App 使用 Android Git 库”之间做设备实证比较，确定原子传输、崩溃恢复、大小限额、hooks/config 禁用和 UI 语义。远程 Git 需要新的联网执行域、凭据所有权和出网威胁模型，不属于 HXA-088 首版。

### 7.4 CLI Runtime（P2 实验）

`cli-runtime` 用于运行厂商官方 Codex CLI/app-server 或 Claude Code CLI。它不是普通 PRoot 执行器，也不是远程 Worker：

- 独立 applicationId/UID，声明 `INTERNET`，不共享离线 PRoot Runtime 的 home、RootFS 和 Job。
- 官方 CLI 自己完成 OAuth/device-code 登录、token 保存和刷新；主 App 只接收登录状态、登录 URL/验证码及有界会话事件。
- 登录完成后的普通会话由 Helix 按需冷绑定，不要求用户保持 CLI Runtime Activity 或进程在前台；进程回收不删除 Runtime 私有凭据。强制停止/禁用/认证失效时显示不可用或重新登录，不自动重放会话命令。
- 主 App 不读取 CLI 凭据文件，不把 Cookie/token 复制进 Keystore，也不调用未公开服务接口。
- 输入是经过 Context Builder 和 Policy 的 Job snapshot；默认不给 CLI All-files、Accessibility、Root、Android Intent 或主 Workspace。
- CLI/Node/runtime 版本、来源、SHA-256、许可证和更新方式进入独立 `cli-runtime-lock.json`。
- `private RootFS` 不是已选定的执行底座。HXA-111/112 Spike 必须分别证明官方 CLI 是否存在可在 Android/Linux arm64 运行的受支持形态，并在“原生 Android 可执行文件”与“独立 PRoot/RootFS”之间产出 ADR。未验证 ABI、libc、Node/runtime 和官方发布支持前，不打包 CLI binary，也不声称可用。
- 官方 CLI 往往自带 Agent 和工具。只有 Spike 证明其工具可以禁用或由 Helix 审批代理后，才可适配为 `ModelProvider`；否则仅作为隔离的 CLI 会话后端。
- 登录、服务条款、账号类型和可用额度会变化，运行时必须展示厂商返回的真实状态，不把订阅描述写死。

网络隔离粒度是 Android UID。`cli-runtime` 获得 `INTERNET` 后无法仅靠 Android permission 对单个进程做 host allowlist，因此它只能访问自身私有 Job 数据，不能同时承担通用文件/Root/Accessibility 执行。

## 8. Android 动态代码与分发边界

Android 官方安全指南建议避免从 APK 外动态加载代码；许多远程动态代码形式可能违反 Google Play 政策。因此构建两种变体：

| 变体 | 内容 | 目标分发 |
| --- | --- | --- |
| `consumer` | 原生 Tools + APK 内 Zipline/QuickJS；不下载 native executable | 常规测试/应用商店评估 |
| 主 App `developer` | 当前直接分发主版本；包含高级能力和 Runtime IPC client，不含 PRoot/RootFS/CLI binary | 内测、直接分发 |
| `proot-runtime` APK | 独立 UID；内含固定 PRoot/RootFS；无 INTERNET | Advanced 用户按需安装的 companion；不进入默认主包下载 |
| `cli-runtime` APK | 独立 UID；内含固定官方 CLI runtime；有 INTERNET，无 Android 高级权限 | P2 可选、直接分发 |

JavaScript 源码是当前任务的数据，由 APK 内置解释器处理；禁止把下载的 DEX/JAR/APK/SO 当更新机制。PRoot/CLI Runtime 通过正常签名 APK 更新，不在应用内部更新 executable。当前不以 Google Play 上架为目标；若未来上架，必须按当时渠道政策重新设计 build flavor 和权限，而不是假定直接分发版本可原样提交。

## 9. 许可证义务

- QuickJS：MIT；Zipline：Apache-2.0。
- PRoot：GPL-2.0。
- `termux/proot-distro`：GPL-3.0；只参考脚本设计，不作为库直接复制，除非 Helix 接受相应许可证义务。
- Alpine 每个 package 有独立许可证。

发布包含 PRoot 二进制时必须：

1. 在 App 内展示完整许可证文本和版权声明。
2. 提供与二进制精确对应的源码获取方式。
3. 保存构建脚本、patch、tag、archive hash。
4. 记录 Runtime APK 压缩体积、安装后 RootFS 体积和峰值临时空间；HXA-120 在发布前根据真实产物设定预算。Runtime 只与 developer 版本成套签名发布，不在 App 内自更新。
5. 对 LGPL/GPL 动态库分别审查链接关系。
6. 不把“Helix 自身许可证”错误地描述为覆盖所有第三方组件。

这不是法律意见；正式商业发布前需要许可证审查。

## 10. 必测攻击场景

### QuickJS

- `while(true){}` 被中断。
- 大数组触发内存限制，不杀主进程。
- 尝试 `fetch`、`require`、`eval`、Java Bridge 均失败。
- 构造超大输出被截断并标记。
- Unicode、NUL、深层 JSON、循环对象处理正确。
- 隔离进程崩溃后主进程收到明确错误并可执行下一任务。

### PRoot

- RootFS archive Zip Slip、symlink escape 被拒绝。
- 哈希不符不激活。
- 工作目录越界被拒绝。
- `HOME` 等环境不泄露主进程值。
- 超时后进程组被终止，无孤儿进程。
- stdout/stderr 洪泛受限。
- 更新失败保持旧 runtime 可用。
- 卸载删除 RootFS、凭据和临时文件，但不误删 Workspace。
- Runtime 未运行且未手动打开时，显式冷绑定可创建进程并完成握手；切换 Advanced 本身不启动 Runtime。
- 空闲解绑/系统回收后下一 Job 可冷启动；连接状态不缓存成永久 Capability。
- 分别在 accepted 前、RUNNING、terminal commit 前后杀主 App/Runtime；重连只查询同一 jobId，未知结果进入 `INTERRUPTED` 且命令只执行一次。
- 后台、锁屏、Doze、低内存和强制停止覆盖通知/停止/timeout；无合法 FGS 类型时按契约暂停或取消，不冒充 `dataSync`。
- 如使用 wake lock，成功、失败、取消、timeout 和异常路径均释放，最长持有不超过 Job deadline。

### CLI Runtime

- 主 App 无法读取 CLI credential files；logout/uninstall 后凭据确实删除。
- 登录取消、浏览器返回失败、token 过期和账号限额均为稳定状态。
- CLI 尝试读取主 App dataDir、All-files roots 或离线 Runtime home 失败。
- CLI 内置工具无法绕过 Helix approval；若无法证明则禁止 Act/Goal 集成。
- 网络/输出洪泛、进程树取消、版本不匹配和 IPC 断连不会拖死主 App。
- 未运行/未打开 companion UI 时可冷绑定；登录 UI 退出后正常会话仍可按需启动。
- 主 App/CLI Runtime 在会话各阶段死亡后只按 jobId 查询/对账，未知结果不重放；后台继续、通知、停止和 wake lock 遵循 §6.7。

## 11. 完成标准

E1 完成：在真实 Android 设备上，用户批准一段 Agent 生成的 JavaScript，代码在无权限隔离进程中运行，能处理 JSON 并返回结果；无限循环、内存膨胀、进程崩溃不影响主应用和后续任务。

E2 完成：同签名的独立 Runtime APK 可从 embedded manifest 安装并验证 Alpine/PRoot，在自己的 Job 输入副本上运行 `python3`/`node`/`git` smoke test；主 App 私有数据不可见，输出经 manifest/hash 验证后才导入，更新失败可回滚，所有第三方许可证和源码信息可离线查看。Runtime 无需用户手动打开即可冷绑定，空闲回收后可重启，进程死亡按 jobId 对账且未知结果不重放；后台、锁屏、Doze、停止通知和可选 wake lock 均有真机证据。

E2C 完成：官方 CLI 在独立网络 UID 中完成官方登录，凭据不离开 Runtime；主 App 可在 companion UI 不常驻时冷绑定、创建和取消有界 CLI 会话，断连后只查询/对账。只有在工具拦截、审批和副作用测试全部通过后，才能把它标记为 Helix Agent 可用后端，否则 UI 必须明确显示“隔离 CLI 会话”。
