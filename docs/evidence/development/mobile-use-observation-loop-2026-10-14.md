# Mobile Use观察闭环优化

日期：2026-10-14。范围：[HXA-282](../../development/tasks/HXA-282.md)，Owner要求综合调研并优化当前Mobile Use。沿[ADR-AGENT-001](../../adr/agent/001-turn-coordination.md)现有工具执行/业务核验分层；不新增执行引擎、模型自动续跑或批量UI执行域。

## 实际问题与基线

旧冻结R6中的三次滚动拒绝是旧token复用：两次在输入后复用输入前的容器，一次在第一次滚动后再次复用同token。原始请求证明token字符复制正确，不能归因为模型抄错、通知干扰或触摸过滤。三次均执行前TOKEN_UNKNOWN，模型随后恢复并完成业务。窗口事件隔离是独立的噪声优化，不把它冒充这三次的根因修复。

R6已独立结算PASS：主APK6cca6be2/测试APKcb60cb7e、同PID7211655ms、172测量业务及2热身通过，资源门禁保持。当前优化源码未注入其进程，该长稳结果不能当新代码设备验收；[完整结算](autonomous-app-and-stability-2026-10-14.md#截图修正后的视觉补验与一次后台长稳)保留采样240/241与中间失败。原始数据见忽略的build/hxa282/mobile-two-hours-receipt-r6/；精确请求核对脚本和临时诊断在对应日期debug/local下，未改旧原帧或结果。

## 竞品参考与取舍

| 参考 | 公开证据 | Helix采纳及边界 |
| --- | --- | --- |
| Codex本机Computer Use Skill | 一个或多个动作后get_app_state，重新读取元素索引，通常以差异返回 | 采用及时提供新状态和减少重复读取；只代表本机安装版本指导，不推断闭源服务如何判旧节点，也不移植其批量权限行为。 |
| [OpenAI Computer use](https://developers.openai.com/api/docs/guides/tools-computer-use) | 宿主顺序执行动作组后回送截图，让模型判断结果 | 动作回执与业务核验分离，必要的新观察随结果返回；不声称API具有Helix节点token失效规则。 |
| [Claude Computer use](https://platform.claude.com/docs/en/agents-and-tools/tool-use/computer-use-tool)与[公开执行器](https://github.com/anthropics/anthropic-quickstarts/blob/main/computer-use-demo/computer_use_demo/tools/computer.py) | 支持短动作组，失败则停止后续动作，并可附带截图减少一轮请求 | 采纳将状态附在结果后的闭环；继续只执行原模型动作，不复现其桌面xdotool执行或把短确认当成功核验。 |
| [Playwright locators](https://playwright.dev/docs/locators)、[actionability](https://playwright.dev/docs/actionability)、[MCP](https://github.com/microsoft/playwright-mcp) | locator按动作重新定位；不同动作采用不同可见/稳定/接收事件等检查；支持定向快照查询 | 采纳按目标查询与重新核对，find/wait可筛选scrollable；Android路径/指纹不等于稳定DOM locator，尚不据此放开跨动作复用旧token。 |
| [DroidRun](https://github.com/droidrun/droidrun) | 移动端结合无障碍树和截图，经Portal控制设备 | 保持Android语义观察与授权内像素补充；README没有证明其token寿命或重试策略，不据此复制设备授权、依赖或执行引擎。 |

仅设计参考，未复制参考代码。公开动态main文件于UTC06:11缓存并记录URL/完整SHA256及大小在忽略的references/provenance.json；没有将动态分支当不可变上游revision。Codex安装Skill来源只记包内相对位置computer-use/skills/computer-use/SKILL.md，SHA256 e6a2b212bacad8d16a9a07e2fef5d428ea00c7b9a8f8529bec1bd2ba48a49c4f；不记录机器绝对路径或账号内容。

## 本次实现

- ui.set_text与ui.scroll在已知DISPATCHED后，经同一原调用绑定端口做一次新语义观察。成功则observationStatus=ATTACHED，最多32节点/12KiB节点载荷；截短、保护字段、目标和授权状态如实保留。模型读取observation中的新token即可继续，缺失、加载中或不足时再观察，不把“附带状态”写成VERIFIED。
- 观察取消/过期时SKIPPED_CANCELLED/SKIPPED_DEADLINE；读取拒绝/异常时UNAVAILABLE。已知输入回执保持，未决/失败动作不触发这次读取或动作重放。读取前后检查取消与deadline；底层原授权、资源占用及退出规则保持，不新建后台等待器或延长调用。异常不伪造状态、不回传异常正文；拒绝/损坏观察不发布节点。
- 仅上述两种常见动作增加附带观察。点击、全局动作和坐标流程保持原有观察要求；旧token仍失效，附带的新token也不能绕过generation、窗口、节点指纹、敏感/范围、取消或授权校验。
- ui.find/ui.wait支持scrollable=true/false，可只返回当前容器，避免为找一个容器拉整棵树；查询仍会替换旧token。TOKEN_UNKNOWN补明确执行前拒绝、旧观察/旧动作或错误标识的可能性、获取并原样复制新token的指引，不伪造具体失效原因。
- 已知非目标窗口的普通点击、焦点、滚动、文字和内容事件不推进目标generation；窗口状态/层级变化、未知事件或未知目标仍保守失效。目标观察器继续后台有界查询，实际窗口改变仍会识别；自有浮窗排除和隐藏确认保持。
- 描述、输入/输出schema与Skill同步。set_text/scroll版本4→5，find/wait9→10；back/home描述更正为宿主选择就绪后端，版本2→3，其他版本保持。无新增用户配置、数据库schema或全局策略。

## 验证与边界

Mobile Use Debug JVM最终280项通过、零失败/跳过。新增16项覆盖附带状态、原身份/作用域绑定、范围拒绝、损坏状态、读取异常、前后取消/过期、原动作取消传播、未决动作不读/不重放、UTF-8限长与截短、可滚动查询及普通/真正窗口事件区分；原敏感、节点过期和资源门禁不删减。

初始失败完整保留：遗漏Json扩展及误用不存在的coroutines依赖已改用现有JDK取消异常；长行格式、Skill入口超过既有1024字节预算通过精简修正；完整门禁首轮发现有界JSON函数返回数超过2，调整结构保留原断言和门禁。不是删除测试或提高阈值换通过。

完整全仓检查all-r3与双渠道/SDK AndroidTest编译androidtest-r3已通过，日志在忽略的build/hxa282/mobile-observation-optimization/。新增pluginSkillPostObservation=true验收参数准备实际Qwen表单/离屏任务，保留独立业务核验并检查真实附带状态；参数不启用时既有用例不受影响。Owner随后明确授权自有API36/现有Qwen最多20分钟独立短验，临时无障碍/悬浮窗、仅合成界面，不重跑长稳、不动日常实例或账号。独立启动UTC06:27:36，绝对结束上限06:47:36；随后短验结算如下，不能据主机结果宣称减少模型调用或普遍提高成功率。新旧制品及结果分开，不借用R6或日常实例。

## 新制品独立短验

Owner本轮授权：自有API36/现有localhost:30008 Qwen，最多20分钟；临时无障碍/悬浮窗、仅合成页面，不重跑长稳、不动日常实例或账号。UTC06:27:36开始，硬截止06:47:36；采用既有owned只读runner、原实际拼音及四模式触摸断言和两个真实模型方法，非独立Runtime宿主。源与原R6分开；新主APK SHA256 b29b7405d33f4ecd569536879351af879b7c9847539e49580400bd4072abe0ac，App测试APK a0485ef7d9ad9a56c3a871be8bec2b535c74afbd67d927f06843d4111904e258。原始日志、APK、wire、实例归属和清理记录在忽略的build/hxa282/layout-and-model-observation-r1/。临时入口先保存于当天debug/local；deadline监督在上限前预留35秒关闭时间，逐方法也预留清理时间，不自动重试失败。

UTC06:32:03结束，约267秒，远低于20分钟上限。实际拼音组合/候选/中文commit一方法、四模式触摸一方法、Qwen离屏查找/普通表单两方法均passed：4业务及8次实际HOME/返回的像素标题和原会话保留通过。12条动作回执均DISPATCHED/UNVERIFIED；41条带原Turn/model-call身份的请求均完整HTTP200、代理request ID及单一SSE响应ID可关联，另2条无身份探测，离线关联错误0。原始check-results、instrumentation、business-logcat、wire-identities、action-receipts均保留。

8条实际附带观察均ATTACHED；模型在3次后续动作中直接使用附带token，但这3次scroll均执行前STALE_TOKEN失败，重新观察后业务完成。另5次选择find/snapshot/wait。失败未自动重试/删日志，不能写成“新token直接执行成功”或“失效已解决”。ATTACHED仅证明采集到新观察，不保证随后generation/节点指纹仍相同；当前日志不足以把3次精确归因于IME、动画、浮窗或其他事件。观察时机/稳定性及区分真实目标变化和噪声仍需进一步定向证据；本轮没有第二组对照，也不证明比旧版节省调用/耗时或普遍减少TOKEN_UNKNOWN。语义/恢复链路检查passed，减少失效和往返的效果未证实。

临时无障碍before/after均null/0，未启用Shizuku/Root或操作真实账号，浮窗未额外开启；recorder PID41027退出0、owned VM PID40365退出0、supervisor退出0，反向端口由owned清理。logcat采集进程由关闭流程SIGTERM结束（-15），不冒充自然退出0。最终ADB仅剩原日常5554；未借用或更新它。新优化未重跑两小时，R6旧制品PASS单独保持。
