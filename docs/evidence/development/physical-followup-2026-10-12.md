# 真机补验与黑屏问题结算

2026-10-12，Owner明确要求连接真机测试，并调整HXA-269结算：本轮未复现则关闭黑屏问题。此前主线程阻塞修复保留；不因Owner接受结算而宣称所有历史黑屏均已证明同一原因。

## 设备与数据边界

唯一实体为OnePlus 6T / ONEPLUS A6013，Android14/API34。它的当前系统/安装包与10月10日API35证据不同，不能沿用旧安装身份。已安装Developer为0.1.0、9月24日更新，APK SHA-256 `78f41fb5b2ff52d909f3b406920785edbd5439df7cdf72962a5bd7959cd04732`；Room identity `e2749856a75a203692e582c5336d2348`，1会话/6Turn，备份integrity_check=ok。原APK和DB/WAL/SHM仅在ignored、0700目录及0600私有数据库副本，不进入仓库，不读取配置密钥/用户正文。没有覆盖、卸载或清理Developer数据。

当前Developer候选schema为eb439f0c60ced11912eaac8b50dada5b，直接覆盖将触发开发库重建。按HXA-269“更新主APK前报告开发库风险并取得确认”询问；首阶段未得到回答前没有执行依赖动作。Owner随后明确允许备份后更新/重建及临时无障碍/悬浮窗、现有Root测试，追加结果如下。

Standard包此前未安装；本轮安装独立com.helix.agent及其测试APK，合成夹具只在这个独立库运行。生产APK `df979f3e9f040b48405d39d61475076f7c89ad43901d7a4a73043efc579853f8`，测试APK `aade96413c4599d71490b9a5d173ebaaf34d10f88a6fa153a56184cfd153434b`。未使用日常模拟器、真实账号或旧Developer库做fixture。新Standard测试包保留在手机，未擅自卸载。

## 当前实际结果

| 检查 | 实际结果 | 边界 |
| --- | --- | --- |
| Standard组合UI与平台后台 | 20/20 passed，25.725s | 实际HOME后原请求/前台服务完成并返回；配置不足解释/确认/取消3项、会话可见性、工具布局、上下文及过程/最终展示；不是Mobile Use执行 |
| 模型socket取消/断流/旋转 | 4/4 passed，10.56s | 原Goal/请求身份与真实loopback SSE socket，不是Qwen远端故障 |
| 新Standard普通后台返回 | 6次，背景2/2/4/4/8/8秒 | 每次0.3秒及随后2秒截图、焦点和PID；同PID10595、焦点正常；查看实际空会话界面，没有黑屏 |
| 原Developer只读页面返回 | 2次，背景2/4秒 | 同PID11365、焦点正常；实际会话列表截图正常，没有进入用户正文/发送消息，不证明旧包含本次源码修复 |

exit-info没有本轮ANR/FATAL退出；Standard有测试正常完成instrumentation导致的USER REQUESTED/FORCE STOP，不能归类ANR。命令TotalTime及含截图/等待的host耗时不是首帧指标，也不把同PID单独当作渲染通过。原始APK、日志及截图在`build/physical-followup-20261012/`，仅保留此去敏摘要。

结果解析器首次拒绝PhysicalBackgroundRecoveryDeviceTest发出的code2、helix.physical.phase诊断。新增仅接受“一个实际方法正在运行、单独非空阶段字段”的处理，诊断不创建通过/不结算方法；缺方法结束、无活动/多活动、空值及部分身份仍失败。原20个方法的完整开始/终态及runner结束经原入口严格核实；新增host回归9/9通过，没有过滤失败日志或凭OK总数冒充逐项通过。

全仓host/source通过：source入口207项、文档/ADR/i18n/secret；包含可选模块的test、detekt、spotlessCheck、双渠道lint/APK及App测试APK BUILD SUCCESSFUL、2m5s、1117实际任务。Python诊断解析变更不影响手机APK；无生产代码改动。

## 结算与剩余

按Owner新规则，HXA-269黑屏问题关闭：本轮未复现，历史已确认无障碍主线程同步节点等待路径已修复并曾回归。历史视觉黑屏未直接复现，不能证明阻塞是所有黑屏的唯一原因；若出现新现场，按新的实际证据排查，不保留无证据的历史黑屏待办。

首阶段Mobile Use/Root/Qwen曾等待Owner选择；已获得授权并完成下方有界补验。Shizuku未安装，未擅自安装或启动新服务。锁屏安全解锁、进程死亡、其他OEM、手机整屏视觉及2h/24h长稳没有在本轮手机执行；已有模拟器/历史真机结果不升级为本轮通过。黑屏结算保持关闭，无障碍模型失败后续已定位修复并完成同机回归，见[观察稳定性修复](accessibility-observation-stability-2026-10-12.md)。下文保留修复前结果。

## 批准后的升级、后端与模型补验

