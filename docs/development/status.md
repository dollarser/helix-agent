# 当前实施状态

2026-10-15最终结案：按Owner要求结束Notion和Linear全部测试，不再列为待办或重构阻塞，不新增授权/刷新/业务调用。两家既有登录、读取、审批与厂商撤销/旧凭据失效证据保留；Notion自然刷新及重启复用已通过，Linear自然刷新未单独验证且不再追加。最近Notion撤销后的正常重连未确认，不虚写恢复通过；最终数据/权限清理见[结案记录](../evidence/development/mcp-revocation-and-browser-closeout-2026-10-15.md)。



更新：2026-10-16。本页维护当前事实、开放范围和唯一下一步；各轮失败、制品、命令和清理详见原任务/证据，不把历史阶段状态当当前阻塞。

## 当前实施

2026-10-16 Owner 追加 [HXA-301](tasks/HXA-301.md)：收尾最新工作区、分阶段本地提交、同源 R8 候选及模拟器真实模型验收。真机核查仅发现模拟器，Owner 随后明确改用模拟器和真实模型；当前设备验收 pending，推送/远端 CI/发布仍暂缓。HXA-295 B/C 与完整重构不扩展实施。

2026-10-16 [HXA-300](tasks/HXA-300.md) 主机交付：导入后精确配置/选用、组件限制与错误摘要、折叠预览、实际更新差异和明确的本地更新目标选择已实现。完整主机通过，定向双渠道各 18/18、原始包与公开配置离线各 3/3；两渠道 AndroidTest 已编译，三个新增 UI/Room 方法未执行。既有安装事务与权限保持，边界见[验收记录](../evidence/development/plugin-import-ux-2026-10-16.md)；设备/真实模型 not requested，未推送发布或启动完整架构重组。

2026-10-16 [HXA-299](tasks/HXA-299.md) 有界交付：三类外部输入已集中到本地 fixture 并分离离线/联网门禁，新增分段成本/省略事实与原生插件测量，省去未选插件上下文探测；真实模型发现的 ask_user 描述换行 INTERNAL 已修复。完整主机、离线双渠道各 3/3、公开 MCP 各 1/1 通过；日常 API36 两渠道样本/UI/诊断与 Qwen 两渠道文件、Developer 原生指导分别闭环。中间网络失败、模拟器无响应与冷启动恢复后 Developer 定向 1/1 见[验收记录](../evidence/development/plugin-diagnostics-and-samples-2026-10-16.md)，不改写原失败批次。已清理临时模型/转发与新增 Standard 安装，保留日常 Developer 和全部既有工作；真机未请求、R8 本轮仅构建，不提交/推送/发布，不启动完整架构重组。

2026-10-16 [HXA-298](tasks/HXA-298.md) 有界交付：插件统一配置／提示与工具／详情、精确选用/配置定位及只读提示审查，完成领域指导整理；模拟器发现插件清理与 Skill 读取反向锁序死锁，已共享关联元数据门闩并补并发回归。完整主机及最终测试 APK/静态检查通过；日常 API36 Developer 41 方法覆盖分批闭环、Standard 30/30、窄屏大字体 4/4，原失败与现场恢复见[验收记录](../evidence/development/plugin-guidance-management-2026-10-16.md)。竞品取舍见[研究](../research/topics/plugin-guidance-and-management-2026-10-16.md)。Owner 明确拼音是人工输入偏好，不是每轮设备测试门禁，AGENTS 已澄清。真机/真实模型 not requested，R8 未运行；未提交/推送/发布，不启动下方架构重组。

2026-10-16 [HXA-297](tasks/HXA-297.md) 主机范围交付：会话活动排序、事务搜索投影、历史窗口与跨窗口定位、GC 引用索引/单候选协调，以及模型/工具持久来源、序号和时间已接生产；JSONL 按需导出更新为 v2，不增加实时双写或备份恢复。完整主机门禁与两渠道 Debug/Release 制品通过；具体测试、跳过项和风险见[优化记录](../evidence/development/session-storage-optimization-2026-10-16.md)。设备/真实模型 not requested；不兼容旧预览内部结构，不启动完整宿主提取，未提交/推送/发布。

2026-10-16 Owner 追加 [HXA-296](tasks/HXA-296.md)：13 个系统行为模板已统一，显式有界读取哈希、编辑已读版本/失败事实、UTF-8 窗口与压缩 SYSTEM 指令已修复。完整主机门禁通过，Files/Workspace/Agent 共 531/531、相关 App 套件双渠道各 25/25，新增 14 项回归通过；具体竞品取舍、首轮失败和验收边界见[实施记录](../evidence/development/prompt-alignment-2026-10-16.md)。没有启动架构重组；设备/真实模型 not requested，未提交或发布。

2026-10-16 Owner 追加[HXA-295](tasks/HXA-295.md)，选择现在实现必要首版并更新后续重构，明确不兼容旧内部方案。A 已实现宿主会话状态、分页历史、搜索、读取已保存引用和条件标题修改，完整主机门禁通过，相关 JVM 套件双渠道各 74/74；任意跨会话列表/创建/发消息仍未实现，按 295 B/C 接续 286/287。竞品与目标边界见[研究](../research/topics/session-management-2026-10-16.md)，实际过程见[首版记录](../evidence/development/session-management-2026-10-16.md)。本次设备/真实模型 not requested，真实 Room 用例仅编译；不改变旧发布候选或暂不推送发布决定。

2026-10-16 预览发布收尾：动态观察、R8 截图残留以及双渠道返回缺少来源身份均已修复。截图候选 `1161b7e8` 动态截图 12/12、维护回归 1/1、双渠道 UI 6/6 通过；最新生产提交 `1d34221a` 在插件输出可信 `originatingHelixPackage`，完整主机通过（Mobile Use 305/305），新四个单 ABI R8 候选从该干净提交构建，保持 code 1 和既有开发证书。新候选 API36 双渠道 UI 4/4、真实 Qwen 独立夹具视觉与返回 1/1，工具结果、最终前台及原会话可见性一致；不把旧抖音辅助通过或截图 12 张循环改标为新 APK 重跑。一次独立实例拼音预检超时保留，后续实例真实中文提交 1/1；两实例均已关闭，测试安装/凭据/转发清理，日常实例未修改。制品来源、失败与范围见[本轮发布收尾](../evidence/development/v0.0.6-release-2026-10-16.md)。Owner 随后明确“只完成本地收尾，暂不推送发布”，本轮按本地完成结案；远端 host-only CI 未请求、未运行，未推送或发布，不再等待许可回复。后续证据提交只更新文档，不改变候选生产来源。以下历史段落不覆盖这一状态。

2026-10-15 GitHub v0.0.6 预览收敛：Owner 限定仅模拟器，并要求正式发布前 `versionCode=1`、删除会话常驻 HTTP 提醒。最终两渠道 ARM64/R8 候选已沿用与 v0.0.5 一致的既有开发证书；完整主机、签名/安装哈希和独立 UI 6/6 通过，未生成长期密钥、未提交/推送/发布。原候选 API36/Shizuku/Qwen 实际完成抖音官方来源安装、卸载及返回；视频有变化和活动音轨，但严重画面损坏及观察拒绝使播放核验未通过，模型关于登录/FLAG_SECURE 的归因不成立。自有实例、临时安装、转发与凭据已清理，日常模拟器未修改；最终制品与原业务测试对象的区别、失败和下一步诊断见[本轮验收](../evidence/development/v0.0.6-preview-acceptance-2026-10-15.md)。

