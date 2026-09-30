# Provider 完整使用链路收口

## 范围与基线

2026-09-30，所有者要求完成上一轮确定的主线：Provider 使用链路、回归与验证、文档和候选提交。基线为 `d9bac5019aca63b14d2cee47f8a30ed9091681f4`，工作树包含此前 Runtime F1～F6 与模型管理增量。保留不相关调试脚本，不推送。Project Memory、Mobile Use 视觉、PDF/Office、新 Workflow 等仍是独立候选，没有因本轮收口启动。

设备与真实账号验证为 **not requested**。本轮仅允许主机验证、AndroidTest 编译及 APK 构建；下列设备旅程是待所有者执行或明确授权后的验收入口，不记作设备通过。

## 已实现的具体修复

| 问题 | 当前实现与后置条件 |
| --- | --- |
| “保存并用于当前会话”把异步入队当成功 | `requestSessionModelSelection` 返回等待实际提交的 receipt。候选保存与会话应用分别反馈；BUSY、会话改变、来源/模型不可用和失败都有独立结果，只有 APPLIED 关闭模型管理。 |
| 选择与发送顺序 | 原同步 UI 入口立即入队，不把入队延后到另一协程；模型与 reasoning 默认值在一个数据库事务中更新。对话框绑定打开时的会话；旧回调不能换掉新会话模型。 |
| 基础模型失败牵连同来源其他模型 | 来源连接、精确模型基础生成与可选能力有不同证据。模型 A 的普通生成失败只更新 A；认证或来源目录失败更新来源，不再以“是否基础模型”判断影响范围。 |
| 认证目录成功伪装模型生成成功 | 来源级订阅连接保留认证目录短路；模型详情的“测试基础生成”显式执行短合成请求。界面披露测试的额度/设备资源影响，恢复前台不自动执行生成。 |
| 可选能力失败与基础生成混淆 | `verifications` 与 `generations` 独立保存。基础生成重测不抹掉工具/视觉等可选能力失败；未知不外推默认模型的工具能力。 |
| 登录后回主应用仍保留旧状态 | 主页面 resume 和操作边界读取 Runtime 公共账号状态。公开值只有状态和随机登录 revision，不含账号名、凭据或其哈希。 |
| 账号更换后旧目录/能力继续有效 | 新登录、退出或凭据不可读废止旧探测票据、目录、模型证据和已检测窗口；基础能力快照也清空，防止从旧默认快照复活。会话、候选和手动窗口偏好保留。 |
| 普通 token 轮换导致误失效 | 刷新保留同一登录 revision；重新登录生成新 revision。暂时查不到 Runtime 时标不可用并保留记录，不能伪报已退出或删用户偏好。 |
| 迟到 token 刷新覆盖新登录或复活退出 | 刷新捕获凭据快照，写回核验登录 revision 与旧快照。迟到成功不覆盖先完成的轮换；迟到永久失败不删除较新的凭据。 |
| 正式执行仍按 Provider 基础模型创建适配器 | AgentLoop 先取得已冻结的实际模型，再构造同模型适配器。BoundModelProvider 检查每次请求的精确目标与当前账号/连接绑定；普通请求、工具回填、压缩和继续请求不得借用默认 A 的适配器目标。 |
| 状态检查自身阻塞 | 复用现有有界、无等待队列的 IPC 调用原语，以单个线程和有界等待读取订阅状态；超时只产生不可用，不证明远端退出或扩大执行许可。 |

保留已有三类模型管理、多选、显式空选择和目录隔离。清空候选不终止当前 Turn，不改写历史，也不等于撤销账号或工具权限。当前任务仍不能在活动任务中静默换模。

## 主机与设备测试入口

新增主机断言由 `SessionModelSelectionReceiptTest`、`ProviderChainStateTest`、`BoundModelProviderTest`、`CliAccountStateTest`，以及原 `ProviderConnectionCheckTest` 和 `CliSubscriptionCredentialVaultTest` 的新增用例承接。覆盖队列提交回执、旧会话拒绝、模型失败隔离、账号身份与偏好独立、实际模型请求不漂移、取消传播、过期刷新和严格公共状态解码。

新增/增强设备用例：

