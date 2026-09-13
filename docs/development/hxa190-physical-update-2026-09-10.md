# HXA-190 真机覆盖更新与有界回归

日期：2026-09-10。HXA-190 仍在进行；本记录仅关闭本轮真机覆盖更新与下列验证，不代表安装入口、图片执行、所有推理档位或 HXA-191 已完成。

## 安装事实

更新前 developer 安装时间为当天 11:32，Runtime 为 12:48；两者版本名仍为 `0.1.0`，因此以 APK SHA-256 核对实际构建。覆盖安装使用 `adb install -r`，不卸载个人应用、不清除数据或凭据。

| 包 | 手机 APK 与当前构建一致的 SHA-256 |
| --- | --- |
| `com.helix.agent.developer` | `5a7b7897644a2a79585803ef8c1b8ad196265f0d77e90e10417de06f3c082011` |
| `com.helix.runtime.cli` | `be734dd6d16d8c5e216a2cf1be9d686d27663692d54572e059a0aaaf9f6e50f1` |

Runtime 原先四个登录 Activity 各自声明 MAIN/LAUNCHER，实际只有一个安装包。新构建只保留 `CliRuntimeHomeActivity` 一个启动入口，四项登录操作在主页选择；原登录 Activity 的显式入口保留。真机发现主页内容被标题栏遮挡，已根据窗口 inset 修复并重新覆盖安装检查。

## 真机证据

USB API35 真机，未操作 Claude 的 emulator-5584，也未改写其冻结 Browser 测试 APK（SHA `b9382e9768568bf71f6e4536b3485388e49e08e108d460a68b036f2c29583778`）。

- Runtime 显式真实账号诊断：2/2 通过；模型目录返回六项，合成文本请求 HTTP 200 并得到完成事件。凭据和响应正文不写入诊断日志。
- 主应用完整会话：第一次连接在目录阶段得到 `TRANSPORT`，未计为通过。打开 Runtime 后一次通过；随后结束空闲 Runtime 进程、确认其 PID 不存在，再从主应用冷启动执行一次通过。两次均保存单次模型调用和 `HELIX_OK` 回复。首次瞬时失败的根因尚未确定，不能声称彻底解决所有连接故障。
- 会话界面模型菜单实际显示六项，输入栏出现推理选择。账号目录中前五项视觉为 true、窗口为 272000；`gpt-5.3-codex-spark` 视觉为 false、窗口为 128000。目录信息不等于每个模型均完成真实图片或全档位验收。
- HXA-189 权限入口、无 Provider 设置导航、系统栏避让及会话顶栏：独立 consumer 沙箱 7/7 通过，包含 Activity 重建。该套不覆盖真实权限允许/永久拒绝全部分支。
- 先前本轮 PRoot PFD 真机回归 5/5 通过，证据位于 `build/debug/2026-09-10/hxa189-proot-pfd-phone`。

测试结束清理自建 consumer 与两个 instrumentation 测试包，手机只剩 developer 和 CLI Runtime。真实会话测试仅新增合成 `real subscription smoke` 会话，没有删除个人会话。最后已启动主应用供用户继续操作。

## 可重复入口与主机检查

日期脚本位于 `scripts/debug/2026-09-10/`：

- `run-codex-account-diagnostic.py`：保留 vault，限定真实账号诊断类，要求非空通过结果。
- `install-physical-developer.py`：覆盖安装并启动主应用。
- `run-physical-codex-chat.py`：仅执行 opt-in 真实 Codex 会话，不运行清理 Provider 的其他夹具。
- `run-physical-permission-ui.py`：仅使用原先不存在的 consumer 沙箱，结束清理。
- `verify-phone-installed-artifacts.py`：读取已安装 APK 哈希、包列表及唯一启动入口。

以上脚本要求显式 `--serial` 和 `--output`；本机输出均在忽略目录 `build/debug/2026-09-10/hxa190-*` / `hxa189-phone-permissions-ui`。没有提交设备标识、账号信息或私有会话数据。

实际命令：双 app Debug/AndroidTest 与 CLI Debug/AndroidTest 构建通过；CLI client、CLI app JVM 通过。App 首轮 JVM 因旧测试仍把 1000001 当作越界失败；上下文上限已由实现扩展，测试改为最大值加一，并增加百万以上窗口保留验证。随后 `:app:testDeveloperDebugUnitTest :app:testConsumerDebugUnitTest` 均通过，`git diff --check` 通过。此记录不替代 HXA-190 后续完整 lint/静态门禁。

