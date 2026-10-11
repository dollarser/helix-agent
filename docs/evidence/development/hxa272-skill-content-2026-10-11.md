# HXA-272：统一 Skill 内容与零工具插件

日期：2026-10-11。Owner授权按272～275顺序实施并使用模拟器及现有Qwen；本记录只覆盖272与前置交互补验。

## 实现与参考

原生贡献改用经过SkillLoader校验的不可变SkillDocument，不再保留PluginBundledSkill正文类型。SkillRepository统一管理目录、inspect、正文读取及资源入口；来源选择、独立启停和工具发布分别检查。原生内容每次请求按当前目录/hash和已提交插件fingerprint重新核验，内容变更导致旧key失效。管理预览不代表执行可用；原生包内内容不出现在独立Skill开关中。相同名称不同hash保留独立身份，共享内容仅由当前有效owner提供。Native原型当前只声明正文，没有额外外部资源；导入包的references/assets继续走原有有界读取与路径校验。

PluginRegistry的选择不再依赖工具数量，零工具原生内容也可激活；ChatRequestAssembler通过同一Repository读取当前选择的指导，保留SKILL/EXTERNAL_CONTENT来源。Mobile Use与Chat Reply已迁移；删除只返回指导的chat.assist占位工具，聊天操作仍使用原Mobile Use授权链，不增加聊天提取、后台监控或自动发送。纯流程作者样例位于[生产解析器fixture](../../../extensions/plugin/src/test/resources/samples/pure-workflow/plugin.json)，配套[Skill](../../../extensions/plugin/src/test/resources/samples/pure-workflow/skills/pure-workflow/SKILL.md)；原生示例见[Chat Reply](../../../extensions/chat-reply/README.md)。

核对[Claude Code Skills官方文档](https://code.claude.com/docs/en/skills)和[Codex Skills官方文档](https://learn.chatgpt.com/docs/build-skills)，采用内容贡献独立于工具以及共享目录/读取身份的设计；未复制参考代码。调用资格将在273实现，渐进装载在278承接，本阶段不宣称节省token或提高普遍任务完成率。

## 验证与失败修复

全仓JVM、spotless/detekt、双渠道lint/APK和Developer测试APK通过，冻结修复候选主机日志为ignored `build/hxa272-cancel-host.log`（退出0，1m18s）。源码门禁206主机fixture、文档/ADR/三语言/secret检查通过，原负向解析fixture的FAIL_FUNCTIONAL输出不属于设备执行。早期长行及ReturnCount失败已用组件结构/排版修复，没有放宽门禁或删除断言。

专用API36 arm64、Developer独立进程的提示3项、会话选择5项、内容/资源2项、现有loopback Qwen3.8-27B真实指南读取1项，共11/11通过、0skip；Qwen实际完成3次工具调用，原生零工具指南通过skills.list/read读取。证据为ignored `build/hxa272-device-model-recheck/`，含冻结制品hash、设备属性、完整instrumentation、logcat和关闭记录。日常模拟器和实体手机未操作，未覆盖日常APK。

首次设备旅程虽完成模型断言，但清理调用异步stop后删除会话，迟到取消在取消事务里查找已删除Turn导致进程崩溃；该整轮失败保留于 `build/hxa272-device-model/`，不记通过。测试清理改为等待准确Turn停止；生产取消处理补齐首次观察与实际事务间删除的NotFound结果，不假报StoppedLive、不取消其他任务。AppAgentRuntime新增迟到删除回归，模块13/13通过；同一11项设备/模型旅程重新完整通过。这不证明历史真机黑屏根因已关闭。

Standard纯Skill旅程首轮填写了错误instrumentation包名（consumer使用com.helix.agent.test，而不是带consumer后缀），因此未执行任何用例；失败记录保留于 `build/hxa272-consumer-pure-skill/`，按制品元数据更正后用新进程重跑，1/1通过、0skip（5.717秒），记录位于 `build/hxa272-consumer-pure-skill-recheck/`。不把命令启动失败当作应用功能失败或设备通过。新增作者样例生产解析1/1通过，补充全仓主机/双渠道lint与两种测试APK退出0（45秒）；最终source门禁退出0，206fixture、825 Markdown/262 HXA、ADR、三语言与secret通过。

窄屏/大字体、完整系统授权恢复后手机操作及其他OEM不由上述有界结果推导通过。后续273～275继续其各自验收；不扩展到276～279或真实账号。
