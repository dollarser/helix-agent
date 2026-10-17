# 预览版交互、帮助与重构前取舍

日期：2026-10-15。Owner 明确预览版不需要数据保留，要求实施上一轮其余优化，并分析重构前是否应补功能。本轮保留既有 APK 优化、UI 与架构文档改动，不提交或发布，不实施 HXA-286～294，不重开已结案账号、提前结束专项或长稳。

## 决定与实现

- 预览版不承诺旧数据保留；不兼容开发库仍重建，数据库外文件范围不变。同步 ADR-PLATFORM-001、HXA-122、README、status 和发行条件，移除预览的保数据前置，不自动清理日常安装。
- 模型选中后关闭选择器，当前模型的推理入口仍在选择器内。异步保存的真实选择仍由现有会话服务决定；测试保留延迟/拒绝切换、实际模型和推理归属断言。
- Runtime 成功/提示使用普通颜色，错误才使用错误颜色；返回管理页后刷新只读可用状态并清除过期跳转提示，不自动绑定或验证运行环境。文案改为就绪、更新、确认删除等实际动作；保留最终删除确认与工作区边界。
- `helix-docs` 八个主题增加中文索引和章节，仍通过原有按需资源读取与内容哈希校验。安装宿主提供版本、编号、渠道、applicationId 和 debuggable；缺失为 null，移除固定版本和通用签名断言。文档 revision 表示正文内容，整个 Skill 快照仍覆盖 manifest，因此不同宿主身份不会共享错误快照。
- 增加按需启用的 `tests/release-smoke` 黑盒测试宿主和构建入口，从独立进程/UID 操作目标 Release；不引用生产内部类或 Debug 专用 Activity/Service，不增加生产 keep 规则。运行器要求显式 serial 和新的隔离包名，校验 APK 身份及 non-debuggable，复用严格 JUnit 解析；不清库、不启动设备、不调用外部模型，退出清理自有安装。基础 UI 冒烟不代替 Mobile Use 生命周期、JNI、真实模型或媒体端到端。
- 审查旧设备运行器发现：前轮仅移除了恢复路径清库，每类测试前仍调用 `pm clear`。本轮移除剩余调用，准备和恢复都只停止所选进程，并加回归断言。测试夹具仍可能修改自身目标数据，所以本轮继续只使用新隔离包；不能把预览不保数据解释为允许测试清理日常应用。