| 测试类 | 范围 | 本轮执行状态 |
| --- | --- | --- |
| `ProviderModelsIntegrationDeviceTest` | 真 Room/Service：选择不改历史；A 失败保留 B；账号更换清证据；迟到 probe 拒绝；factory 与请求均使用 B | 编译验证，不执行设备 |
| `ProviderModelSelectionFeedbackDeviceTest` | 真实偏好服务加可控应用回执：BUSY 时保留窗口与原因，APPLIED 后关闭 | 编译验证，不执行设备 |
| `SessionModelDeviceTest` | 真实会话选择和下一次请求，活动任务 BUSY、错误会话 SESSION_CHANGED | 编译验证，不执行设备 |
| `ProviderTurnJourneyDeviceTest` | 真实 ChatService/AgentLoop/loopback 协议：选 B → 只读工具发现 → 工具回填 → 手动压缩 → 继续；逐次核验 model=B | 编译验证，不执行设备 |
| `ProviderModelPickerDeviceTest` / `LocalModelDialogDeviceTest` | 复用候选筛选、隐藏当前项和本地安装 UI 边界 | 编译验证，不执行设备 |

上述旅程使用合成模型或本机内的回环 fixture，不代表模型真实规划效果；公共模型服务、订阅或真实本地权重都没有调用。

## 最终主机验证

2026-09-30 收尾复验退出码 0、`BUILD SUCCESSFUL`：1013 个任务中 14 executed、999 up-to-date。常规 `test`、detekt、Spotless、两渠道 lint、App 与 QuickJS/CLI Client/PRoot App 的 AndroidTest Kotlin 编译、两渠道 Debug APK 和 AndroidTest APK 均通过。复用兼容结果不等于本次强制重跑全部测试。没有运行设备或真实账号。

```bash
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:compileConsumerDebugAndroidTestKotlin :app:compileDeveloperDebugAndroidTestKotlin \
  :runtime:cli-client:compileDebugAndroidTestKotlin :runtime:quickjs:compileDebugAndroidTestKotlin \
  :runtime:proot-app:compileDebugAndroidTestKotlin \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --continue --configure-on-demand --no-configuration-cache --console=plain
```

当前报告目录计数和 APK SHA-256 由 `scripts/debug/2026-09-30/summarize-subscription-closeout.py provider-chain-closeout` 保存到 `build/provider-chain-closeout-2026-09-30/host-summary.json`。此前 Runtime F1～F6、模型管理与本轮链路共同组成收尾候选；只提交相关源码/测试/文档和证据统计脚本，一次性调试脚本不纳入。后续订阅扩展是另一提交，不追溯扩大本记录验收范围。

## 手动验收与任务效果基线

执行前固定 commit、APK SHA-256、渠道和 Android/API；使用专用会话/Workspace，不清理用户数据。先运行上述合成设备用例，再按当前授权选一个真实模型作效果样本。沿用[内测清单](../../development/internal-pilot.md)，不建立另一套生产遥测或后台任务。

| 固定旅程 | 独立通过条件 |
| --- | --- |
| 配置并多选 | 目录有多个模型，候选仅有勾选项；清空并重开仍为空；刷新不全选。 |
| 保存并应用 | 空闲时模型改变且 reasoning 重置；活动任务只保存偏好，明确未应用；目标会话改变时不修改另一会话。 |
| 多模型失败 | A 的生成失败不禁止 B；账号鉴权失败禁用来源；可选能力失败不显示为账号退出。 |
| 登录生命周期 | 退出再返回主应用显示退出；重新登录清除旧账号证据而保留偏好；正常 token 刷新不清空已选项。 |
| 完整 B 旅程 | B 在请求记录、回填、压缩和继续中始终一致；源配置 A、其他会话和已冻结 Turn 不被改写。 |
| 停止与重开 | 使用已有 Runtime 故障 fixture；不重放原动作；ACK 重试不重新生成；取消请求与物理退出分开。 |
| 独立产物检查 | 按既有内测 CSV 2/3/5 → 2/4/5 任务核验合计 10 → 11，而不是看模型说“完成”。 |

每项记录成功/失败/未执行、首次独立完成、介入次数、端到端耗时、实际产物、token/调用次数及失败后可恢复性；没有执行就保留未执行。不能用一次成功估算总体成功率，也不声称性能提升百分比。

## 边界与停止条件

本轮修复的是本地提交、证据归属、登录生命周期与请求目标；不是跨进程/远端服务的全局事务。账号变化检查与远端受理之间仍可能存在时序窗口，已被服务端接受的请求不能假定撤销；不把状态检测或 token 轮换等同于撤回外部效果。内核冻结、OEM/Doze、真实账号、物理资源峰值、断电与完整 Runtime 矩阵仍以各自实测证据为准。

达到源码/主机门禁、测试制品、文档及提交的范围后转入指定设备验收或用户内测，不继续借收口启动全局架构或所有候选功能。
