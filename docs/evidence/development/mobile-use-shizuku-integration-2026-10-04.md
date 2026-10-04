# Mobile Use：Shizuku 正式工具接入验证

日期：2026-10-04。所有者在安装器研究/模拟器试验任务中明确选择“不自建，接入 Shizuku”。本轮延续同一 API36 模拟器的定向验证，未调用真实模型、未卸载微信或清除其数据，未提交/推送。

## 交付范围

- Advanced/developer 的 Mobile Use 设置新增 Shizuku 状态和用户授权/打开入口。状态区分 unavailable、permission required、ready、lost；授权 Activity 不导出，不由模型工具自动启动。
- `ui.device` v2 返回 `shizukuState`。`ui.click_match` v4 保留默认 Accessibility，增加显式 `backend=shizuku`，此后端只接受 `packageName`、`viewId`、`text` 的精确匹配，不忽略额外筛选参数，不自动切换或重试。
- 生产路径：Plugin → 原 ToolDispatcher → 原会话授权范围 → AutomationPermissionCenter → 原无障碍物理租约/输入占用 → Shizuku UserService。没有新增工具执行器框架或自建 ADB client。
- Shizuku v13+ 系统服务及授权仍由用户提供。复用固定 13.1.5 client 依赖，保留锁和 checksum；Developer APK 包含 MIT 许可/署名，Consumer 不包含 Shizuku provider、授权 Activity 或实现/依赖 DEX。

调用示例（模型参数只选择操作，不授予权限）：

~~~json
{
  "backend": "shizuku",
  "packageName": "com.google.android.packageinstaller",
  "viewId": "android:id/button1",
  "text": "Update"
}
~~~

## 权限、执行与结果

原 Conversation ID 与批准的 scopeRef 仍逐调用校验；只读/工具拒绝仍由 Dispatcher 执行。缺少会话范围、锁屏、无障碍断连、范围替换、停止/接管、取消和截止时间不会被 Shizuku 系统授权覆盖。

UserService 的 Binder 操作只允许本包 UID；反向 guard 只接受 UID 0/2000，并清除 Binder 来访身份后检查主机事实。进程身份实际检查为 shell/root，桥绑定/解绑有界，每次调用结束后 guard 失效。XML 与节点文本留在辅助进程，密码文本不序列化；树有节点、深度、字符限制，拒绝重复目标、非法边界/旋转、DTD/ENTITY。生产输出只返回稳定状态。

两次新鲜观察定位同一个唯一、可见、enabled/clickable 节点；缓存在每次观察前清除。注入前 Host guard 检查原租约、目标窗口、默认显示、旋转、窗口内坐标和既有覆盖窗口限制。输入与 Accessibility 手势共享物理占用；同步远端操作结束前不因取消提前释放。点击派发后允许目标页面正常变化，仍检查取消、截止时间、系统权限和原租约。非零退出、IPC 丢失和未知输出均不冒充成功，不自动重放。

`SUCCEEDED` 只表示点击已派发；原工具 actionHint 要求继续观察，安装完成仍由独立页面/包时间确认。

## 设备结果

设备 `emulator-5554`，Android 16 / API36 / arm64，Shizuku server/UserService UID 2000；未使用 root。微信为 8.0.79 的同版本覆盖更新。设备时钟为 2026-10-03，与主机日期不同。

`ShizukuMobileUseDeviceTest` 通过 **1/1**，直接调用真实 AppContainer 的 Dispatcher 和已注册 Plugin 工具，包含：

| 场景 | 实际结果 |
| --- | --- |
| READ_ONLY | `Denied(OPERATION_DENIED_DOMAIN)`，未更新包 |
| 替换范围后使用旧 scope | `NO_ACTIVE_SESSION`，未更新包 |
| 新范围只允许 Helix，却请求安装器 | `TARGET_NOT_ALLOWLISTED`，未更新包 |
| 正确范围但不存在目标文字 | `TARGET_NOT_FOUND`、sideEffectFree=true，未更新包 |
| 正确范围/精确 Update | `Succeeded`，actionHint 保留派发与完成的区分 |
| 独立安装结果 | lastUpdateTime 从设备时间 `13:41:50` 变为 `13:47:38`；firstInstallTime 保持 `12:22:28`；存在一条 `tool_dispatch` 审计 |