## 尚待继续

### 后续显示名称与界面统一

所有者随后要求按实际功能更名。当前该包的生产路径负责订阅认证、凭据保管与模型请求转发，不提供通用 CLI 终端；用户可见名称改为 `Helix Subscription Provider`。内部 `com.helix.runtime.cli`、组件名、签名权限、Binder 描述符和凭据目录保留，历史 ADR 的 CLI Runtime 名称仍用于追溯。此为可回滚显示层调整，没有更改 ADR-0021/0007 的安全或生命周期边界。

首页及 Codex/Copilot/Claude/Grok 登录页共用原生 `SubscriptionScreen`：与 Helix 当前浅色界面协调的背景/强调色、48dp 顶栏、圆角控件、文字与间距、返回入口和可滚动正文。设备码复制/打开验证页、取消和退出回调均保留。统一 shell 接管窗口 insets，删除旧页面重复计算，避免标题遮挡或双重顶部留白。没有引入 UI 依赖，也没有为界面刷新发起账号登录或网络探测。

五页在 API35 真机逐页打开并截图检查，Codex 保持登录。最新覆盖安装 SHA：developer `921de7ba7bb49dfc2afc0b5144c12ab8dea72f7e7a6a72fae7039580e73a79c9`，subscription `71539dc2deccef948369bfd3871a6ef7439806b75d939721239fb752b32b7608`；手机与构建一致，仍只有两个产品包及一个订阅启动入口。此后续 SHA 取代上方较早安装批次。

验证：`:runtime:cli-app:assembleDebug :runtime:cli-app:lintDebug :runtime:cli-app:testDebugUnitTest`、`:app:assembleDeveloperDebug`、i18n 和 diff 通过；全仓 Detekt 曾因新 shell 的长方法及此前目录/协议文件的格式和返回数失败，shell 已按装配/顶栏/控件拆分，其他 HXA-190 项仍待收口，未宣称全仓门禁绿。屏幕证据在本机忽略目录 `build/debug/2026-09-10/subscription-pages`，日期脚本 `inspect-subscription-pages.py` 不点击登录/注销，也不读取凭据。

### 名称复核与桌面残留纠正

后续复核采用 **Helix Subscriptions**（Helix 订阅），比 `Helix Subscription Provider` 简短且更适合面向用户；`Helix Accounts` 不足以表达模型调用职责，`Helix Connect` 容易与已有 Connector 功能混淆。仅是当前产品命名判断，不声明商标或全球名称唯一性。

用户报告多个图标属实：此前只查询 PackageManager 的一个 MAIN/LAUNCHER 入口，不能证明桌面已经只剩一个快捷方式。API35 真机机主桌面实际保留五个同名图标，系统只有一个订阅包、一个当前启动入口，分身用户没有第二个订阅包。关闭并重新启动当前默认桌面 `com.android.launcher/.Launcher` 后，原 Helix 桌面页只剩一个主应用和一个订阅图标，未清除 launcher 数据、未卸载订阅应用。证据：本机忽略目录 `subscription-launcher-inventory`、`subscription-after-launcher-restart`，后者 `icons.json` 记录两个实际可见图标。

更名后再次覆盖安装并检查桌面，使用 `find-subscription-launcher-page.py` 有界定位与留存 UI，不以启动入口清单替代实际桌面证据。包名、源模块路径和组件身份不必为显示名强制迁移：底层仍是隔离 Runtime 的实现，修改身份会增加安装/IPC/测试迁移成本，且不解决本次 launcher 旧入口缓存。所有者允许按需修改，本次判断无需修改；登录数据保持原应用内。

最新名称批次 SHA：developer `acdcc1b5188f419c10681002f70dd680174d26892f5a9aceceaa347942f4bd47`，subscription `b524c556eeed74d45094bcb5ad630fca026aaffb4c02e86263f01e7058c0ae24`。APK 构建与订阅模块 lint 通过，手机 APK 哈希与构建一致。该批次只调整名称资源，没有改变模型/认证协议。

### 仍待收口

主 App 的 Runtime 安装入口、图片快照端到端验收、各推理强度与模型切换能力一致性、首次传输失败归因，以及 HXA-189 剩余系统授权分支仍需收口；之后继续已授权 HXA-191 配置引导、审批折叠、深色模式和会话搜索。没有把这些事项记为已完成。
