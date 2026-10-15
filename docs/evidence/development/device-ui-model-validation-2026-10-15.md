# 真机、真实模型与交互整理

日期：2026-10-15。Owner 在 APK 体积优化之后明确要求真机、真实模型测试、修复和减少非必要界面打扰。本轮不提交、发布或推进 HXA-286～294 架构重组。此前 APK 主机验证见[独立记录](apk-size-optimization-2026-10-15.md)，不以旧 Debug 或模拟器结果代替当前运行。

## 环境与隔离

- 当前源码基线为 `348632a8` 加保留的未提交工作；开始时记录分支、HEAD、状态和既有 diff。本轮只整理相关 UI、测试与运行器，未覆盖并行架构文档工作。
- 指定 OnePlus 6T，Android 14 / API34、ARM64；未操作另一台已连接模拟器。原日常 Standard/Developer 数据库仍为开发期版本 1，但 identity 与当前源码不同，覆盖安装有数据重建风险，因此使用独立 applicationId 的两渠道测试包，未更新日常包。
- 真实服务由 Owner 指定，模型目录返回 `Qwen3.8-27B`，使用 OpenAI Chat Completions。凭据不写入源码、报告或应用长期配置；测试使用本机回环转发和指定真机的 ADB reverse，限定上游地址与路径，临时凭据文件在转发启动后移除。此方式验证真实推理与 Helix 协议/工具回填，但不证明 Android 直连上游域名的 TLS/鉴权路径。
- 对外只发送生成的文件、文档与合成测试页面，不使用真实账号或日常应用数据。权限、插件选择和无障碍设置按夹具范围恢复。Release 隔离候选采用本机测试签名、启用 R8、关闭 debuggable；不是正式发行身份。

## 交互改动与设计依据

