# Mobile Use 安装器：无障碍复验与 Shizuku shell 路径

日期：2026-10-04。范围：所有者明确要求在此前模拟器尝试无障碍，失败后继续修改当前 ADB/Shizuku 或 Root 方案；另一 Agent 已停止。本轮接手已有 Shizuku 实验代码，未提交或推送。

## 结果

| 路径 | 本轮结果 | 证据边界 |
| --- | --- | --- |
| Helix 普通 Accessibility snapshot/token action | **failed**：10 秒 fresh snapshots 始终缺少 `android:id/button1` / Update，只读到 Cancel | 未发出确认点击，微信更新时间未变；这是能力路径失败，不是跳过或测试通过 |
| Shizuku 错误目标反例 | **passed**：不存在的标签返回 `TARGET_NOT_FOUND`，更新时间不变 | 测试拒绝路径，没有安装副作用 |
| Shizuku shell UserService | **passed**：实时层级唯一定位并复核 Update，派发点击后完成微信同版本覆盖安装 | 这是有界 Developer 诊断，不是正式 Mobile Use 接线或全新未安装状态验收 |
| Qwen 视觉安装 | 所有者报告另一 Agent 已通过 | 本轮未调用模型，也不重归因其历史安装动作 |
| App libsu Root / 真机 / 其他 OEM | 未执行 | 当前 shell Shizuku 已完成所需定向试验，未刷机、安装 Root manager 或改变 Root 实现 |

## 环境及制品

- 设备：`emulator-5554`，API 36 / Android 16 / arm64。
- build fingerprint：`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`。
- PackageInstaller：`com.google.android.packageinstaller`。
- 微信：`com.tencent.mm`，8.0.79，保留已安装数据，只进行同版本覆盖安装。
- ADB shell、Shizuku server 和 UserService 实测均为 UID 2000；server 已运行且用户授权已存在。本轮未把 `adb root` 或 RootManager 当成前提。
- 设备时钟与主机不一致：以下 `2026-10-03 12:xx` 是设备原始时间，不能按主机 2026-10-04 时间混排。

| 制品 | SHA-256 |
| --- | --- |
| `app/build/outputs/apk/developer/debug/app-developer-debug.apk` | `8df9e3ef353a87fc9fbf56066d205ccb23e1bd4d459a4e49b02e7ce2ebe82f72` |
| `app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk` | `a877771c58cb1932819fd2f07f06d510bb9219887438dd1430efa38df8c23c38` |

安装两份 Helix APK 使用 `adb install -r`；没有清除 Helix/微信数据。测试前后无障碍配置均保持 `com.helix.agent.developer/com.helix.tools.automation.HelixAccessibilityService`。Shizuku UserService 结束后进程列表没有残留 `helix_ui`，原 Shizuku server 继续保留。

## 无障碍测试

原 `MobileUsePackageInstallerVisibilityDeviceTest` 自带 ActivityScenario、卸载微信、修改 Chrome app-op 和固定坐标打开下载项，既会丢数据，也与“当前安装页”前提矛盾。已移除这些准备动作；由 host 打开已知的 APK URI，fixture 不启动 Activity，使用普通生产 snapshot/nodeAction，并以新的 `lastUpdateTime` 判断成功。

通过此前活动记录找到 `content://media/external/downloads/44`，用公开 VIEW intent 打开；系统测试树明确显示：WeChat、`Do you want to update this app?`、Cancel、Update。host 的 UiAutomation 只用于准备与观察，运行 instrumentation 时没有同时启动第二个 uiautomator。

~~~sh
adb -s emulator-5554 shell am start -a android.intent.action.VIEW \
  -d content://media/external/downloads/44 \
  -t application/vnd.android.package-archive -f 0x10000001
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.helix.app.eval.MobileUsePackageInstallerVisibilityDeviceTest \
  -e helixPackageInstallerVisibility true \
  com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner
~~~

结果：`Tests run: 1, Failures: 1`。最后 snapshot 包名仍为安装器，`truncated=false`，包含更新询问和 `android:id/button2` / Cancel，不包含 button1；10 秒等待失败，没有进入 nodeAction。前后 `lastUpdateTime` 均为 `2026-10-03 12:22:28`。

该结果确认本设备普通无障碍的语义限制仍存在。未声明 `isAccessibilityTool=true`，未移除敏感节点保护，也未把测试通道树冒充生产 Accessibility 树。

## Shizuku 修复与实测

原桥接收裸 x/y，`input` 返回非零也可能被上层写成 `PASS`，绑定超时不经过 unbind，结果固定文件名没有运行身份。本轮修复为：

