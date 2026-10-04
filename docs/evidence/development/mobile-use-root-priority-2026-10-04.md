# Mobile Use：Root 接入、自动优先级与插件边界验证

日期：2026-10-04。所有者要求先验证 Root 再接入，按 Root → Shizuku → Accessibility 选择，并分析插件/模块边界；随后授权寻找已有 rooted 模拟器，必要时准备新的。保留其他未提交改动，未提交/推送，未使用真机或真实模型/账号。

## 交付范围

`ui.click_match` v5 默认 `backend=auto`：对于精确 `packageName`、`viewId`、`text` 且没有其他筛选参数的点击，执行前只选择一次已授权且连接的 Root → Shizuku → Accessibility。显式后端固定路径；拒绝、断连、取消或 UNKNOWN 不跨后端重放。其他筛选参数、节点 token、截图、手势与输入仍使用 Accessibility。`ui.device` v3 返回 `rootState`、`shizukuState`、`clickMatchBackend`，最后一项不代表所有操作的后端。

Root 复用既有 libsu 6.0.0 非 daemon RootService，不自建 ADB，不提供任意 shell 或静默安装工具。`RootAutomationService` 未注册成 Android manifest service。Root 与 Shizuku 共享有界 UiAutomation 观察、双次唯一节点匹配、固定 input tap 协议与原调用 guard；Root 验证 Binder 对端 UID 0。XML 留在辅助进程。工具仍经原 Plugin、Dispatcher、会话范围、无障碍租约、物理输入占用、取消和审计。

Mobile Use 设置提供独立显式 Root 授权/断开入口；查询和工具调用不创建 Root shell、不弹授权、不冷绑定。连接允许切换目标应用；原 `root.*` 的后台失权规则不变。两者共享 libsu 底座，不声称进程/凭据隔离。Root manager 撤销未来 su 授权未必终止已运行的 UID 0 服务，界面和 ADR 明示此边界；主动断开、停止/接管、关闭/替换会话范围及锁屏仍约束后续动作。

## 环境与设备结果

| 环境 | 当前验证事实 |
| --- | --- |
| 既有 API36 `emulator-5554` | shell 可 `su 0`，应用 UID 无此权限，不能算 Helix Root；Shizuku UID 2000 可用 |
| 既有 `Helix_Verification_Root_API34` / `emulator-5570` | 恢复官方 Magisk 临时 setup，正常管理器授权后，应用 Libsu UID 0 + fixture 点击探针通过；旧应用数据库 9 → 当前 1 降级失败，保留旧数据 |
| 新 `Helix_MobileUse_Root_API34_20261004` / `emulator-5572` | 使用已有官方 API34/default/arm64-v8a 镜像新建独立 AVD，Magisk 30.7；ADB shell 仍 UID 2000，Helix 通过正常 Magisk 授权取得应用 Root；未安装 Shizuku |

