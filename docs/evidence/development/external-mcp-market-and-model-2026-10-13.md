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
