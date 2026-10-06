# Mobile Use 安装与授权交互优化（2026-10-06）

## 范围与结论

所有者明确授权本轮模拟器与当前真实模型复测。基于当前整合工作树实施，未提交、未推送；保留其他未提交工作。对照为[前轮三后端评测](mobile-use-backend-install-eval-2026-10-06.md)，不是公共 benchmark、OEM 或真实账号验收。

**Root、Shizuku 的安装保护误判已修复，两组真实模型安装成功；三组卸载成功。普通无障碍安装仍失败，新增指导没有证明能稳定消除模型重复尝试。**

## 实施

- 高权限语义观察与动作共用系统安装器公开按钮例外：PackageManager 解析系统 APK 处理器，校验组件、资源 ID、Button 类型和非密码/不可编辑属性；仅在全手机且无排除应用授权下生效。未知敏感节点、密码和保护窗口未放开。
- `ui.apps` 升至工具版本 2，可传目标 `packageName`，返回 INSTALLED、NOT_INSTALLED、UNKNOWN；受原调用范围与发布前授权复查约束。普通列表缺失不再作为卸载证明。完整包可见性不足时不能声称目标不存在。
- 内置 Skill 区分安装来源、安装器与目标应用；补充无进展、安装器无障碍限制、实际调用截图而非计划式结束的指导。STALE_TOKEN/TARGET_CHANGED 反馈提示刷新观察；重复失效时可选择现有 `ui.click_match` 原子匹配，不放宽窗口或 token 校验。
- 集中授权页优先展示设备控制，普通悬浮窗归入此组；Standard 没有相关能力时不留下空分组。Root 显式授权后连接已启用功能注册的服务，关闭插件不连接；打开页面及被动状态观察不申请、不连接。
- 评测夹具增加跨 UID 的测试 APK 安装文件存在检查，防止未准备页面就消耗模型额度；临时模型迁移测试代码已删除，Root 测试凭据已清理。

## 真实模型结果

当前模型 Qwen3.8-27B；应用为 Developer debug。API36 `emulator-5554` 分别隔离 Shizuku 与普通无障碍；API34 `emulator-5572` 为应用真实获准 Root 的专用 AVD。Root/Shizuku 测试关闭 Helix 无障碍，普通无障碍测试停止 Shizuku。只操作无用户数据的 `com.helix.validation.installfixture`，不是代用户安装第三方账号应用。

每项单次用户任务，不追加“继续”指令、不代点确认；独立查询系统包状态和持久化 turn 终态判定。

| 后端 | 安装 | 卸载 | 卸载工具调用：前轮 → 本轮 |
| --- | --- | --- | --- |
| Root / API34 | 成功，17.718 秒，10 次模型/9 次工具 | 成功，13.972 秒，7 次模型/6 次工具 | 19 → 6 |
| Shizuku / API36 | 成功，30.978 秒，10 次模型/9 次工具 | 成功，24.318 秒，7 次模型/6 次工具 | 13 → 6 |
| 无障碍 / API36 | 失败；第二轮 195.976 秒，29 次模型/28 次工具 | 成功，23.062 秒，7 次模型/6 次工具 | 10 → 6 |

时间来自夹具 elapsedMs，是单次观测，不是稳定延迟承诺。无障碍安装失败后由主机预装同一个测试 APK，仅为卸载用例准备前置条件，不计作 Helix 安装成功。

### 保留的失败和修正边界

- Shizuku 首次准备把 APK 放到了目标应用而非测试 APK 私有目录，安装来源 Activity 未打开；模型随后从桌面运行，该轮不计作正常安装结果。第二次前置检查遇到跨 UID 文件可见性问题，在模型调用前拒绝，模型/工具均为 0。改为测试 APK 独立状态 Provider 查询后，第三次完成安装。
- Shizuku 成功安装后的最终说明曾用来源 `com.helix.agent.developer.test` 查询结果证明目标存在。安装器已明确显示成功，独立包检查也确认目标已安装，因此实际安装判定有效；这条模型解释仍错误。随后已补充工具/Skill 的身份区分，但未重复高权限安装验证这条文案改动的收益。
- 普通无障碍首轮 123.945 秒、30 次模型/29 次工具未安装；加入安装页具体提示后第二轮仍未安装。截图能看见安装按钮，多次手势已派发但确认页没有变化。该结果证明当前组合不可完成，不能仅凭回调断言 Android 拒绝原因或安装成功。模型仍重复截图/手势，指导改动不足以证明无进展问题已解决。
- Root 的前轮计划式结束在本轮未出现；不据一次成功断言所有模型都会正确继续。最后补充的 token 失效反馈经主机验证，并随无障碍卸载包安装；未再做第三轮无障碍安装。