使用 [Magisk v30.7 官方版本](https://github.com/topjohnwu/Magisk/releases/tag/v30.7) 的 `build.py emulator` 路径，下载 APK SHA-256 `e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5`。临时 Root 未覆盖 SDK 共享 system image，冷启动后可能需要重新准备。旧 AVD 私有数据先备份至忽略目录，结束后关闭本轮启动的旧 AVD，新 AVD 保留。

最终正式设备用例 **4/4 通过**，不把前期探针或失败运行计入：

| 用例 | 实际结果 |
| --- | --- |
| `RootMobileUseDeviceTest`，API34 | MainActivity 前台授权后切到目标 Activity，Root 保持；AUTO 输出 root，独立无障碍观察计数 1；主动断开，旧 Binder `pingBinder=false`，显式 root 返回 `ROOT_UNAVAILABLE`；新的 AUTO 输出 accessibility，计数 2。只读、旧 scope、范围外目标、不存在节点均拒绝；成功审计 1 条 |
| `ShizukuMobileUseDeviceTest` Root 参数，API34 | AUTO 输出 root，点击 `com.android.packageinstaller:id/button1` / `INSTALL`；微信 8.0.79 首次安装成功，firstInstallTime / lastUpdateTime 为设备时间 `2026-10-04 04:16:43`，应用内包时间 `0 → 1791058603858`；审计 1 条，拒绝路径未安装 |
| 同上默认参数，API36 | 无应用 Root 时 AUTO 输出 shizuku；微信同版本覆盖更新，lastUpdateTime 为设备时间 `2026-10-03 14:28:21`，firstInstallTime 仍为 `12:22:28`；拒绝路径和审计通过 |
| `ShizukuSettingsDeviceTest`，API36 | 实际 Compose 显示 Root 状态、授权/断开按钮及 Shizuku 状态；未操作不启动 Activity，点击后生成明确目标 Intent。启动被 fixture 拦截，不代表完整系统授权旅程 |

Root 安装只在新 AVD 进行，未登录微信。宿主复制已有 APK、通过系统 Files 打开、开启该来源安装许可、重绑测试无障碍服务；未代点 INSTALL/Update、未通过 `pm install` 安装微信。ADB 安装仅部署 Helix App/测试 APK。API34 shell VIEW 被平台拒绝后使用正常 Files 来源，未为 Helix 添加安装权限。Root 测试恢复原无障碍设置（null / 0）、清理本次会话及连接，最终无 Helix RootService 进程残留；API36 保留原设置及 Shizuku server。

## 失败记录与修复

- 直接复用原 `root.*` 连接会在 Helix 退后台时断开，跨应用无法工作。新增 Mobile Use 显式连接 owner，MainActivity → 目标应用路径复验通过；未放宽原只读 Root 规则。
- 旧 AVD 数据库降级失败通过新建独立 AVD 避开，未删除旧数据。管理器授权超时、一次被后续 instrumentation 中断的探针均保留为失败，不计通过。
- fixture 原文字 `Fixture click` 与实际 `FIXTURE CLICK` 不同，精确匹配正确拒绝后修正测试。断开后 cached `isBinderAlive` 不等于物理 Binder 死亡，最终用 `pingBinder` 验证。窗口变化时 Accessibility 拒绝 TARGET_CHANGED，最终 fixture 做新鲜只读观察等稳定，不重放失败动作。
- Root 安装首轮在点击前返回 `ACTION_NOT_DISPATCHED`，系统同时记录 window 添加超时，未安装。最终 fixture 在授权后连续观察安装器窗口稳定再执行，未放宽生产 guard；不声称已确定平台瞬态唯一原因。
- 共享特权执行把悬浮窗隐藏纳入 try/finally，避免隐藏异常泄漏物理输入槽。

## 主机与制品

定向 JVM **170** 项通过，0 failure/error/skip：tools/automation 125、tools/root 22、extensions/mobile-use 8、App automation 15。覆盖所有后端状态组合、三路选择、显式指定、额外参数保留 Accessibility、UNKNOWN 不重放、选择后 Root 丢失不降级、回退校验包名及原 Root/特权协议测试。

以下检查分阶段运行通过：

~~~sh
./gradlew spotlessCheck detekt :tools:automation:testDebugUnitTest :tools:root:testDebugUnitTest :extensions:mobile-use:testDebugUnitTest :app:testDeveloperDebugUnitTest --tests 'com.helix.app.automation.*' :app:assembleDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebugAndroidTest --max-workers=2
bash scripts/check-lockfiles.sh
python3 scripts/verify-integrated-runtime-apks.py
bash scripts/check-all.sh --source
python3 scripts/debug/2026-10-04/run-root-mobile-use.py
python3 scripts/debug/2026-10-04/run-root-mobile-use.py --installer
python3 scripts/debug/2026-10-04/run-shizuku-production.py
~~~

38 份依赖锁、实际 Debug APK 渠道/DEX/manifest 检查通过。Developer 包含 Root UI/libsu，Consumer 不包含，RootAutomationService 不是普通 manifest 服务。libsu service 经 tools/root API 依赖供宿主子类编译，固定版本不变；补齐锁及官方 AndroidX annotation 1.3.0 module checksum。

| 制品 | SHA-256 |
| --- | --- |
| Developer Debug App | `1032b942878d588e603b7d08d1efd40d39389b88b0bf4f54cae20b27be4ac96c` |
| Consumer Debug App | `d6c2f172279c6d047f20cc10ed0049b21a59fc2ddee3e64c5fe24bfdde10ad2b` |
| 最终 Developer AndroidTest | `44edee7c75848a319d5ba30747558876755f7618e80cecaa007bee260159dcf4` |

正式设备用例使用同一 Developer App。Root 生命周期通过后只修改安装器/设置 fixture 并重编 AndroidTest；最终 test APK 用于后三项。日志、失败运行及 summary.json 在忽略目录 `build/mobile-use-root-2026-10-04/`，包括 `root-production-1791058370.txt`、`root-ShizukuMobileUseDeviceTest-1791058558.txt`、`shizuku-auto-final.txt`、`settings-device.txt`；不归档旧 AVD 私有数据或下载 APK。

## 插件分析与剩余边界

Mobile Use 仍是有 manifest、PluginOrigin、注册/启停与统一工具发布的 **host-native 插件**，编译进 Advanced APK，尚非独立可加载外部插件。`extensions/mobile-use` 负责插件组合/呈现，依赖 `tools/automation` 的 Android 执行能力，没有两套重复快照/手势引擎，暂不需要合并。宿主仍有专用设置、grant store、`ui.*` scope 接线与 Root/Shizuku 适配器，后续应优先收敛这些耦合。详见[研究第 23 节](../../research/topics/mobile-use-packageinstaller-visibility-vision-and-privileged-backends-2026-10-04.md#23-插件边界与模块职责复核)，本轮未合并模块。

本轮不是全 OEM/真机、冷启动恢复、所有 root manager 或发布验收。Root 与 Shizuku 同时就绪的优先级有穷举逻辑测试，未做同机双后端设备矩阵；完整服务 crash/撤权/取消时序矩阵未设备覆盖。仍需无障碍连接维护租约和窗口 guard；特权 snapshot、截图、文本输入与通用手势不在本次交付内。
