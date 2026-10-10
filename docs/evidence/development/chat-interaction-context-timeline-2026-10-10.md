# 会话交互、上下文与工具时间线验证

日期：2026-10-10。范围为 [HXA-271](../../development/tasks/HXA-271.md)，Owner授权模拟器、现有Qwen与分阶段提交；本轮真机验证/更新 not requested。参考来源、选择与估算边界在任务及现有权限/Agent ADR，不新建执行框架。

## 改动与复现

1. Mobile Use 执行或等待审批时点击“查看会话”：只导航原会话；继续执行资格不变。“接管”仍停止。进入任意恢复中的Helix对话页（含空白草稿）隐藏浮窗，离开恢复原任务投影；不修改权限与物理执行槽。
2. 点击上下文指示器：只显示上限与占用。占用采用已确认输入加采样边界后实际保留surface估计，显示≈；旧边界未知显示≥输入，未知不伪装零；原预算和压缩准入不改。
3. 发起“先说明、写文件、再说明、读回、最后回复”的任务：文本/工具按持久消息sequence及原批次index交错。工具逆序完成、审批更新和重开保持位置；搜索按每条实际节点定位。
4. 默认工具展示动作/目标/状态，完整请求、结果、调用标识、时间和诊断展开查看。审批默认显示实际待操作内容和必要外发目标，原请求完整事实展开仍可查；批准/拒绝身份与一次消费语义保持。

## 主机与失败尝试

阶段1 `730c4105`、阶段2 `873be3c0` 已提交；阶段1包含已完成HXA-270宿主审批接线前置。本任务保留其他在途插件/ANR改动，不整体暂存工作树。

主机全仓命令：

```sh
./gradlew spotlessApply spotlessCheck detekt test \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleDeveloperDebugAndroidTest :extensions:mobile-use:assembleDebugAndroidTest \
  -PincludeSpikes=true
./scripts/check-all.sh --source
```

`build/chat-ui-20261010/host-final-complete.log` 最终主机全仓通过，双渠道APK/设备测试APK已构建，包含缺失正文显示保护与对应夹具、输入法首次语言横幅处理。最终文档/源码检查记录在 `source-complete-r2.log`。首次 `source-complete.log` 发现未闭合验收却同时创建完成记录/保留活动任务，违反仓库库存与完成记录规则；已撤去新完成记录，保留活动任务和输入法pending，不改门禁。JVM包括上下文估计、未知/溢出/目标切换、真实调用顺序、搜索每条工具定位、原审批/取消/持久化回归；不将旧未包含模块或归档XML合计冒充当前执行数量。

保留失败尝试：初次插件JVM任务名错误；AndroidTest构造参数不全、样式/函数长度、搜索旧列表分区遗漏、复杂度过高均修正，不弱化门禁。ToolPurpose原“不得把命令/正文当普通目标摘要”测试发现过度展示，修正生产摘要，原测试保留。

设备 r1：旧测试AVD已装versionCode4，候选versionCode1安装被拒绝，未卸载/降级/清数据。改新建自有API36 AVD，未借用日常实例。r2真实Qwen完成实际write/read，但位置映射误用wireId；修正为持久localId并补不同wire/local身份的夹具。r3/r4最终消息序列已落盘，测试却先读取到定向“已完成”状态更新，未等待最终文本异步投影；r4记录原始序列0/1/2/3/4/5/6/7与缺少末尾7的瞬时UI。保留最终回复在工具后与重开一致断言，改为等待那条持久最终消息真正出现在UI。真实失败与夹具失败分开归因，最终结果以新冻结轮次为准。

## 设备与真实模型结果

passed：r5 会话UI/真实模型 22/22、浮窗专项6/6。真实Qwen实际write/read各一次，最终回复在工具之后，重开序列一致；测得最近输入7413，保留回答后占用估算7472。r5 follow-up在六项浮窗通过后因Gboard设置页按钮实际名“Search language”而失败，属于新AVD输入法准备，不冒充整轮runner成功；后续拼音独立补验结果见下。

自有 `Helix_HXA271_API36` / API36 arm64 / DeveloperDebug，owned runner使用只读、无快照的新进程，每轮冻结APK并记录owner/closed；`hw.keyboard=yes`、软键盘与硬键盘同时可用。仅现有localhost:30008 Qwen3.8-27B，有界合成workspace读写，不操作真机/微信/其他账号。原始日志与制品仅在ignored `build/chat-ui-20261010/`，不入仓。

完整其他OEM/IME、Consumer设备矩阵、长稳、模型普遍完成率与真机黑屏属于原有独立边界，本记录不关闭它们。


r5 冻结制品 SHA-256：