会话：Root 安装 `ce19a7292c1b70c53d55bf010eab142d`，卸载 `fce688f01ba1c10ed2b019d9bd9de10d`；Shizuku 安装 `b9a65d8a6cd12656ee5b0eeafe4a7323`，卸载 `c8b900795c11bfaab5151210a360b77b`；无障碍第二轮安装 `dff0650b4d708a19d60d1010d844b6d5`，卸载 `fb090b18e8459f935fbdd44a0d77a958`。

## 验证与环境恢复

- 主机：Mobile Use 214/214；Root 26/26；应用 Developer 1233 通过、4 跳过。双渠道 debug APK 与 Developer AndroidTest APK 构建、spotless、detekt、Mobile Use/DeviceAccess/双渠道 app lint 通过。
- `bash scripts/check-all.sh --source`、文档、ADR、i18n、secret 检查通过；`git diff --check` 通过。
- API34：`RootMobileUseProbeDeviceTest` 1/1，验证宿主授权、关闭插件不连接、启用后连接及真实 UID 0 点击；`SystemPermissionsLayoutDeviceTest` 2/2。
- API36：`SystemPermissionsLayoutDeviceTest` 2/2、`ShizukuSettingsDeviceTest` 1/1，涵盖布局、文件/诊断跳转与打开授权界面不连接 Mobile Use。
- Root AVD 启动启用硬件键盘与硬件键盘下显示 IME；逐键拼音组合并提交“你好”已验证。
- 原始证据位于忽略目录 `build/backend-optimization-2026-10-06/`，主机日志 `build/mobile-optimization-*.log`。恢复日常设备 Shizuku 和原无障碍关闭状态；测试 APK 来源权限恢复默认、测试安装文件清除。Root 模型配置与 Keystore 测试凭据清理通过，临时明文缓存和迁移测试源码删除，测试包重新构建安装；专用 Root 实例关闭，日常实例保留最新版。

## 剩余工作

普通无障碍安装成功路径、模型无进展收敛仍开放；优先使用实际就绪的 Root/Shizuku。进一步优化需有重复屏幕与动作的真实证据，不能把派发成功改写成业务成功，也不能用固定任务状态机替模型决策。真实厂商设备、真实应用完整下载链路、账号任务均未在本轮验证。

## 无障碍点击定点审查补充

本次所有者另行授权日常模拟器定点测试，不调用真实模型。Developer debug、API36、`emulator-5554`；运行时停止 Shizuku，断言工具选择 Accessibility，经生产 Dispatcher 调用 `ui.device`、`ui.gesture`。没有将主机 shell 对照计作 Helix 自主完成。

### 观察结果

| 同坐标测试 | 手势派发 | 实际效果 | 独立观察 |
| --- | --- | --- | --- |
| 普通测试按钮 | SUCCEEDED | 点击计数 1 | Android View 安全过滤接受，flags=2048 |
| 显式 accessibilityDataSensitive 按钮 | SUCCEEDED | 点击计数 0 | View 安全过滤拒绝，flags=2048 |
| filterTouchesWhenObscured 按钮 | SUCCEEDED | 点击计数 0 | View 同时视为 sensitive，安全过滤拒绝，flags=2048 |
| 同一敏感按钮，shell 触摸对照 | 已执行 | 点击计数 1 | View 接受，flags=0 |
| 真实 PackageInstaller 安装按钮，无障碍点击 (885,1380) | SUCCEEDED | 目标包仍不存在 | 独立包查询 |
| 同一安装页同坐标，shell 触摸对照 | 已执行 | 测试包安装成功 | 系统包路径存在 |

合成按钮用例 1/1 通过（包含三个模式），真实安装页定点用例 1/1 通过。安装页用例的“通过”指验证无障碍未安装，并非安装成功。测试应用重写安全过滤方法只记录 `super` 的结果，不更改返回值。flags=2048 是无障碍注入，未设置窗口遮挡位，因此该合成复现不是悬浮窗遮挡。没有直接观测系统安装按钮内部方法；同坐标对照和平台源码支持其符合相同过滤机制，不声称取得系统进程内部日志。

### 原因与后续修复边界

Android 16 的 [View.onFilterTouchEventForSecurity](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/core/java/android/view/View.java) 在相应平台开关启用时，拒绝非 accessibility-tool 服务向敏感 View 注入触摸；这与遮挡过滤是独立分支。官方[安全说明](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data)说明了敏感视图对读取和交互的限制。当前 Helix 未声明 `isAccessibilityTool`，与其通用 Agent 定位一致，不应为操作安装器冒充残障辅助工具。

