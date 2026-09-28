# 系统设置自动化：显式会话授权与复测

日期：2026-09-28。所有者在 AndroidWorld 系统设置被固定策略拒绝后，明确选择“增加正式产品授权入口”。本轮修改权限契约及正式 UI，不把 benchmark 夹具当作生产授权来源。

后续查因与修复见[搜索/恢复/滑块记录](automation-recovery-progress-2026-09-28.md)。本文保留首次授权运行的0/2及当时限制。

## 实现范围

Advanced 的“设置 → 权限与安全 → Accessibility 自动化”增加默认关闭的系统设置/快捷设置选项。只有用户启动会话时的选择才写入 `ActiveAutomationSession`；活动会话显示实际授权且不可中途修改，停止、到期、服务断开及进程重建后不继承。此授权不持久化，也没有对应模型工具。

授权只对 `com.android.settings` / `com.android.systemui` 的 package 默认拒绝作例外；二者仍必须在用户填写的目标包白名单中。snapshot 和实际节点动作均复检。其他敏感包、password/accessibilityDataSensitive、敏感动作语义、token/generation、锁屏、时间/动作限制及 Dispatcher 审批保持。它允许设备设置更改，不保证识别 Settings 内每个安全页面；OEM 自定义包不通配放行。

不新增坐标、手势或 slider set-progress 工具。旧会话调用默认参数仍保持原拒绝行为。未修改另一端 Plugin、Dispatcher 或 effect owner。

## 主机验证

- `:tools:automation:testDebugUnitTest`：48/48 通过；新增默认关闭、明确授权、白名单、密码/敏感节点、危险点击、停止/到期/新 manager 不继承测试。
- 双渠道 app unit、lint、debug APK、AndroidTest APK 与 detekt：通过，`build/settings-auth-gates.log`。
- 授权控件补上可访问名称后 developer lint/APK/test APK 与 detekt 再通过，`build/settings-auth-final-build.log`。
- source gate：通过，`build/settings-auth-source.log`。

新增 snapshot 正向测试最初用无任何文本的空节点，得到合法 `UNSUPPORTED_UI`；夹具补成有 Display 语义的节点后通过，未改变空树拒绝行为。

## 设备与模型试跑

正式入口设备状态：**passed，1/1**。独占 API36 arm64 developer，400 dpi，4 GiB / 4 cores；UI 测试实际勾选、启动、读取 session grant、停止、确认开关复位、再次启动并确认默认无授权。仅所有者授权的模拟器，未使用物理真机。

随后通过同一个 PermissionCenter 启动显式授权会话，运行 upstream `SystemBrightnessMin` / `SystemBrightnessMax` 原始初始化与 oracle。夹具明确记录会话授权，仍只批准本次 UI 操作，其他待审批行为拒绝。模型任务结果 **0 PASS / 2 FAIL / 0 fixture ERROR**；两项 instrumentation 都正常结束，但不代表任务成功。

| 任务 | 初始→最终亮度 | Turn / error | Agent elapsedMs | 工具调用 |
| --- | --- | --- | --- | --- |
| SystemBrightnessMin | 255→255 | FAILED / MODEL_CALL_LIMIT | 69043 | 32 |
| SystemBrightnessMax | 1→1 | FAILED / MODEL_CALL_LIMIT | 189965 | 33 |

两项都实际获得 Settings snapshot，并点击了 Settings 搜索入口；此次没有 `SENSITIVE_UI` 拒绝。随后出现 `TARGET_NOT_ALLOWLISTED`，动作返回 `SESSION_PAUSED`，模型继续尝试/等待直至耗尽32次模型调用预算。源码将该状态映射到目标变化暂停，而非每10动作的 `CHECKPOINT_REQUIRED`。目前证据未记录被拒绝窗口的 package，不能确定具体组件；不擅自将所有 Settings 关联包加入白名单。

这证明显式授权路径已工作，但未证明完整系统设置任务可用。跨包授权/暂停恢复 UX、模型识别需要用户介入以及反复使用旧 token/无效动作，继续作为后续工作。现有模块有确认恢复 API，但产品界面尚无对应恢复控件；本轮没有自动调用该 API 绕过用户确认。滑块能力仍未验到，不能将本次失败归因为不支持滑块。

`closed.json` 确认本次独占模拟器已退出，runner 退出0只表示准备/脚本正常执行，任务FAIL以oracle为准。

原始证据：`build/public-eval/androidworld-settings-authorized-20260928/`。本次为 API36/Helix Accessibility 动作空间适配试跑，不能作为官方榜单分数；历史未授权试跑 0/2 仍保留。


## 制品与边界

- 基础 HEAD `8f26b704` 加本轮工作树差异；并非 clean 正式 P5，不覆盖历史15/15。
- app APK SHA256：`22603495dae3532d8004053f3c790dad130bc8074b63c73f09bd3f098aae9b9e`。
- test APK SHA256：`f7353f84b7779eea80cb4c4011f8303e763f792a8fbc0a42c19b8d76be5966aa`。
- 运行目录保留 `androidworld-source.diff`、`authorization-source-sha256.json`、新增 UI 测试源码、官方 revision/预算 manifest 与两项 Agent 轨迹。未经脱敏的详细节点输出不提交仓库。
- `spotlessCheck`、最终 source gate、`git diff --check` 通过。修改仅位于隔离分支，未合并主目录、未推送；另一端插件改动保持独立。