- Developer 主APK：`b37819221b46a46ef9ccaf59299cc99ff5a980fcc314e9a87d437ef3e674b67e`
- App 测试APK：`c7ecfb7af833337643975e515531003c45df73e89aa8b5ba7bbdef952ec4e677`
- 设备/API/ABI身份在 `api36-r5/device-properties.json`，主套件 `instrumentation.txt` 为 OK (22 tests)，模型实际事实在完整 `test-logcat.txt`；浮窗六项 fresh XML及摘要在 `api36-r5/mobile-overlay/`。`closed.json` 确认自有进程结束；不把一个follow-up失败抹成runner整体通过。制品基于记录时脏工作树构建，其中既有在途代码未纳入本任务提交，不能将其说成纯净HEAD制品。


最终显示保护候选 r6：主会话/真实Qwen套件仍22/22，无skip；损坏/缺失正文留下无法定位的调用行在Turn末端，批次正常位置不受影响。Qwen实际write/read各一次，序列为用户0、说明1、工具2、说明4、工具5、最终回复7；重开一致。输入7456，投影7506。r6主APK `6104ba8abd81df199bf391aa6ac146a1d5a7a9f2da3495cd964450a711d43bc5`，测试APK `19f5d05e308192d6fa49c954efb70823ae903323ec73a06d7da072d564e50f2c`，均在`api36-r6/artifacts.json`冻结。主APK签名验证通过；本轮没有安装到日常模拟器或真机。

IME补验沿用相同主候选制品，独立轮次不再调用模型。准备尝试记录在`api36-ime-r1`～`r10`：搜索入口有“Search language”与“Search for a language”，简体中文条目显示本地名“简体中文”，默认拼音已经选中，重复点击会取消选择并隐藏保存按钮。r4已确认简体中文/拼音配置；后续处理首次语言横幅、首次启动引导、窗口检索、语言包等待，并只调整自有未运行AVD原生显示尺寸。r9/r10仍未出现你好候选，Latin字符直接进入输入框；硬件Shift切换尝试未解决。r10会话显示测试1/1通过，随后实际拼音断言失败，owned runner已关闭。`pinyin.txt`及`pinyin-ime-probe-attempt-1.png`保留节点和画面。该输入法候选/模式环境问题尚未定位，不能证明Helix中文输入通过，也不能断言生产输入框缺陷；状态为额外拼音检查 failed、输入法验收 pending。保留严格预编辑/你好提交/零发送断言，不以UTF-8注入、跳过或仅配置成功替代。后续需在自有实例复核Gboard语言包与中文输入模式，再运行同一断言。

## 工具披露交互追加

同日Owner反馈中文测试正常，并指出工具默认样式和右侧独立展开按钮仍不符合要求。中文反馈是Owner观察，不反写前轮自动夹具失败。按固定DSH流程header/样式参考，去掉卡片背景/粗标题，使用灰色文字和紧贴标题前的16dp指示符；整个48dp标题行点击只修改本地详情开关，指示符没有独立执行动作。审批仍默认可见，错误/拒绝状态保留。普通过程说明与最终回复字号区分是后续设计建议，本次没有将所有assistant消息统一折叠或修改其语义。

追加主机命令与前轮相同（本次未改Mobile Use模块，不重复其AndroidTest APK编译）；`tool-disclosure-host-r3.log` exit 0，全仓JVM/spotless/detekt、双渠道lint/APK和App AndroidTest APK通过。初次新增审批测试超出函数长度限制，提取辅助方法保留断言；验证运行与源码更新曾重叠，另一轮lint出现Kotlin FIR异常，已结束该轮并在冻结源码后串行完整重跑通过，不忽略lint。源码门禁日志为`tool-disclosure-source-final.log`。

设备仅同一自有API36/Developer，三个类ToolTimelineLayoutDeviceTest、ToolTimelineScrollStateDeviceTest、ApprovalLayoutDeviceTest，总13项。命令沿用owned runner入口，输出`tool-disclosure-api36-r2`；原始制品哈希/设备身份/owner/closed均在该目录。r1为12/13：整行可点击将耗时标签合并到无障碍header，旧测试查询默认合并树失败；改用未合并树，保留展开后实际可见断言。r2 passed：`instrumentation.txt`为OK (13 tests)，无skip，owned进程已关闭。追加回归覆盖标题/指示符点击仅展示、紧邻位置、窄屏/大字体、长参数与结果、滚动重入、待审批仍可操作及折叠不批准/拒绝。没有追加模型调用或覆盖安装日常/真机；前轮真实Qwen证据不冒充本次执行。

r2冻结主APK SHA-256为`fda1f05f75c56837651bae34745bf3916433bbc86ceb2bc4d39d2c76102eaba5`，测试APK为`d1f3911b99c17b893b2df307dfc98d140103751aacfc0d01c89d5e721468272b`；工作树既有在途改动仍未纳入本次提交，不将制品称为纯净HEAD。
