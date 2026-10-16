# 插件提示与统一管理验收（2026-10-16）

对应 [HXA-298](../../development/tasks/HXA-298.md)。Owner 要求审查插件提示提供方式、参考竞品并统一配置管理 UI，授权日常模拟器测试及重建测试数据。当前工作基于 `main` / `6bbff42d`，保留 HXA-295～297 的未提交工作；未提交、推送或发布。

## 实施与审查记录

- 原生插件必要指导、Skill 目录/按需正文/参考资料、MCP 工具描述沿用既有生产链路。管理预览增加真实必要指导与原生参考资料，停用后也能检查，不改变注册、默认选择、会话选择或授权。纠正“选用后自动加载全部 Skill”及原生记录显示零技能的误述。
- 所有插件统一“配置／提示与工具／详情”。MCP 认证与连接直接放在配置区；Mobile Use 保留插件自身的专属配置。使用按钮传准确安装 ID，配置返回准确卡片及配置页；卸载展示目标与后果并确认，取消保留安装和凭据。
- Mobile Use 必要指导去重，保留观察/凭据有效期、提交语义、未知结果不重放、真实结果核验及有条件返回原会话。Documents 指导移入自身资源；Chat Reply 明确独立 Mobile Use 依赖、草稿核验、禁止发送及宿主历史/审计边界。Browser/Mobile Use 参考资料移除重复 Skill frontmatter。
- 竞品一手资料、实际源码入口与取舍见[研究](../../research/topics/plugin-guidance-and-management-2026-10-16.md)。未复制竞品代码；不把提示整理等同模型收益。原 ADR-CONNECTORS-003、ADR-SKILLS-001 与 Harness §23.12 记录接续行为，不增加兼容层或启动完整架构重组。

## 失败、修复与范围澄清

1. 首次主机文档门禁因新 HXA 尚未进入 inventory 失败；补齐索引。静态检查曾报告长方法/参数，提取职责明确的展示辅助方法和动作记录后重新检查，没有豁免规则。
2. Developer 首批 8 方法中，插件 UI/内容 7 项通过；额外加入的拼音 1 项失败，因此该批整体失败。Owner 随后明确拼音面向人工输入准备，不要求每次测试验证；停止这一范围外排查。Git 显示 AGENTS 拼音规则于 2026-10-05 的 `73be3752` 加入，这不是原始用户意图的证明。已按本次澄清收窄规则，保留已有中文输入环境；未将失败改记为通过。
3. Developer 第二批 33 方法中 30 项通过，3 项失败：两个会话选择测试使用未连接 MCP 却预期可选，改用包含已启用 Skill 的真实就绪安装并保留未就绪配置拒绝回归；一个 Mobile Use 注册测试依赖其他方法残留无障碍状态，改用既有 OwnedAccessibilityBackend 自有 fixture，在每个方法结束恢复原系统设置。没有放宽生产权限或就绪检查。
4. 新增配置返回回归覆盖“先查看提示，再使用，再配置”；修复原返回仅切管理大页、未定位当前插件配置区的问题。
5. 修正前置后，下一批在第 19 方法清理时卡住。对当前测试 PID 的线程转储证实：卸载持有 plugin mutation lock 等待 Skill lock，页面刷新持有 Skill lock 等待 plugin mutation lock。保留未完成 instrumentation 并停止自有测试进程；不计该批通过。SkillRepository 现在接受宿主元数据门闩，DefaultAppContainer 注入既有插件门闩，消除反向锁序，同时保留发布、引用和隐私清理原子边界。新增 JVM 两线程并发回归及 Android 实际接线断言；不串行化工具执行。
6. 死锁修复后 41 方法完整运行，40 项通过；剩余 Mobile Use 注册测试此前取“第一个 PluginOrigin”，实际取到了其他插件的工具，导致来源可用性断言失败。改为精确匹配 `mobile-use`，保留选择、撤销、旧绑定失效和修复后重新发布的全部断言，再单独复测。
7. Standard 首批 31 方法中 29 项通过，2 项失败：卸载测试只等持久删除，未等异步界面刷新；补充有界等待后仍断言卡片消失。公共贡献测试要求 Chat Reply，但当前它只由 Developer composition root 打包；将该完整测试移到 Developer 的 `ChatReplyContributionDeviceTest`，没有删除断言、加 skip 或扩大渠道能力。Standard 的其余共同贡献测试保留。
8. 清理检查发现 OwnedAccessibilityBackend 立即关闭 shell 输出管道，可能尚未恢复设置就被下一方法读取。改为读完两条恢复命令后返回；最终外部复核 `enabled_accessibility_services=null`、`accessibility_enabled=0`，与本轮基线相同。

## 验证记录

原始命令、日志、JUnit、失败截图及基线位于忽略的 `build/hxa298/`，临时设备入口位于忽略的 `scripts/local/debug/2026-10-16/hxa298-device.py`，复用维护中的 instrumentation 解析器。最终结果如下，初轮失败不覆盖、不改写。

