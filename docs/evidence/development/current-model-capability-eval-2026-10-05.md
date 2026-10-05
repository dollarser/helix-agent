# 当前模型自主解题实测与修复

日期：2026-10-05。所有者明确授权本轮模拟器、当前真实模型评测及修复后复测；未授权提交或推送，本轮未执行。

## 方法与边界

日常 `Helix_API_36` / `emulator-5554`、API36 arm64、Developer debug，当前配置模型 `Qwen3.8-27B`（OpenAI Chat Completions）。实际 UI 观察与节点动作使用 Shizuku，Root 不可用。本轮不代表 API29/34、普通无障碍单后端、Root、真实手机或其他模型通过。

借鉴 [AndroidWorld](https://github.com/google-research/android_world) 的任务初始化、真实状态评分和清理方法，以及 [MobileWorld](https://github.com/Tongyi-MAI/MobileWorld) 的多步骤组合任务思路。这里是自建的小规模诊断集，并非运行其官方数据集、排行榜成绩，亦未与豆包手机硬件同场比较。[豆包手机助手](https://o.doubao.com/) 的跨应用任务体验只作为产品方向参照。

每个任务创建独立会话、工作目录与合成订单数据，仅允许测试应用和 Helix 包。模型经正式 ChatService/Harness 自行选择工具并执行；测试没有代点目标按钮、代写输出或提供逐步动作脚本。未发送消息、下单或操作真实账号。中文测试验证 `ui.set_text` 的中文内容，不是拼音输入法组成回归；没有截图视觉推理基准或真实第三方 App 完成率结论。

评分同时要求 turn 完成、原始数据不变及目标状态匹配。GUI 的独立只读测试 ContentProvider 验证结果为 `TEST_DONE:你好 Helix` 且完成按钮只点击一次；文件内容由宿主测试代码核对，不采用模型最终回复自报成功。Provider 仅存在于测试 APK，不是产品自动化入口。

## 实测发现和最终修复

1. **屏幕外元素被建议点击。** Shizuku 树中可存在倒置或空的裁剪边界，仍标记 clickable。现在输出 `offscreen`，不生成该节点或其父节点的点击建议，并明确提示先滚动、重新观察。
2. **输入法透明区域误判为遮挡。** API36 Gboard 窗口边界几乎覆盖整个屏幕，但实际可触摸区域只是若干小块。原矩形检查使节点滚动和后续点击返回 `TARGET_CHANGED`，模型随后反复尝试点击、返回、猜坐标。最终对输入法窗口使用平台 `getRegionInScreen` 的真实可触摸区域；其他覆盖窗口继续按矩形保守检查，API29 维持旧边界回退。语义滚动可检查容器内有限的候选可见点，不要求容器中心可用。窗口身份、旋转、节点指纹、范围和 live guard 保持，失败/UNKNOWN 不跨后端重放。没有保留中途试验的“忽略输入法窗口”实现。平台依据：[AccessibilityWindowInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo#getRegionInScreen(android.graphics.Region))。
3. **文件前置条件没有到达模型。** 原始 write 工具说明补充父目录要求后仍反复失败，追查发现会话层 `FileToolArguments` 覆盖了 read/write 描述。现在保留原始执行契约，再追加会话层使用说明，避免两套说明分叉；明确可直接读取已知路径、缺目录先发现并调用 `files.mkdir`。不偷偷创建目录，不改变写入或覆盖权限。
4. **减少无意义动作。** Mobile Use 内置技能与工具说明明确可直接 `ui.set_text`，无需先点击打开键盘；屏幕外节点先滚动，不因重复 `TARGET_CHANGED` 猜坐标。模型仍控制流程，没有新增固定多 Agent 编排或按任务关键词强制续跑。

## 最终模型结果

`region-repeat` 为最终行为实现上的完整复测；键盘额外在 `region-fixed` 单独复测过一次。每次是新的会话、目录和页面。

| 任务 | 独立判定 | 耗时 | 模型调用 | 工具调用 | 失败工具 |
|---|---|---:|---:|---:|---:|
| 中文填写、滚动、完成一次 | 通过 | 15.33 秒 | 8 | 7 | 0 |
| 汇总完成订单，北京 42 / 上海 9，排除取消项 | 通过 | 15.56 秒 | 7 | 6 | 0 |
| 给出错误文件名，定位真实订单并完成汇总 | 通过 | 17.16 秒 | 8 | 7 | 0 |
| GUI 完成后把实际结果写入文件 | 通过 | 26.37 秒 | 13 | 12 | 0 |
| 先点击打开键盘，再完成 GUI 任务 | 通过 | 31.00 秒 | 12 | 11 | 0 |

键盘场景修复前 `final/keyboard`：失败，74.28 秒、25 次模型调用、24 次工具调用，其中 11 次工具失败。最终修复后独立一次为 21.61 秒、11 次模型调用、10 次工具调用、零失败；上述完整回归再次通过。中途仅修正滚动而未修正点击区域的 `keyboard-fixed` 仍失败，已保留记录，不计为通过。

初始 `baseline/combined`：76.77 秒、26 次模型调用、25 次工具调用后失败。普通 GUI 和两项文件任务可完成，但文件任务均先写入失败再创建目录。模型有采样波动，基线 GUI 评分还曾失效，因此不构造“基线总成功率”或宣称普遍速度提升。

最后五项服务端报告的累计输入 token 分别为 121170、81263、95313、215786、193809；输出分别为 884、1020、1162、1597、2068。这是各次调用 usage 相加，包含重复上下文，不等于唯一上下文大小或实际计费额度，未获得缓存计费明细。仍存在多余观察/目录检查和较大上下文开销，不能用少量通过样本证明消费级完成率。

## 评测设施失败记录

- 最早使用普通无障碍 snapshot 核查 Shizuku 完成结果，误判 GUI；最终 Shizuku 观察实际已包含成功标记。该评分不进入通过率比较。
- 改用 instrumentation UiAutomation 读取时与 Shizuku 的 UiAutomation 连接冲突；撤销该方式。
- 测试 APK 独立进程没有目标 APK 的 Kotlin runtime，新加可变闭包与 Kotlin Provider 引发测试夹具崩溃；改为原生数组计数和 Java 只读 Provider 后验证正常。这是测试设施失败，不计为模型解题失败。
- 中断夹具后，已从最近一次日常安装前备份恢复测试修改的 Mobile Use 全局配置键，并逐字核查其他偏好项未变；原会话启动目标、无障碍设置恢复。测试会话和证据保留，未回滚用户数据库。
- 额外通过应用自身 PluginCatalog 清除本轮各 trial 的会话插件选择，包括中断夹具留下的选择；不改其他会话。清理用例不调用模型，见 `cleanup-device.log`。

## 验证与复现

入口：`CurrentModelCapabilityDeviceTest#solveOwnedTask`。必须明确 `helixRealModel=true`、本地 Provider ID、模型、trial 和 case；默认不会调用真实服务。主机驱动为 `scripts/debug/2026-10-05/run-capability-eval.py`，Provider/模型由 `HELIX_EVAL_PROVIDER` / `HELIX_EVAL_MODEL` 输入，不保存凭据；真实服务仍须每个任务的当前所有者授权。

后续审查已加固该驱动：还需 `--allow-real-model`，默认五项含 keyboard；拒绝复用 trial 和未知/重复 case。使用共用 instrumentation 解析器核对唯一指定测试通过，再校验本次 JSON 身份、COMPLETED 和独立评分；失败非零退出。超时先停止被测进程，避免后台模型任务继续运行；缺失统计字段保留 null。只有再次获得当次设备/额度授权后才执行，例如：

```text
HELIX_EVAL_PROVIDER='<local-provider-id>' HELIX_EVAL_MODEL='<model-name>' \
  python3 scripts/debug/2026-10-05/run-capability-eval.py fresh-trial --allow-real-model --serial emulator-5554
```

这份报告上方的模型指标属于原运行，不因驱动改进重新计算成功率或冒称再次实测；新增工具说明边界和错误提示仅通过[后续主机审查](../../bug-fixes/2026-10-05-current-integration-audit.md)。异常中断后的会话插件选择可由 `CapabilityCleanupDeviceTest` 使用精确 trial 清理，不能仅凭 adb 客户端退出认定设备任务已停止。

关键主机命令：

```text
./gradlew detekt spotlessCheck :extensions:mobile-use:testDebugUnitTest :tools:files:test \
  :app:testDeveloperDebugUnitTest --tests com.helix.app.chat.FileToolArgumentsTest \
  :app:assembleDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebugAndroidTest
```

`build/model-capability-eval/verified-host.log`：通过。Mobile Use 196、Files 121、会话文件参数 10 项单测，共 327 项；双渠道 APK、设备测试 APK 和静态检查通过。增加屏幕外节点不能借父节点点击的断言后再次运行 Mobile Use 与静态检查，结果见 `last-host.log`。设备仅安装 Developer，Consumer 是构建验证。

最终日常模拟器已覆盖安装并冷启动，进程存活；本地与设备 APK SHA-256 一致：`940d5ad85c45ce8ca70a53e754958f4f5068ff154b0f3e19e0c9f5282053306a`。中文键盘保留，`show_ime_with_hard_keyboard=1`；未启动第二台模拟器。文档门禁通过（`docs-check.log`），清理测试新增后的编译及静态检查分别见 `cleanup-build.log`、`final-static.log`。

忽略目录中的原始证据：`build/model-capability-eval/{trial}-{case}.json`、instrumentation 日志、`summary.json`、`keyboard-windows.txt`。JSON 包含工具参数、结果、durable 调用统计及 usage；不提交设备数据库、配置备份或完整模型日志。

下一轮最有价值的是增加真实 App 的可重复无账号任务、页面/数据变体与更多随机重复；分别验证视觉路径、普通无障碍、Root 和 OEM，并单独测量上下文/缓存成本及不可用工具的恢复提示。当前 5/5 只证明本页列出的有界样本，不能作为发布或排行榜验收。
