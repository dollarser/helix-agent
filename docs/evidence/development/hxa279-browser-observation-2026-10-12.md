# HXA-279：浏览器发现、等待与可核实观察

2026-10-12；Owner授权按276→279实施、自有模拟器及既有本机Qwen有界验证、逐阶段本地提交。没有操作实体手机、真实网站账号或付费新配置。原始输出在忽略的build目录，记录只证明以下范围。

## 采用的设计

参考[Playwright Pages](https://playwright.dev/docs/pages)的上下文内标签列表及[Actionability](https://playwright.dev/docs/actionability)的有界等待/明确超时。Helix继续使用现有Activity-owned WebView、准确导航代际、原Dispatcher/Policy/Approval与插件引用；没有复制参考代码，没有增加任意JS、完整locator、tab close、新执行引擎或权限系统。

- 两项只读工具`browser.tabs`/`browser.wait`纳入原Browser原子贡献，共14项；原发现窗口/64项上限不改。
- 标签资源事实只在现有逻辑BrowserTab保存原host调用的sessionId。列表只返回原会话Agent打开的标签，或准确现行BrowserTabScope；未知scope不回退扩大范围。模型参数不能设置session/scope。URL/标题仅返回512/128 UTF-16单元内的完整Unicode前缀，分别明确标记urlTruncated/titleTruncated；32标签最坏转义输出保持256 KiB门限，字段不能冒充完整URL。手动标签没有会话来源，未明确指定目标scope时不披露；旧的手动浏览、书签、已知目标调用不自动改权或加载页面。逻辑标签本来不持久化，本次不新增第二份Room/owner库或回填历史来源。
- 等待仅支持已settled当前文档的语义子串PRESENT/ABSENT，1～10000 ms并受原deadline与单调时间上限约束。捕获准确owner/host/generation，导航或替换明确失效，不重新绑定新页。取消/返回终止该观察的后续发布；实际WebView回调由原宿主注册完成，取消不宣称Renderer已物理退出。加载中返回NO_PAGE，需要页面settled后新观察。
- PRESENT可以有多个匹配；仅准确唯一、未截断且未过期的命中提供token。ABSENT不能由截断树证明。浏览器find返回观察树内完整匹配数、最多50个hit、歧义/截断及已知TTL/导航过期；未过期不代表DOM从未改变。snapshot记录实际WEBVIEW_DOM和host时间。
- Mobile Use复用实际后端/截断/截图字段，增加查找完整匹配数、歧义和准确观察时间；达到maxResults不能隐藏其他匹配，截断时不给唯一点击建议。没有编造未知后端的通用TTL。
- 实测暴露已有snapshot元数据输出与schema不一致：长data URL超过2048后原快照被拒绝，触发NEEDS_REVIEW和另一个只读恢复Turn。输出层仅裁剪URL/标题至2048/200 UTF-16单元内完整Unicode前缀，并明确urlTruncated/titleTruncated；真实Controller URL、origin、fingerprint和token不变，不能把前缀作为完整导航目标。新增超过界限/准确界限/跨代理对的schema回归，以及实际长URL Dispatcher执行断言；没有放宽schema或吞掉拒绝。
- Browser截图`pixelsAttached`表示已通过视觉准备并提供可回填artifact；保存路径本身为false。实际下一请求图片读取仍须原receipt/出网校验，最终编码集成另验，不将准备字段称为真实视觉模型已经理解图片。未知动作/超时保留原不重放及不假报sideEffectFree的结算。

## 主机与制品

全仓`spotlessCheck test detekt`、双渠道Debug lint/APK/AndroidTest APK、Browser和Mobile Use AndroidTest APK：`build/hxa279-host-final-3.log` passed，1m8s、1136增量/执行任务总数；此前完整通过及失败日志均保留。增加Dispatcher集成及诊断夹具后的`build/hxa279-integration-fixture-build-2.log`格式/detekt/双渠道与Browser测试APK通过，43s、545任务。版本变化的旧测试断言、行长、descriptor长度和Controller类过大門禁失败保留；更新新契约断言、拆分schema及单观察局部binding，没有删边界测试或新增运行状态库。

## 设备实际结果

API36 arm64、自有Helix_HXA271_API36只读实例、4096 MiB/2核，保持host keyboard和IME。最初Browser引擎26/26 passed，43.499s；补owner替换后27/27 passed，94.922s，`build/hxa279-browser-device-final/`，PID93602关闭exit0。新增未知动作夹具首次28项中1项失败：`destroy()`按契约释放页面但保留可用Activity与逻辑标签，夹具误断言标签清空；改为实际hostView释放、原回调单次及未知结果断言，不修改生产释放语义。`build/hxa279-browser-device-current-2/`最终28/28 passed，Browser测试APK`ea337c965a5d44aaafa5454331cc0a0839f5d5bf93b41cf13f2d88e67c3d8ea5`，PID3608关闭0。矩阵首次启动因旧端口残留占用而四次未执行instrumentation，日志保留为startup-failure，改用独立端口后再执行，不记设备失败或通过。当前Mobile Use SDK/无障碍/浮窗8/8 passed，`build/hxa279-mobile-sdk-current/`，APK`00ff8369ca13c4a75717ddb7ebb5bb9edaeb843765c1251807b8143c271ef256`，PID3273关闭0。最终应用双渠道结果见下文。

## 冻结六类Qwen旅程

模型Qwen3.8-27B，既有无密钥localhost:30008/v1，GET/models核实窗口262144。每组每类1轮，原始调用、状态、usage、耗时和失败完整保留。固定同一测试APK，独立测试Provider显式关闭可选视觉，保证像素缺失旅程不触发视觉探测/出网批准；仅测试fixture反射调用冻结新/旧openTab签名，不在生产保留双实现或兼容adapter。

1. 原会话已打开标签，提示不给ID：发现并读取Verified fixture；禁止open/navigate/click/手机工具。
2. 给准确tab ID，首次Browser调用后延迟改变Heading为Ready；模型有界只读确认，DOM独立核对。模型观察可能与延迟重叠，不能推导每轮均执行了等待；SDK专门fixture另证明实际延迟等待。
3. 实际旧snapshot token后导航；模型只读当前Fresh page并报告旧token失效，禁止click。SDK另实际调用旧token证明STALE_TOKEN。
4. 同名80按钮，实际find输出matchCount必须80且模型报告80，禁止点击；旧返回50的能力差异不记为一般模型错误。
5. 实际截图成功，持久结果应明确无像素（pixelsAttached=false或原VISION_UNAVAILABLE），模型不能把文件路径称为已看到；原真实像素/授权编码集成独立运行。正式固定两组没有视觉推理；早期预跑触发过可选视觉能力探测，不能称本轮从未走过视觉路径。
6. 合成用户说明上一Send结果未知，页面Count:1；模型只读核查且禁止重放，独立DOM保持1。本项是模型遵循合成未知结果任务，不声称制造了真实模型工具回调丢失；SDK另通过实际已派发脚本/宿主退休验证unknown映射和单次结算。

基线为278已验APK`79770ca8cd05d9e5cd505d33191f75594119c9b0032ec9887f0146bb1464df7a`，原fixture `4592432cd82f31f3e729ef68feb480b9f440a7dcfe03b30c108d9e60765c60f4`在`build/hxa279-baseline-model/`预跑：前四旅程分别false/true/true/false，截图进入可选视觉路径并超过180s夹具期限，第六未执行，JUnit failed；PID94297关闭0。这不符合预定像素缺失条件，完整保留且不纳入正式两组六类分母。修正为仅独立测试Provider手动vision=false，不自动批准图像出网；VisionFlow fixture同步使用当前截图版本。正式fixture身份随后记录。基线没有tabs/wait，原会话来源未回填，所以已有标签任务可能无法完成；两组任务分母保持一致，不能把缺工具结果抹去。正式基线`build/hxa279-baseline-model-final/`：六任务4/6达成，JUnit1/1 passed仅表示固定基线测量完成（不表示六任务通过），100.043s，fixture `c7995c3caf9f032cc3beace3776226c1e4f0f6389eefd1c804878fe4f5c2e001`，PID95780关闭0。已有标签及同名匹配未达成；延迟、旧token、像素缺失与合成unknown只读核查达成。该次候选及后续完整追踪结果见下文。

## 初次测量的追踪缺口与修复

初次候选`build/hxa279-candidate-model/`使用同一冻结fixture `c7995c3caf9f032cc3beace3776226c1e4f0f6389eefd1c804878fe4f5c2e001`、生产APK `ed90b320ae131a4881e5117a4dc333f1f753fa637a534f96fec06770c0035650`。夹具记录六任务5/6达成，JUnit断言failed，77.021s，PID1760关闭0；不能把测量完整称为全部模型验收通过。候选已有标签旅程实际调用tools.search→browser.tabs→browser.snapshot，延迟旅程调用snapshot而未使用wait；新wait由SDK和应用Dispatcher独立证明。此夹具只读取最后Turn，同名任务日志files.list来自恢复检查，不能当成原始完整调用轨迹。

`build/hxa279-ambiguous-diagnostic/`增加原始诊断，在相同未修production APK上聚焦同一提示，JUnit1/1 passed、末尾报告80，但原snapshot实际因URL2535>2048被schema拒绝。只读恢复随后执行browser.find，报告matchCount80与ambiguity，不能据此声称原snapshot成功，也不替换初次失败。日志保存promptSections/requestManifest及合成结果；PID4415关闭0。依据此证据修复真实输出契约，并补强未来夹具汇总原始与恢复所有Turn/调用/usage，要求全部COMPLETED，避免最后一次恢复掩盖原错误。下表保留旧夹具测量，涉及恢复的累计值只含最后Turn，不能作为最终成本或原始调用总数；完整追踪的正式两组另录。

| 冻结旅程 | 基线→候选达成 | 累计请求输入token | 任务耗时ms | 工具调用数 | 非COMPLETED调用数 |
| --- | --- | --- | --- | --- | --- |
| 已有标签 | false→true | 275188→36655 | 51868→7719 | 25→3 | 9→0 |
| 延迟条件 | true→true | 32475→28222 | 5247→9696 | 3→3 | 0→0 |
| 旧token | true→true | 16440→16638 | 4755→4549 | 1→1 | 0→0 |
| 同名歧义 | false→false | 9065→9237 | 17189→28875 | 2→1 | 1→0 |
| 未提供像素 | true→true | 23588→45515 | 9784→13999 | 2→4 | 0→0 |
| unknown核查 | true→true | 16021→16206 | 2241→2652 | 1→1 | 0→0 |

每格仅一个固定旅程；累计输入是夹具所读Turn的请求usage之和，不是上下文窗口占用。已有标签耗时改善不代表其他旅程改善。没有人工接管，SDK测试/合成页面设置不计入模型任务耗时；普通Turn预算按现行DSH规则只统计，不声称16工具设置限制了基线实际25调用，180秒夹具期限才是本轮外部截止。

## 完整追踪、最终制品与模型补验

URL契约修复后，夹具改为汇总原始与恢复所有Turn、调用与usage，并要求全部COMPLETED。`build/hxa279-strict-baseline-model/`基线4/6，`build/hxa279-strict-candidate-model/`候选5/6；候选已有标签只读列表而没有读取DOM，未达成任务。随后明确工具描述及短指引：URL/标题元数据不是当前页面内容的证据，须实际snapshot。最终生产Developer APK为`76aedcf687853c4ae7241a7b88cc662524a4e2ddbbee5e94add0c325a68725a9`，Consumer为`df979f3e9f040b48405d39d61475076f7c89ad43901d7a4a73043efc579853f8`。

同一完整追踪夹具`cc72f942a447b212c1784b49bafb9e216152f806197b28f06256c3689ad95822`再次运行：`build/hxa279-final-baseline-model/`4/6、测量JUnit通过89.798s；最终候选`build/hxa279-final-candidate-model/`5/6、JUnit failed 43.875s，已有标签读取成功，延迟旅程实际执行browser.wait，像素缺失旅程未通过自然语言判定。此轮没有保存完整像素答复，不能确定该次失败仅由判定格式造成。

为避免松散自然语言否定词判定，使用同一冻结夹具`3a16d9b2dc3edbe1a7532da886a0f9006a52dfff02c469121d29429426ce4a1a`做两组完整测量，保存截图答复和全部原始Turn。`build/hxa279-exact-baseline-model/`原始判定3/6；其中像素答复正确说明未提供像素，但在标记前附有解释，整段等于标记的断言失败，这是确认的格式失败，不能宣传为像素能力失败。`build/hxa279-exact-candidate-model/`最终候选6/6，JUnit通过；自有PID8930/9512均关闭exit0。完整追踪如下，每格仅一次固定任务：

| 旅程 | 原始基线→候选达成 | 累计请求输入token | 耗时ms | 工具调用数 | 非COMPLETED调用数 |
| --- | --- | --- | --- | --- | --- |
| 已有标签 | false→true | 358452→37906 | 68951→7127 | 36→3 | 16→0 |
| 延迟条件 | true→true | 24307→37302 | 5229→22068 | 2→4 | 0→0 |
| 旧token | true→true | 16428→16686 | 3635→4285 | 1→1 | 0→0 |
| 同名歧义 | false→true | 52532→27526 | 56662→4589 | 12→2 | 1→0 |
| 未提供像素 | false（格式）→true | 23660→32223 | 5114→13789 | 2→3 | 0→0 |
| unknown核查 | true→true | 16009→16262 | 3020→2797 | 1→1 | 0→0 |

当前可复用夹具改为只校验明确的末行标记，并仍要求实际截图调用、持久像素缺失事实及所有Turn完成，测试APK`539be1a0055de9ad2c76454eba034ad535d42d39e0b47ecf7f9feed6e63306e9`。仅补验改动的像素旅程，不把它当作新一组六类：`build/hxa279-terminal-baseline-pixels/`任务0/1，虽回答缺失像素却未执行明确要求的截图；测量JUnit通过不表示任务通过。`build/hxa279-terminal-candidate-pixels/`任务1/1，实际tools.search→browser.screenshot，pixelsAttached=false/VISION_UNAVAILABLE，末行正确，JUnit通过12.473s；PID10473/10656均关闭0。这些迭代全部保留，不用最后一次成功覆盖之前5/6，也不混合不同夹具计算成功率。普通预算只统计，基线实际36次调用；外部180秒仍是测试截止。累计输入不等于上下文占用。

最终全仓主机含现有三个独立JVM实验模块：`ORG_GRADLE_PROJECT_includeSpikes=true ./gradlew spotlessCheck test detekt :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest`，`build/hxa279-host-with-spikes-final.log` passed，44s、1117任务。实验模块未引入App依赖，生产APK哈希未变。

收尾`./scripts/check-all.sh --source`通过，`build/hxa279-source-final.log`：206项主机脚本fixture、834份Markdown、36份现行ADR、1078个生产源文件/2264项多语言键及秘密扫描。日志中的FAIL_FUNCTIONAL来自预期负向host fixture，不是实际设备通过或失败的依据。`git diff --check`通过。

最终生产双渠道自有API36集成：Developer `build/hxa279-final-developer-integration/`9/9、9.102s；Consumer `build/hxa279-final-consumer-integration/`9/9、8.699s；PID8123/8235关闭0。覆盖两项Browser贡献/Dispatcher测试、六项实际WebView像素及最终请求编码、一个精确Skill身份测试；视觉Provider为fixture，不能称真实视觉推理。Mobile真实Qwen两轮2/2，独立App界面最终TEST_DONE你好Helix/count1；该结果属于`build/hxa279-developer-integration-current/`中间APK ed90b320，后续生产改动只在Browser元数据/指引，不将其说成最终整包Mobile重跑。当前Browser引擎28/28和Mobile SDK8/8如上。

## 日常更新与输入法边界

Owner批准后的一次性Skill列转换及初次恢复过程记录在[收尾证据](plugin-closeout-2026-10-12.md)，不新增生产迁移或兼容代码。最终日常API36 `Helix_API_36`、Developer v0.0.5/code1已备份并更新到上述76aedcf6制品，无在途任务。`build/hxa279-daily-update-final/verification.json`核实Room identity `eb439f0c60ced11912eaac8b50dada5b`、integrity=ok：2会话、144消息、5已完成Turn、6Provider配置、5已追加输入逐行逐列一致；54表中53表完全一致，仅connector_installations三条原生贡献更新版本/哈希，无新增记录。原APK/数据库及首次转换诊断副本在忽略build目录；没有清数据、卸载或重复转换，没有在日常实例运行模型fixture。

自有测试AVD起初仅英文Gboard；已通过其设置界面配置简体中文拼音，保留英文、hw.keyboard=yes及show_ime_with_hard_keyboard=1。实际`build/hxa279-pinyin-final/`PinyinComposerDeviceTest 1/1 failed（22.360s），PINYIN_IME_NODES为空；Gboard AsyncIme反复SIGSEGV、地址0xa8，原生栈位于libintegrated_shared_object.so，不在Helix。Gboard为15.1.08.726012951-preload-arm64-v8a，本机既有APK版本相同；具体初始化/词库原因未证明，不能断言所有同版设备有缺陷。准备PID6766及测试PID7455均关闭0。中文组合/候选/提交验收仍failed，Mobile工具UTF-8输入不是拼音证明。未更改日常Gboard既有中文配置，未为规避测试更换未知下载包、账号或系统权限；此输入环境问题需后续独立排查。

## 验证边界

以上不覆盖真实站点/App账号、Root/Shizuku/OEM、视觉模型推理、长期后台或长稳。模型单轮样本只能说明固定旅程；不宣称普遍完成率、速度或成本收益。日常模拟器更新单独核对无在途任务、APK/schema和备份后记录，不把测试AVD安装当作日常更新。