最终 UserService 无 `helix_ui` 进程残留，原 Shizuku server 保留。测试只创建/删除本次 fixture 会话并恢复原 profile；宿主仅准备 APK VIEW 页面、重绑原本已开启的无障碍服务和读结果，没有 host `input tap Update` 或 `pm install` 微信。

设置组件测试 `ShizukuSettingsDeviceTest` **1/1**：实际 Compose 状态/按钮可见可点击，未点击前不启动 Activity，点击后生成明确的 Shizuku manager 或 Helix 授权入口 Intent。此测试拦截启动，不改变已有系统授权；不宣称首次安装 Shizuku、无线配对或系统拒绝弹窗旅程已验收。

## 发现并修复的问题

1. instrumentation 启动会 force-stop 目标进程，旧无障碍服务可能进入 crashed/disconnected；fixture host 在测试启动后重绑原已启用组件，不将该测试生命周期失败视为 Shizuku 能力失败。
2. 最初复用诊断的 `uiautomator dump` 会抑制原 Accessibility 服务，实时 guard 因此拒绝。正式实现改为 **UserService 内 UiAutomation + FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES**；平台连接/显示访问仅在高权限进程内使用反射，主应用不引入 hidden-API bypass 库。
3. 一次点击实际更新了微信，但派发后的目标变化被误当成中断，返回 UNKNOWN。先核查包更新时间和安装完成页，再修复“注入前目标复核”与“派发后执行有效性”的区别，没有盲目重放未知动作。
4. 重用旧安装器 task 曾回到其他页面，目标校验正确拒绝；后续 host 使用新 task 的 VIEW intent（`0x18000001`）并确认 Update 页面再运行。生产工具仍精确检查包名/目标，不硬编码点击坐标。

## Host 与制品

- JVM：tools/automation **119**、extensions/mobile-use **8**、App Shizuku **15**，合计 **142**，0 failure/error/skip。
- `detekt spotlessCheck`、Developer/Consumer Debug APK、Developer AndroidTest APK 构建通过。
- `check-all.sh --source`、38 份依赖锁检查及 Debug APK 渠道/manifest/DEX 核验通过。
- Developer App SHA-256：`aa6a1acfb6c0fc48e2d95a815d50ce061068095bb24d68aaabee1acb4af4cafe`；安装器用例与设置组件用例使用同一 App APK。
- Consumer App SHA-256：`54d67eef65b192b8e9b8b0a7c52a6ea08439851173621592df68eb5b5bd77ea5`（仅 host 制品检查）。
- 最新 AndroidTest APK SHA-256：`b0e2346e11d49ffe5e7a849d5ebc84b249f5d3836eb7a959afae7d7e2a5d95ac`；生产安装通过后仅追加设置组件用例并重编 test APK，安装用例源码未变。

原始日志、各次失败/修复运行与 summary.json 在忽略目录 `build/shizuku-integration-2026-10-04/`；fixture 驱动在 `scripts/debug/2026-10-04/run-shizuku-production.py`。首轮诊断记录保留于[前阶段证据](mobile-use-backends-2026-10-04.md)，不能与本次正式接线混算。

## 边界

本次完成可选的精确节点点击接入，不是完整 Shizuku snapshot/截图/手势/输入后端；仍需无障碍连接，只支持默认显示。两次观察与注入间仍存在平台竞态。取消/撤销/异常后的拒绝与 UNKNOWN 有主机测试，未完成全设备 Binder 死亡/重启/权限撤销故障矩阵。未覆盖其他 API/OEM、真机、root、全新安装、真实模型自主选后端或正式发布。平台隐藏接口兼容性需按设备实测；失败保持拒绝/待核查，不自动改权限或切 root。

参考：[Shizuku UserService API](https://github.com/RikkaApps/Shizuku-API#userservice)、[Android UiAutomation 源码](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/app/UiAutomation.java)。仅查阅 API 行为，自行实现接线和有界遍历，没有复制参考实现代码。
