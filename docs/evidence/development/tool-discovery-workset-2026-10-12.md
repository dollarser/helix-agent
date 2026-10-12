# 工具发现与请求工作集优化验证

日期：2026-10-12。HXA-280。Owner授权实现及模拟器/真机/现有模型验证；研究和取舍见[工具专题§12](../../research/topics/tool-exposure-and-discovery-2026-09-29.md#12-2026-10-12-工具架构重新评估与优化设计)。只记录实际切片，不将设计或历史结果当新验收。

## 切片1 取消全局工具数量上限

移除生产与评测组装的固定take、ModelRequest/ModelToolBindings/manifest的全局64计数校验。唯一名称、精确binding、schema校验、现有上下文预检和256KiB manifest字节边界保持；不改数据库或Provider能力快照，不假定任意服务有统一数量上限。

新回归验证160个工具的请求和最后一个绑定、完整manifest及过大绑定清单明确拒绝；曝光排序测试改为完整集合和安全准入断言，不再维护默认工具数门禁。模式/禁用和发现失效断言保留。定向core:model及app曝光/发现JVM通过；首次格式命令误用了不存在的模块spotless任务，改用已有根spotlessKotlinApply后通过。全仓source/host220项、docs/ADR/i18n/secret通过。日志在ignored `build/tool-cap-host-final.txt`、`build/tool-cap-source-r2.txt`。

该切片当时未执行设备和模型；后续整合验收见下文，不把定向通过当整项完成。

## 切片2 同源能力目录与检索

目录从本次已准入的descriptor快照投影，提供来源和类别，正文有字节预算并标记截断；不是全量工具正文或另一注册表。tools.search版本2增加source/category过滤、来源与类别输出；索引已有名称/描述、来源、参数字段和宿主维护的常见中文动作词。availability和精确版本过滤保持，未选择/禁用来源不借发现启用。

新增中文截图/归档、字段查询、来源过滤、会话隔离和动态禁用目录回归；原取消测试与ownership测试改为当前注册版本，保留取消和资源身份断言。app MCP定向JVM、spotlessCheck和detekt通过。中途长行、复杂方法及测试写死版本导致失败，分别拆分只读元数据/相关性计算并修正当前契约引用，未放宽静态门禁。证据为ignored `build/tool-discovery-host-r5.txt`、`build/tool-discovery-static-r3.txt`。

已冻结原主APK/test APK，其hash与此前真机长稳制品一致。自有API36/Qwen流程与Browser各两轮业务4/4完成，其中Browser一轮先调用未曝光动作，被正常拒绝后发现恢复；记录全部调用与用量供候选对照，不以4/4推导零无效调用。Mobile Use基线和候选整合仍在后续切片，日常模拟器未操作。

## 切片3 近期工作集与完整操作组

tools.search版本3将匹配项与仍有效的近期发现合并，不再按16项替换整个窗口。64KiB估算schema预算只控制旧项保留，当前匹配及其已准入宿主前置工具不因预算丢弃；最终上下文和manifest字节门禁仍是请求条件。估算包含名称、描述、schema UTF-8及128字节框架，不冒充Provider实测token。搜索无匹配不清空旧工作集，契约/来源/可用性变化重新校验。宿主操作组补齐Browser观察、Mobile Use节点或手势观察、Linux Job查询/收取/取消/等待；只在同源且当前允许的集合里补齐，不接受远端自报依赖授予能力。

MCP从按工具数16/17的二分阈值改为16KiB估算成本，按来源整组确定性选择；其余保持可搜索。这不是新的整个请求数量限制。Mobile Use默认仅延迟ui.device，手势发现会补齐设备和截图；没有移除截图、节点观察或动作常用组，也没有增加用户预算设置。

新增超过16项近期保留、同数量不同schema成本、预算淘汰、无匹配保持、不可用前置工具排除及超过旧MCP阈值的小schema常驻回归。原窗口替换/提示断言更新为新契约，取消、撤销、UNKNOWN和权限断言保留。全仓JVM、spotlessCheck、detekt及两渠道Debug主/测试APK编译通过（`build/tool-workset-integration-r2.txt`）；后续完整门禁单独记录。

## 固定真实模型对照

自有只读API36实例，Qwen3.8-27B、本机30008服务、相同合成任务及各两轮；原APK SHA256为`8102cc38e48eae8903a07389e9b9b771542614b1992286304b7a24d681f4f916`。原版与候选均6/6业务成功，后台返回各4次通过。用量为Provider每次请求报告的累计输入/输出，不是最终保留上下文占用；重复输入照实计入。按先基线后候选执行，服务负载和生成随机性未配对消除，因此只作有界回归，不能归因全部耗时差异。

| 任务/轮次 | 基线模型请求 / 输入 / 输出 / 毫秒 | 候选模型请求 / 输入 / 输出 / 毫秒 |
| --- | --- | --- |
| workflow/0 | 4 / 37155 / 824 / 15379 | 2 / 16356 / 737 / 7499 |
| workflow/1 | 2 / 16042 / 943 / 8671 | 2 / 16354 / 841 / 7334 |
| browser/0 | 8 / 80903 / 1319 / 12656 | 6 / 52655 / 877 / 11085 |
| browser/1 | 6 / 58112 / 972 / 11729 | 5 / 50975 / 1171 / 13648 |
| mobile/0 | 8 / 124031 / 753 / 16744 | 8 / 129688 / 846 / 47799 |
| mobile/1 | 9 / 139093 / 1393 / 19116 | 8 / 129929 / 783 / 22126 |

两组Browser第0轮均调用一次未曝光browser.click，Dispatcher明确拒绝，搜索后成功；没有证明此类误调用消失。Workflow和Browser请求/输入减少，Mobile输入和耗时为混合结果；不宣称统一成本或成功率提升。原始用量与所有调用见ignored `build/tool-optimization/baseline-model/`、`baseline-mobile-model/`、`candidate-model/`。

## 请求完整性与真机

新增opt-in Developer真实模型夹具，经连接探测后创建自有Provider/会话，隔离其他工具，只向正常生产组装、绑定、持久化和Dispatcher提交70个无外部效果的MCP-origin工具。Qwen调用最后一项一次，Turn COMPLETED，首请求manifest完整包含70项；不能把此夹具误称为真实远端MCP服务协议验收。夹具最后恢复原profile、删除自有Provider/会话/可用性记录和注册。

初版夹具先建会话、后探测连接，准备阶段被正常拒绝，候选首套件5方法中1失败，另4方法及6业务轮通过；修正测试准备顺序后，独立完整性/发现/实际中文拼音3方法通过。失败原日志保留，不删除或改写为通过。实际中文检查使用IME拼音组成与“你好”草稿提交，零发送，不以UTF-8注入替代。

同一OnePlus A6013/API34 Developer备份后保留数据更新候选APK；Room identity相同，无重建。完整性、发现、Workflow、Browser4方法及4业务轮通过，无Root/无障碍/浮窗权限变更，无真实账号操作。检查发现原发现夹具留下自有Catalog会话；修复finally清理，受显式单个fixture ID、标题/无Provider及仅CREATED/搜索记录校验的恢复入口清理本次残留，定向复测1方法通过。原8会话、10个Turn、5个Provider记录逐行一致，数据库integrity=ok，临时ADB reverse已撤回。外层临时汇总脚本首次将tuple键直接JSON编码失败，修正为class/method列表后从原逐方法日志重建汇总，不重新推断设备通过。

ignored证据：`build/tool-optimization/physical-check/`、`physical-before/`、`physical-after/`；数据库和原始记录不进入仓库。

随后组合重跑6方法中5通过，业务仍6/6；70工具夹具在提交前遇到已删除的旧UI会话。这次定位到生产启动接缝：resolveTurnSession即使有显式目标也急切读取currentSession，旧UI引用可先抛异常。将UI解析改为惰性函数，仅无显式目标时读取；显式目标不存在仍拒绝，绝不回退其他会话。新增正常/缺失显式目标均不读取抛错UI引用的JVM回归，两渠道单测及主/测试APK重新通过。未将组合失败误算为模型拒绝70工具，也没有靠放宽断言隐藏该缺陷；修复后同顺序组合及同机定向重测另行结算。

修复后自有API36 Developer同顺序最终组合6方法通过、0失败/跳过：Workflow/Browser/Mobile六业务轮、70工具最后项调用及完整manifest、发现撤销、实际中文拼音。Mobile后台返回4次通过。最终Browser两轮都发生一次未曝光调用后正确发现恢复，保留此事实，不把最终全绿等同无误调用。ignored `candidate-final-r2/`记录该次独立结果；前两次失败目录保留。OnePlus/API34最终同四方法再次通过，临时reverse撤回，8/10/5原记录仍逐行一致；最终已安装主APK与测试制品hash一致。

最终Developer主APK SHA256：`b37605302164ac54c47f2b8ba18c67841a61e1aa5eeed49b6e301657c23e5892`；测试APK：`c0a4d11fb9335cee0ee1698bf3d6bedadf4180e44ce789cf6c3c05b86ed87fc0`。实现提交`c946d099`。本轮执行跨过Mac当地10月13日零点，UTC及任务日期仍10月12日，原日志时间不改写。

## 全仓门禁及剩余边界

首次完整source/JVM/静态、双渠道Debug/Release lint和制品构建通过，但依赖锁验证发现既有chat-reply缺锁及Browser锁只含JVM配置。按现有Gradle入口补齐chat-reply锁和Browser Android配置锁；Browser原19个artifact版本全部保留，新增178个当前既有依赖的解析条目，无依赖版本/仓库变更。锁验证40文件通过，未放宽门禁。

`./scripts/check-all.sh --all`最终exit0（ignored `build/tool-optimization/full-host-final.txt`）：220项host、docs/ADR/i18n/secret、全仓JVM/spotless/detekt、双渠道Debug/Release lint及主APK、private Runtime制品、40文件依赖锁、两个渠道Debug/Release打包边界和CLI边界全部通过；两渠道AndroidTest APK另行编译通过。不是远端GitHub CI或发布/商店验收。

Standard初次发起额外设备检查遇旧端口占用，启动前拒绝，未执行测试；换新端口自有API36实例后发现1方法通过，拼音方法因系统无IME窗口失败。失败窗口XML实际显示“System UI isn't responding”，不是将它归为Helix或Gboard代码根因。保留`consumer-final-r2/`，主机重负载门禁结束后用新自有只读实例再验，不清除原AVD数据或关闭日常实例。最终Standard发现/实际拼音2方法通过，0失败/跳过，见`consumer-final-r3/`；没有修改断言或增加重试掩盖原失败。

Standard主APK SHA256：`e2b6cb50cb08bf851f23e76b96e0088291aab5a900fd007722fa7d15bc1f2b1e`；测试APK：`15c032ef71417e6d9603b2ac0b4b811939db6e496bdcf1816d84d10a9615da3a`。自有AVD `Helix_Validation_API36_20261012`只读无快照运行，Developer最终port5598、Standard最终port5602；两次runner退出0并关闭各自进程，不借用或覆盖日常模拟器。中文配置、硬件键盘及显示IME沿已有owned runner入口。

Developer最终类：`ProgressiveSkillModelDeviceTest#workflowGuide/#browserButton/#mobileSyntheticForm`、`LiveToolExposureDeviceTest`、`McpToolDiscoveryDeviceTest`、`PinyinComposerDeviceTest`；Standard为后两类。入口`scripts/run-owned-emulator.py`，真实模型参数`pluginSkillPort=30008`、`pluginSkillModel=Qwen3.8-27B`、`liveToolExposure=true`，Developer runner `com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner`，Standard runner `com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner`。真机最终逐方法原始status日志为`physical-check/final-instrumentation.txt`，完整preservation元数据和结果在同目录`result.json`；不把汇总脚本成功代替逐方法设备事实。

本轮不覆盖24h、其他OEM、持续两小时Mobile Use/Qwen或其他真实Provider/账号；历史首轮资源超限仍保留，另一次两小时资源precheck及报告器修复见[资源证据](resource-gate-review-2026-10-12.md)。原生Provider发现接口和向量/PTC等候选不自动进入此任务范围。
