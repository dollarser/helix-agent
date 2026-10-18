# 内置模型接入验收记录

任务：[HXA-307](../../development/tasks/HXA-307.md)。基线为 `main` / `00e529b9`；接续本任务已有未完成的 Provider/默认选择代码，保留全部既有工作。验证后仅本地提交，不推送、发布或更改已发布资产。

## 本地实现

固定内置 Qwen 来源、公开 Client ID 传输和不可编辑配置；新安装预选、已有明确选择与会话保持；复用图片与工具 SSE；连接事实与声明能力分开。401/429/502/504 提示按原调用身份展示，禁止自动重试和付费回退。设置与新空白会话披露云端数据范围，共享额度不显示成个人额度。

## 缺陷与修正

本轮编译检查发现新增 provisioning 的测试枚举需要补齐；定向测试修正了 504 超时映射的旧断言。边界审查还发现规范化地址显式带 `:443`，已允许同一固定端点的规范写法并拒绝其他端口、路径、查询及来源。失败日志保留于 `build/hxa307/`；未放宽权限、地址或能力检查。

进一步验证了传输层隐藏重试：本地 `503 + Retry-After: 0` 在只关闭连接重试时仍产生额外请求，新增断言修复前为预期 4、实际 5。内置请求体增加 one-shot 语义，阻止 HTTP follow-up 重发而不改写状态/响应头；修复前 XML 与日志独立保留。普通自配 API 默认传输行为不变。错误展示只给内置调用快照增加来源字段，不改写其他来源的快照格式或历史 Turn 身份。

其他门禁曾发现固定内置模型与“所有来源都须手选”的旧测试断言冲突、简体中文资源漏项及描述中模型名触发 Android TypographyDashes。现已按新契约替换断言、补齐三套语言资源，并从描述移除重复模型名；单独的模型标识仍保留准确拼写，没有关闭 lint 或放宽检查。

## 验证结果

以下结果来自本轮提交前冻结的同一生产与测试输入；后续本地提交固定这些已验证源码与文档，不把 APK 内原基线元数据当作新提交的干净构建。输入清单、校验结果、原始日志、失败 XML 和汇总保存在忽略的 `build/hxa307/`；未将旧发布 APK 的验收转作本次证据。

| 检查 | 本轮结果 | 边界 |
| --- | --- | --- |
| `:app:testConsumerDebugUnitTest` | 1301 通过、0 失败、4 条件跳过 | 包含新增内置模型、默认选择和真实 HTTP socket 夹具 |
| `:app:testDeveloperDebugUnitTest` | 1425 通过、0 失败、4 条件跳过 | 两渠道重复的方法分别计数，不是 2726 个独立业务场景 |
| `:provider:api:test` | 118/118 通过，零跳过 | 固定目标/公开标识与 HTTP 状态映射 |
| `:core:model:test` | 163/163 通过，零跳过 | 新 provisioning 与 HTTPS/无凭据契约 |
| `BuiltInModelTransportTest` | 8/8 通过，零跳过；已包含在应用套件中 | 分段 SSE、图片、工具及回填、401/429/502/503/504、重定向、取消与半截工具流；没有公网调用 |
| `:app:assembleConsumerDebugAndroidTest`、`:app:assembleDeveloperDebugAndroidTest` | 编译通过 | 新增 Room 3 项、Compose UI 2 项的设备执行见下表 |
| `./scripts/check-all.sh --all` | 退出 0 | 276 项 source 测试、文档/ADR/i18n/秘密扫描、Spotless/Detekt/lint、全仓 JVM、双渠道 Debug/Release/R8、Release smoke 测试 APK 编译、依赖锁和制品边界通过 |
| 日常 API36/ARM64、两渠道 Debug 的 `BuiltInModelStorageDeviceTest` 与 `BuiltInModelUiDeviceTest` | 每渠道 5/5 通过，零失败/跳过 | 临时 Room 数据库和纯 Compose 夹具；不是完整真实服务验收 |
| 同源 R8 两渠道冷启动与进程重启 | 每渠道 2/2 通过 | 原会话恢复、已有 Provider/model 绑定、界面模型名及安装身份一致 |

