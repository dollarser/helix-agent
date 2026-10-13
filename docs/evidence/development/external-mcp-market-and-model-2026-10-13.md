# HXA-282：公网 MCP、市场恢复与真实模型验收

日期：2026-10-13（Asia/Shanghai）。本轮Owner授权模拟器、同一OnePlus及现有Qwen/Codex；指定Remote Notes MCP虚拟认证Site，并批准发布最小原生回调修正。保留前次成功、失败与当前pending的区别。本记录不关闭HXA-126真实厂商账号、全部OEM、24h或商店发行边界。

## 实施与来源

当前订阅Runtime合入Developer主APK的`:subscriptions`，使用手机已有Helix登录；未导入主机CLI凭据。错误的旧独立Runtime/库测试宿主配置准备在文件不存在处失败，未读取/刷新主机凭据，已退役；[清理记录](runtime-artifact-retirement-2026-10-13.md)和提交`bee04ad5`保留来源。

公网Site：<https://remote-notes-oauth-demo.shenhuo2580.chatgpt.site>，MCP入口`/api/mcp`；version 15、source `339a210e62becbd75c0f8a64158d39d4fac1334a`、deployment `appgdep_6acdaa27a81c81918e0f4544644bdf61` succeeded。只增加consumer/developer两个精确包名回调；16项回调边界和Site构建通过，保留原HTTPS/loopback规则，不改DB、账号或token TTL。使用合成demo账号，不代表真实用户身份认证或人工浏览器登录UI验收。

两项生产修复：

- `McpOAuthCoordinator`保持原始wire resource，不把省略端口的URL强制改成`:443`。安全绑定比较仍验证规范化endpoint；带默认/自定义端口及路径大小写的原值回归保留。
- `McpHttpClientFactory`把初始化版本guard及响应大小guard移到OkHttp application interceptor，检查解压后内容。此前network interceptor对gzip字节做JSON解析，实际公网响应被错误判为缺protocolVersion，压缩尺寸也不能代表解压后的大小。DNS/SSRF、origin/bearer和禁止重定向保持。