参考 [Claude Code 交互说明](https://code.claude.com/docs/en/interactive-mode)、[权限模式](https://code.claude.com/docs/en/permission-modes)及 [Codex App](https://openai.com/index/introducing-the-codex-app/)：常用操作保持直接可见，详细状态按需展开，执行权限仍由用户明确选择。借鉴信息呈现方式，不复制实现，也不把桌面交互照搬到手机。

| 发现 | 实施与边界 |
| --- | --- |
| 尚无上下文用量时，输入栏问号像持续警告 | `ConversationComposer` 在未知时收起环形指标；“更多”中仍可查看上下文详情。已有用量仍显示，估算/未知语义不变 |
| Provider 常用操作与上下文容量、能力探测、手动视觉声明混排 | `ProviderRow` 将后三项放入默认收起的高级选项；模型选择、连接测试、编辑和删除仍直接可用；展开不会触发请求或改变能力，明确执行的探测结果仍可见 |
| 用户已点击插件配置，还要确认一次进入设置 | `ConnectorSessionPanel` 直接导航到明确的配置入口；保存后返回原会话，仍需明确勾选插件，不产生授权；异步选择失败的解释仍保留 |
| 展开已处理工具记录时，历史审批仍以“允许此操作？”开头；批准文案又固定附带“执行中” | `ApprovalCardScreen` 只有 PENDING 使用询问标题，已处理记录使用操作标题；中英文“已批准”只表示授权事实，执行状态继续由工具结果提供。审批按钮和作用域不变 |
| 设备基线运行器可能在恢复失败后清空应用数据，且失败汇总仍退出 0 | 改为进程重启和只读健康探测，保留数据；只有非空、无缺失、全 PASS 才退出 0。增加隔离 applicationId 参数及失败/恢复回归 |

Mobile Use 保持插件边界；本轮未改变会话授权、运行时凭据、撤销或恢复契约。

## 验证与审查记录

所有原始结果、失败尝试、测试包、mapping 和合成截图位于忽略目录 `build/device-ux-2026-10-15/`。临时驱动位于 `scripts/local/debug/2026-10-15/`。以下数量按实际测试方法统计，针对同一方法的重复运行不累计成新增覆盖。

| 检查 | 当前结果与范围 |
| --- | --- |
| 双渠道 UI/基础运行时 Debug | Standard 13 类 / 45 项、Developer 16 类 / 50 项通过，包括最终 Mobile Use 配置/注册 4 项复测和两渠道各 2 项新增中英文历史审批检查；无跳过。覆盖输入栏、上下文、模型选择、权限/审批布局、运行控制、设置、本地模型错误边界；Developer 另含实际 XZ RootFS 安装和 PRoot/CLI 私有进程 Binder |
| 最后一次输入栏修改 | 两渠道各 13 项重新运行通过，属于上行重复覆盖 |
| 原生文档与脚本 Debug | 两渠道各 16 项通过：PDF/DOCX 实际读取/渲染、隔离进程，QuickJS JNI、文件/网络、超时、取消、授权撤销及执行进程退出；不等于 R8 Release JNI 全矩阵通过 |
| 真实模型配置与文件任务 Debug | 两渠道各 1 项连接/生成/能力探测、1 项文件工作流通过。工作流实际发现 Skill/工具、从缺失输入恢复、计算并保存 East=140 / West=100 / total=240，读回和独立文件断言通过 |
| 真实模型帮助 Debug | 1 项通过：发现内置 helix-docs，读取 manifest/index/章节、引用 revision，并对未记载问题明确界定 |
| 真实模型 Mobile Use Debug | 2 项通过：无障碍后端操作合成页面，中文输入、滚动、完成一次、验证实际状态；默认返回原会话和明确停留分别核验窗口焦点 |
| 真实模型文档 Debug | DOCX 通过；API34 PDF 发现夹具漏处理图片告知，修复后 41 秒通过，实际查看两页图像、核验答案且源文件未变。两次 240 秒超时仍保留 |
| R8 Release 真机 | 两渠道 ARM64 隔离候选实际启动通过；Standard 真模型发现/读取帮助 manifest/index 并返回版本；Developer 真模型在一次性审批后执行隔离 QuickJS，工具结果独立显示 `42`。Developer 删除自有 Runtime 后完整解压安装 2,692 个成员，再经 UI 私有进程验证通过。最终措辞候选再次安装，两渠道启动/已有真实结果显示通过，Developer 展开记录仍能独立查看 `42` 且未出现重复审批请求 |
| 主机 | 最终 `spotlessCheck detekt :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest`、两渠道 Debug/Release lint 和四 APK 构建通过；恢复默认输出后的 Debug/Release 制品边界通过。App JVM 共 2,581 项，2,573 通过、8 条件跳过、0 失败；跳过为未启用外部 Connector 验收及未提供本地样本，两渠道重复，无新增跳过。`./scripts/check-all.sh --source` 的 245 项 Python、文档、37 份 ADR、三语言资源和秘密扫描通过 |

失败与修复保留：

1. 旧布局断言依赖未知上下文环；改用明确用量夹具保留宽度/布局断言，新增未知时可从更多查看的独立检查。Provider 上下文测试先展开高级选项，不删除原保存和按模型隔离断言。
2. 隔离测试 APK 的两个 Provider authority 原先写死日常包名；改用 applicationId。Mobile Use UI 旧夹具仍期待禁用复选框或额外确认，按当前可导航的交互契约修正，仍断言未选择和未授权。
3. instrumentation 启动会强停目标并断开无障碍；在启动后重新连接自有测试服务，恢复原系统设置。首次使用须知未完成也曾阻挡真实旅程；完成隔离安装的须知后再测，模型尚未执行的失败不记作模型结果。
4. 临时模型夹具最初未接入 AGP 的 Kotlin sourceSet，修正接线后重新编译并通过。临时夹具不进入产品实现。
5. 并发 R8/lint 耗尽默认 4GB Gradle 堆，故障进程遗留构建锁；停止已确认的故障进程，以 `--no-parallel --max-workers=2 -Dorg.gradle.jvmargs='-Xmx8g -Dfile.encoding=UTF-8'` 重跑通过，未删除缓存或跳过门禁。
6. 完整现有 AndroidTest 源集依赖 Debug 专用 Activity/Service，无法原样编译为 Release 测试 APK。本轮采用 Release 外部 UI 检查，不向 Release 加入调试夹具或扩大 keep 规则；Debug instrumentation 与 Release 运行证据分开。
7. 文档真实模型测试原先对所有 API 强制要求 `documents.read`。API34 PDF 无原生文本提取，应验证两页 `documents.view`；已修正平台分支，保留独立答案及源文件不变断言。超时另由图像发送告知未响应造成：普通工具图片继续要求内容/目的地绑定的告知，FULL_ACCESS 不代替这项许可。夹具仅响应自己生成文档的会话，修复后真实视觉复测通过；不是服务延迟的证据，也未扩大产品授权。
8. Release 外部 UI 检查实际展开 QuickJS 已完成记录，发现审批标题仍是请求式文案。补中英文四种已处理状态检查，不将批准误称为执行事实。真实模型/Runtime Release 证据与最后的文案构建分开记录，最终候选复验已有历史记录展示。

## 改动与剩余边界

后续审查更正：本轮运行器只移除了崩溃恢复中的清库，每个测试类前仍残留 `pm clear`；后续[预览优化](preview-polish-2026-10-15.md)移除该路径并统一保留数据的进程停止准备。此前执行针对自有隔离包，不能据恢复路径修复声称当时运行器所有路径均不清库；日常应用未覆盖的证据不变。

生产改动在 `ConversationComposer.kt`、`ContextWindowIndicator.kt`、`ProviderRow.kt`、`ConnectorSessionPanel.kt`、`ApprovalCardScreen.kt` 及三份语言资源。相应 AndroidTest 覆盖未知上下文、Provider 展开、配置导航和历史审批；测试 Manifest、Mobile Use 后端夹具及文档平台/图片告知流程单独修正。维护的运行器、Python 回归和说明位于 `scripts/device_baseline/`、`scripts/tests/test_device_baseline_runner.py`。

具体审查入口：

- 生产 UI：[输入栏](../../../app/src/main/kotlin/com/helix/app/ui/ConversationComposer.kt)、[上下文详情](../../../app/src/main/kotlin/com/helix/app/ui/ContextWindowIndicator.kt)、[Provider 行](../../../app/src/main/kotlin/com/helix/app/provider/ui/ProviderRow.kt)、[插件会话面板](../../../app/src/main/kotlin/com/helix/app/connector/ConnectorSessionPanel.kt)、[审批卡](../../../app/src/main/kotlin/com/helix/app/ui/ApprovalCardScreen.kt)；语言资源为 `app/src/main/res/values{,-en,-zh-rCN}/strings.xml`。
- 共有设备测试：`ComposerInteractionsDeviceTest.kt`、`ConversationComposerDeviceTest.kt`、`ProviderContextDeviceTest.kt`、`ProviderModelPickerDeviceTest.kt`、`ApprovalLayoutDeviceTest.kt`，均在 `app/src/androidTest/kotlin/com/helix/app/ui/`。
- Developer 测试：[Manifest](../../../app/src/androidTestDeveloper/AndroidManifest.xml)、`plugin/` 下的 `MobileUseBackendFixture.kt`、`MobileUsePluginRegistrationDeviceTest.kt`、`MobileUseSelectionSetupDeviceTest.kt`，以及 `provider/SglangUiSmokeTest.kt`、`documents/DocumentModelDeviceTest.kt`；其中旧 `SglangUiSmokeTest` 仅更新高级选项导航并编译，其旧 Ollama/HTTP 配置旅程未运行，不计为通过；本轮真实模型使用上表专用旅程。
- 维护脚本：[运行器](../../../scripts/device_baseline/run.py)、[说明](../../../scripts/device_baseline/README.md)、[Python 回归](../../../scripts/tests/test_device_baseline_runner.py)。其余 APK 优化和架构规划脏文件沿用既有任务，不作为本轮新修改。

设备主机门禁、Debug 运行、R8 Release、真实 Provider 和 FFmpeg/PRoot 媒体端到端独立记账。正式签名、覆盖升级/数据迁移、其他 OEM/API/ABI、Mobile Use Root/Shizuku 后端、长稳及媒体端到端不由本轮小样本推导通过。R8 的完整 Mobile Use/本地模型权重/文档矩阵仍未执行，不能继承 Debug 结果。PRoot 的安装/握手通过不等于真实媒体任务通过，也未测量安装峰值内存。

临时模型转发已停止、ADB reverse 已移除、凭据文件不存在；本轮文本日志/配置检查未发现该凭据格式。四个自有隔离应用/测试包已卸载，自有设备临时 UI 文件已清理；日常两渠道数据库 identity 和会话计数与基线一致，无障碍设置恢复原值。未提交、推送或发布。

最终测试签名 APK 和对应 mapping 位于 `build/device-ux-2026-10-15/isolated-release/`；实际模型与 Runtime 旅程使用的文案修复前 Release 冻结在 `isolated-release-model-tested/`，最终版本另做启动与持久结果展示复验，不混写为重新调用模型。原始日志分别为 `approval-host-final.log`、`approval-release-final.log`、`artifacts-closeout.log`、`release-artifacts-closeout.log`、`source-gates-final.log` 及各设备目录；去重统计为 `final-summary.json`，清理断言为 `cleanup.json`。普通 `app/build/outputs/` 已恢复默认包名/双 ABI 输出。