应用每渠道 4 条件跳过分别是公开配置、公开 MCP 只读查询、QwenWork 包、WorkBuddy 包；本次没有启用其外部输入变量，未修改或删除用例，不借用先前通过记录补算本轮结果。使用现有 JUnit 汇总脚本读取当前模块报告，含跳过的应用汇总如实标为 `INCOMPLETE`；它不表示主机必选检查失败。

完整门禁日志为 `build/hxa307/host-all-verified.log`，双渠道测试 APK 编译日志为 `build/hxa307/android-test-final.log`。成功构建的双渠道 Debug、未签名 Release 及本轮 Debug AndroidTest APK 身份保存在 `build/hxa307/artifacts.sha256`；源码输入复核一致，未把目录中旧 Release AndroidTest APK 纳入本轮编译证据。

## 日常模拟器追加验收

Owner 明确要求实际执行上述 5 项设备用例并验证保留已有选择的重启。使用现有 `Helix_API_36` / `emulator-5554`，没有创建新实例或清理应用数据。复用本地保数据安装检查及 `scripts/instrumentation_junit.py`；签名和 Room schema 一致、没有活动 Turn 后才执行 `adb install -r`。两渠道命令均为 `adb -s emulator-5554 shell am instrument -w -r -e class com.helix.app.provider.BuiltInModelStorageDeviceTest,com.helix.app.provider.BuiltInModelUiDeviceTest <package>.test/com.helix.app.HelixAndroidJUnitRunner`，其中 `<package>` 分别为 `com.helix.agent` 和 `com.helix.agent.developer`。

Room 夹具只创建/删除独立临时库；界面夹具没有真实服务请求。每次 APK 替换前后逐表摘要一致；首次实际启动只增加内置 Provider，原生插件按既有注册流程递增 revision。原 Provider 全字段、会话/消息/调用及其他表保留；插件除 revision 外的内容、启用和默认选择一致。既有自配模型仍绑定原会话，冷启动和再启动截图/XML 中的模型名一致，没有被内置来源替换。测试运行器的语言设置已恢复，两个测试包已卸载，日常模拟器保留本轮 R8 包并停在 Advanced 供手测。此处使用主机默认多 ABI 构建在 ARM64 上运行，没有制作新的单 ABI 发布资产。

首个 Standard 批次设备测试已 5/5，但后续临时保数据脚本错误地要求插件 revision 也不变而中止。经核对 `PluginRegistry.register/publish`，并将新记录的两次 revision 增量还原后，完整表摘要与原值相同，证明没有其他字段变化。收紧到“仅允许既有原生插件 revision 递增，其他字段仍严格一致”后，两渠道完整批次及 4 次启动全部通过；首批失败日志保留，不混入最终 10 项统计。设备阶段没有发现需要修改生产代码的新缺陷。

最终原始 instrumentation、JUnit、截图、安装摘要及选择核验位于 `build/hxa307/device-final/`；初次检查和修正依据保存在 `build/hxa307/device/`。设备全程没有发送模型任务，`model_calls` 表未变化；这里不把已有会话中的历史 Qwen 输出计作本轮模型调用。

## 交付与剩余边界

新增实现集中于 `BuiltInModel` / `BuiltInModelRegistration` / `BuiltInModelProvider` / `BuiltInModelRow` 及 `PublicClientWireClient`，现有 Factory、ProviderService、选模存储、错误投影和资源接入；没有新增依赖、Room schema 或第二套 Agent 执行循环。Provider ADR、当前状态、HXA 和双语 README 同步，README 明确已发布 v0.0.6 不含此接入。

真实网关联调为 **pending**：等待 Owner 确认公开 Client ID 白名单登记。登记后需验证公网 TLS、内置模型文本/图片与工具结果的实际消费、真实错误/取消及完整 Agent 闭环。新安装默认选择已有独立 Room 夹具证据，但未清空日常数据进行全新安装旅程；保数据启动/重启已按上节实际执行。真机不在范围。JDK loopback 夹具不代表 SGLang、图片理解质量或跨用户限额实测通过。无历史 Key 回退、推送、发布、tag/资产修改或用户数据清理。