依据[MCP资源绑定要求](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)及[OkHttp源码与文档](https://github.com/lysine-dev/okhttp)；采用原请求标识与解压后边界，不增加协议/执行框架。App Links与loopback本轮仅设计咨询，不实现通用OAuth后端。

## 公网 OAuth 与市场

| 当前执行 | 结果与边界 |
| --- | --- |
| OnePlus API34 Developer，PUBLIC-r1 | 3方法passed：实际Android Intent回调及PKCE链路、读取notes_list、刷新/refresh token轮换、重复回调拒绝、授权拒绝、本地断开；另含市场两方法 |
| API36 Standard PUBLIC-r4/r5 | r4协议链路1方法passed；r5原生Intent回调/同一完整OAuth方法及实际拼音输入2方法passed，owned实例正常关闭 |
| API36两渠道market-r1 | 各3方法passed，含真正killProcess后的新PID恢复；未发布目录候选不会替代已发布source/version/hash/安装ID |
| 真机及Standard索引匹配包 | JSON index发现候选、需原包、preview/hash一致才安装、取消不发布通过；真实Content URI服务边界，未操作完整SAF picker UI |

Site当前没有revocation endpoint，断开结果`vendorRevoked=false`；本地删除凭据不等于远端token已撤销。已部署源码实际支持refresh rotation，不能依据旧checkout断言无刷新能力。原始公网gzip实际响应及失败尝试保存在本机忽略目录，报告不含Cookie、code/state/token/Note内容。

## 模型对照

同一自有API36、同一冻结测试APK、固定三类任务各两次：Skill读取并起草、浏览器按钮恰好一次、手机中文输入/滚动/完成并独立读回。基线`c946d099`，候选包含HXA-281当前工具发现指导与本轮MCP修复；这三个任务不使用MCP。APK SHA-256：

| 身份 | SHA-256 |
| --- | --- |
| 基线主APK | `07f4414de5bb12c77e68a5f1cad0e4b5c37dc76357e68dce12fef5b53ad51e42` |
| 候选主APK | `98985b913b9d25c6eb03150df9db2062f79d8db449d644fc601397ed566364e2` |
| policy-r1测试APK | `6e25da8d115400ec315edc828bd6669ecfa4d2e881875a78348303a00627d1ea` |

| policy-r1业务观察 | 成功 | 工具调用 | 模型调用 | 输入token | 输出token | 业务耗时 |
| --- | --- | --- | --- | --- | --- | --- |
| Qwen基线 | 6/6 | 34 | 40 | 505541 | 6664 | 88.554s |
| Qwen候选 | 6/6 | 29 | 34 | 422780 | 7509 | 65.940s |

两组原JUnit浏览器方法在业务两次成功后因ActivityScenario清理失败，不能把原方法记passed。改用实际Main UI dispatcher后，policy-r2两组浏览器专项各1方法passed，两个业务各2/2。序列固定、样本小、同期有长测负载，只有本样本成本/行为观察；不宣称普遍收益，不据结果改生产加载策略。

| OnePlus真实模型观察 | 已执行业务 | 成功 | 工具/模型调用 | 输入/输出token | 业务耗时 |
| --- | --- | --- | --- | --- | --- |
| `gpt-6-luna`首次 | 4 | 3 | 16/20 | 214454/2283 | 118.018s |
| 既有配置模型`gpt-6-astra` | 6 | 6 | 36/42 | 520514/3942 | 357.330s |
| Qwen workflow/browser | 4 | 4 | 16/19 | 247228/5065 | 62.829s |
| Qwen手机任务补验 | 2 | 2 | 17/19 | 307994/2402 | 52.432s |

只统计对应业务Turn usage，不含连接探测/目录/安装热身；不同模型tokenizer与套餐计费不同，耗时也有服务/负载差异，不据此排名或折算费用。工具调用包括DENIED/FAILED及成功后恢复，不把内部调用完成当作业务成功。

Luna失败是模型改写长节点token，两次`browser.click`返回`stale-token/malformed-token`，没有点击成功；未放宽校验或把COMPLETED Turn当成功。首次Mobile方法在模型执行前因系统保留enabled list但标记服务crashed而超时，两模型均受影响；夹具只移除/重新加入Helix自身服务，等待系统enabled/crashed状态确认后再启用，不动其他服务。Astra六业务成功后仍有浏览器ActivityScenario清理失败；直接Main UI dispatcher在模拟器验证修复，真机同路径方法补验pending。

原生Codex独立聊天首次误读尚未更新的UI末项（用户提示），持久Turn已完成。修夹具后同时断言持久assistant与有界等待页面投影，`HELIX_OK`通过，1模型调用、输入1700/输出7 token。旧夹具`runtimeForeground`也改用`app.packageName`定位内置页面。

## 连续任务与失败归属

此前FD漂移试跑包含夹具反复NEW_TASK启动导致32个MainActivity的证据；诊断GC只能区分滞后清理，不是资源验收。统一SINGLE_TOP/CLEAR_TOP及持久provider/UI owner后，180秒试跑passed：实际测量181264ms、3测量cycle、6测量业务加2热身业务、同PID3920；采样峰值FD166/线程51/PSS160076KiB。中间ActivityScenario清理失败的attempt不记通过。

两小时首轮`mobile-two-hours-r1`在最后记录1347102ms/21测量cycle后，第45个真实任务报`PROTOCOL`，没有工具调用且usage为null，最终FAIL_FUNCTIONAL。没有触发FD/线程/PSS门禁，时长不足，不归因资源泄漏、不算两小时passed；当时没有原始SSE，不能断定具体协议原因。

复测`mobile-two-hours-r2`同进程3948，在1936987ms/24测量cycle后触发`idle FD drift`；baseline FD159（上限167），前一成功采样峰值166/线程61/PSS158665KiB。旧idle断言未记录触发数值，runner错误归为FAIL_FUNCTIONAL；实际为资源断言失败，确切峰值/描述符原因尚未知。已补在断言前记录数值/新增描述符并使用同一cumulative错误格式，不改原FD+8、线程+16、PSS+96MiB，不强制GC验收。两小时仍pending，不能记passed。

该轮真实Qwen合成目标均有独立结果和HOME/返回验证，业务失败完整记录且最终all-results断言保持。临时loopback recorder仅转发匿名合成Qwen到既有30008，捕获请求/原始SSE作诊断，不改OAuth；此轮未观察到`PROTOCOL`。recorder、反向端口及owned VM已关闭，日常实例未操作。下一步先定向诊断实际FD来源，诊断GC仅用于区分滞后关闭，不作接受证据；原因修复后才重新两小时验收。

本轮不证明24h、长单会话/压缩、全部Root/Shizuku/OEM或Codex长稳。日常模拟器未借用、更新或清数据。临时模型/服务参数只传本轮fixture，不改默认配置。

## 主机、清理与剩余

`check-all.sh --all`新鲜r5及最新r7 exit 0；含Spotless、Detekt、所有JVM/lint、Debug/Release双渠道制品及锁/边界。MCP facade 18项无失败/跳过，含gzip正常/缺wire版本、17MiB解压后JSON拒绝、普通/gzip SSE单事件超限拒绝及多健康事件累计超过16MiB仍允许。双渠道AndroidTest APK编译通过。前次失败包括错误Gradle task、格式/Detekt、源夹具语法与报告tuple-key序列化；原始结果保留，均不以放宽门禁解决。

生产修复提交`4cff8607`；有界设备/模型夹具与诊断提交`ee09789b`。FD诊断完成51/60 pair，另有第103个业务成功，随后在第104个任务配置阶段出现10000ms TimeoutCancellationException；方法failed，不记诊断通过。实际FD在163～169波动，diagnostic GC前后均169（线程62→61，PSS161062→156426KiB），GC未使该点FD下降；owned VM正常关闭，无剩余转发器/测试VM。

完整`run-as /proc/PID/fd`元数据快照差异集中在`anon_inode:sync_file`（11→8），其他分类保持；另一次读取该类型已消失。只说明与图形同步fence相关的候选，不据此断定根因或排除泄漏；[Android16 UiAutomation源码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/app/UiAutomation.java)显示takeScreenshot显式关闭HardwareBuffer并回收中间bitmap，不能直接假定调用者忘记关闭该buffer。后续需区分绘制fence、稳定基线与真实retention，并定位配置投影timeout的实际等待阶段；原资源门禁不降低，完整两小时仍pending。

OnePlus在最终核对时已断开；断开前无障碍设置before/after一致、30008反向端口已删除，model-r2自身合成Session/provider有finally清理。PUBLIC-r1后原schema/integrity及9个session/27个Turn/5个provider完整hash不变，随后matrix-before也有备份；最终matrix-after导出失败，不能把当前原记录保留核对记passed。需要原手机重连后完成核对与浏览器方法补验；不清数据/重建库，不安装旧Runtime。

所有原始模型轨迹、SQLite/APK备份、截图、私密捕获在忽略的`build/hxa282/`，不提交。临时launcher在日期`local/`下；owned runner finally关闭自己的VM，recorder/反向端口须有关闭记录。HXA-282保持进行中；最终资源/业务结果与手机边界待结算。

## OnePlus 重连补验

Owner确认重连后核对同一`561e3b15`（OnePlus6T/API34/Developer），先备份当前库、确认无在途任务，再仅补验合成浏览器，不重跑已完成的市场/OAuth检查、不更新主APK或重建库。`phone/browser-final-r1`现有Helix Codex `gpt-6-astra`及匿名本机Qwen各1方法通过，各2个业务成功；Main UI dispatcher清理正常，原ActivityScenario方法失败仍保留。

最终`phone/final-record-preservation.txt`核对schema identity、integrity和原9 session/27 Turn/5 provider完整hash与最初备份完全一致；activeTurns=0。before/after无障碍设置相同，30008反向端口移除。测试APK更新只用于夹具，未读取或导入宿主CLI凭据；本节关闭手机数据及浏览器清理pending，不关闭资源长测。

## 配置投影与截图定向调查

当前源码`ChatService.openSession`原先在`turnGate`外读取配置，再异步发布到`_runControl`，可能覆盖已经在同一gate内持久化/发布的新配置。现将读取与发布一起放入该gate，workspace准备仍在gate外；250次ACT/PLAN交替打开/提交回归通过。夹具保留原10000ms超时，并新增session/configuration阶段及当前mode/budgets诊断。此结果确认修复了代码竞态，不证明它是此前第104次任务超时的唯一原因。

`mobile-window-precheck-r1`实验仅将返回Helix后的截图改成自有Window PixelCopy（[Android官方接口](https://developer.android.com/reference/android/view/PixelCopy)），像素对比、会话/后端确认和原FD+8/线程+16/PSS+96MiB断言保留；实际4个业务成功且8次后台返回像素检查成功，但第一测量pair即FD154→167资源失败，新增项主要sync_file。因此该方案未解决FD问题，源码实验只保留在忽略的本地patch，不纳入最终实现，默认全屏截图不变。实例PID88807已exit0。

同期开独立baseline实例试图对照配置竞态，但应用启动前ANR（failed to complete startup），没有非空JUnit或250次结果，不能声称baseline成功/竞态已复现。其系统CPU pressure some avg10=93.16、avg60=84.77，与并行主机门禁/另一个AVD同期；这是负载影响的证据，不排除原包启动问题。baseline实例PID94278已exit0。后续设备诊断须避免同时跑重型主机构建和多AVD；此处不以负载为由删除资源失败。

资源夹具已增加触发FD门禁时逐条描述符元数据，避免单行4KiB截断；不读取描述符内容，不改变采样值、物理边界或失败结论。此前PROTOCOL没有原始帧，仍未关闭。

最终生产竞态/配置等待诊断/逐条FD诊断提交`60a10c4c`；当前源码`check-all.sh --all`新鲜r11 exit0、Developer AndroidTest APK新鲜r5编译通过。r9 LongMethod及r10格式门禁失败已通过提取诊断helper和定点格式修复，未关闭门禁；全部早期日志保留。手机数据/浏览器pending已关闭，真实FD漂移、历史PROTOCOL因果与完整7200秒仍未关闭，本次不启动尚缺资源前提的新两小时实例。

## FD 分阶段因果对照与协议重放

`mobile-fd-phases-r1`自有API36/Developer、PID3848完成6 pair/12业务，诊断方法通过（不是资源接受）；126个阶段记录独立保存。即时清理后FD160～164；所有6组1秒及5秒均FD166、sync_file23。第一次HOME/返回在截图前sync_file8→17，截图前后sync_file数量不变；模型任务/HOME阶段还会升高并回落。不能把全部增长归因于截图、传输socket或Java永久retention。

`mobile-fd-frame-r1`另一个自有实例、PID3905，4 pair/8业务诊断通过；92阶段记录中1475次fence poll可读（已signal），4次pending。帧提交后FD162～166变化，全部4组1秒/5秒仍FD166、sync_file23。`gfxinfo`显示1个MainActivity ViewRootImpl及当前BLAST Consumer的3个1080×2400 buffer；描述符由主进程持有，与返回/绘制生命周期高度关联。关联到framework窗口图形资源是依据时序及graphics信息的推断，未取得fd→native分配栈；当前内核fdinfo仅有pos/flags/mnt_id/inode，不提供fence driver/timeline。两次owned VM与recorder均exit0，反向端口删除，日常模拟器未操作。

实际发现夹具在清理/返回后立即取基线，未等同一渲染阶段，而idle样本在界面稳定后获取。修正仅作用连续测试：要求MainActivity处于RESUMED，确认一帧已提交（最大5秒，失败直接失败），再固定静置1秒，基线与每组测量均相同。采用[Android官方FrameCommit API](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable))和既有app resource fixture的固定静置惯例；不按FD值重试、不GC、不改两warm任务及FD+8/线程+16/PSS+96MiB。帧提交本身不保证全部资源已稳定，故保留一致静置及后续idle门禁。`check-all.sh --all`新鲜r12 exit0、测试APK编译通过；当前资源短测仍进行中，不提前计资源通过。

历史PROTOCOL实际只留下rep44 FAILED/PROTOCOL、0工具和usage=null；本地既无原响应帧、decoder violation detail，也无当轮SQLite归档。当前ModelEvent.Error不携带detail，ModelCallEntity不保存该错误细节；30008监听是现有VS Code转发，未提供远端服务日志访问，因此不能从现有证据还原具体违规类型/服务端请求。已将第二轮415条实际200 SSE完整字节用当前ChatCompletionsStreamDecoder按1/7/8192字节分块重放，1245次解析均唯一正常终结、无Error，单个离线JUnit通过；这证明这些已捕获流与分块健壮性，不能证明第一轮错误修复。临时重放JUnit与init script在忽略的local目录，编译产物已从普通测试输出移除，避免后续误用。下一轮recorder保留实际Accept-Encoding协商、原响应编码及字节，并维持失败记录；不将历史PROTOCOL归因于MCP gzip修复或会话竞态。

`141739e0`提交上述连续夹具采样同步及opt-in内核元数据。`mobile-settled-precheck-r1`3方法通过（中文拼音、250次配置回归、180秒连续资源）；8个实际Qwen业务成功，含2 warm/6测量业务，测量约182443ms，原FD/线程/PSS断言未触发，实例PID34648 exit0。不能把这记为两小时通过。

随后启动唯一`mobile-two-hours-r3`，launcher PID39727、owned emulator PID39768（API36 Developer）、recorder PID40656、主App PID3853；启动08:28:13 UTC，测量开始08:30:10 UTC。原7200秒、单PID、全业务与资源门禁/主机独立采样保留；当前运行中、pending，预计测量结束10:30:10 UTC后结算并关闭自己的实例/转发器。父/后续会话应检查这一既有目录与PID，不能另起重复长测。手机补验不重跑，日常模拟器不操作。只有长测结果待等时按Owner此前指示交接，不提前关闭HXA-282。

## R3 运行中业务失败记录

R3仍使用冻结的原APK/测试APK，未重启。rep30在08:50:35 UTC业务校验失败：Turn COMPLETED/error=null，但只执行skill read（空sessionId被原schema拒绝）、snapshot、set_text、find；find明确返回offscreen及滚动提示，模型最终文本为需要fresh scrollable container token，未继续snapshot/scroll/click。`000259`～`000262`原始200 SSE及请求已保存；结束前ui.snapshot/ui.scroll仍在加载的34工具内，所以不属于缺工具或PROTOCOL。独立校验正确保留失败，不能计作成功。当前all-results门禁不变，R3继续完整资源测量，整体将因已有业务失败而未通过。

已准备小范围引导修正，尚未构建/安装到R3：明确Skill读取的当前会话由host绑定、模型省略可选sessionId；保留原minLength=1及caller-bound执行，增加空hint拒绝回归。Mobile Use required规则明确需要观察/滚动不是blocker，必须继续真实调用直至验证完成或具体外部阻塞，不盲目重放不确定效果。这只澄清原有行为，不新增权限或自动恢复执行。参考[Claude Skills渐进读取](https://code.claude.com/docs/en/skills)继续保留短必需规则和按需正文；不把一次Skill拒绝断言为提前结束的唯一根因。检查/定向模型验证须待当前长测结束后串行进行，当前数据不使用这些未验证修正。

协调层容量中断后的存活复核：2026-10-13T09:52:00Z，原launcher39727/VM39768/recorder40656仍在，主App PID3853持续；测量4905779ms、业务118（失败1），FD峰值168、线程63、PSS161417KiB，均未触发原门禁；无读取/连接阻塞。`monitor.jsonl`每45秒保留只读检查，`live-status.json`为本次快照；原R3不重启。业务失败和仍在测量分别记录，不能因协调中断判断测试进程失败。
