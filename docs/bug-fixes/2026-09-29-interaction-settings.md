# Bug Fix: 抽屉、返回、输入区、重新生成与配置建议收口

Status: fixed
Date: 2026-09-29
Related HXA: HXA-199, HXA-214, HXA-218, HXA-228
Affected modules: app

日期：2026-09-29。基线：本地 main `742d93b6` 加本轮交互修复。所有者明确授权本轮 API36 定向验证，以及能力查询、设置入口、当前会话模式/模型/思考强度建议；验证不包含真机、真实账号；后续获所有者授权本地提交，未推送。

## Problem

所有者报告抽屉易误开且过宽、返回层级不符预期、重新生成闪退，并要求终端独立、Provider 协议选择与输入区布局调整。设备验证另外暴露了草稿状态与异步刷新交错的问题。

## Impact

影响日常导航、消息重试及草稿连续性；终端原先无法在没有会话目录时正常启动。配置能力通过用户确认作用于后续请求，不扩展权限。

## Root cause

重新生成在进入 IO 协程之前读取 Room；会话导航主动清理返回栈；终端目录解析依赖当前聊天。异步持久刷新没有识别临时草稿，启动恢复在 IO 查询结束后没有与新的 UI 选择重新仲裁。旧测试也包含过时布局断言及窗口焦点/投影采样假设，生产修复与夹具修正分别记录。

## Fix and invariants

- 关闭状态的抽屉只通过菜单按钮打开，已打开时仍可滑动关闭；宽度按实际窗口取 2/3，移除重复的会话搜索条目。历史列表中的搜索保留。
- 打开会话不再清空导航栈；顶栏返回、系统返回继续交给当前页面和导航栈处理，保留 Provider 分类、权限子页与弹窗的逐级返回。
- 终端的普通相对目录以 `workspaces/app/terminal` 为基准，不再读取当前会话目录，无会话也可启动。显式 `scope:` 入口、Advanced 可用性、终端租约、停止及结算边界不变。
- 自配置 Provider 可独立选择 OpenAI Chat Completions、OpenAI Responses、Anthropic Messages。保存和模型发现均使用实际选择的协议；变更协议会失效旧模型发现结果。API Key 继续显示且可不填。
- 输入框上方为语音（左）及附件/停止/发送（右）；下方为模式（左）、模型与思考强度（右）。窄屏与大字体保留可访问控件；权限与上下文选项仍在更多菜单中。
- 重新生成改为图标，保留无障碍名称。原实现点击后、启动后台任务前调用 `currentSession()`，在主线程触发 Room 读取；现在后台读取并固定点击时的 session，提交时再次校验，避免跨会话重试。保留旧回答的 supersession/audit，不复制用户消息。
- 修复草稿刷新边界：持久会话刷新若遇到新打开的临时草稿，不得按“数据库无此行”清除其 ID。启动恢复先在 IO 上查询，再在主线程检查并应用，不能覆盖用户已经选择的会话或草稿。
- 新增可通过 `tools.search` 发现的 `helix.settings`；没有扩张默认工具面或提高工具数量上限。`inspect` 返回平台能力状态、Standard/Advanced 说明和可选模型，不输出 endpoint/header/key。`propose` 仅产生原会话的临时待确认建议，工具结果明确 `applied=false`。
- 配置建议 ID 绑定 session/turn/tool call；仅原会话空闲时展示，连接显示可读名称。用户确认后，应用服务重新校验会话、执行状态、模型和思考选项，并在事务中应用。模型不能授予权限、切换安全 profile 或改变正在运行的 Turn；进程退出丢弃未确认建议，不自动重放。

## Alternatives considered

不提高默认工具数量、不让模型直接修改权限或执行中的配置，不引入新导航框架、第二条执行路径或数据库迁移。使用现有工具注册、应用服务和显式确认入口；返回测试以真实系统键及可见状态验收，不用固定睡眠代替状态断言。

## Regression verification

### 主机验证

- 双渠道 app unit：consumer 921 项（4 条件跳过），developer 969 项（4 条件跳过），共 1,882 通过、0 失败、8 跳过。跳过不计通过。
- 双渠道 debug APK、AndroidTest APK、lint、detekt 通过。交付日志：`build/interaction-delivery-build.log`；最后测试夹具构建：`build/interaction-draft-oracle-build.log`。
- 最终格式/静态检查、source gate 与差异检查的运行日志分别为 `build/interaction-final-format.log`、`build/interaction-final-source-gate-r2.log`；差异检查命令为 `git diff --check`。
- 本轮没有修改 Dispatcher、effect classifier 或授权执行逻辑；权限、Provider、终端 ADR 在原主题文件更新。