- 固定 typed `CLICK_MATCH(packageName, resourceId, text)` Binder 操作，不提供任意 shell 字符串接口，移除旧 raw tap transaction。
- UserService 使用固定 `uiautomator dump` 命令获取有界 XML；唯一目标须包名、资源 ID、文本一致且 enabled/clickable，拒绝 password、非法 bounds、DTD/ENTITY、过大 XML 和重复目标。
- 再获取一次层级，复核目标 bounds/rotation 一致后，才根据节点中心派发 `input tap`；坐标不是 host 写死或模型猜测。
- 命令等待有界，超时终止进程并等待退出；绑定等待也置于 finally/unbind 内，Binder/权限丢失不自动重放。
- 保留 `android.permission.DUMP` 入口保护，增加 debuggable、显式 `enabled=true` 和受限 `runId`；同一 Service 不并发启动动作。
- 每次运行独立证据文件，带 runId、时间、UID、两次 hierarchy SHA。退出码 0 只写 `DISPATCHED`，非零为 `OUTCOME_UNKNOWN`；顶层仍为 `DIAGNOSTIC_ONLY`，真实安装结果另验。

官方 [Shizuku API 指南](https://github.com/RikkaApps/Shizuku-API#userservice)区分 shell/root UserService，并规定 unbind/destroy 生命周期；本轮使用现有锁定 `13.1.5`，补齐 Debug AndroidTest 与 UnitTest 的依赖锁配置，没有升级依赖。

### 反例 `missing-target-01`

请求安装器 `android:id/button1`，文字 `HELIX_NO_SUCH_CONFIRMATION`。返回 `ERROR_VERIFY_BEFORE_RETRY` / `IllegalStateException: TARGET_NOT_FOUND`。前后安装时间完全一致，未残留 UserService。

### 正例 `semantic-update-01`

请求同一安装器 `android:id/button1`，文字 `Update`。

~~~json
{
  "serverUid": 2000,
  "userServiceUid": 2000,
  "status": "DISPATCHED",
  "exitCode": 0,
  "x": 888,
  "y": 1372,
  "rotation": "0",
  "observationSha256": "bc1c5e96c036453689bb1915171ece4888f755620d2db1f568953ae616d7116e",
  "recheckSha256": "bc1c5e96c036453689bb1915171ece4888f755620d2db1f568953ae616d7116e"
}
~~~

坐标来自本次两次匹配层级的 `[798,1301][978,1443]`，不是生产固定坐标。随后单独核验：

- `lastUpdateTime`：`2026-10-03 12:22:28` → `2026-10-03 12:52:25`。
- `firstInstallTime`：仍为 `2026-10-03 12:22:28`。
- versionName：仍为 8.0.79。
- PackageInstaller 新页面：`App installed.`、Done、Open。

该过程没有 Qwen 调用、无障碍确认点击、host `input tap Update` 或 `pm install`。ADB 只安装 Helix 候选、准备安装页、触发诊断和读取结果；最终确认点击在 Shizuku UserService 中发生。它证明本设备 shell 权限方案可以处理该确认页，不证明所有安装器/ROM 都支持。

## 验证与剩余边界

定向 host：8 项 `ShizukuUiTargetTest`（0 failure/error/skip）、`detekt spotlessCheck`、Developer App/Test APK 构建通过。曾遇到缺少测试配置依赖锁和格式/复杂度门禁，已补锁并修复，最终不跳过门禁。

`./scripts/check-all.sh --source` 通过，包括文档、ADR、国际化、secret scan 和源代码契约检查；`git diff --check` 通过。完整锁检查首次发现 App Shizuku lint 配置及 mobile-use 既有 AndroidTest lint 配置缺项，已使用仓库脚本按当前依赖图补齐；后者还将该 lint 配置的 annotations 选择与 AndroidTest 的 23.0.0 对齐，没有修改版本目录。

依赖锁再次完整解析通过，结果记录于 `lockfiles-final.log`。

详细原始记录在忽略目录 `build/mobile-backends-2026-10-04/`：`accessibility-instrumentation.txt`、`host-shizuku-final.log`、两个 runId 目录、最终 UI 与 package/进程记录。调试脚本在 `scripts/debug/2026-10-04/`，不会自行卸载微信。

当前桥仍位于 Developer `eval`，未注册成生产 Tool、未实现通用会话 target/frame/token 与 Dispatcher 接线。两次 XML 复核缩小变化窗口，不能保证观察到注入间 UI 永不变化；取消/进程死亡/权限撤销后的完整 UNKNOWN 恢复、任意多窗口和全新安装仍未验收。后续正式后端必须复用既有授权/作用域/审计，不能将该 DUMP 诊断入口直接暴露给模型。
