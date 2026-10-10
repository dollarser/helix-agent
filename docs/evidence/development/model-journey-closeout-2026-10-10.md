# 当前Goal契约与真实模型旅程

日期：2026-10-10；范围：[HXA-266](../../completion-records/HXA-266.md)。Owner本轮明确授权独立模拟器与既有Qwen及逐项提交。仅已有本机30008/Qwen3.8-27B（GET models窗口262144），不使用其他服务或账号。沿用accepted DSH/Goal与Skill契约，无参考代码采用、生产默认阈值或权限修改。

旧opt-in GoalRealModelUiDeviceTest按累计maxModelCalls停泊并期待BUDGET_EXHAUSTED；当前Goal只限制自动轮数，故静态检查即发现契约过期，没有执行旧契约测试或恢复旧产品行为。模型可主动报告完成，真实模型不应被要求忽略已完成任务直到额度耗尽。迁移为UI创建→模型get_goal/update_goal报告阻塞→UI编辑轮数不运行→显式recheck不产生调用→编辑本轮目标→同Goal显式继续→模型报告完成。两轮Turn完成、原Goal绑定、持久调用账本、零自动轮与短回复完全可见均保留断言；首轮BLOCKED(MODEL_REPORTED)、后轮MODEL_COMPLETED，不把诊断回复冒充任务完成。

确定性GoalRoundAllowance原两项保留：默认256个自动提示Turn、人工与恢复不计轮、取消不返还、Room重开和解除轮数阻塞；GoalReminderControls原三项保留负数拒绝、放弃编辑不改事实、预算持久化不运行、提醒/取消、删除确认及清理失败重试。自然模型与确定性边界独立计数，不删除旧覆盖来换通过。

首轮冻结基础e9889f24：API36 Developer自有5676/4096MiB/2核/只读Helix_Main_Verify_API36，main原拼音+上述5项+Goal live+两个Skill，共9项。7/9 passed、2 failed、零skip，两个Skill均在原180秒上限超时。仍存活的自有serial检查owner/AVD后保存只含合成fixture的Room副本；第一个Skill session无Turn、无model_calls、无session_inputs、无tool_calls，配置ACT/OFF、32768输出，并非OUTPUT_TOKEN_LIMIT或模型服务耗尽。根因为sendTestMessage(text)读取尚未刷新的screen.openSessionId，openSession投影异步更新，输入绑定错到前一会话而被正常拒绝。修正只在AndroidTest共享helper等待目标投影后设自己的配置，再显式sendTestMessage(sessionId,text)。已有发送helper与其他调用者不变，原工具成功断言、审批精确路径/hash、模型预算和180秒上限不变。首轮Goal有6次实际完成模型调用；Skill任务无持久化模型调用，不据超时提高额度；Provider连接探测不由此推导为零请求。两失败及owner正常退出证据保留。

首个主机门禁因新说明字符串MaxLineLength失败；正常换行修正，未静态抑制。审查同时修正JUnit调用账本数量的Int/Long比较类型；没有因设备失败弱化断言。重新执行全部test/detekt/spotless/双渠道Debug lint/App和AndroidTest编译；新SHA重新冻结后再跑完整9项。首次日志model-closeout-host.log，修正日志-r2/-r3及source日志分别保留。

第二轮goal-ui-closeout-r3实际8/9 passed、1 failed、零skip，Goal及Skill Creator通过；Installer已write/preview/install完成，但两次skills.enable均DENIED，随后skills.read失败。全体实际模型调用COMPLETED且Turn无token错误。根因是旧测试批准写入/安装却拒绝测试目标明确要求的启用；不能绕过生产审批或降低成功断言。修正isExactApproval，仅对本轮目录名、当前preview hash、已安装repository中的完全一致source/name/snapshotHash批准skills.enable；其他Skill和其他审批仍拒绝。失败轨迹原样保留，不作为成功安装旅程。

脚本：shared host slot包围现有run-owned-emulator.py，main真实拼音/GoalRoundAllowance/GoalReminderControls/GoalRealModelUi/SkillCreatorModel/SkillInstallerModel；显式helix.goalUi=true、port30008/modelQwen3.8-27B、skillCreatorModel=true、skillInstallerModel=true，900秒上限。Skill批准只限fixture新建SKILL.md、精确preview hash的本地安装及上述精确已安装快照启用，所有其他审批拒绝，finally删除自建文件/快照及Provider。Goal保存截图和结果，after-script在owner/AVD核验下复制当前合成结果与逐调用usage/工具名称状态，不提交数据库/原始模型内容。

原始记录在ignored build/maintenance-followup-20261010/goal-ui-closeout及后续fresh尝试，含owner/artifacts/closed/JUnit/持续logcat、首轮failure-model-facts（诊断副本非独立验收）、最终Goal截图/JSON与synthetic-model-facts.json。不覆盖日常APK，不操作真机。有限同模型旅程不证明所有Skill内容质量、所有账户或完整故障矩阵；历史缺少逐调用证据的截断仍不能唯一归因。

## 本轮最终结果

最终goal-ui-closeout-r4自有API36 Developer/5680完整9/9 passed、零skip，owner正常退出0；含真实拼音1、原256轮与取消/重开2、Goal UI控制3、真实Qwen Goal1、Skill创建1及安装/启用/读取1。不得将9项都称为9次模型测试。

Goal结果：state=COMPLETED，runs=2，modelCalls=6，automaticRounds=0，completionClaim=True。两个原身份Turn语义阻塞/显式继续完成，截图和实际调用账本一致。

当次Goal/两Skill共15次实际持久化模型调用，全部COMPLETED，所有关联Turn COMPLETED且无errorCode；最大单次outputTokens=2172。这是本轮有界证据，不解释没有逐调用记录的历史截断。

Goal real UI fixture 工具事实：get_goal:COMPLETED, update_goal:COMPLETED, get_goal:COMPLETED, update_goal:COMPLETED。

Skill Creator evaluation 工具事实：write:COMPLETED, tools.search:COMPLETED, skills.preview:COMPLETED, write:COMPLETED, tools.search:COMPLETED, skills.preview:COMPLETED, skills.install:COMPLETED, skills.enable:COMPLETED, skills.read:COMPLETED。

冻结基础e9889f24，最终制品SHA256：

- developer-app: `557dce87f27db98129f22bd3c4505608b05203e73f01379d75c8b72d69b84633`
- developer-test: `efce842c9ea1900d73d9f7c4e736085d08839ca5b47e08fc5e8ee5b3f05bec3e`

最终完整主机test/detekt/spotless/双渠道Debug lint/App及AndroidTest编译通过：model-closeout-host-r4.log（43秒990 tasks/31 executed）；含实验模块的完整model-closeout-host-r5.log也通过（42秒1033 tasks/19 executed）。所有修改仅AndroidTest；生产APK hash保持。source172项、803个Markdown/249 HXA/36 ADR门禁与文档校验通过，不把宿主通过代替设备通过。