### API36 定向验证

独占 `Helix_HXA229_Closeout_API36`，API36 / arm64-v8a / 4 GiB。通过 `scripts/with-host-slot.py` 串行运行 `scripts/run-owned-emulator.py`，每轮 fresh/read-only emulator，`--clear-app-data --raw-results`。仅清理测试模拟器，不触碰手机。

共同完整集合：`ConversationComposerDeviceTest`、`GroupedNavigationDeviceTest`、`HierarchicalNavigationDeviceTest`、`ProviderSettingsFormDeviceTest`、`ProviderOptionalKeyDeviceTest`、`ChatSubmissionReceiptDeviceTest`、`MessageRegenerateDeviceTest`、`SessionSearchDeviceTest`、`ChatSessionLifecycleDeviceTest`。developer 额外运行 `ProotTerminalSessionDeviceTest`，从独立终端 Activity 验证无会话启动、连接/结算及长命令租约到期。

| 运行目录（均在 `build/` 下） | 原始结果 | 解释 |
| --- | --- | --- |
| `interaction-api36-consumer-r4/` | 37/37 通过 | 包含历史列表搜索；不是进程重启阶段 |
| `interaction-api36-developer-closure/` | 45/45 通过 | 包含草稿竞争修复后的生命周期和真实终端 |
| `interaction-api36-consumer-closure/` | 41/42 通过 | 新压力夹具过早把上一帧草稿 ID 当成最终选择；保留失败，不改写运行结果 |
| `interaction-api36-developer-delivery/` | 12/12 通过 | 最终 APK：生命周期 5、导航 5、重新生成 1、设置确认 1 |
| `interaction-api36-consumer-delivery/` | 12/12 通过 | 同上；确认修正后的草稿夹具及配置卡可读连接名称 |

最终 12 项使用本轮最后的 app/test APK：压力夹具先通过应用服务确认投影确实指向当前临时草稿，再断言身份稳定且没有数据库行；没有降低身份、不入库、发送/停止或权限边界断言。设置验收包含工具超时不生成建议、建议不自动生效、原会话确认、错误会话/模型拒绝、OFF 思考强度与安全 profile 不变。重新生成通过 UI 点击，验证保留一条用户消息、产生新 Turn、旧回答被 supersede。

每轮 app/test APK SHA-256、API/ABI、runner 输出与关闭状态分别保留在 `artifacts.json`、`device-properties.json`、`instrumentation.txt`、`closed.json`。设备已在各轮结束后关闭。

### 中间失败与修正

- 初始 developer 28/29、31/32：旧断言仍要求操作按钮位于输入框下方、思考强度隐藏；按本轮明确布局要求更新，保留边界与实际点击检查。
- 较早 `interaction-api36-developer-final/`：首次发送等待超时，以及终端无会话等待超时。终端夹具原先启动聊天首页，而首页会自动新建空白会话；改从独立终端 Activity 进入。发送测试统一通过主线程选择会话、验证持久会话和输入内容，并在失败时记录受理/确认/Turn 状态。
- 后续 consumer 的准备草稿超时促使继续检查生产代码，修复上述持久刷新误清草稿与启动恢复覆盖新选择的竞争边界。首次发送超时缺少当时的完整状态快照，不据后续通过断言它与上述问题存在唯一因果关系。
- 弹窗返回曾因 Espresso 等待被遮挡主窗口焦点失败；改用系统返回键。抽屉测试必须先确认抽屉已显示，再验证关闭结果，不能把尚未打开时的返回当成抽屉返回。只增加关闭等待的失败也保留在 `interaction-api36-*-navigation-final/`。
- 中间主机检查发现窗口尺寸 API、格式与方法复杂度问题，已修复后重跑，没有添加 lint baseline。

## Residual risk

这是本轮交互与配置建议的定向验收，不是全产品 device baseline；组合证据不把 consumer 的 41/42 运行改称完整 42/42。本轮没有新的真实 SGLang/云端调用或协议服务端兼容性结论，UI 协议选择测试不替代历史在线兼容性证据。真机安装与验收为 not requested。模型偶发额外调用、历史输出截断、OEM/长稳及发行边界没有由本次测试关闭。

## Related records

- [当前状态](../development/status.md)
- [模式与元数据边界](../adr/permissions/002-review-modes.md)
- [模型与连接](../adr/provider/001-models-and-connection.md)
- [终端与后台任务](../adr/runtime/002-terminal-and-jobs.md)
