# 全局原生网络域名解析（2026-10-05）

## 范围与实现

所有者要求统一原生网络客户端并进行模拟器测试，随后明确删除订阅快捷入口。唯一编辑入口为 **设置 → 通用 → 网络设置 · 域名解析**，Standard 与 Developer 共用宿主页面。订阅账号页及旧订阅首页不再显示该入口。

- `NativeDnsSettings` 管理有有效期的 IPv4/IPv6 映射；`NativeNetwork` 在各非隔离进程读取同一份宿主文件，保存时同步写入临时文件再原子替换。
- API Provider、订阅 HTTP（OAuth、目录、连接测试和生成）、MCP（含 OAuth）、A2A 连接器及原生 HTTP 工具接入同一解析配置。订阅不再读取自己的 DNS SharedPreferences。
- MCP 与 HTTP 工具先检查解析结果，再连接已获准的固定地址；映射不扩大网络权限。保留 URL、Host、HTTPS 校验、超时和取消行为。
- 修改影响新建连接；已有连接继续。映射过期或删除后使用系统解析。未配置时不预填历史固定 IP。
- WebView、终端、外部浏览器及独立下载器不在本轮覆盖范围。HTTP 代理若自行解析目标域名，其解析行为不由此映射控制；本轮不绕过系统代理。
- 本轮不兼容旧订阅 DNS 配置文件格式/位置；旧偏好文件不删除。此次日常模拟器备份中不存在旧订阅 DNS 配置文件，没有遗失已有映射。

## 主机验证

`build/global-dns-final-host.log` 中以下任务通过：

```text
:core:policy:test
:provider:api:test
:extensions:mcp:test
:extensions:a2a:test
:runtime:cli-app:testDebugUnitTest
:tools:android:testDebugUnitTest
:app:assembleDeveloperDebug
:app:assembleConsumerDebug
:app:assembleDeveloperDebugAndroidTest
detekt
spotlessCheck
```

六个模块共 638 项 JVM 测试通过，0 失败、0 跳过。A2A 实际由 `testDebugUnitTest` 执行。新增/更新回归包括独立读取者看到持久更新、IPv6 字面量、非法配置不覆盖、映射过期/删除、本机真实 HTTP 与 Host 保持原域名，以及 MCP 不因映射到 loopback 而跳过地址授权。

首次主机检查发现新增共用解析依赖缺少锁状态、请求构造器误加客户端配置、非法 IPv6 异常类型与格式规则不一致，均修复。只更新既有固定版本 JSON 依赖在 `core:policy` 的锁状态，未新增版本或仓库。设备用例适配后重跑测试 APK 编译及静态检查通过，见 `build/global-dns-device-fix-host.log`。

## 模拟器验证

设备：日常 `emulator-5554`，`Helix_API_36`，Android API36，Developer 包 `com.helix.agent.developer`。复用当前模拟器，未启动第二台；保留硬件键盘与软键盘配置。安装前数据库及偏好备份于忽略目录 `build/global-dns-device/preinstall-private-state.tar`，不提交用户数据。

执行 `scripts/debug/2026-10-05/run-global-dns-device.sh`，覆盖安装本轮 APK 和测试 APK，定向运行：

- `GlobalDnsDeviceTest.generalSettingsOpensTheSharedPage`：从通用设置点击进入共享页面。
- `GlobalDnsDeviceTest.uiSaveConnectsAndUpdatesTheAlreadyRunningSubscriptionProcess`：通过真实表单保存保留测试域名，API 默认客户端连接本机服务器；在另一个 PID 的既有 `:subscriptions` 进程观察新增、更新、删除立即可见。仅使用 debug 非导出的只读探针，不调用订阅服务。
- `IntegratedRuntimeUiDeviceTest.privateSubscriptionPagesRenderInTheHostApplication`：五个订阅账号页正常显示，不发起登录或模型请求。

最终 **3/3 通过**，日志 `build/global-dns-device/run2.log`。最初旧订阅页面回归把迁至宿主 Compose 的页面继续当原生文本页面查询，出现标题未找到；改为宿主 Compose 点击导航回归，订阅回归仅保留其实际远程页面。失败记录保留在 `run1.log` 与 `navigation-alone.log`。

手工从抽屉设置进入通用再打开域名解析，确认页面实际可达、系统栏避让和三个有效期控件正常；截图保存在 `build/global-dns-device/settings-page.png`。测试只添加并移除 `helix-dns-fixture.invalid`，不清空其他映射。安装包与设备 `base.apk` SHA-256 一致：`09e130e65f3c2aa4db10330ef071ed6b99d4a282d6152dcbbfdabe1447d13217`。

## 边界

设备检查 passed，仅覆盖上述 API36 Developer 用例；Consumer 只有构建验证，其他 API/OEM、真机、真实账号/模型、代理、真实公网 TLS 连接 not requested。未提交或推送；本次通过不代表所有原生下载器、浏览器或整机 DNS 已统一。

## 后续调整：hosts 文本与内联关于（本轮仅主机验证）

所有者进一步要求删除旧订阅 DNS 实现、直接编辑 hosts 文本，以及关于 Helix 不再弹窗。旧订阅设置类、页面和初始化包装层已删除；原先位于 Codex OAuth 的有界 DNS 缓存迁至共享 `NativeDnsResolver`，订阅客户端使用同一个解析入口。订阅登录、账号及模型调用保留。

全局配置改为 `native-hosts.txt`，支持 IPv4、IPv6、多个域名/别名、空行与 `#` 注释；允许同一域名对应多个地址，相同地址去重。保存前校验全部文本，报告行号，任何错误都不写入；原子替换文档，保留注释，取消逐项有效期，清空保存恢复系统解析。旧 JSON 格式不再读取，不增加兼容迁移。上限为 128 KiB、每域名 16 个不同地址；映射仍不扩大网络权限。

关于 Helix 直接显示在通用设置：一句授权范围内的产品说明、版本、项目源码和问题反馈。移除重复裸网址、开发者主页及弹窗层级；链接仅显式点击打开，无法打开仍显示错误。

验证日志 `build/hosts-final-host.log`、`build/hosts-final-check.log`：六模块 638 项 JVM 测试，覆盖完整文档校验、别名/注释、错误行号、保存失败、配置更新、清空后回退、本地真实 HTTP 及 MCP 地址授权；双渠道 APK、Developer AndroidTest APK 编译和静态检查。旧订阅配置单测按新契约迁入 core policy，过期用例改为持久 hosts 与清空回退，不保留已移除的 TTL 行为。初次静态检查发现格式、通配导入及页面函数过长问题，整理格式并拆分输入控件后重新验证。JSON 专用依赖与对应锁增量已移除。

更新设备用例覆盖非法文本禁用保存、文本编辑、跨进程观察和关于内联显示，**仅编译，未执行**。本轮设备及真实账号 not requested，未覆盖安装、提交或推送；上节 API36 的 3/3 结果仅对应旧表单，不证明本次文本编辑器通过设备验证。

## 关于链接补充

按所有者随后要求，关于内联区域增加作者 dollarser（GitHub 主页）、合并的“项目主页和源码”（GitHub 项目首页）、下载更新及 Issues 反馈。查询仓库未配置独立 homepage；当前 v0.0.4 等发布均为 prerelease，GitHub latest API 返回 404，故下载更新使用 releases 列表，支持最新预览版且不固定版本号。链接仅由显式点击打开。主机检查记录 `build/about-links-host.log` 与 `build/about-links-merged.log`，更新点击与内联可见性设备用例仅编译，本轮未再次安装或执行设备测试。