同一OnePlus/API34，Developer生产APK `76aedcf687853c4ae7241a7b88cc662524a4e2ddbbee5e94add0c325a68725a9`，安装后从实体读取匹配；新库identity eb439f0c60ced11912eaac8b50dada5b、integrity_check=ok、初始0会话/0Turn，证明按批准重建，不说保留原数据。旧APK/库备份仍在私有ignored目录。APatch版本11224，已有su返回UID0；ADB自身未切Root。无密钥本机30008/v1、Qwen3.8-27B模型列表及上下文262144可用，通过临时ADB reverse连接，没有使用其他账号或.env凭据。

| 当前补验 | 实际结果 | 核验范围 |
| --- | --- | --- |
| Mobile SDK悬浮窗/服务 | 8/8 passed、21.852s | 真实gesture取消等待后的引用/物理容量、抑制与恢复、接管、只导航返回、权限撤销/可见性、事件风暴与失效查询；是独立SDK测试安装 |
| Root直接探针 | 1/1 passed、4.572s | 实际App RootService UID0及精确目标DISPATCHED，不把shell UID0单独算Root可执行 |
| Root Dispatcher及显式断连恢复 | 1/1 passed、8.112s | 实际Root点击一次；断连且原Binder死亡后新独立无障碍调用，最终独立观察clicks=2；只读、范围外、旧许可、缺失目标拒绝保留；不是未知结果跨后端重放 |
| Root短按与取消前置 | 1/1 passed、10.825s | 实际3次DOWN/UP和60..250ms短按、各一次效果/审计；预取消、只读、旧许可及范围外拒绝；不等于全部Root中途取消/UNKNOWN矩阵 |
| 真实Qwen/Root合成表单 | JUnit1/1、两轮业务2/2 passed | 28.441/21.530s，实际输入你好 Helix、滚动、一次点击，独立ContentProvider核实TEST_DONE/次数1；实际工具回填包含Root backend；不是手机IME拼音/整屏视觉或自动返回验收 |
| 真实Qwen/无障碍同组表单 | failed | 首轮Turn COMPLETED、业务未成立；次轮240秒测试超时，原始失败保留，不据外层调用完成认定成功 |

无障碍首轮与次轮均观察到输入之后多次ui.snapshot/ui.find返回TARGET_CHANGED；次轮另有STALE_TOKEN及ui.launch测试APK的ACTION_NOT_SUPPORTED。新Scope仅允许Helix与其测试APK，没有放宽节点/目标/权限保护。Root对照在两个新合成会话执行，不复用/重放原失败调用；无障碍服务关闭时Root两轮仍完成。这个小样本将诊断重点收窄到无障碍观察/失效恢复及测试应用不可启动的恢复限制，不能证明唯一原因、Root普遍准确率或所有模型无责任。保留此开放缺陷，当前未修改生产快照安全判断或工具策略。

失败环境记录：`mobile-qwen.txt`/按App PID过滤的`mobile-qwen-logcat.txt`及只查询Progressive mobile合成会话的inflight诊断。SDK结果`mobile-sdk.txt`；Root初始绑定等待失败、r2等待失败、r4 UNKNOWN及探针TARGET_NOT_FOUND分别保留。修正测试：MainActivity先启动、授权后显式connect；Root期间不用测试自己的UiAutomation占用同一系统服务，ADB在MainActivity可见之后临时启用普通服务；固定fixture关闭视觉ALL_CAPS，精确文本保持Fixture click及原viewId，不放宽生产匹配/不删除负向断言；探针每次新建自己的fixture task。

Android14 [UiAutomationManager源码](https://android.googlesource.com/platform/frameworks/base/+/android-14.0.0_r1/services/accessibility/java/com/android/server/accessibility/UiAutomationManager.java)核实同一UIAutomation服务重复注册会拒绝；curl读取的原始内容仅在ignored证据目录。测试冲突发生时Root后端保守返回UNKNOWN，独立探针没有持有测试UiAutomation，能返回精确TARGET_NOT_FOUND；调整标签/测试连接之后正常执行，不归类生产Root修复。

最终Root原生制品测试APK `081afbf4d8de53c1946c74e136b16001231a5ade03be61bfa629c12e28b056a7`；随后增加显式pluginSkillBackend=root对照，最终测试APK `7dc7970945bfa27a0b3f25b6f677028915a80a0a1eb6e6016f4c27edc6eeeb49`。普通无障碍默认路径和原后台返回断言保留；Root对照只验业务，避免测试UiAutomation竞争，不宣称Root模型后台返回4/4。诊断覆盖所有失败业务调用，即使其外层COMPLETED也记录领域结果。解析器扩展code2单独stream诊断，只在一个活动方法期间接受非空字段，仍不产生方法终态；增加空值/部分身份/多活动/截断回归，10项通过。

最终包含可选模块的全仓test、detekt、spotlessCheck、双渠道lint/APK及App测试APK通过：`host-final.log`、54s、1117实际任务，生产制品未改变。source最终检查另在source-root-final.log记录；未提交私有日志/数据库/模型内容。全部临时无障碍恢复null/0、悬浮窗恢复default、自有30008 reverse移除；Root服务/连接退出，ps没有Helix Root服务，测试模型/会话清理，未更改Root manager配置。最后返回Helix页面；原历史黑屏仍按Owner规则关闭。