2026-10-15 Owner 追加结合竞品收尾：Mobile Use 派发前/观察阶段取消与超时的效果事实、截图的 `ui.device` 默认及发现配套、锁屏授权旧提示已修正，完整主机门禁通过。日常 API36 隔离 Developer Debug 的真实 Qwen 截图/中文输入/一次完成及返回/停留重复旅程 4/4 通过；首轮漏返回后已明确插件收尾指导，保留原失败。独立 R8 取消、进程死亡 UNKNOWN/不重放、旧节点拒绝/新节点可用及配置/手动撤销共 4/4 通过；修正测试夹具异步断言，临时安装/转发/无障碍均清理，日常包未覆盖。采用现有插件/Dispatcher 接口，不提前启动统一贡献重构，不把 Debug 真实模型结果当作 R8 真实模型或完整 OEM 验收。见[追加收尾](../evidence/development/pre-refactor-finish-2026-10-15.md#竞品对照追加收尾)。

2026-10-15 重构前收尾复核：Owner 将剩余设备验收改为日常 API36 模拟器。独立 R8 两渠道基础 UI 4 项、Developer 配置/会话选择/进程重启/手动撤销旅程 1 项通过；随后追加的执行中取消、进程死亡 UNKNOWN/不重放、后端重建后旧节点拒绝与新节点可用 3 项也通过。修复 `ui.wait` 将无副作用取消误报为 NEEDS_REVIEW 的缺陷，自有安装和临时无障碍设置已清理；这些有界结果不代替全部 live UNKNOWN/gesture frame、Root/Shizuku 或 OEM 矩阵。此前 GitLab 市场提示、Detekt/lint 遍历竞态及清理报告修复保留，最新完整主机门禁通过。直接内置与市场选装逐项建议见[插件评审 §6.2](../research/topics/tool-and-plugin-competitive-review-2026-10-15.md#62-直接内置与内置市场的重新划分)，没有提前迁移插件。具体制品、失败修复与发行边界见[收尾记录](../evidence/development/pre-refactor-finish-2026-10-15.md)。

2026-10-15 Owner 后续确认：**预览版不要求旧数据保留**，不再以旧库迁移或无损升级作为本次预览发布前置；现行不兼容开发库重建及数据库外文件边界保持，正式发行契约另定。模型选中即返回、Runtime 反馈/返回刷新、中英文帮助与实际安装身份绑定已实施；完整主机门禁、OnePlus/API34 两渠道 Debug 交互/帮助/Runtime 恢复 22 项与 R8 独立黑盒 4 项通过。移除旧设备运行器每类测试前的剩余清库调用，日常安装未覆盖；验证范围、失败修复和重构前功能取舍见[本轮记录](../evidence/development/preview-polish-2026-10-15.md)。完整 R8 Mobile Use 生命周期仍须独立补验，不提前实施 HXA-286～294。

2026-10-15 Owner 随后追加 OnePlus 6T/API34 真机、指定 Qwen 模型和交互优化：上下文未知提示收起、Provider 高级项折叠、插件直接配置导航和历史审批文案已修复；127 项有界设备检查、9 项 Debug 真实模型检查通过，含 Mobile Use 中文输入/滚动/一次完成及返回/停留、PDF 图像与 DOCX 读取。R8 Release 的两渠道真实模型旅程、隔离 QuickJS 和 Developer 首次 RootFS 安装/验证另有实际证据；最终措辞候选启动/持久结果复验、相关主机及制品门禁通过。日常应用未覆盖，临时模型转发、凭据和测试安装已清理；准确构建口径、失败修复和未覆盖项见[真机与交互记录](../evidence/development/device-ui-model-validation-2026-10-15.md)。不推进下方架构队列，也不扩展已结案的账号测试。

2026-10-15 APK 体积优化已完成主机阶段：Release R8、单 ABI 分发和有界 RootFS XZ 编码/解码已接入；完整主机门禁、默认双渠道 Debug/Release 边界、四个独立 ABI Release 候选及三个 AndroidTest APK 编译通过。[实现与验证记录](../evidence/development/apk-size-optimization-2026-10-15.md)保留失败修复、组合收益口径和跳过项。本轮设备/真实 Provider/媒体端到端 not requested，Release 候选未签名，未安装或发布；不继承旧 Debug 验收，也不推进下方架构重组队列。

2026-10-15 工具/插件与执行边界评审已整理为 HXA-286～294 待实施队列，见下方 Next task。目标设计、依赖与删除范围已同步原主题 ADR；本轮为文档与任务更新，未实施代码或新增测试结论。以下各段保留既有交付证据，不作为新任务已完成的证明。

2026-10-15 R2-B首个有界成本对照已完成：同一clean实现/制品、相同谓词、仅改变观察提示，Qwen A6/B6全部passed，单次255秒结束并恢复关闭自有实例。B输入略少但中位耗时更慢，未证明稳定收益，保持生产默认，不再把该实验列为等待授权。该轮账号状态是历史快照；Notion/Linear后续测试范围已按Owner决定结案，当前结论及未执行边界见页首[最终结案记录](../evidence/development/mcp-revocation-and-browser-closeout-2026-10-15.md)。

2026-10-15 Owner追加MCP连接体验优化与重构前收尾，明确允许设备/真实模型/真实MCP。默认“连接账号”、高级参数折叠及手动客户端身份复用已实现；API36 OAuth界面4项、原生文档4项、Room上下文6项及Qwen的PDF/DOCX两项真实摘要通过。引用仅来自保存快照的真实压缩与跟进也已通过；两家官方MCP指定对象读取/工具审批拒绝复测通过。完整主机门禁、最终数据恢复与阶段提交由[本轮收尾](../evidence/development/pre-refactor-closeout-2026-10-15.md)及[MCP体验](../evidence/development/mcp-connection-ux-2026-10-15.md)记录。实体设备是否验收、发行输入和长稳仍按各自范围处理；Notion/Linear账号测试随后已由Owner结案，不再追加刷新、撤销或恢复测试，见页首结案记录。按主会话要求，提前结束专项与长稳保持暂停，不自动启动。

阶段记录（结案前）：Owner追加[HXA-126](tasks/HXA-126.md)真实Linear/Notion连接修复：46d10b13已解决不透明凭据；10月15日日常API36保留数据更新成功，原3会话/5个Turn保留，Linear实际连接和工具发现passed。随后Notion实际OAuth完成，定位并修复80,556字节Schema被旧64 KiB上限拒绝，以及2020-12日期format注解适配失败；同一实例实际连接/工具发现及notion-search/fetch只读工具激活passed。Owner指定两家对象的真实Dispatcher读取及工具审批拒绝已通过；在该阶段自然刷新/旧Token厂商失效仍开放，后续于同日按Owner决定结案。完整过程和未执行边界见[修复记录](../evidence/development/mcp-opaque-credential-fix-2026-10-14.md)、[兼容注册收尾](../evidence/development/mcp-oauth-client-setup-closeout-2026-10-15.md)、[真实对象验收](../evidence/development/mcp-official-object-read-2026-10-15.md)及页首结案记录。

Owner要求先收尾；[HXA-285](tasks/HXA-285.md)引用/压缩一致性生产修复及主机切片已交付，19项相关JVM及完整主机门禁通过。10月15日追加授权后，当前API36实际Room6项及保存引用的真实Qwen压缩/跟进通过；不再将三项新增Room夹具描述为仅编译。执行边界和上下文策略提案不随本次实施，提前结束专项与长稳仍暂停。

Owner已授权[HXA-284](tasks/HXA-284.md)：统一PDF/DOCX文档插件。完整主机和双渠道制品/测试APK通过，10月15日当前API36原生PDF/DOCX4项及Qwen真实文档读取/摘要2项通过；不继承HXA-283验收，不据小样本关闭API29视觉、大文档资源峰值或完整Word排版边界。

2026-10-15 `helix-docs` 首阶段证据：8 个英文离线章节、内容 revision/hash、按需资源读取和默认工具发现通过当时的定向 JVM、API36 隔离模拟器和 Qwen 验收；生成的 consumer/developer `0.0.5` Debug preview 未修改版本或覆盖真机。随后本页首段已补中文章节及实际包版本/渠道身份，且 Owner 取消预览保数据前置；首阶段未完成项不能继续当作当前事实。独立静态帮助页、从文档源生成、精确构建提交/签名身份及全章节引用质量评测仍开放。Owner 指定的下一发布版本为 v0.0.6，正式发布签名与版本升级未在本轮实施。

## 当前结论

| 领域 | 最新事实 | 保留边界 |
| --- | --- | --- |
| 架构和测试维护 | HXA-253～268已按各自范围交付；应用接口/状态owner、生产测试桥、测试入口、页面归属及历史产物清理不重复实施 | 完成记录只覆盖明确范围，完整OEM/账号矩阵仍独立 |
| 插件与工具发现 | HXA-272～281已有界交付，统一内容/资格/就绪/贡献/资源及市场导入已接生产 | 工具/Skill成本收益混合，未执行的桌面组件明确诊断，不承诺任意桌面包可运行 |
| Mobile Use新观察 | 8e9a60d2：289项JVM和全仓通过；API36/Qwen固定4业务/8返回通过，直接新token6/6成功、输入附带正确文字4/4，中间工具失败0 | 单次小样本不证明普遍收益；新版本未跑两小时，原三次STALE_TOKEN证据保留 |
| 最近两小时长稳 | R6冻结旧制品PASS：7211655ms，同PID，172测量业务＋2热身通过；原资源门禁通过 | 中间3次TOKEN_UNKNOWN恢复，240/241采样；不能继承为8e9a60d2长稳 |
| 实际App旅程 | 旧制品曾通过；v0.0.6 上一候选视频损坏/观察拒绝，当前修复与最终复验见页首 | 历史成功不覆盖最新失败，不推导所有App/账号/后端可用 |
| 文档读取与总结 | HXA-284统一documents插件、PDF页面/区域、DOCX图文/表格、持久快照及阅读Skill已接生产，完整全仓/双渠道制品与测试APK通过 | API35以下PDF/扫描件依赖视觉；DOCX无Word分页/完整排版。API36/Qwen有界补验见[收尾证据](../evidence/development/pre-refactor-closeout-2026-10-15.md)，其他API/OEM和大文档峰值不推导通过 |
| 黑屏 | HXA-269主线程阻塞修复和同机有界回归已交付，按Owner后续规则关闭黑屏问题 | 其他OEM、24h与完整生命周期不自动通过 |

最新观察实现与对照见[观察闭环证据](../evidence/development/mobile-use-observation-loop-2026-10-14.md)；R6、实际App和历史失败见[独立验收记录](../evidence/development/autonomous-app-and-stability-2026-10-14.md)。历史R5失败保持，旧阶段“未安装/仍pending”不覆盖随后结果。

## 当前整合与验证边界

- 2026-10-13 Owner追加[HXA-281](../completion-records/HXA-281.md)设备/真实模型验收：OnePlus/API34最终5/5，Developer/API36业务7项及中英文UI2项定向复测通过，Standard/API36最终6/6，两渠道实际拼音通过。Qwen完成Codex格式导入Skill/HTTP MCP真实标记，以及Standard导入Claude格式文件Skill缺失恢复/保存读回；完整主机与测试APK通过。真机保留原8会话/10个Turn/5个Provider更新，自有实例关闭、日常未覆盖；原夹具渠道/异步/清理竞态失败与崩溃均保留，不宣称普遍模型收益或公网账号/OEM全通过。[补验证据](../evidence/development/plugin-marketplace-device-model-2026-10-13.md)。

- 2026-10-13 [HXA-281](../completion-records/HXA-281.md)原主机阶段交付：当前schema/执行权限分层，桌面MCP名称只作检索线索；主流包路径数组、有序MCP配置、离线市场发现源与逐项安装、配置/版本反馈。13项新增边界回归、两渠道WorkBuddy原样本、完整全仓主机门禁及两渠道AndroidTest编译通过。当时设备/真实模型not requested、未安装APK；随后Owner追加补验见上方，不继承280验收或声称普遍Qwen收益；[主机阶段证据](../evidence/development/plugin-marketplace-reuse-2026-10-13.md)。

- 2026-10-12 [HXA-280](../completion-records/HXA-280.md)工具架构优化有界交付：取消全局64截断，同源能力目录/中文与字段检索、成本约束近期工作集及完整操作组已接生产；修复显式目标被陈旧UI会话阻断的启动接缝。完整主机/双渠道Debug和Release制品及边界通过，自有API36 Developer6方法（6业务轮与70工具完整性）、Standard发现/实际拼音2方法、OnePlus/API34最终4方法通过。真机已保留数据更新，原8会话/10个Turn/5个Provider逐行一致；日常模拟器未覆盖，临时映射撤回。基线/首候选业务各6/6，成本混合，不预报普遍收益。[证据与失败尝试](../evidence/development/tool-discovery-workset-2026-10-12.md)。

- 2026-10-12 Owner要求先复核门禁再重测。资源预算保持原阈值；同一OnePlus/API34原APK两小时precheck完成340轮、设备7,220,700毫秒、同PID，原FD/线程/PSS断言通过，host237/238采样且最大间隔30.7秒。设备结果passed；主机finalize因logcat非UTF-8字节失败，原result.json未生成，离线核对与原始证据分别保留；报告器已修复，新增回归及原日志独立离线重处理PASS，不覆盖原缺失报告。没有改库/权限，不代表24h或连续Mobile Use/Qwen通过。[完整结果与边界](../evidence/development/resource-gate-review-2026-10-12.md#完成后的离线核对)。

- 2026-10-12 HXA-271阶段11已实现隐藏确认组复用及原生取消/超时结算；OnePlus/API34实际AX/Root/Shizuku Qwen各2轮、Qwen截图/坐标两轮、后台返回8次、SDK9项、Root/Shizuku短按及两种实际进程死亡不重放恢复均通过。Shizuku本轮授权安装/ADB运行后撤销临时Helix授权，未重建数据库；首轮两小时主机检查因Codex重启采样中断为INFRA_INTERRUPTED，手机随后触发PSS增长约102 MiB超过96 MiB门禁而failed，清理UiAutomation又遇连接失效。独立后台复测设备已通过，见上方；首轮根因尚未由此确定，历史失败保留，其他OEM/24h/第三方账号不推导通过。[阶段证据](../evidence/development/mobile-backend-closeout-2026-10-12.md)。

- 2026-10-12 HXA-271阶段10减少浮窗显隐：纯观察保留浮窗且排除自有节点，截图/动作保留移除、两帧及View脱离确认，150毫秒合并显示恢复，审批/取消和实际物理租约不延后。全仓主机/双渠道制品通过，同一OnePlus/API34最终SDK9/9及实际Dispatcher中文输入/连续观察2/2通过；已更新真机、保留数据库并恢复临时权限。本轮无真实模型/日常模拟器/账号测试，首个像素失败及其他OEM边界如实保留。[本轮证据](../evidence/development/overlay-observation-retention-2026-10-12.md)。

- 2026-10-12 修复OnePlus/API34无障碍输入后连续TARGET_CHANGED：浮窗隐藏后的延迟窗口层级事件使遍历结果过期；保留失效保护，原授权内有界重采只读观察，不重放动作。严格观察修复前2/10、修复后10/10；新增8项JVM、真实Qwen表单两轮及4次会话后台返回通过，全仓主机/source通过。已更新真机并恢复临时设置，没有再次重建数据库；Root前轮通过及黑屏关闭保持，其他OEM/Shizuku/长稳独立保留。[修复对照](../evidence/development/accessibility-observation-stability-2026-10-12.md)。

- 2026-10-12 Owner批准真机备份后更新/重建及临时权限。首阶段OnePlus/API34 Developer更新到76aedcf6，新库identity/完整性正确；SDK8项、Root探针/Dispatcher断连恢复/短按各1项及Root真实Qwen表单两轮通过。无障碍Qwen当时首轮业务失败、次轮240秒超时，后续已按上方记录定位修复；不重开历史黑屏。[阶段证据](../evidence/development/physical-followup-2026-10-12.md#批准后的升级后端与模型补验)。Shizuku未准备及其他OEM/长稳仍独立保留。

- 2026-10-12 Owner连接OnePlus/API34要求真机补验，并允许本轮未复现则关闭黑屏问题。独立Standard实际UI/后台20/20、socket取消/断流/旋转4/4、新Standard6次及原Developer2次普通返回通过，未复现黑屏；HXA-269按Owner规则关闭。原Developer是9月24日不兼容旧库，首阶段未覆盖；后续批准的升级及后端补验见上方，不由普通UI通过代替。[真机证据](../evidence/development/physical-followup-2026-10-12.md)。

- 2026-10-12 输入法/真实视觉/稳定性补验：只读root确认故障AVD的70份Gboard公共中文资源全为0字节，健康同版资源完整；保留故障数据，新空白自有API36实际拼音及冷启动通过。真实Qwen图片请求字节与附件哈希一致，两种布局实际选择/点击通过；健康中文Mobile Use合成表单2/2、SDK8/8、固定Developer14/14和Consumer16/16及双渠道900秒资源pilot通过。当前无实体手机，不关闭Root/Shizuku/OEM及真机黑屏边界；更强会话返回和最终主机结果见[补验证据](../evidence/development/device-vision-stability-followup-2026-10-12.md)。未更新日常APK或清数据。

- 2026-10-11 HXA-271阶段9按Owner要求将配置不足的静默跳转改为先说明原因和步骤，再由“去设置”导航；会话选择和默认新会话开启统一提示，取消不选中/不保存。全仓主机/source、双渠道lint/APK与Developer测试APK通过，三项UI夹具仅编译；设备/模型not requested，未安装APK。[证据](../evidence/development/plugin-setup-explanation-2026-10-11.md)。

- 2026-10-11 HXA-271阶段8检查Owner指定日常API36的抖音链接：已安装40.7.0且域名verified，但首页URL无抖音Activity匹配，系统解析为Chrome；会话把opened误说成必然进入App。已澄清工具说明、要求观察及授权应用启动，全仓主机/source/双渠道制品和映射30/30通过；当前设备只读诊断passed，真实模型遵循率未验，未更新设备APK。[证据](../evidence/development/open-uri-routing-diagnosis-2026-10-11.md)。

- 2026-10-11 [HXA-271阶段7](tasks/HXA-271.md)优化系统授权/连接状态和恢复入口，Root复用授权的显式连接，以及Mobile Use执行拒绝原因；全仓主机/source、双渠道lint/制品及定向19/19通过。本轮设备/真实模型not requested，阶段6安装包未更新，后端恢复后真实工具执行边界仍开放。[证据](../evidence/development/mobile-use-authorization-recovery-2026-10-11.md)。

- 2026-10-11 [HXA-271阶段5](tasks/HXA-271.md)日常模拟器调试交付：复现并修复输出配置错误滞留及过期总额度文案，明确保存/数字输入/范围反馈并保留未保存输入，精简重复说明；Mobile Use具体工具动作、模式本地化、披露标题48dp。全仓主机/source、双渠道lint/制品通过；Owner指定日常API36 Developer纯展示22/22、实际配置/工具/上下文页面与3次后台返回通过，已更新日常APK，原数据库关键记录逐行一致。[证据](../evidence/development/daily-emulator-ui-audit-2026-10-11.md)。本次真实模型not requested，不关闭真机黑屏/OEM/完整页面矩阵边界。

- 2026-10-11 [HXA-271阶段4](tasks/HXA-271.md)过程/最终答复展示分层主机交付：根据持久原子工具步骤识别过程，灰色小字，成功且存在之后可见回答时长说明三行预览、整行披露；未确认/流式及失败/停止/恢复正常展示，提问/审批独立，搜索命中展开，原文和调用顺序不变。全仓JVM/source/静态、双渠道lint/APK及App测试APK通过，新增分类每渠道6项通过；[本轮证据](../evidence/development/chat-message-presentation-2026-10-11.md)。本轮设备/模型not requested，四项UI夹具只编译，未更新日常或手机。

- 2026-10-10 [HXA-271](tasks/HXA-271.md)会话交互本地交付：查看会话仅导航、对话内隐藏浮窗，简洁窗口上限/保留上下文占用，工具按原持久调用顺序穿插、搜索逐节点定位，审批/诊断详情展开。全仓主机/双渠道lint与制品通过；自有API36 Developer会话/真实Qwen22/22、浮窗6/6通过，无skip。[证据](../evidence/development/chat-interaction-context-timeline-2026-10-10.md)保留真实wire/local身份修正和夹具失败；新AVD额外Gboard实际拼音检查失败，输入法验收pending，不覆盖日常/真机APK或关闭其他OEM边界。

- 2026-10-10 [HXA-251](tasks/HXA-251.md) 有限补验交付：最终全仓主机/静态检查、双渠道lint及制品编译通过。双API Developer既有各26项、Consumer Memory真正进程死亡与项目提示各3项、CLI wire各4项通过。Provider窗口适配已修复，默认表单各5项、窄屏/大字体输入与保存各9项、实际拼音候选/中文草稿各1项通过；插件取消更新六项及双API Developer原生修复保持选择/权限通过。真实Qwen六轮发现提示对照基线1/3、候选3/3，失败轮包含UNKNOWN_TOOL拒绝及无进展，未发现脚本重复执行；四请求真实Memory读取/拒绝/项目隔离通过。不宣称生产策略普遍收益，[本轮证据](../evidence/development/refactor-closeout-2026-10-10.md)保留完整交叉矩阵、其他IME/OEM、摘要质量和长稳边界。日常主APK未覆盖安装。

- 2026-10-09 Mobile Use 收尾提示：[HXA-250](tasks/HXA-250.md) 在插件技能、摘要和 ui.launch 说明要求验证业务结果后返回原 Helix 对话，再回复；尊重停留/前台活动/手动接管，返回不扩权、不重放。216 项插件主机测试、静态检查与双渠道 APK 构建通过；随后授权日常 API36 Developer 安装及真实 Qwen 返回/停留 2/2，原对话实际顶部标题/窗口焦点确认。[本轮证据](../evidence/development/mobile-use-return-conversation-2026-10-09.md)保留测试资源争抢/夹具失败，不推导普遍遵循率、长稳或真机/OEM 通过。

- 2026-10-09 实际模型协议瓶颈：[HXA-249](tasks/HXA-249.md) 修复 Chat 前导 system 编码兼容，全仓主机门禁通过。所有者明确要求后已覆盖安装日常 API36 Developer 0.0.5，安装前后 Room identity 一致；同一 Qwen 的新编码协议对照、真实 J1 与 AUTO 各 1/1 通过。[对照与旅程证据](../evidence/development/chat-system-compatibility-2026-10-09.md)保留旧 PROTOCOL 失败，不宣称普遍完成率、成本收益或真机通过。

- 2026-10-09 Project/Memory 本地收尾：API36 Developer/Consumer 界面/权限/项目隔离各 6/6，Global/Project 真正进程死亡恢复 1/1；修复项目文件页来源异步加载前崩溃。[本轮证据](../evidence/development/project-memory-device-2026-10-09.md)保留夹具失败、实际崩溃与各制品身份，其他设备/IME/真实摘要质量不由此关闭。

- 2026-10-09 J2 本地交付：AUTO 与 USER 后台释放保持同一原 Job 身份、权限和 lease；全仓主机/双渠道编译通过，API36 Runtime/UI 八项通过。[本轮结果](../evidence/development/j2-auto-local-2026-10-09.md)保留旧主 APK 两次真实模型 PROTOCOL；HXA-249 后续修复并验证当前真实 J1/AUTO，通过不关闭全部生命周期组合或真机边界。

- 2026-10-09 有限基线补验：API36 Developer 输入、插件、前后台刷新、实际 Runtime/PTY、J1 等待与收取共 60 个不同场景已有通过证据；初次类名/异步 fixture 失败及修正如实保留。[本轮基线](../evidence/development/local-baseline-closeout-2026-10-09.md)不关闭 IME/OEM/全部真实模型边界。DSH 预算已独立提交 `0d987abf`；新 J2 检查点与项目/Memory、R2-B 继续按下方顺序推进。

- 2026-10-08 预算补充收尾：API29/API36 双渠道完整定向矩阵 316 项通过，包含模拟服务长上下文压缩、旧累计额度不阻止执行、项目 Room 检查及 8 次真实 SIGKILL 恢复；日常 API36 Developer 模拟器安全覆盖安装，原记录身份和数量保留。[本轮证据与改动审查](../evidence/development/dsh-budget-followup-2026-10-08.md)明确失败尝试、夹具修正及边界。真机卡顿/黑屏、真实摘要质量、完整项目 UI/Memory 和后台新功能仍开放；未提交或发布。

- 2026-10-07 所有者要求预算对齐 DSH：普通 Turn 累计用量只统计，Goal 默认最多 256 个自动轮次；上下文压缩与累计任务预算分离。全项目主机测试、静态检查、双渠道 APK 与测试 APK 构建通过；后续授权 API36 Developer 隔离模拟器定向回归 53/53，通过范围与一次旧断言修正见[本轮证据](../evidence/development/dsh-budget-policy-2026-10-07.md)。未覆盖日常模拟器，Consumer/其他设备及真实模型未运行；未提交或发布。

- 2026-10-06 main 整合与发布验证：只保留 main、公共评测、BioHelix 三个工作树；完成当前授权的 API29/API36 双渠道扫描与定向复测、24 组输入恢复、每 API 37 个进程死亡场景、API34 Root 5 项及真实模型 Shizuku 安装/卸载。修复前台通知限流与旧开发库版本启动失败；[发布回归记录](../evidence/development/v0.0.5-release-2026-10-06.md)明确失败、修复、跳过和长期/OEM 边界。发布来源与该提交 GitHub CI 以 v0.0.5 的 BUILDINFO 为准，本地通过不代替远端 CI。

- 2026-10-06 无障碍点击反馈优化：区分显示屏与窗口坐标，补充截图兜底与系统触摸过滤的边界，保留无节点控件视觉点击；结果不明不自动跨后端重放。[定点审查与后续优化](../evidence/development/mobile-use-install-optimization-2026-10-06.md)。后续所有者授权 API36 日常模拟器回归：修复无语义测试夹具，四模式定点与安装页对照各 1/1 通过；系统过滤仍存在，不等于无障碍安装成功，未调用真实模型。

- 前轮整合范围：接手优先级 1（版本整合回归）、优先级 2（Mobile Use 分后端验证）与优先级 3（真实任务稳定性与效率实测）。前轮审查已收敛缺陷并完成主机与文档门禁。
- 优先级 1 整合回归：修复并验证 `ConversationTopBarDeviceTest` (4/4)、`IaAuthorityDeviceTest` (2/2)、`ConnectorUiDeviceTest` (1/1)、`SystemBarInsetsDeviceTest` (1/1) 与 `HierarchicalNavigationDeviceTest` (5/5)；覆盖这些用例实际断言的 Extensions 展开、导航与会话菜单；不包含全局 Hosts 和消息排队投递的完整回归。
- 优先级 2 Mobile Use 分后端验证：`MobileUseOverlayDeviceTest` (4/4)、`AutomationServiceDeviceTest` (1/1) 全流程生命周期、无障碍断言及即时停止测试通过；验证 Shizuku 授权就绪 (`moe.shizuku.privileged.api`)、Shizuku 独立设置检查 (`ShizukuSettingsDeviceTest` 1/1 通过)；Root 后端在当前模拟器无 Magisk/KernelSU 守护且 Helix 未获授权时，`RootToolsRootlessDeviceTest` (2/2 通过) 验证无 Root 时的状态与执行拒绝；设置页检查不证明 Shizuku 执行成功，Rootless 检查不证明自动后端回退。真实模型轨迹的观察后端为 Shizuku，Root 成功路径及普通无障碍单后端仍待验证。
- 优先级 3 真实模型实测：经所有者明确授权 Qwen3.8-27B 模型，在 `emulator-5554` (API 36 arm64) 执行 3 轮完整独立重复合成任务评测（`eval-trial-1`、`eval-trial-2`、`eval-trial-3`），包含 `gui`、`files`、`recovery`、`combined`、`keyboard` 5 项用例，共 15 次独立判定**全部达成并通过 (15/15, 100%)**；详见[模型实测](../evidence/development/current-model-capability-eval-2026-10-05.md)。
- 2026-10-06 后续修复：应用启动、返回/主页和系统动作接入 Root/Shizuku；修复系统通知弹窗公开选项被整体脱敏。API36 无障碍关闭时 Shizuku 导航及通知回归通过，当前 Qwen3.8-27B 一次通知/输入/滚动任务通过；[本轮证据](../evidence/development/mobile-use-navigation-notification-2026-10-06.md)。未重跑真实酷安首次弹窗或抖音安装，也不推导 Root/OEM 成功。
- 2026-10-06 悬浮窗解耦：Helix 集中管理普通悬浮窗授权，Mobile Use 优先普通窗口、回退无障碍窗口；Root/Shizuku 工具绑定共享任务呈现。210 项主机测试及双渠道构建通过，新增设备测试仅编译，设备验证 not requested；[证据与边界](../evidence/development/mobile-use-independent-overlay-2026-10-06.md)。
- 2026-10-06 三后端真实模型安装/卸载评测：三组安装均未完成，三组卸载通过；高权限安装确认被 Helix 保护节点规则拦截，无障碍安装反复无进展，Root 复现计划式最终回复。普通悬浮窗无无障碍回归 1/1；[结果、配置交互与优先修复建议](../evidence/development/mobile-use-backend-install-eval-2026-10-06.md)。后续修复与当前剩余问题见下一项，不以历史安装记录覆盖。
- 2026-10-06 安装与授权优化：Root/API34、Shizuku/API36 真实模型安装成功；三后端卸载均通过，各 6 次工具调用。修复系统安装按钮错误保护、增加 `ui.apps(packageName)` 精确核查、集中 Root 授权后连接已启用功能，并调整权限布局。普通无障碍安装仍失败，提示改进未证明能消除重复尝试；[本轮结果与剩余边界](../evidence/development/mobile-use-install-optimization-2026-10-06.md)。
- Standard/consumer 是完整商店面向产品，Advanced/developer 增加平台/渠道允许的能力。debug 构建、局部设备通过、主机检查和真实账号验收分别记录，不互相替代。
- 开发期 Room 采用 v1 baseline：不兼容库重建，数据库外文件保留；正式签名、applicationId 和升级承诺仍由 HXA-122 承接。版本下载入口不代表当前未提交代码已发布。

| 最近整合领域 | 当前实现与证据入口 | 尚未由此证明 |
| --- | --- | --- |
| Mobile Use / 宿主授权 | Helix 管无障碍、Root、Shizuku 授权与连接；插件管自动化范围、工具和内置 Skill。会话只选择插件；[架构精简](../evidence/development/plugin-architecture-simplification-2026-10-05.md)、[系统授权拆分](../evidence/development/system-permissions-plugin-separation-2026-10-05.md) | 高权限不等于所有操作可用，独立插件不等于第三方原生代码沙箱 |
| 语义与视觉后端 | 支持的观察/节点/截图/手势按实际就绪能力选择 Root → Shizuku → Accessibility；高权限路径不统一依赖普通无障碍。[五项优化](../evidence/development/mobile-use-refactor-2026-10-05.md)、[特权语义](../evidence/development/mobile-use-privileged-semantics-2026-10-05.md)、[工具指导](../evidence/development/mobile-use-tool-guidance-2026-10-05.md) | 系统动作等仍按具体后端实现；失败或 UNKNOWN 不跨后端重放，未证明所有 OEM/安全窗口 |
| 安装和任务实测 | [抖音/酷安安装记录](../evidence/development/douyin-coolapk-model-install-2026-10-05.md)记录自主操作、追加提示和独立包事实；[最新合成任务](../evidence/development/current-model-capability-eval-2026-10-05.md)记录工具效率与键盘修复 | 安装记录不等于当前设备仍有该包；有人工追加提示的轨迹不算一次请求无干预成功 |
| 插件 / 会话界面 | [统一插件管理与更多菜单](../evidence/development/conversation-more-and-plugin-layout-2026-10-05.md)、[插件设置](../evidence/development/mobile-use-plugin-settings-2026-10-05.md)、[会话投递设置](../evidence/development/session-message-delivery-2026-10-05.md) | 某次界面回归不关闭完整扩展生命周期或全部并发旅程 |
| 模型 / 订阅 / 权限交互 | [Provider 入口](../evidence/development/provider-entry-and-subscription-ux-2026-10-05.md)、[模型选择与授权分类](../evidence/development/model-combobox-navigation-permissions-2026-10-05.md) | 支持配置不等于每家真实登录/模型通过；[Antigravity 定向结果](../evidence/development/antigravity-foreground-login-2026-09-30.md)仅覆盖记录中的精确模型 |
| 原生网络与关于页 | 设置中统一 hosts 文本，保存前整篇校验；API、订阅、MCP、A2A、原生 HTTP 共用解析。[实现与验证](../evidence/development/global-native-dns-2026-10-05.md) | WebView、终端、外部浏览器、独立下载器未纳入；映射不扩大网络权限 |
| 项目与文件 | [HXA-246](tasks/HXA-246.md)本地主机及 API36 双渠道 UI/Memory 与进程恢复已交付；[项目设计](../product/projects.md) | API29/API36 有界 Room 重开/服务检查已通过；其他设备与模型摘要质量仍待验，不代表已支持 Git 回退 |
| 手机助手体验 | [竞品差距与入口优化](../product/competitive-doubao-phone-gap.md)保留首任务、语音及验收协议；系统语音优先 | 没有 OEM/System Image 发行承诺；真实第三方 App 成功率和语音 OEM 兼容性仍待验证 |

## Completed

- 2026-10-12 [HXA-280](../completion-records/HXA-280.md)：工具发现与请求工作集按授权范围交付；当前验收与独立边界见本页顶部。

- 2026-10-10 旧版报告整理：9份客户端/Provider/旧发布报告有效内容汇为[客户端交互](../evidence/development/historical-client-ui-validation.md)与[Provider/发布经验](../evidence/development/historical-provider-and-release-validation.md)，原重复报告删除并修入链，206项host回归及源码门禁通过；历史结果不计当前设备通过。

- 2026-10-10 [HXA-268](../completion-records/HXA-268.md)：按Owner追加授权删除62个历史版本脚本/日志/旧发布产物，必要结论与来源hash汇总文档；206项host回归与源码门禁通过。设备和真实模型not requested，当前回归/构建输入/未闭合故障与工作树恢复包保留。

- 2026-10-10 [HXA-267](../completion-records/HXA-267.md)：固定套件总数门禁改为实际方法/完成事件，清理390个已失效或已迁移脚本/本地草稿，有效host覆盖归并统一发现，206项回归及完整Debug主机门禁通过；[维护手册](debugging-playbook.md)保留方法和模板。设备与真实模型not requested，历史证据及唯一诊断保留。

2026-10-10：[HXA-255](../completion-records/HXA-255.md)权限页经EgressRuleService读写，窄行模型与精确目标/固定TTL校验，取消/致命错误传播、无message读取失败可重试，旧提交不清空新草稿；移动页面到permissions/ui。新JVM5项每渠道、主机/两渠道Debug lint与制品通过；API36 Developer4/4、Consumer3/3（均含实际拼音）通过。[本轮证据](../evidence/development/egress-application-interface-2026-10-10.md)保留夹具断言失败，日常APK/数据库未动；继续测试桥和ChatService职责整理。

2026-10-10：[HXA-254](../completion-records/HXA-254.md)项目会话页面消费窄读模型，Room 驱动外部重命名、归档/恢复及新候选实时刷新；读取失败可重试，切换/归档清理加入模式，原归属与活动 Turn 保护保持。默认构建主机及两渠道 Debug lint/制品通过，API36 Consumer/Developer各8项（含真实拼音）通过；[本轮证据](../evidence/development/project-session-readmodel-2026-10-10.md)保留两次输入夹具失败及端口启动拒绝。日常APK/数据库未动，未调用模型或关闭真机卡顿/黑屏边界。

2026-10-10：[HXA-253](../completion-records/HXA-253.md)统一 host fixture discovery，补齐五套漏测入口并拒绝空/全跳过/导入/断言失败；151 项回归及 source gates 通过。没有删除旧测试或在 CI 启动设备。

2026-10-10：[HXA-252 通用工具发现提示](../completion-records/HXA-252.md)已交付，主机门禁通过；API36/既有 Qwen 有效严格对照候选 6/6、基线 5/6，手动 Job 的窗口误用减少，未扩大工具/授权策略。原归档夹具错误与补测、快照恢复失败及所有者随后授权数据库重建均保留在[本轮证据](../evidence/development/discovery-guidance-2026-10-10.md)。日常同哈希原 APK、重建库、Qwen、无在途任务及实际拼音提交核查通过；没有安装候选日常 APK，不宣称历史数据恢复或普遍模型收益。

完整历史交付查[完成记录索引](../completion-records/index.md)和[M0](../completion-records/M0.md)；各记录只证明其明确验收范围。

当前整合基线包含恢复/QuickJS、历史正确性修复及 **HXA-231 R1 原子工具绑定**。主机、有界 API36 和真实 SGLang 的实际范围见[整合验证](../evidence/development/r1-device-service-closeout-2026-09-30.md)；它不是发布 APK、clean 正式 P5 或所有设备/任务通过的证明。

| 领域 | 已有能力与证据入口 | 不由此推导 |
| --- | --- | --- |
| 工具绑定 | [HXA-231](../completion-records/HXA-231.md)：单一 binding、请求/调度/审批/执行同源、全部来源迁移与撤销准入；[有效契约](../adr/tools/001-descriptor-contract.md) | 不再重做 R1；不等于完整 R2/R3/J1 |
| Core/恢复 | [HXA-220](../completion-records/HXA-220.md)、[221](../completion-records/HXA-221.md)、[223](../completion-records/HXA-223.md)：唯一 Engine 与 CAS；[HXA-234](tasks/HXA-234.md) 补齐唯一 Core Loop 和有界上下文迁移的本地主机交付 | 后续候选未提交；不等于设备全验、R2-B 收益或 JobObservation 自动纳入 |
| 输入、导航与活动 | HXA-214～219、[226](../completion-records/HXA-226.md)、[228](../completion-records/HXA-228.md)、[229](../completion-records/HXA-229.md)；[跨会话输入与联合回归](../evidence/development/merged-api36-regression-2026-09-29.md) | 不把历史输入/草稿问题重新列为未修复 |
| 自主视觉 | [HXA-225](../completion-records/HXA-225.md)：view_image、浏览器视觉回填、三协议编码和有界校验；[产品边界](../product/image-reading.md) | 有限工具看图验证不覆盖全部协议、手机整屏截图或设备内视觉 |
| Linux Job/终端 | [HXA-196](../completion-records/HXA-196.md)～[199](../completion-records/HXA-199.md)：后台 Job 与双 PTY；[HXA-236](tasks/HXA-236.md) 原身份观察与 [HXA-248](tasks/HXA-248.md) AUTO/后台等待释放本地已交付 | API36 Runtime/UI 已通过；完整设备/真实模型组合仍开放，不由等待释放恢复未知执行 |
| 扩展 | [HXA-129](../completion-records/HXA-129.md)、[130](../completion-records/HXA-130.md)、[212](../completion-records/HXA-212.md)；[HXA-235](tasks/HXA-235.md) 已接统一安装目录、会话选择与停用/更新/修复，HXA-251 双API真实 Room 取消更新/原生修复通过 | 不重做 R3 主体；完整在途引用/撤销/进程死亡交叉矩阵尚未验收 |
| Workspace/Memory | [HXA-210](../completion-records/HXA-210.md)、[目录恢复](../evidence/development/recoverable-workspace-2026-09-27.md)、[HXA-230](../completion-records/HXA-230.md)、[HXA-246](tasks/HXA-246.md) | Global/Project 主机、API36 双渠道 UI 与项目记忆进程恢复已通过；其他设备与模型质量仍开放 |
| 设备内模型 | [HXA-222](../completion-records/HXA-222.md)、[收口](../evidence/development/hxa222-closeout-2026-09-28.md)、[安装](../evidence/development/p3-local-model-install-2026-09-28.md)/[首次使用](../evidence/development/p4-first-success-journey-2026-09-28.md) | 本地 Provider 可驱动完整 Loop，不等于所有设备与任务质量已验证 |
| Eval/整合 | [HXA-227](../completion-records/HXA-227.md)、[设备基线](../evidence/development/hxa227-device-baseline-2026-09-27.md)、[历史真机验收](../evidence/development/physical-oneplus-acceptance-2026-09-24.md) | 每份证据仅覆盖原基线；公共 benchmark 与生产 Harness 分开报告 |

## 候选与有限接受范围

[候选索引](candidate-decisions.md)集中记录有限接受、已有基础和未排期能力。R1 已交付。2026-10-01 所有者明确要求依次完成审查列出的剩余工作，包含 R2-A/Core、R3/插件生命周期、J1/J2、Project Memory 的实施推进；按下方当前顺序逐项建立具体任务和验收。R2-B 仍须等价基线与收益实验，不因授权预报收益；未排期的 PDF/视频/子 Agent/远程等不是此次自动扩展目标。不重开已完成的生命周期、输入或视觉任务。

## In progress

- 2026-10-14 Owner追加日常安装：emulator-5554/Helix_API_36/API36的Developer已保留数据覆盖到f779a48f制品并打开MainActivity；原3会话/5 Turn/6 provider完整hash及schema/integrity不变，设备读回APK hash匹配。未运行模型/fixture或重建库，此项是安装核对，真实代理关联待Owner手测。[安装证据](../evidence/development/debug-model-correlation-2026-10-14.md#owner追加日常安装)。

- 2026-10-14 HXA-282 Debug自定义API诊断关联已交付：App HTTP装饰复用已有context，三个协议均支持，不改核心循环/请求/协议岛；已有LineStore绑定明确表单来源，不猜域名，旧记录不回填。全仓主机/两渠道Debug与Release制品及测试APK编译通过，新增类每渠道6方法通过，实际manifest Debug为true/Release为false；设备/真实模型not requested。Owner提供旧rep93/140生成日志，直接机制为普通文字后im_end且无工具标记，未见解析丢失/长度截断；有效HF模板hash及诱因仍待证。[证据](../evidence/development/debug-model-correlation-2026-10-14.md)。

- [HXA-282](tasks/HXA-282.md)：公网合成OAuth、市场恢复、Qwen/Codex有界业务、消息回填及Debug自定义API关联已交付。R6旧冻结版本两小时PASS、新稳定观察固定短验通过，完整范围见本页顶部证据。Notion/Linear测试已按Owner决定结案，新观察版本长期资源仍独立开放；不重复已完成市场/协议主体，也不将历史R5失败覆盖为通过。

- 2026-10-12 Owner 授权继续收尾及 276→279。日常 API36 已保留数据更新到 HXA-275 验收 APK，自有 API36/Qwen 完整文件任务 1/1 通过，失败尝试与转换过程见[收尾证据](../evidence/development/plugin-closeout-2026-10-12.md)。HXA-276 单一 Browser 贡献已通过全仓主机、API36 双渠道各 9 项及共享浏览器引擎 29 项有界验收，见[276证据](../evidence/development/hxa276-browser-contribution-2026-10-12.md)；HXA-277 已完成在途资源引用与清理、主机和 API36 后端/应用组合有界验收，见[277证据](../evidence/development/hxa277-native-resource-lifetime-2026-10-12.md)；HXA-278渐进装载、精确资源和有界就绪事实已通过主机、双渠道内容及固定Qwen对照，见[278证据](../evidence/development/hxa278-progressive-skills-2026-10-12.md)；模型成本为混合结果。HXA-279已完成浏览器发现、等待与本地有界交付，见[279证据](../evidence/development/hxa279-browser-observation-2026-10-12.md)：引擎28项、最终双渠道各9项及严格六类Qwen通过，先前模型失败/单轮边界保留；日常已更新到最终APK并核实原数据保留。测试AVD的Gboard中文拼音因原生崩溃仍failed，不作整体验收通过。

- HXA-271阶段7授权/连接交互及后续恢复实现已交付；当轮设备/模型未请求是历史边界，随后同机AX/Root/Shizuku及现有Qwen补验已完成，当前结果见本页顶部阶段11。其他OEM和长期组合仍独立保留，不重新实施旧切片。

- [HXA-271](tasks/HXA-271.md)：会话导航/浮窗、上下文与工具时间线、灰色整行披露、过程/最终答复分层、配置就绪提示及隐藏确认优化均有本地主机与对应有界设备证据。日常更新和保留数据、后续同机后端/恢复/两小时资源补验见本页顶部；长稳报告器已修复。历史故障AVD中文资源损坏保留，新自有API36两渠道实际拼音已通过；不再将旧“后端未连接/未请求”列为当前阻塞。其他OEM、完整页面/账号组合与24h仍是独立验收边界，不能生成全面通过记录。

- [HXA-270](tasks/HXA-270.md)：Owner追加悬浮审批要求，原任务只读诊断确认 launch/snapshot 成功、ui.click 待审批；实施可信原生单次批准/拒绝、完整详情展开、等待状态同步及查看会话不取消。主机source/全仓JVM/静态/双渠道lint与制品编译已通过，新增6项回归通过；[证据](../evidence/development/mobile-use-overlay-approval-2026-10-10.md)。Owner追加手机更新测试后，OnePlus/API35 的现有 Qwen 合成 Settings 原生批准/拒绝、详情滚动、查看会话和接管路径 passed；已修正实机布局，更新最终APK，未重试或批准真实微信操作，其他设备矩阵 pending。

- [HXA-269](tasks/HXA-269.md)：Owner追加实施修复及同一真机/Qwen回归，已移除无障碍事件主线程节点查询，采用有界后台观察、合并及失效结果丢弃；224项插件测试、全仓JVM/source/静态/双渠道lint与编译通过。最终OnePlus/API35 Developer 6次返回、12次滚动及实际Qwen launch/snapshot通过，无新Helix ANR/FATAL；保留2次未伴随黑屏的系统窗口异常。阻塞路径修复及有界回归交付；2026-10-12新增API34补验未复现，按Owner新规则关闭黑屏问题。其他后端/OEM完整矩阵独立保留；历史诊断详见[诊断与修复证据](../evidence/development/physical-mobile-use-black-screen-2026-10-10.md)。

- 本轮[HXA-263](../completion-records/HXA-263.md)连续测试日志与真实Qwen压缩约束保留2/2；[HXA-264](../completion-records/HXA-264.md)双渠道主回归各42/42、追加20项及14个实际进程死亡边界；[HXA-265](../completion-records/HXA-265.md)双渠道900秒资源pilot；[HXA-266](../completion-records/HXA-266.md)当前DSH契约/真实Qwen Goal与Skill完整9/9均有界本地交付。HXA-267/268脚本与历史版本维护也已主机交付；此前维护检查点已收口；HXA-269本轮真机诊断、阻塞修复与有界回归已记录，黑屏问题已按2026-10-12Owner规则关闭；下表保留此前维护检查点完整设备/服务矩阵与长稳边界，本轮另行操作真机，未操作日常模拟器。

以下保留尚未闭合的验收范围；已实现的主体不重复开发。HXA-237～245 的阶段命令及交付查各任务和证据，不能用较早“仍依赖无障碍”“尚未安装”等叙述覆盖上方后续实现。

| 开放工作 | 已有基础 | 仍需处理的范围 |
| --- | --- | --- |
| [HXA-239 会话输入](tasks/HXA-239.md) | 选模、光标、权限入口及有界API29/API36实际输入旅程、指定拼音预编辑/候选/中文commit与草稿零发送通过，见HXA-251 | 其他IME/OEM与完整切换/恢复边界仍需证据；不再列已执行场景为“仅编译” |
| [HXA-238 执行与表单](tasks/HXA-238.md) | 多执行身份、实际引擎/容量协调、PRoot Job/终端共存、Provider 滚动/缺项定位已接线；HXA-251 双API有限 Job 与窄屏/大字体输入表单通过 | 完整并发、取消恢复组合及其他 OEM；真实模型结果不由主机代替 |
| [HXA-236 J1](tasks/HXA-236.md) | 原身份观察/等待、同一 ContextCompiler 状态投影、可信 Dispatcher 进展证据已接生产；HXA-249 真实 J1/AUTO、HXA-251 双API六项及固定模型对照已有实际证据 | 完整取消/恢复交叉组合与其他服务/OEM；有限模型完成率不升级为普遍策略收益 |
| [HXA-233 回放生命周期](tasks/HXA-233.md) | 引用感知清理、归属/分支/在途保护、存储反馈及主机回归；HXA-251 双API Room/清理各4项、CLI wire 各4项通过 | 保守保留未知归属；真实服务、长稳和完整故障矩阵独立，不由有限设备结果推导真机通过 |
| [HXA-232 自主恢复](tasks/HXA-232.md) | 自动化授权内恢复、Goal 局部额度衔接、无进展收尾、Runtime 查询/收取、UNKNOWN 只读核查、原 Goal 账本、队列重验证、统一授权；QuickJS 原生总开关、结构化反问与提示词资源化已有实现及有界验证 | 真实模型恢复完成率、完整进程/订阅/PRoot 故障矩阵及 OEM；不把诊断完成或有限场景通过当作原任务成功 |
| [历史正确性收口](../bug-fixes/2026-09-30-historical-correctness-audit.md) | Provider 探测发布/取消、压缩参数、Git、内容发布/删除并发和执行线程容量已有修复，纳入整合基线 | 仅按记录中遗留问题和新证据继续，不重新执行整个历史缺陷清单 |
| [偶发问题](../evidence/development/intermittent-closeout-2026-09-28.md) | 可复现 SAF 撤销/异步投影和 Goal 仅规划问题已修 | 历史截断、探测偶发失败与额外只读调用仍保留调查边界；未复现不等于根因关闭 |
| [HXA-126](tasks/HXA-126.md) | issuer/resource、准备取消、CIMD/显式 DCR、持久归属及配置修复已通过主机集成；Notion/Linear真实服务子范围已结案，见页首结案记录 | 不再等待两家账号、自然刷新或重连验收；自有HTTPS/App Link、签名与渠道输入属于独立发行准备 |

## Next task

当前执行 [HXA-301](tasks/HXA-301.md)：先补插件/会话真实数据库与 UI 回归，再冻结同源 R8 候选并完成最终模拟器/真实模型及发布材料。本轮已授权本地分阶段提交，未授权推送或发布；以下已结案记录保留原范围。

[HXA-300](tasks/HXA-300.md) 导入体验与主机验收已收尾；设备/模型本轮未请求，CLI/账号业务运行不在该范围。当前没有本轮待执行的授权步骤，下方架构队列不自动启动。

[HXA-299](tasks/HXA-299.md) 本轮诊断、样本及有界日常模拟器/真实模型验收已收尾，后续接续边界同步 Harness §23.12 和 HXA-293。当前没有本轮待执行的授权步骤；下方既有架构队列不自动启动，外部网络/宿主资源波动和未覆盖能力仍按证据保留。

[HXA-298](tasks/HXA-298.md) 插件提示/管理体验及有界日常模拟器验收已收尾，接续约束同步 Harness §23.12；没有本轮遗留阻塞，下方既有待授权架构队列不自动启动。

[HXA-297](tasks/HXA-297.md) 本轮存储优化与主机回归已完成，已将接续边界同步至 HXA-287/288/294；当前没有本轮未完成的主机收尾，以下完整架构队列不自动启动。

[HXA-296](tasks/HXA-296.md) 系统提示与文件契约收尾已完成主机交付；读取哈希、编辑版本/副作用事实和窗口边界不再作为等待重构的缺口。后续迁移保留这些回归；提示遵循率、真实模型成本及 Document Provider/OEM 行为仍需单独授权和验证，不由本轮主机结果推导通过。本次独立修复不启动下面的架构队列。

Owner 新增的 [HXA-295 A](tasks/HXA-295.md) 首版已完成主机交付；设备/真实模型 not requested，这次独立交付不等于启动 286～294。后续 295 B 的目标授权/查询管理依赖 286 契约与 287 应用命令/真实 Room，295 C 再接创建/Queue/Steer；纳入 294 冻结范围。具体方案见 [Harness §23.10](../architecture/harness-refactor-plan.md#2310-会话管理与受控任务投递)，不保留首版内部兼容层、不新增远程服务。既有发布/重构顺序仍按下面的 Owner 决定执行。

本轮本地收尾已完成：动态观察、R8 浮窗残留和双渠道返回来源缺口已修复，生产候选及验证见[本地收尾证据](../evidence/development/v0.0.6-release-2026-10-16.md#最新候选验证和清理)。2026-10-16 Owner 明确仅完成本地收尾，暂不推送发布；本轮没有待执行的远端写入或 CI，不再等待确认、不自动重试。四个已签名候选保留；未来另获发布授权时再核对源码/制品和对应 host-only CI。正式长期发行身份仍由 HXA-122 决定。

2026-10-15 Owner 要求按本次评审优化文档和任务：采用单一目标契约，不兼容旧 Helix 内部方案，以当前产品结构和后续扩展成本决定改动。已登记 HXA-286～294，**本轮仅完成规划，代码均未实施**。设计见 [Harness §23](../architecture/harness-refactor-plan.md#23-2026-10-14-执行边界重构提案)，范围和验收只在各任务维护。

**既有“先发布、再启动生产重构”的顺序保留；此次暂缓发布不自动启动下列重构。** 当前只落实共同契约、任务依赖和验收定义；发布收尾参考[发布与顺序复核](../research/topics/v0.0.6-release-and-refactor-order-review-2026-10-15.md)，其中保数据前置已由 Owner 后续预览决定取消。包体优化的[主机验证](../evidence/development/apk-size-optimization-2026-10-15.md)之后，测试签名 R8 隔离包已有真机启动、两渠道模型工具旅程、QuickJS 和首次 RootFS 安装/验证[实际证据](../evidence/development/device-ui-model-validation-2026-10-15.md)；当前 API36 的独立配置/会话恢复补验见[收尾记录](../evidence/development/pre-refactor-finish-2026-10-15.md)。仍需界定最终分发身份和执行恢复覆盖，不能将测试签名包当成已发布制品。重构前功能取舍见[本轮分析](../evidence/development/preview-polish-2026-10-15.md#重构前功能取舍)。

| 顺序 | 任务 | 推进方式与依赖 |
| --- | --- | --- |
| 1 | [HXA-286](tasks/HXA-286.md)：统一工具契约、插件贡献与前置依赖 | 共同定义 Turn/call/Job/长期资源/产物身份、四个完成条件、取消/迟到、重启 reconcile、清理及上下文正确性；与规范工具/发现依赖和删除清单一起落地，参与设计不依赖后续代码先完成 |
| 2 | [HXA-287](tasks/HXA-287.md)：应用命令与完整 Turn；[HXA-288](tasks/HXA-288.md)：结果、Job 与产物 | 286 完成后分别推进 287-A 受控 executor 的完整 Turn 最小纵切与 288-A 执行核心；两片就绪后共同接真实执行/最小 Room，再扩展宿主和领域适配，整任务不互设启动前置 |
| 3 | [HXA-289](tasks/HXA-289.md)：glob/grep；[HXA-290](tasks/HXA-290.md)：网页研究 | 契约稳定即可按路径交付，不等待所有宿主工作。真实搜索依赖 provider/配置，正文提取和夹具可先做 |
| 4 | [HXA-291](tasks/HXA-291.md)：MCP Resources；[HXA-292](tasks/HXA-292.md)：Browser 表单与 Mobile Use 参数 | 复用来源、授权和结果/产物契约；按独立功能切片，不重开已结案 OAuth |
| 5 | [HXA-293](tasks/HXA-293.md)：Memory/上下文质量 | 来源/快照/配对/授权与恢复正确性在 286/287 定义并验证；检索/排序/摘要策略在稳定基线上单变量评估，无收益不改默认 |
| 6 | [HXA-294](tasks/HXA-294.md)：冻结后的集成与恢复验收 | 汇总各阶段已积累的正确性证据，回归冻结范围并清理旧路径；不是第一次验证取消/身份/恢复，设备/模型/时长仍按当轮授权 |

发布之后的首个重构检查点是 HXA-286 的共享契约及目录/依赖生产接线；287/288/293 在 286 共同设计，后续共同集成须等 287-A/288-A 各自就绪，不代表无依赖并行改公共接口。287 首片即验证 crash/reload、call/result 配对、无 UI 审批/停止，288 先核心后领域适配；完整 Turn 与真实 Room 分别举证，不等待第二个产品消费者。不包含独立 Server、CLI、云宿主或第二个 Engine；每条生产路径切换即删旧 DTO/别名/双写/永久桥，外部格式和真实基础设施适配保留。

本次扩展性补充不改变顺序：HXA-286 定义组件、阶段与长期资源 owner/控制权等共同契约，287/288 分别落实独立运行与执行资源接线，289/293 沿已定义内容版本/快照推进，294 汇总反例。目标见 [§23.9](../architecture/harness-refactor-plan.md#239-后续扩展约束)。四类功能仍未立项；“单一引擎”不限制为全局单运行，“结束调用”不等于销毁共享资源，验收不以提前建空 hooks/LSP/子 Agent 框架完成。

既有生命周期补验、HXA-284 文档阅读、HXA-285 引用/压缩收尾及 R2-B 有界对照不重复实施。Notion/Linear全部测试和黑屏已结案；长稳、提前结束专项继续暂停。旧 R6 不证明新制品长稳，其他 OEM/资源峰值等边界留在原证据；有新可复现故障时定向处理。签名、稳定包名/渠道及正式发行独立于本重构，见[发行条件](release-readiness.md)。上述规划阶段未运行门禁或操作设备；后续 Owner 追加的优化与验证以本页当前实施记录为准。

### 插件优化队列（2026-10-11）

Owner 已要求将[调研](../research/topics/agent-plugin-architecture-research-2026-10-11.md)和[优化建议](../research/topics/helix-plugin-architecture-optimization-2026-10-11.md)结合当前代码拆成任务，并明确**不兼容旧版，按单一目标实现设计**。HXA-272～279已登记；本轮Owner授权收尾交互并完成272～275，272统一内容实现的主机/source、Developer API36 11项含真实Qwen、Standard纯Skill 1项及作者样例解析均通过；迟到取消崩溃已修复并复验，其他OEM/长稳边界保留。273调用资格与可信输入已通过全仓主机/source、Developer API36含Qwen5项、storage17项和Standard窄屏/大字体2项；[273证据](../evidence/development/hxa273-skill-invocation-2026-10-11.md)保留失败尝试。274本轮同源就绪投影与恢复导航有界交付，含全仓主机/source、Developer组合20项、最终内容/Qwen4项与Standard窄屏11项；[274证据](../evidence/development/hxa274-plugin-readiness-2026-10-11.md)保留失败尝试。275组件诊断/作者样例已通过全仓主机、Developer API36含Qwen8项与Standard窄屏13项；[275证据](../evidence/development/hxa275-component-diagnostics-2026-10-11.md)保留失败尝试。276已完成单一贡献与双渠道有界验收，277资源契约已交付，278有界实现与验收已交付，279本地有界实现与验收已交付，Gboard输入环境失败及未测边界独立保留。当前代码复核与研究切片映射见[优化方案 §7](../research/topics/helix-plugin-architecture-optimization-2026-10-11.md#7-任务承接与验收)。

2026-10-11 已按所有者要求同步四份现有主题 ADR 的有效正文与 Decision history，见 [HXA→ADR 归属表](../adr/README.md#插件优化任务的-adr-归属2026-10-11)。HXA-277 的通用原生资源规则归 ADR-CONNECTORS-003，Browser 具体生命周期归 ADR-PLATFORM-002；设计已接受，277已完成授权有界实现与验收，后续完整故障组合和长稳仍需独立证据。

**调度规则。** HXA-269/270/271 已交付的切片不重新列为进行中；新发现的相关执行错误、授权错误或数据损坏优先修复。其余 OEM、账号和已记录设备补验独立排队，不作为全部新开发的前置。272～279本轮切片已交付；后续优先处理有实际复现的故障和已记录设备边界，不自动启动条件性新功能。2026-10-12已定位测试AVD的Gboard公共中文资源损坏，保留原数据并建立实际拼音通过的自有替代环境；真实Qwen浏览器像素识别、图片传输及操作核验和双渠道900秒pilot已补验。同一OnePlus/API34的Root/Shizuku定向任务及两小时应用资源precheck已补验，后者主机报告器编码/异常结算已修复并回归。旧制品R6连续Mobile Use/Qwen两小时已通过；最新观察版本未继承该结果，其他OEM、未覆盖视觉场景及24h长稳独立保留。实体黑屏问题按Owner规则关闭，完整证据见本页顶部。

下表保留已交付任务的原实施顺序与技术依赖，不是当前待办。原 P1/P2 是开发优先级，不是缺陷严重度；未覆盖验收及新工作按上方 Next task 和原 HXA 推进。

| 顺序 / 优先级 | 任务与交付 | 技术硬依赖 | 退出重点 |
| --- | --- | --- | --- |
| 1 / P1 | [HXA-272](tasks/HXA-272.md)：统一 Skill 内容贡献，零工具插件独立激活 | 已交付 R1/R3/Skill 基础 | 单一内容来源，list/read 与会话选择一致，无占位工具或旧正文通道 |
| 2 / P1 | [HXA-273](tasks/HXA-273.md)：用户/模型调用资格与本次显式激活 | 272 | 实际读取检查资格，用户输入来源可信，不由内容或模型伪造激活 |
| 3 / P1 | [HXA-274](tasks/HXA-274.md)：就绪原因、保存/加载/请求生效反馈、恢复导航边界 | 272；复用 271 已交付部分 | UI/模型同源只读投影，恢复目标由当前页面处理，无被动授权/连接或服务持有页面导航回调 |
| 4 / P1 | [HXA-275](tasks/HXA-275.md)：组件兼容诊断和三种作者样例 | 272、273、274 | 当前外部格式支持明确，局部可用，样例走生产解析/执行路径 |
| 5 / P2 | [HXA-276](tasks/HXA-276.md)：Browser 原生插件贡献与统一选择 | 272、274 | 12 工具唯一 PluginOrigin，手动浏览独立，直接删除旧 built-in 注册 |
| 6 / P2 | [HXA-277](tasks/HXA-277.md)：原生实例/共享资源的停止与释放 | 276 | Turn/调度/执行/插件引用各有 owner，复用原 Runtime 退出事实，最后引用后清理，旧回调不影响新实例 |
| 7 / P2 | [HXA-278](tasks/HXA-278.md)：Skill 渐进装载、失效与压缩一致性 | 272、273；推荐 275 后 | 内容正确性先交付，收益用固定任务证明；不保留生产双策略 |
| 8 / P2 | [HXA-279](tasks/HXA-279.md)：UI 观察事实、Browser 标签发现与有界等待 | 276、277 | scope/deadline/取消有效，未知动作不重放，有界任务对照 |

HXA-278/279 的原准备与接线依赖已经随各自切片完成，不再等待 275/276/277 重新开发。HXA-235 保留安装/引用/取消更新/进程死亡的原验收，HXA-277 只承接新增原生资源；HXA-271 保留已有系统授权恢复产品行为及验收，HXA-274 已扩展同源投影并替换所涉恢复导航承载接口。HXA-252 的发现提示和既有 R1/R3/J1/J2/视觉能力不重新立项。

**目标实现约束。** Skill、native 与 MCP 使用同一工具治理链，按真实执行边界选择 adapter，Mobile Use 保持 native。旧 Skill 注入、Browser built-in 发布和被替换的内部接口随任务删除；不安排旧版数据库/会话/批准迁移、双写或兼容桥。开发期持久化按现行 baseline 规则处理；本轮计划更新不操作数据。当前外部包格式的互操作支持与旧 Helix 兼容是不同范围。

**条件队列。** 外部异步 provider（F）先确定一个服务与 start/status/wait/collect/cancel 语义；文件流程（G）先选一项格式/任务及双渠道可用依赖。具体准备步骤保留在[候选索引](candidate-decisions.md#插件研究的条件性增量)，暂不创建无服务或格式输入的实现 HXA。MCP 服务端导出、Code Mode、任意 hooks/LSP 执行和完整定位器仍按各自进入条件处理；HXA-275 只诊断支持范围。

### 系统分层优化承接（2026-10-11）

本节保留2026-10-11系统分层分析的原承接与交付边界，不是待实施队列。Owner要求将AgentLoop/Core/UI/Turn/工具/权限分析纳入原计划与ADR；[源码分析与去重表](../research/modules/01-architecture-and-execution-engine.md#9-2026-10-11-分层复核与优化承接)、[当前架构](../architecture/overview.md)已同步。当前排期看上方Next task；此处整理不新增HXA或改变设备验收结论。

| 优先级 / 实施位置 | 承接任务 | 本次补充与完成边界 |
| --- | --- | --- |
| P1 / 原队列 1～4 | HXA-272～275 | 内容/调用资格先行；274 内按“结构化原因与恢复目标 → 同源投影和页面消费 → 删除旧导航回调/重复 ready 推导 → 生命周期验证”实施，275 复用结果。只收窄实际恢复消费者，不重做271系统授权中心 |
| P2 / 原队列 5～6 | HXA-276、277 | 先 Browser 单一贡献，再补真实资源的 owner/引用/释放；277 明确 Turn live owner、Scheduler 槽位、Executor/Runtime 容量及插件引用的区别，复用原退出/对账事实，无第二执行注册表 |
| P2 / 原队列 7～8 | HXA-278、279 | 沿原依赖完善内容与观察；279 消费277的实际 owner/generation，不自行解释 Runtime 是否结束 |
| 原条件项；2026-10-15已调整 | 应用入口收窄、完整 Turn 宿主提取 | 旧阶段要求实际新消费者；本次评审以当前 App/Room 解耦与完整无页面测试为明确需求，已归 HXA-287。当前依赖与验收看上方 Next task，不再套用旧进入条件 |

对应有效决定已补入 [ADR-AGENT-001](../adr/agent/001-turn-coordination.md) 的应用入口/表现/执行边界与 [ADR-CONNECTORS-003](../adr/connectors/003-ownership-and-installation.md) 的恢复投影和原生资源条款；HXA-274恢复投影及HXA-277资源契约代码已交付，验收以各自任务为准。原权限与工具准入规则继续适用，不另建“总引擎”ADR。完整Turn宿主提取仍未实施；2026-10-14新增执行边界设计见[Harness方案 §23](../architecture/harness-refactor-plan.md#23-2026-10-14-执行边界重构提案)，不从设计文档推导实现或验收。

HXA-220/223 的 Engine owner、HXA-234 的 Core 迁移、HXA-254～258 的应用接口/测试桥/状态与事务收敛均复用原交付；不以 ChatService 行数、变成空壳、全量 MCP 化或分出更多模块作为退出条件。已有 HXA-269/270/271 当前切片及真实故障优先规则不变，完整设备/OEM/长稳/账号矩阵独立保留。

### 既有验收与发行分支

2026-10-10 架构审查后的维护切片依据[系统/测试审查](../research/topics/architecture-and-test-maintenance-audit-2026-10-10.md)已交付：HXA-253解决主机fixture漏测，HXA-254/255收敛项目/权限应用接口，HXA-256完成生产测试桥清理，HXA-257/258交付会话状态owner与元数据事务，HXA-259完成22个项目/Provider页面功能归属的主机验证，HXA-260完成路径级清理和复杂类/构建条件取舍。HXA-261～266已完成各自有界性能、页面/压缩、日志、停止/进程恢复、资源pilot和Goal/Skill旅程。已有能力不重复开发，不以ChatService行数或变成空壳作为退出条件。历史未知截断及未覆盖故障组合/OEM/长稳仍保留证据边界；黑屏已按Owner规则关闭，不能再次列为待办，也不能由结构变化推导其他问题已改善。

上方 main 本地整合及授权回归已收敛；v0.0.5 发布仍遵循精确提交 CI 成功后生成制品的门禁。下表保留后续开发路线；本次只关闭实际执行并有证据的验证项，不自动宣称完成全部 HXA/OEM/真实账号验收。

HXA-239 本地实现和主机整合完成；J2-1 AUTO 与 J2-2 用户后台按钮现由 HXA-248 完成本地主机/API36 Runtime/UI，真实模型组合边界仍开放。Project Memory 转 HXA-246 收尾验收，不重复其已交付接线，也不重做 HXA-238 或 HXA-236 基础 join、上下文/进展以及 R2-A/R3。前置检查点 `9d0ab5c0` 不自动证明本轮输入体验，239 使用自己的交付证据；固定 P5 仍需选定 clean 提交、指定设备和模型，真实内测另需试用者。

| 顺序/分支 | 剩余工作 | 依赖与退出条件 |
| --- | --- | --- |
| 1．既有重构验收余项 | HXA-251有限补验已交付；HXA-264增加双渠道停止/队列/Goal取消/恢复组合和14个实际进程死亡边界，HXA-265增加双900秒同PID资源pilot，HXA-266当前Goal/Skill模型旅程通过 | 后续按具体验收切片处理完整取消/恢复交叉矩阵、其他IME/OEM及长稳；不重复已通过旅程，设备/账号仍须当轮授权 |
| 2．J2-1 → J2-2 | [HXA-248](tasks/HXA-248.md) 本地主机及 API36 Runtime/UI 八项通过；HXA-249 新编码真实 J1/AUTO 各 1/1 | 当前模型协议失败已修复；其他服务与跨生命周期组合仍开放，有限通过不关闭全部设备范围 |
| 3．Project Memory | HXA-246 本地主机与 API36 双渠道 UI/Memory 各六项、项目记忆进程死亡恢复通过 | 其他设备/真实摘要质量和长稳仍保留，不重复已交付接线与本轮 UI 旅程 |
| 条件性补验 | HXA-232 故障矩阵/真实恢复；233/234/235/236/237/238/239 设备；125/126/190 服务；同候选 P5/独立内测 | 指定设备、账号及当次授权；缺条件只阻塞对应项，不把 fixture、编译或旧绿色当通过 |
| 有证据再做 | R2-B、工具发现/Mobile Use、模型/Memory 效果与长稳 | 固定对照或实际瓶颈；区分完成率、时延、成本、资源与 OEM，不凭减少 token 宣称提升 |
| 发行 | HXA-120 → 122 → 121 → 123 | 渠道审计→身份/签名/升级承诺→同签名候选验收→材料/提交；发布另需明确授权 |

上述表格保留既有工作的交付与验收分支，不承担当前排期。PDF/DOCX阅读已由HXA-284交付主机及10月15日API36/真实模型有界验收，其他格式、编辑/生成、视频、子Agent和远程能力不随该交付启用。真实用户试用尚未执行，P0～P4、P7和R1的已有交付不重排为底座开发。

后续结构与功能投入由实际瓶颈决定，选择方法见[开发策略](feature-refactor-strategy.md)，目标契约见[Harness 方案](../architecture/harness-refactor-plan.md)，非重构依赖见[内测](internal-pilot.md)和[发行就绪](release-readiness.md)。账号、真机和发行输入不阻塞无依赖的本地工作。

最近记录的 clean 正式 P5 来自 `92e93bf5`，固定 15/15 与准备 smoke 1/1 通过，详见[Runtime / Provider 设备收口](../evidence/development/runtime-provider-device-closeout-2026-09-30.md)和[系统基线](harness-system-baseline.md)。此前 `8c7a95b4` 和各 candidate 证据保留；新改动不自动继承历史绿色。

## Blocked

| 项目 | 缺少条件 / 决策 |
| --- | --- |
| 16 KiB 物理巡检 | Android 15+、16 KiB 页面物理硬件 |
| HXA-125 受保护 Connector | Notion/Linear真实账号切片已结案；其他Connector契约按原任务范围维护，WorkBuddy来源样本已有 |
| HXA-126 外部验收 | Notion/Linear真实服务测试已结案，不再追加授权、自然刷新或恢复验收；保留实际通过与未执行边界。自有HTTPS/App Link与发行签名属于发行准备 |
| HXA-190 真实订阅 | Claude/Grok 付费账号不可用，按所有者决定暂缓；fixture 不替代 |
| 发布验收 | HXA-122 的稳定 applicationId、渠道命名、签名与升级路径；保持 120 → 122 → 121 → 123 的发行顺序 |

## Current interfaces

职责与调用路径统一查[当前总体架构](../architecture/overview.md)。TurnEngine 是 live driver 和 durable 生命周期 owner；SessionWorkScheduler 仲裁 Queue/Goal，TurnCoordinator 保留 round checkpoint。R1 绑定契约见[工具 ADR](../adr/tools/001-descriptor-contract.md)。

会话配置在 Turn 准入冻结；跨会话引用在输入接受时形成有界快照。模型 per-call intent 只作展示，不进入业务参数、审批或效果事实；大结果分页与模型/UI 投影保持分离。具体规则见[上下文](../adr/agent/002-context-compaction.md)、[结果/预算](../adr/agent/006-model-data-budget-boundaries.md)、[Goal](../adr/goal/001-lifecycle-and-completion.md)。

QuickJS 默认 isolated UID，原生总开关对应共享应用 UID 私有进程；PRoot/订阅也不声称凭据隔离，见[执行域](../architecture/local-code-execution.md)。后台 Job/手动 PTY 独立，A2A Client 不等于本地子 Agent；手动文件/配置/任务控制不必创建模型 Turn，WebView 仍由浏览器 Activity owner 持有。

## Known limitations

| 范围 | 当前保留边界与证据 |
| --- | --- |
| SAF/模型偶发行为 | 已修复项不重开；其他 OEM 空列表、OUTPUT_TOKEN_LIMIT、探测偶发失败、skill-001/003 多余调用保留原轨迹，见[偶发记录](../evidence/development/intermittent-closeout-2026-09-28.md)与[最终收口](../evidence/development/final-closeout-2026-09-29.md) |
| 系统与长稳 | HXA-265双渠道900秒及OnePlus/API34 Developer两小时应用资源precheck的设备门禁通过；[两小时原报告失败、修复与离线核对](../evidence/development/resource-gate-review-2026-10-12.md#完成后的离线核对)独立保留，24h、其他OEM及全部Runtime未由此验收。JNI/Binder、goldfish FD/UID-proxy Binder 根因未全部关闭；[释放调查](../evidence/development/native-reference-release-trace.md)、[浏览器核验](../evidence/development/browser-controller-reference-verification.md)、[剩余调查](../evidence/development/main-optimization-todo.md) |
| 真机/OEM/资源 | OnePlus API35 Root 与[P0修复](../bug-fixes/2026-09-17-physical-p0-baseline.md)是固定范围；其他 OEM、低内存、热压、Doze、Root grant/revoke/loss、16 KiB 另验；x86_64 静态制品不证明运行 |
| 文件与 Runtime 恢复 | HXA-182 不承诺断电事务、字节续传、跨 Provider 原子性或自动后台队列；目标/备份变化需核查。订阅结果物化与资源压力的历史边界结合 HXA-232 后续切片判断，见[原记录](../bug-fixes/2026-09-18-authorization-runtime-convergence.md) |
| 模型与附件 | 导入不等于理解；图片受视觉能力/预算约束；任意文档、音视频、OCR 未因管线存在而交付；真实 token 偏差与物理内存峰值仍须测量 |
| 未来能力 | Project Memory 的完整设备验收仍开放；持久 Git 写操作/远程凭据、生产子 Agent/Workflow 未交付；其余见候选索引，accepted 不等于实现完成 |
| 发行/测试跳过 | debug/CI 不等于签名 release、商店审核或渠道验收；HXA-184 外部材料、长稳和设备条件跳过不计通过；外部 profile 缺失与无效分开，见[公共验收](verification-matrix.md) |

历史命令、测试失败、提交与安装快照继续保存在原[完成记录](../completion-records/index.md)和[证据](../evidence/README.md)，不因本页收短而删除或扩大验证结论。