- 截图可以补充模型对按钮位置的认识，不能改变 Android 对触摸来源的判断；盲目多次截图和手势无法解决此限制。
- 手势完成回调只证明注入序列完成。当前工具已有“验证实际结果”提示，必须保持派发结果与业务结果的区别。
- 已授权且就绪的 Root/Shizuku 是不同输入来源；普通无障碍遇到这种页面需要用户完成确认。不能将 API36 结果推广为所有 Android/OEM 安装器必然失败。
- 另有独立的工具描述问题待修复：`ui.device.screenBounds` 表达目标窗口矩形，而 `ui.screenshot.screenBounds` 表达截图映射区域。弹窗时两者不同，模型若用窗口原点修正全屏坐标会点错。应明确区分 `windowBounds` 和截图坐标映射，增加非零窗口原点测试。
- 截图描述当前偏向“语义失败则立即使用手势”，缺少系统敏感交互例外。应在描述和反馈中明确截图不是绕过系统触摸过滤的手段；仍由模型决定任务策略，不增加安装任务专用流程。

本次增加可复现的测试夹具与记录，未修改生产点击实现。原始证据为忽略目录 `build/accessibility-click-review/` 的 `fixed-touch.log`、`touch-logcat.txt`、`shell-touch-oracle.txt`、`installer-touch.log`、`installer-logcat.txt`、`installer-shell-oracle.txt`。完成后卸载独立测试包、移除暂存 APK、恢复来源安装权限默认值、原无障碍设置和 Shizuku，回到 Helix。未消耗模型额度、未提交或推送。

本次主机检查：Developer AndroidTest APK 构建、Developer lint、spotless 格式化及 detekt、文档检查（760 篇 Markdown、230 项 HXA）、`git diff --check` 通过。设备结果来自格式化和方法提取前的同等用例；最后测试源码另经重新编译和 lint，未进行真实模型复测。

## 定点审查后的工具反馈优化

所有者随后要求落实优化。本轮统一 `ui.device` 全屏与窗口坐标，工具版本升为 4；截图仍携带自身的映射区域。补充非零窗口原点、裁剪截图映射和原始绝对坐标传递的主机回归。工具与内置技能明确节点缺失原因未知、视觉兜底仍有效、系统可能过滤触摸，以及派发不等于生效；不增加自动跨后端重放或任务专用决策流程。

定点设备夹具增加 `no-semantics` 按钮模式，断言语义观察没有按钮文字且固定坐标触摸可以生效；敏感按钮拒绝的原用例保留。本轮设备验证 not requested，仅编译设备用例，没有使用模拟器、调用模型或安装 APK。此前设备结果只属于此前审查版本，不能当作新增用例通过。

本轮新结果：Mobile Use JVM 216/216 通过；Developer AndroidTest APK 构建、Mobile Use lint、spotlessCheck、detekt、`bash scripts/check-all.sh --source` 和 `git diff --check` 通过。首次检查暴露新增字符串超长与坐标测试 Int/Float 比较错误，已修正并完整重跑上述主机检查。未进行设备或真实模型复测，尚不能量化无效重试减少的收益。日志：`build/accessibility-click-review/optimization-final.log`、`optimization-source.log`。

## 最新优化版模拟器回归

所有者随后明确授权模拟器测试及直接修复。本轮构建并覆盖安装最新 Developer debug 与测试 APK，在日常 API36 `emulator-5554` 经生产 Dispatcher 运行，不调用模型。

- 首次新增 `no-semantics` 检查失败：仅设置 IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS 的 Button，仍被当前包含非重要节点的服务读取到文字；原夹具不能证明无语义场景。改用 Canvas 绘制标签的自定义 View，保留“语义观察无文字”和“坐标触摸实际计数增加”两项断言，没有放宽检查。
- 修复后四模式定点用例 1/1（6.602 秒）：普通、自绘无语义区域点击生效；显式敏感、遮挡过滤隐式敏感按钮被系统拒绝，工具仍准确要求观察实际效果。
- 安装确认页用例 1/1（1.768 秒）：无障碍在 (885,1380) 派发后目标包仍不存在；随后 shell 同坐标触摸使测试包安装成功，由系统包路径独立确认。该 shell 对照不计作 Helix 自主安装成功。
- 没有发现新的生产点击缺陷；本轮修复测试夹具，并让测试脚本容忍 Shizuku 未运行的准备状态。未验证真实模型是否减少重复尝试、其他 OEM 或其他 API。
- 原始结果保存于 `build/accessibility-click-regression/`，包含首次失败、修复后测试输出与同坐标包状态证据。结束后卸载独立测试包、删除暂存 APK、恢复来源安装权限原值 deny、无障碍原关闭状态和 Shizuku，打开最新 Helix；原有用户数据保留。未提交或推送。

本次修复后主机门禁：Developer 测试 APK 构建、Developer lint、spotlessCheck、detekt、`git diff --check` 通过。APK SHA-256 保存于同目录 `apk-sha256.txt`；生产实现无新改动，因此没有重复上一轮已通过的 216 项 JVM 测试。