| 检查 | 实际结果 |
| --- | --- |
| `./scripts/check-all.sh --all` | 退出 0，最终生产修复日志 `check-all-lock-final.log`：269 项主机脚本、文档/ADR/i18n/凭据、Spotless/Detekt、适用 lint、全仓 JVM、双渠道 Debug/Release、Release smoke APK 编译、依赖锁及 APK 运行边界通过 |
| 相关 JVM 报告 | Plugin 81/81、Skills 60/60、Mobile Use 305/305、Documents 27/27；App Standard 1,247 通过/4 跳过，Developer 1,368 通过/4 跳过。跳过项仅既有外部输入 ConnectorSuppliedArchive、WorkBuddySuppliedArchive、ConnectorExternalAcceptance，不计通过 |
| 最终测试代码检查 | `spotlessCheck detekt :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest` 退出 0，`test-apks-accepted.log`；两渠道测试 APK 已实际安装并执行下列有界方法 |
| Developer / API36 | 同一最终生产代码的 `developer-lock-final` 40/41，加 `developer-targeted-final` 8/8，覆盖原失败与整理后的 UI、修复、Chat Reply；注册测试辅助方法整理后 `developer-registration-accepted` 2/2。41 个方法的验收覆盖分批闭环，**不是一次 41/41 运行**，没有 skip |
| Standard / API36 | `consumer-accepted` 30/30，无 skip；公共 UI、配置/取消、会话选择/默认隔离、Skill/MCP 就绪与引用、安装清理、Browser Dispatcher 撤销边界 |
| Standard 窄屏/大字体 | `consumer-small-font` 4/4，无 skip；840×1800、density 420、font scale 1.3，覆盖配置定位、内容、卸载取消和状态恢复。结束恢复 1080×2400、font scale 1.0 |
| 实际主界面抽查 | 从导航进入扩展，展开 Browser 配置并切换提示页；三分区、描述和按需加载说明可见，截图在本地证据目录。没有调用真实模型或修改插件默认集合 |

设备原始命令保存在每批 `command.json`，方法级结果在 `instrumentation.txt`；成功批生成 `junit.xml` 与 `result.json`。失败批不生成成功结果。Release/R8 仅构建及静态边界通过，未在本轮模拟器运行 Release。

设备限定为既有 `Helix_API_36` / `emulator-5554`，API36 ARM64；没有启动其他模拟器或使用真机。Developer 测试数据按 Owner 授权重建。测试仅使用合成安装/凭据与本应用授权范围，没有真实 Provider、外部账号或第三方业务操作。

保留日常 Developer 最新 Debug 安装；本轮新增的 Standard 主应用已移除，测试创建的插件/会话由 fixture 清理。分辨率、字体和临时无障碍设置恢复，既有键盘/中文 IME 配置保留。Git 分支/HEAD 未变；无关既有文件与基线逐字相同，交叠文件仅追加本轮文案/导航/状态和 DefaultAppContainer 一行元数据锁注入，未覆盖原会话工具接线。详细核对结果留在本地证据目录；格式化限定本轮路径，没有提交、推送或发布。

## 主要改动入口

| 范围 | 文件 |
| --- | --- |
| 管理与导航 | App `PluginManagementCard.kt`、`ConnectorSection.kt`、`ExtensionsScreen.kt` |
| 内容预览 | App `PluginBundledContents.kt`、`PluginService.kt`；Plugin `PluginRegistry.kt` |
| 插件指导 | Mobile Use `required.md` 与 Android 参考；Browser 参考；Documents `required.md` / `DocumentsPlugin.kt`；Chat Reply `SKILL.md` |
| 文案 | 三组语言的 `plugin_management.xml`、`strings.xml` |
| 回归 | `PluginRegistryTest`、`PluginManagementDeviceTest`、`PluginContentsDeviceTest`、`ConnectorSessionPanelDeviceTest`、`MobileUsePluginRegistrationDeviceTest`、`PluginSkillContributionDeviceTest` / `ChatReplyContributionDeviceTest`、`OwnedAccessibilityBackend` |
| 并发修复 | `SkillRepository.kt`、App `DefaultAppContainer.kt`、`SkillMetadataConcurrencyTest`、`PluginLifecycleDeviceTest` |
| 决策与导航 | 上述研究/ADR/Harness 接续、HXA-298、status/roadmap、AGENTS 输入偏好澄清 |

## 保留边界

本轮不是整产品、全部 API/OEM、R8 运行或长稳验收。真实模型与真机 **not requested**；提示遵循率、token/延迟和任务完成率未对照。HXA-293 的统一上下文预算仍属后续范围；跨插件依赖自动解析没有新增。Mobile Use 仍是插件，配置预览不会授予屏幕或跨会话权限。