参考 [Claude Code 模型配置](https://code.claude.com/docs/en/model-config)将模型与 effort 视为不同选择；Helix 保留手机上的显式入口，不引入自动切换策略。[Skill 官方说明](https://code.claude.com/docs/en/skills)支持指导和参考资料按需加载，本轮沿用 Helix 既有资源读取，不把中英文帮助全文塞进系统提示。[Android UI Automator 文档](https://developer.android.com/training/testing/other-components/ui-automator)支持从独立进程检查收缩后的 Release；本轮测试宿主复用已锁定 Android 测试依赖及平台 UiAutomation，不升级到预览依赖，不进入产品依赖。参考资料只支持设计取舍，不证明 Helix 验收通过。

## 验证与剩余范围

来源为 `main` / `348632a8979467eaf83e300c07bef7cad67fba5c` 的 dirty 工作区；既有 APK、交互与架构方案改动均保留，不声称本轮独立提交可重现全部内容。原始状态、diff、命令与日志保存在忽略目录 `build/preview-polish-2026-10-15/`。下列数字只计本轮，设备为 Owner 指定 OnePlus 6T / API34 / ARM64；没有启动或使用模拟器。

| 验证 | 当前结果 | 证据与准确范围 |
| --- | --- | --- |
| 完整主机门禁 `./scripts/check-all.sh --all` | passed，退出 0 | `final-gates.log`；Spotless、Detekt、双渠道 Debug/Release lint、构建及制品边界，42 份依赖锁校验通过。JVM 报告共 5,922 项：5,914 passed、8 skipped、0 failures/errors；含既有 spike 和缓存命中，详情见 `host-summary.json` |
| 最终源码与文档门禁 | passed，退出 0 | `final-source.log`；250 项 Python 回归，889 份 Markdown、277 个 HXA、37 份 ADR、2,298 个三语言键及秘密扫描通过；包含最后移除清库的回归 |
| 最终默认身份构建与静态复核 | passed，退出 0 | `final-debug-restore.log`、`final-artifacts.log`；新增 Runtime 测试编译、Spotless/Detekt，以及恢复后的两渠道默认 Debug APK/AndroidTest 和组件/渠道边界通过 |
| 主机跳过项 | 8 skipped，未算通过 | 两渠道各 4 项外部 Connector / 用户提供归档验收；本轮未提供这些可选输入，也未重开账号测试 |
| 两渠道 Debug AndroidTest APK | 编译 passed | `debug-isolated-build.log`；编译与下面实际执行分别记账 |
| 真机 Debug：模型选择、会话实际模型、Runtime Standard 入口 | 每渠道 9 passed，0 skipped | `debug-consumer/`、`debug-developer/`；包含异步延迟/拒绝切换及推理归属，所用模型服务为本地夹具 |
| 真机 Debug：helix-docs | Developer 1 passed，0 skipped | 实际包身份、中文章节、内容哈希、未知路径拒绝；`debug-developer/` |
| 真机 Debug：Runtime 就绪与恢复 | Developer 3 passed，0 skipped | `debug-runtime/`、`runtime-device.log`；真实 RootFS 安装/验证、缺失验证记录后的显式修复、Activity 停止再恢复后的只读状态刷新。成功消息不标记为错误，返回页面不自动重建验证记录 |
| 真机 R8 Release：独立黑盒宿主 | 每渠道 2 passed，0 skipped | `release-consumer-final/`、`release-developer-final/`；真实启动、设置导航、返回和实际 About 版本，所有自有安装均清理 |
| R8 中文资源 | 每渠道 9 个资源完整 | `isolated-release/resource-verification.json`；8 篇中文主题＋索引，APK 内 UTF-8 正文和启动资源加载成功 |

Release 真机目标及实际通过的独立测试宿主 SHA-256 保存在每次运行的 `result.json`，以该原始证据为准。结束后归档的重建宿主 `isolated-release/test-host/release-smoke-debug-androidTest.apk` 只通过编译，未再次安装，不能冒充先前实测的二进制。均为隔离测试身份与测试签名，不是正式发布安装包。`isolated-release/apk/androidTest/` 中保留的是早期失败的同进程测试 APK，复跑应按下文重新构建独立宿主。

上述真机共 26 项实际通过、0 skipped：Debug 22 项、R8 Release 4 项；不与前轮 127 项或真实模型 9 项相加。Release 运行器逐包确认卸载；Debug 两轮退出时所有自有应用/测试宿主的卸载均返回 `Success`，日常应用没有更新、清库或删除。随后额外枚举安装包时设备已断连（`device not found`），该次独立终检未完成，不写成通过；不影响此前实际执行及卸载结果。

失败尝试保留：直接复用目标 app 的 Release instrumentation APK，运行时因收缩后的共享依赖缺少 `androidx.tracing.Trace` 崩溃；没有用全局 keep 或忽略失败解决。改成独立测试宿主后真实执行通过。初版 accessibility `ACTION_CLICK` 在该机不稳定，改用当前语义节点边界注入触摸，仍校验目标应用与实际页面，未放宽成功断言。早期受沙箱限制的 Gradle 缓存/ADB 操作没有形成通过结论，获准在主机执行后重新验证。

审查记录：生产变更保持插件、权限与持久状态边界；运行器验证错误 APK、已有安装、日常包名和 shell 字符串均在设备写入前拒绝，保留超时日志和清理失败；继承的架构文档末尾多余空行只作一行门禁修复，未代写其他所有者的架构设计。

完整 R8 Mobile Use、Root/Shizuku、本地模型权重、文档/媒体端到端与资源峰值按实际后续执行范围独立记账。预览不保数据不削弱当前版本的会话隔离、授权持久化、取消、UNKNOWN 与不重放契约。

本轮没有调用真实 Provider、安装本地模型权重或复跑长稳；前轮真实模型/文档/Runtime 的证据仍见[独立记录](device-ui-model-validation-2026-10-15.md)，不得并入本轮通过数。完整 R8 Mobile Use 授权、取消、断连和恢复矩阵仍待专门补验。静态帮助页、从源文档生成资源、构建提交/dirty/签名证书身份与全章节问答质量仍未实现或验证；本轮帮助身份仅覆盖实际安装元数据。

## 本轮主要改动文件

- 交互：[ComposerModelMenu](../../../app/src/main/kotlin/com/helix/app/ui/ComposerModelMenu.kt)、[ProotRuntimeSection](../../../app/src/main/kotlin/com/helix/app/ui/ProotRuntimeSection.kt)、当时的 `ProotVerificationNote`（HXA-305 已替换为 [ProotEntryNote](../../../app/src/main/kotlin/com/helix/app/proot/ProotEntryNote.kt)）、Developer `ProotToolModule` 及三语言资源；相关模型、Runtime 设备测试。
- 帮助：[BundledHelp](../../../app/src/main/kotlin/com/helix/app/skills/BundledHelp.kt)、`DefaultAppContainer`、Skills 中的 `BuiltInSkills`、`HelixHelpIdentity`、`HelixUserGuide`、`HelixUserGuideZh`、9 个中文 Markdown 资源，以及主机/设备断言。
- 验证：[独立测试宿主](../../../tests/release-smoke/build.gradle.kts)、[Release 运行器](../../../scripts/run-release-smoke.py)、[隔离构建入口](../../../scripts/release-smoke.init.gradle)、`settings.gradle.kts`、主机门禁与依赖锁；[设备运行器](../../../scripts/device_baseline/run.py)及对应 Python 回归。
- 文档：[发行 ADR](../../adr/platform/001-distribution.md)、[HXA-122](../../development/tasks/HXA-122.md)、README 中英文、status、release-readiness、本轮记录及前轮记录的事实更正；不把其他所有者的既有 dirty 文件算作本轮成果。

## 重构前功能取舍

**不建议再加入一批大功能。** 当前最有价值的是让已有第一条手机任务可理解、可完成、可核验，并形成可重复的候选回归。新增功能应当由实际任务缺口驱动，不能因为竞品有就先塞进旧结构。

| 项目 | 重构前处理 | 理由与边界 |
| --- | --- | --- |
| Mobile Use 首次任务与恢复反馈 | 在最终候选中重点核验：配置 → 选用 → 会话授权 → 观察/操作 → 核验结果 → 返回/停留；后台恢复明确区分授权仍在、后端就绪与任务运行 | 这些能力已有实现，不从零重写或再加授权入口；发现阻断才定向修复。停止与撤销分开，进程恢复不得重用旧 node/gesture 凭据 |
| Mobile Use 工具前置依赖 | 若候选复现单独发现截图后缺失 ui.device/frame，按实际故障做窄修；通用贡献与依赖闭包留 HXA-286 | 已接受任务明确覆盖三条发现路径；不为“重构前补齐”再造一套发现注册 |
| 帮助与反馈 | 本轮完成中文帮助和实际安装身份；保留现有 About/反馈入口 | 静态帮助阅读页、源文档生成可延期；准确版本与权限说明不应延期 |
| 可重复 Release 验证 | 本轮接基础黑盒回归，后续沿同一入口积累模型、授权和恢复旅程 | 测试身份、真实方法结果和当前候选必须可追溯；不把 Debug 通过或启动成功泛化成完整验收 |
| Mobile Use 简化参数、坐标与截图映射 | 留 HXA-292，复用 286/288 稳定契约 | 减少模型猜单位、手算缩放/旋转；保持插件实现、宿主权限与事实回执，不把插件揉进 Agent Core |
| glob/grep、网页搜索/正文提取、MCP Resources、Browser 表单增量 | 按 HXA-289～292 既定切片实施 | 有明确任务价值，但并非已有 Mobile Use 预览发布的必要条件；搜索还需实际服务/配置选择 |
| Memory/上下文选择质量 | 保留 HXA-293 单变量评估 | HXA-285 引用/压缩一致性已修，不能重开；无收益不改变默认，不先加向量库或后台 Memory Agent |
| 定时循环、生产子 Agent、远程执行、hooks/LSP、文档编辑/OCR | 本轮不追加 | 涉及新的生命周期、授权或产品范围；先完成已有契约重构再按需求立项，不预留空实现 |

重构前建议增加的是**验收样本**：一条无模型的确定性工具旅程、一条真实模型的合成 Mobile Use 旅程，以及一次停止/失联后不重复业务操作的恢复反例。使用已有夹具和原持久事实，不另外创建任务账本。这些是优先验证方向，不宣称本轮全部完成。

## 运行入口

```bash
./gradlew -I scripts/release-smoke.init.gradle \
  -PincludeReleaseSmoke=true \
  -Phelix.smokeApplicationId=com.helix.validation.previewcheck \
  -Phelix.abi=arm64-v8a \
  :app:assembleConsumerRelease :app:assembleDeveloperRelease \
  :tests:release-smoke:assembleDebugAndroidTest
python3 scripts/run-release-smoke.py --help
```

构建不操作设备。测试宿主本身是 Debug APK，受测 Helix 必须是 non-debuggable 的 R8 Release，两者不混淆。运行时需按当次 Owner 授权指定设备，传入目标 APK、`tests/release-smoke/build/outputs/apk/androidTest/debug/release-smoke-debug-androidTest.apk`、正确 applicationId、全新输出目录及 SDK 工具路径。init 脚本只生成测试签名隔离包，不进入正常分发脚本。运行器拒绝已有同名安装；不会用卸载旧包来绕过签名或身份错误。
