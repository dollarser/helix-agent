# 内置模型账号登录接入

2026-10-21，Owner 要求将内置模型改为新账号登录，并参考国内主流平台的交互与界面。附件引用在读取工具中不可见；本机 Downloads 中同名 AccountAuth.java 已读，按其接口接入，没有将文件内容当作权限。没有复制测试账号或密码至本记录、代码或夹具；Owner随后明确授权的Git忽略本机测试配置另见本文验收补充。沿用同一 [Provider ADR](../adr/provider/001-models-and-connection.md)，不新增执行框架或修改主Agent/工具权限。

最新契约以以下“当前部署”与“账号额度和诊断”两节为准。下文带日期的早期验收保留其当时范围，不作为本轮客户端或真实服务验收的替代证据。

## 当前部署（2026-11-15）

官方账号地址为 `https://accounts.hilex.me`，模型公网 base URL 为 `https://api.hilex.me/v1`，由 `OfficialServices` 统一派生。OpenAI 云模型不得通过本机免认证入口调用。账号请求使用 Bearer access token 和有效 `X-Client-ID`；自配 API Key 使用自身 Bearer Key，不借用账号管理令牌，不在客户端存放 Worker 内部密钥或上游 OpenAI 服务密钥。

Owner 确认旧、新账号域使用通用令牌，持久记录不保存或判断签发域；有效会话不因换域强制重新登录。官方请求目标仅允许当前 accounts/api HTTPS 主机，WS 精确限定当前官方路径，不保留旧域回退或重定向。第三方 Provider 与自定义语音认证路径保持独立。

完整发现使用 `GET /v1/models?output_modalities=all`，根据 `architecture.output_modalities` 与可选 `endpoints` 区分对话、识别及合成，语音模型不进入聊天选择器。发现仍取决于服务端模型目录权限，只有 ASR/TTS 权限的 Key 不能因为可直接调用语音就被认为能够发现目录。

云端 `gpt-transcribe` 使用 HTTP `/v1/audio/transcriptions`，`gpt-realtime-2.1-mini` 使用 HTTP `/v1/audio/speech`；保留自建语音 `/ws/asr`、`/ws/tts`，但不能只改 model 名称便假设 WS 会转发至 OpenAI。具体音频约束与当前路由见[云端语音接入](cloud-speech-integration.md)。

## 账号额度和诊断（2026-11-15）

已登录账号详情提供按需展开的“额度与用量”。`AccountUsageClient` 只接受 `AccountSession`，通过其精确目标绑定的认证 wire 访问账号域名：

- `/account/luna-usage`：`model/month/timezone` 和 `monthly_tokens/used_tokens/reserved_tokens/remaining_tokens`；月周期显示服务端 `YYYY-MM`。
- `/account/audio-usage`：`day/timezone`，`asr`、`tts` 各有 `model/unit/limit/used/reserved/remaining/requests`。ASR 的 `milliseconds` 除以 60000 显示分钟；TTS 的 `characters` 原样显示，不混用秒或 UTF-16 长度。
- `/account/usage`：展示 `today`、按 service 汇总的请求数量及最多 20 条服务/状态记录。只映射展示白名单，不把原始 JSON、账号标识、IP 或设备标识放进 UI。

两类额度响应的可选 `resets_at` 解析为 `Instant`，界面按各自服务端 `timezone` 显示下次重置时间；ASR/TTS 使用音频响应顶层的同一重置时刻。缺失或非法值不推算、不用设备时区代替。

额度上限和剩余值均由服务端返回；不写死默认配额，不用 `limit-used-reserved` 重算余额。已计入包含预留/待核对量，不能再次扣除。日/月周期以服务端 `Asia/Shanghai` 为准，不使用客户端本地日期重算。仅配置 API Key 的 Provider 不调用这些账号接口、不伪造余额。额度读取绑定当前登录代际，切换/退出后的旧响应不能进入新账号；响应大小和总耗时有界。

账号错误先展示已解析的服务端 `detail`，无有效说明时用本地化分类；不展示异常堆栈或任意响应全文。保留 `X-Request-ID` 并提供复制入口。429 不无限重试，展示并遵守 `Retry-After`；401 只进行一次正规的刷新流程，403 保留服务端权限、封禁或首次改密提示。网络中断不无条件重交同份文本/音频，旋转刷新结果未知仍要求重新登录，不把请求诊断 ID 当作幂等保证。

请求诊断使用应用版本、随机安装实例 ID、应用 User-Agent 和每次逻辑请求随机 ID；安装 ID 不使用硬件序列号、MAC 或邮箱，也不参与认证。诊断信息不携带凭据，不把凭据或诊断字段塞进 WS URL 查询。常规日志不写识别结果、待合成文本或原始音频。

本日独立真实服务探测已获得三项额度接口 HTTP 200，验证上述字段和单位；登录、目录、合成音频 HTTP TTS/ASR、退出也取得成功响应。只保留脱敏结构和状态，不保留身份/凭据或真实用量。该探测证明服务器契约，不等于 Android 额度 UI、所有模型或发布候选全部通过。本轮客户端集成测试状态另随最终验收记录，不沿用下文历史通过数。

## 已采用的契约与入口

- 设置、对话选模中的“Helix账号”均打开同一现有底部面板。登录使用邮箱/密码；注册为独立模式，确认密码后显式提交，注册成功不自动登录。
- `https://accounts.hilex.me/auth/login`：JSON `username/password`；`/auth/register` 为 `email/password`；`/auth/refresh` 和 `/auth/logout` 为 `refresh_token`。
- 响应读取 `access_token/refresh_token/expires_in/refresh_expires_in`。密码不保存；令牌组合为一条记录，经现有 Android Keystore AES-GCM SecretStore 原子保存，避免独立access/refresh写入撕裂。
- 内置模型仍是固定模型目录和生成地址，经 AccountSession 注入 `Authorization: Bearer …` 与应用版本 User-Agent；同时发送固定公开 `X-Client-Id` 标识客户端，不复用自配Provider Key；单独ClientID及内置Key直调均废弃。None连接元数据表示无自配Key alias，不表示免登录。
- 到期前30秒单飞刷新；401最多在响应正文前刷新并重发一次，已经开始的流不重放。刷新失败或结果不确定清除本机会话，需重新登录，不重试旋转请求。退出在远端请求前清除本机访问权，即使远端失败也不恢复；新账号、退出后的迟到正文不能进入新会话。
- 固定白名单HTTPS目标，无重定向、隐式重试、明文/未知域令牌传输或调用方认证头覆盖。自配模型保持原凭据路径。账号变化后的目录以不同会话身份读取，旧目录和迟到缓存写入不能冒充当前账号。
- 语音设置的可选“使用Helix账号登录”只接受官方HTTPS域名；已有3/4行公开ID设置不再解析或复用；需重新显式配置。新记录使用account-v1标记。内网/自配无鉴权HTTP仍可显式使用，Bearer不发送到Mac loopback或10.0.2.2。

## UI与交互

当前官方部署基址统一于 `OfficialServices`：账号为 `https://accounts.hilex.me`，推理为 `https://api.hilex.me/v1`，语音 WS 为 `wss://api.hilex.me`。只有 accounts/api 两个 HTTPS 主机可接受账号令牌；WS 精确限定 `/ws/asr`、`/ws/tts`。官方语音地址由模式派生，设置不再提供独立地址编辑框；自定义 HTTP/HTTPS 地址与其无账号认证路径保留。此次不做旧语音域回退或重定向。详见[当前网关契约](cloud-speech-integration.md#当前统一网关owner-契约2026-10-28)。

实际查看[阿里云官方登录页](https://account.aliyun.com/login/login.htm)与[腾讯云官方邮箱登录](https://cloud.tencent.com/login)，参考其账密表单层级、突出主按钮、密码显隐及独立注册入口，使用Helix自有主题和图标。手机布局为欢迎标题、邮箱与密码、52dp圆角主按钮、注册切换和登录状态。提供邮箱格式反馈、Next/Done键盘行为、重复密码确认、请求中禁用重复提交/模式切换、退出确认和安全错误提示；密码状态不使用rememberSaveable，关闭表单即释放引用。未提供实际服务契约的短信、微信、QQ、找回密码不展示；没有伪造协议链接、默认勾选或无效按钮。

## 早期主机验证事实

真实主机请求只向确认的官方账号/API域发送测试账号；终端隐藏输入，凭据/令牌只在进程内存，结束时退出。报告仅记录HTTP状态及合成回答，不含账号、密码、token或服务原始响应。`build/account-service-probe-first.json`：登录/刷新/目录/生成/退出200，退出后刷新401；首条生成max_tokens=16时流正常结束但可见文字为空，未记为回答通过。唯一一次max_tokens=256对照 `build/account-service-probe.json` 得到可见“OK”并正常结束，退出后刷新401。不据此声称工具、视觉、WS或所有模型通过；没有创建新账号或修改密码。

主机fake测试覆盖未登录不出网、登录原子保存/不存密码、HTTPS/目标/认证头边界、并发单飞旋转、401仅重发一次、刷新不确定不重试、远端退出失败仍清除并拒绝迟到正文、保存失败不发布成功。原模型SSE/图片/工具分片/错误和目录socket回归已改为账号Bearer夹具，不调用真实账号。目录新增账号变更及迟到旧身份写入检查；语音设置覆盖旧公开标识不会变为登录许可。

mock模拟器表单测试只使用合成字符串，不向账号服务发送、也不持久化测试账号；验证空/非法邮箱、显式注册与确认、请求中禁用、密码显隐及空白表单截图。主机/设备命令与最终结果见下节。

## 账号初版实现与验证（真实Android验收之前）

账号适配器在创建时固定本进程账号版本，在授权锁内核对；即使旧适配器尚未发出第一次请求，之后登录/退出也不能使它借用新账号。访问令牌轮换保持原会话身份，登录/清除改变版本，正文同时校验版本与持久会话标识。存储清理失败会报告错误并阻断本进程后续授权，不把内存清空误报为持久清理成功；远端撤销失败不等于服务端凭据已经撤销。TTL采用正数和精确算术溢出校验，不假设服务未声明的最长年限。

最终主机命令：

```sh
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest detekt \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --offline --max-workers=1
./gradlew spotlessCheck :app:lintConsumerDebug :app:lintDeveloperDebug --offline --max-workers=1
./scripts/check-secrets.sh
./scripts/check-docs.sh
./scripts/verify-adr.sh
./scripts/check-i18n.sh
```

`build/account-final-build.log` 为最终构建与回归成功记录。Consumer 1368、Developer 1503，均零失败/错误，各5项跳过（保留现有/可选profile的跳过，未删弱测试）。AccountSessionTest共10项，新增目录身份边界1项，原有内置模型socket/SSE/工具分片/视觉与语音回归同时保留。初期新增代码曾触发格式、JUnit方法返回类型与异常边界静态规则，中文夹具曾传错LocaleList类型，新增版本绑定曾与“清空后本地401”断言冲突；均修复实现或测试声明，保留行为断言，并执行新鲜回归。静态检查的凭据边界局部说明只用于禁止原始解析/Keystore异常携带敏感原因链，不关闭全局规则或返回伪成功。

源码检查通过：秘密扫描无内容回显；文档957份/297个HXA，40条当前ADR验证成功；国际化1198生产文件、2673键在base/en/zh-rCN一致。最终格式/lint日志为 `build/account-final-static.log`。

最终模拟器命令与真实账号隔离：

```sh
# ANDROID_HOME 使用本机SDK位置；output每次须为新目录。
python3 scripts/run-owned-emulator.py \
  --avd Helix_Validation_API36_20261012 --port 5576 \
  --apk app/build/outputs/apk/consumer/debug/app-consumer-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk \
  --classes com.helix.app.ui.AccountLoginFormDeviceTest \
  --runner com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner \
  --output build/account-final-consumer-20261021 --raw-results --gpu swiftshader --timeout 600
```

Advanced使用5574端口，APK路径中consumer替换developer，runner包为com.helix.agent.developer.test，输出 `build/account-final-developer-20261021`。两者均为API36 ARM64独立只读实例（不写回既有AVD用户数据），4 passed、0 skipped/failed，自建进程已按closed.json退出；原模拟器保留、未操作真机。各目录冻结实际app/test APK与hash、系统属性、原始instrumentation/logcat、summary、键盘设置和无账号截图。中文快照由测试Activity使用现有应用语言包装接口生成，仅为mock表单预览，不冒充真实账号登录截图或全产品页面视觉验收。

4项为：空/非法邮箱不能提交且合法邮箱后可提交；注册确认不一致不能提交且匹配后必须显式点击；提交期间禁用输入、密码显隐及模式切换且只提交一次；空白表单显隐控件与无账号截图。Next/Done和退出确认已实现，不把这4项冒充所有IME、验证码或全账号中心验收。

真实服务测试是主机HTTP契约对照，模拟器只验证mock表单。尚未在Android里使用真实账号跑Keystore恢复/真实模型端到端；未创建真实注册账号、测试找回密码、短信、第三方登录或WS，官方ASR/TTS Bearer链路也未真实执行。此前语音严格转写质量与并发409边界不由本次账号测试关闭。没有发布、推送或修改防火墙。

## 文件与关键类型

| 路径 | 内容 |
| --- | --- |
| app/account/AccountTokens.kt | AccountTokens、AccountTokenStorage、AccountSecretStorage，安全令牌记录与既有Keystore适配 |
| app/account/AccountSession.kt | AccountSession、固定目标AccountServiceWire、单飞与版本/迟到正文边界 |
| app/account/BuiltInAccountWire.kt | 内置固定GET/POST及每次调用的独立身份绑定 |
| app/HelixApplication.kt、DefaultAppContainer.kt | 同一应用账号所有权、Provider与目录身份注入 |
| app/provider/ProviderFactory.kt、BuiltInModelCatalog.kt、BuiltInModelDiscovery.kt、ProviderTestStatusStore.kt | 账号注入、缓存身份与原身份发布 |
| app/ui/AccountSettings.kt、AccountLoginForm.kt、SettingsScreen.kt、ComposerModelMenu.kt | 服务意图、登录/注册表单和现有入口 |
| app/voice/SpeechSettingsStore.kt、HttpSpeech.kt，app/ui/CloudSpeechSettings.kt | 当前账号语音设置、官方地址派生及自定义 HTTP 模式 |
| 三套strings.xml | 账号、登录错误及语音模式文案 |
| app/src/test/.../account/、provider/BuiltInModel*Test.kt、voice/HttpSpeechTest.kt | mock会话、身份、socket和配置回归 |
| app/src/androidTest/.../ui/AccountLoginFormDeviceTest.kt、voice/CloudSpeechDeviceTest.kt | 合成表单检查及旧真实服务profile明确拒绝 |
| README、Provider/语音同一ADR、status、HXA-307、接入说明 | 当前事实与历史证据边界 |

表中app主源码缩写相对于app/src/main/kotlin/com/helix，测试缩写为对应测试根；精确实际源输入索引保留在忽略的build/account-source-inputs.json。此前语音任务及其他任务的脏改动均保留，未重置/stash或覆盖初始implementation-guide的独立改动。

最终冻结源码的双渠道格式/lint命令通过（build/account-final-static.log，4m23s），两份最终中文mock设备目录summary均为4/0/0且closed.exit=0。生产APK与测试APK已绑定实际安装件，未将前轮APK当作新构建证据。中文表单截图位于build/account-final-consumer-20261021/account-login-form.png及developer对应目录。真实账号端到端Android验证仍未执行，不能把mock表单或主机HTTP对照称为该项通过。

## 账号与客户端标识补充

Owner进一步明确：内置模型目录与生成请求必须同时携带账号Bearer和原固定ClientID。ClientID仅标识客户端，不能代替登录。标识由账号鉴权后的固定内置服务适配层注入，调用方不能覆盖；未登录不发网络请求，401仅按既有账号刷新策略重试，不尝试单独ClientID、内置Key或其他Provider。自配Provider能力保留，不能作为内置服务的自动兜底。本次不改变语音服务的独立显式账号配置，也未新增真实账号登录验证。

已移除无生产调用的旧 PublicClientWireClient 及其单标识协议测试，相关固定端点、禁止覆盖认证、重定向与无兜底边界由 AccountSessionTest、BuiltInAccountWireTest 和内置模型HTTP测试覆盖。内置服务连接固定为无自配Key alias，测试中的凭据查找器一旦被调用即失败。

本次增补主机验证：`./gradlew :provider:api:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest detekt spotlessCheck :app:assembleConsumerDebug :app:assembleDeveloperDebug --offline --max-workers=1` 成功。Provider API 117项零失败；Consumer 1370、Developer 1505项零失败，各5项既有skip。新增2项账号双头测试，并将模型生成/目录本地HTTP测试接到生产 BuiltInAccountWire；覆盖双头、401刷新后保留标识、二次401不降级、未登录零网络及调用方覆盖认证拒绝。既有重定向、取消、工具与图像流测试仍通过。首次误用JVM模块不存在的testDebugUnitTest任务仅在任务选择阶段失败，改为test后完整重跑。ADR检查40项、文档检查957份/297任务通过，diff检查通过。日志位于本地忽略路径 `build/account-client-id-final.log`。本轮未重新登录、未执行设备测试、未提交或推送；新双头真实服务端验收尚未执行。

## 仅账号访问内置模型

Owner明确删除单独ClientID和内置Key入口，不再兼容。生产内置服务仅保留账号Bearer加固定客户端标识；退出或未登录时目录为空、不请求目录、不能选择或执行内置模型，历史Session/Turn目标保留但不授予访问权。未登录用户可自行配置API、订阅或下载本地模型，现有各渠道平台约束保持。旧公开ID语音设置的3/4行格式不再解析，不复制地址或认证开关；必须通过当前设置重新配置。未删除用户既有文件或Keystore内容。Connector OAuth的Client ID属于第三方连接器协议，不是内置模型直调入口。

仅账号入口增补验证：`./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest detekt spotlessCheck :app:assembleConsumerDebug :app:assembleDeveloperDebug --offline --max-workers=1` 成功。Consumer 1372、Developer 1507项，零失败/错误，各5项既有skip。覆盖未登录不发目录请求、缓存不展示、账号恢复后可用、退出立即锁定、旧3/4行语音设置不复用。初次静态检查发现目录read返回数量超限，合并拒绝条件后完整重跑通过。国际化1198源文件/2673资源键、ADR40项及文档957份/297任务检查通过。主机日志 `build/account-only-final.log`；本次没有新真实账号或设备联调，未暂存、提交或推送。

## 账号双头真实验收补充（2026-10-21）

Owner明确授权除真机以外的剩余验收与本地提交，并允许长期测试账号写入不提交Git的合适文件。测试profile位于Git忽略的 `scripts/local/profiles/account-test.json`，权限0600；不在命令参数、源码、日志或截图写入其内容。应用仍不保存密码。维护脚本 `scripts/run-account-service-emulator.py` 验证profile未跟踪且被忽略，只通过一次性host-loopback桥交给自建模拟器，然后清除桥内存；依赖现有owned runner冻结APK、启用键盘与IME并关闭自建实例。

官方账号登录、刷新、退出与账号服务请求统一带固定ClientID；模型及显式账号语音额外带Bearer，不实现管理员Key入口、仅ClientID直调或失败回退。已认证ASR/TTS OpenAPI实际包含ClientID与AccountKey/Bearer安全定义；客户端只采用账号access_token。真实主机双头TTS200、ASR两次200；源WAV24kHz/mono/PCM16/68160frames，服务识别侧报告16kHz/2.84秒，两次均转写为“哦你好这是语音传输测试”。原文不含“哦”，严格质量不通过；容器/传输正确不证明TTS发音或ASR质量正确。两次串行TTS200；2并发对照为200/409，不做无界重试，保留客户端合成物理串行锁。原始合成WAV、OpenAPI与安全报告保留于忽略的 `build/account-live-host/`。

当前公开与已认证语音文档均只给health/file-ASR/complete-WAV-TTS，没有WebSocket路径、消息或事件定义，仓库中也无可用部署契约；不能虚构生产协议。剩余最小输入为WS完整地址、鉴权头/握手、起始参数/采样率/音频帧编码、partial/final快照或增量语义、结束/flush/取消/错误与请求身份、TTS追加文本及PCM/容器格式、并发/复用限制。HTTP模式继续明确整段上传、分句请求、整段WAV返回后播放，不声称原生双流或持续ASR partial。

真实模拟器首次请求（Standard，API36 ARM64）已达成模型可见OK、正常完成、进程重启与账号旋转；随后账号TTS在发送前因规范化HTTPS补443与绑定原地址不一致被精确匹配拒绝。该轮整体failed，finally退出且自建实例closed.exit=0。修复为绑定与发送同一规范化端点，新增无端口/显式443/ASR目标的双头socket替身回归，不放宽AccountServiceWire精确匹配。另一次16kHz PCM16对照上传200，仍同样多“哦”，证据 `build/account-live-host/rate-control.json` 与对应WAV，未将传输通过当作质量通过。

## 首次完整模拟器结果（列表通知修正之前）

两渠道API36/ARM64均完成真实登录、官方模型目录、可见“OK”及正常完成、受控进程退出/新PID恢复、Keystore账号恢复、真实旋转刷新、账号语音HTTP往返、退出后刷新401、目录隐藏与旧绑定请求拒绝。每渠道主套件6项与补充语音/UI12项全部通过，共36次方法执行；语音往返嵌套于账号方法内，不额外增加计数。API调用使用生产AccountSession、ProviderService/BoundModelProvider和HTTP语音适配；表单UI单独使用合成输入，不把表单mock当作真实登录。没有执行真实Agent工具任务、注册真实新账号、麦克风录音、扬声器声学或真机验收。

| 渠道 | 冻结候选与证据 | 结果 |
| --- | --- | --- |
| Standard | `build/account-live-consumer-20261021-r3/`；实际安装冻结APK，主套件JUnit6通过；补充raw12项与JUnit XML | 18通过；退出并丢弃只读实例，closed.exit=0 |
| Advanced | `build/account-live-developer-20261021-r3/`；主/补充raw日志、账号与合成音频报告、installed-artifacts哈希独立匹配冻结APK | 18通过、0跳过；closed.exit=0，按既有工具汇总为18项 |

各目录含源候选APK/hash、系统、进程退出/重启、IME设置与仅空白合成表单截图。两轮真实语音均返回24kHz WAV，77760/85440帧，转写均为“你好这是模拟器语音接入测试”；这是传输对照，不能覆盖另一固定样本多“哦”的质量失败。补充12项覆盖捕获上限/final一次、读取失败、迟到取消、草稿审阅后采用且不发送、取消/后台/切换会话保留草稿、朗读停止/切换/背景/焦点、缺模型只提示显式下载与正常assistant入口；主套件另覆盖增量去重与停止后后续token不重启、表单校验/注册确认/繁忙禁用/显隐和中文布局。

首个Standard生产端点规范化缺陷已修复并重跑通过，原失败保留。Advanced首次只读克隆带旧预览库，被既有保护规则阻断，未登录；仅在新建只读克隆执行clear-app-data，不改变原AVD/既有模拟器或生产数据库保护。下一轮账号6项已通过，但收集脚本错误以exec-out的退出码推断包存在，误选Standard；修复为读取冻结APK真实包名、检查证据内容并独立核对安装哈希后，最终r3两套均通过。首次runner名预检失败未启动模拟器；以上失败不被最终通过日志覆盖。既有emulator-5554保持，所有自建实例均关闭。

实际命令使用 `python3 scripts/run-account-service-emulator.py --profile scripts/local/profiles/account-test.json --channel consumer|developer --avd Helix_Validation_API36_20261012 --port 5576|5574 --output build/<新证据目录> --with-speech`；渠道/端口分别执行，原始命令日志仅含profile路径，不含凭据。维护runner从APK冻结后按项目HelixAndroidJUnitRunner执行受控setup与恢复、一次性凭据桥、显式官方语音profile与后续12项边界；不使用真机。

## 模型选择列表的账号通知修正

最终审查发现底层目录和请求权限虽已阻断，但ProviderRowUi快照未统一订阅账号变化，退出后UI候选可能短暂残留。AccountSession现发布不含令牌的账号身份StateFlow（读取/登录成功和清除后更新，刷新不换身份）；组合根沿用appScope订阅并刷新ProviderService行，覆盖设置页退出、过期清除和账号切换，不依赖选模页回调。真实账号测试新增等待可见模型行出现及退出后行模型清空的断言，不再把缓存store为空当作UI列表通过。该修正后的构建、主机测试及两渠道真实模拟器已通过，完整主机门禁最终已通过，见本节末。

## 最终候选的模拟器与注销契约

列表通知修正后的唯一最终候选证据为 `build/account-rows-consumer-20261021/` 和 `build/account-rows-developer-20261021/`：每渠道6+12=18项、0跳过、0失败，按既有汇总工具分别核对18个实际方法；新断言明确等待ProviderService真实UI行出现，并确认退出后行模型清空。两渠道均独立读取已安装APK并与冻结app/test哈希匹配，均在新只读克隆中清除测试应用数据，正常退出并丢弃，既有emulator-5554保留。最终两次TTS均24kHz/84480帧，ASR均“你好这是模拟器语音接入测试”，不覆盖固定主机样本的质量失败。

为区分客户端拦截与服务端真正失效，另做一次有界主机注销契约对照：登录200、原access访问目录200、logout200，随后旧refresh401且旧access访问目录401；access TTL响应为900秒。仅记录状态与TTL，未输出或持久化token，报告 `build/account-live-host/logout-contract.json`。此前模拟器的boundRequestRejected证明客户端拦截，此对照另外证明本次服务端两类旧令牌均已拒绝，不把二者混为一项。

当前HTTP取消仅停止本机等待/输出并关闭连接，不证明服务端合成已经终止或占用立即释放；409和WS取消ACK仍需要服务端契约。既有Agent任务和工具权限不由停止朗读改变。

## 最终主机门禁与交付范围

`GRADLE_OPTS='-Dorg.gradle.workers.max=1 -Dorg.gradle.parallel=false' scripts/check-all.sh --all` 最终退出0，日志 `build/account-acceptance-final-all.log`。283项主机脚本通过；四variant静态分析5m7s、全模块测试/Debug及Release-R8构建1m41s、release-smoke AndroidTest APK编译和lint、42份依赖锁、Debug/Release组件/UID/载荷/HTTP-TLS及Runtime制品边界均通过。Consumer/Developer Debug JVM分别1373/1508，零失败/错误，各5项既有条件skip；Provider API117项通过。最终Debug app/test输出、冻结候选和已安装字节三方哈希相同；哈希与源输入保留在忽略build证据，不复制到追踪文档。

同一命令第一轮已完整通过，最终审查发现UI行账号通知缺口后，只因新修正而重跑了受影响候选、两渠道36项与完整门禁；不将第一轮结果冒充新源码。最终模拟器为Debug/API36 ARM64，Release/R8这里只证明主机构建及静态制品边界，不声称Release真机、声学或全产品验收。原 `docs/development/implementation-guide.md` 无关两行修改始终保留、不纳入本轮提交。仅本地提交相关实现/测试/说明，不推送或发布、不修改版本。

上述HTTP交付时，WS契约尚缺；Owner随后提供完整协议，持续WS已在同一任务完成，最新证据见云端语音说明。原固定样本多字的WAV/JSON和16kHz有限对照保留，不能被新样本正常覆盖。最新有限双WS繁忙关闭码为1013；服务端HTTP FIFO队列深度/超时未做压力验收。

## 原型到 Compose 的第一组界面（2026-10-21）

Owner 采纳 HTML 原型确定交互、Android 继续使用 Compose 的方案。本轮仅调整抽屉—账号—模型选择—返回会话。设计参考为 [HTML 原型片段](../design/helix-account-navigation.html)，包含未登录、已登录、需重新登录，以及模拟等待、失败重试、取消返回和模型搜索词保留。它只使用合成展示状态，账号操作不联网；需要在支持片段的预览容器中渲染，不是生产 H5 页面或登录实现。生成的独立预览放在忽略的 `build/ui-proposals/`，不打入 APK。

`GroupedNavigation` 的 header slot 承载固定顶部账号；`DrawerSettingsNavigation` 通过现有 `ConversationSheet` 保留六个设置权威。中部内容滚动，底部设置/权限模式不跟随滚动。抽屉为窗口 88%、最大 360dp。账号入口复用 `AccountAccessEntry` 和既有邮箱密码表单；模型选择器以 accountEntry slot 复用同一交互。登录成功只关闭账号面板，原选择器继续存在，用户仍需明确选择并保存；取消与失败不替换草稿、不触发发送。退出确认及账号双头鉴权不变。

`AccountStatus` 仅供展示，不授予访问。恢复时清除 refresh 已到期的本地记录，access 到期但 refresh 有效时仍可正常刷新。刷新被拒绝或结果不确定时要求重新登录；存储不可用与一般模型网络失败分别处理。重新登录原因不新增持久字段：后续进程没有该原因时显示未登录，不伪称确定过期。未获取已确认的账号展示资料，登录后采用通用账号标题。

新增主机覆盖：恢复时 refresh 到期与 access 可刷新分别处理、刷新失败与普通模型网络故障区分、远端注销失败仍显示本地退出、存储不可读不冒充过期。新增 Compose fixtures 覆盖固定顶部/底部、四态入口，以及真实模型选择器中的合成登录成功/失败取消返回与草稿保留；设备执行需当前明确授权。旧设置展开断言改为面板打开/选择关闭/全部分类可达，保留受支持入口覆盖。

本轮未使用真实账号、未安装或操作模拟器/真机；设备状态为 **not requested**。浏览器本地文件预览被 URL 策略拒绝，未绕过；原型仅完成生成及 JavaScript 语法检查，尚无浏览器视觉通过结论。主机门禁和编译的最终结果在本轮交付时追加。

### 本轮最终主机结果

`GRADLE_OPTS='-Dorg.gradle.workers.max=1 -Dorg.gradle.parallel=false' scripts/check-all.sh --all` 退出0，日志 `build/ui-redesign-final-all.log`。283项主机脚本、文档/ADR/国际化/秘密检查、静态分析与四变体lint、全模块测试、两渠道Debug及Release/R8构建、release-smoke测试APK编译、依赖锁及Debug/Release组件/进程/载荷/HTTP-TLS/Runtime制品边界均通过。Consumer/Developer Debug JVM分别1396/1531项，零失败/错误，各5项既有条件skip；新增4项账号状态测试通过。

最终 `./gradlew spotlessCheck :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest --max-workers=1 -Dorg.gradle.parallel=false` 退出0，日志 `build/ui-redesign-final-test-apks.log`。新增4个Compose场景只证明测试APK可编译，未执行设备断言。初轮并行回归中的既有SocketWireTest握手超时在单worker及完整门禁中未复现；未延长超时、删除或放宽断言，不将一次复跑推断为已确定根因。初轮格式与复杂度问题已修正，最终检查通过。

主机产物保留在 `app/build/outputs/apk/`，源码与产物的本地完整性记录位于忽略的 `build/ui-redesign-acceptance/`。本轮源码改动未提交、未推送，原 `implementation-guide.md` 的无关修改保留。新UI尚未安装；浏览器视觉核验和设备交互验收没有通过结论，之前账号/语音阶段的设备成功不能替代本轮新界面验收。

## 抽屉视觉还原修正（2026-10-21）

Owner 在实际安装后指出与已选 HTML 原型的布局、字体、圆角、间距和图标差异。本轮将原来 Material 默认抽屉条目改为原生 `DrawerRow`：正文14sp/21sp行高、次级12sp/18sp，普通字重、零额外字距，行圆角24dp，新会话16dp，头像40dp，线性图标20dp，图标与文字间距14dp。账号区18dp水平/20dp垂直内边距，列表外边距12dp、行内16dp；保留至少48dp触控区和用户字体缩放。Android系统无衬线字形与桌面浏览器系统字体可能不同，不打包来源不明字体。

`DrawerVisualTheme` 只作用于导航表面，使用原型的浅/深灰绿色、明确的表面层级和文字颜色；Drawer容器显式指定surface，避免Material默认surfaceContainerLow继续显示淡紫色。导航主项和展开子项均有原生矢量图标，模型及设置有进入箭头，分组使用展开箭头。权限模式改成单行，仍保留Advanced风险确认和原权限语义。没有改变会话、账号鉴权、模型选择、Agent执行或用户文件。

此修正继续使用Compose和现有依赖。Owner另询问M3 Expressive的新导航容器；[官方建议](https://m3.material.io/components/navigation-drawer/overview)在采用Expressive后使用展开式Navigation Rail。候选方向为手机模态展开、宽窗口可折叠侧栏，避免挤占对话；本轮没有把该建议当成容器迁移已经获准或完成，字体/颜色/间距token可复用。

模拟器使用现有API36 ARM64日常实例，测试仅选择纯UI与内存合成账号fixture，并传入 `helix.preserveUserSettings=true`；不清除数据、不使用真实账号。首轮两项登录返回断言误把OutlinedTextField标签也计入整段Text相等；原始日志保留，修正为完整EditableText精确匹配后9项通过，没有放宽字符串比较。后续容器配色修正仍需绑定最终APK复核。手机PLC110/API35仅覆盖原有Advanced，日常模拟器覆盖原有Standard和Advanced，不新增启动图标；手机处于指纹锁屏，未绕过解锁，不能把安装成功当成手机视觉验收。

### 视觉修正最终结果

最终候选命令为 `./gradlew spotlessApply detekt lintDebug lintConsumerDebug lintDeveloperDebug :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest --max-workers=1 -Dorg.gradle.parallel=false`，退出0，日志 `build/ui-visual-alignment-accepted-host.log`。两渠道Debug JVM分别1396/1531项，零失败/错误，各5项既有条件skip。`scripts/check-all.sh --source` 同样退出0。此前第一组UI的Release全门禁属于此前候选；本次纯视觉修正没有重跑Release/R8或做发布验收。

手机原有Advanced、日常模拟器原有Standard和Advanced均覆盖安装；安装后回读APK与本机最终产物哈希一致。最终Consumer app/test APK也逐字节核对，模拟器以该候选执行9项，全部通过、无skip。原始日志、JUnit、用例名单、源输入与产物绑定保留在忽略的 `build/ui-visual-alignment-device/`。覆盖短窗口大字号可达性、六类设置面板的打开/选择关闭、当前会话直接返回、工具分组展开、固定账号与设置、四态入口、40dp头像/20dp图标/至少48dp触控区，以及合成登录成功和失败取消时搜索词/草稿完整保留且不保存或发送。测试安装包为本轮新增，验收后移除；不清除主应用数据，保留既有IME和语言选择。

模拟器浅色实际抽屉截图用于复核图标、文字层次和间距；这不等于桌面HTML像素级相等。Android系统字体和SP缩放保留。手机安装后初次被指纹锁屏阻挡，未尝试解锁；随后手机可见时已在同一最终APK补查浅色实际抽屉、顶部账号、图标、单行Advanced权限模式与设置入口，截图保留于本地证据。9项自动回归仍只在模拟器执行，手机未做完整功能回归；暗色与宽窗口视觉本轮未验收。容器仍为ModalNavigationDrawer，未将视觉修正写成M3 Expressive迁移完成；当前BOM及实际Material3版本以版本目录和依赖锁为准，现有解析制品已含ModalWideNavigationRail，后续迁移需要窗口宽度、返回/关闭与可达性回归。本轮未提交或推送，无关implementation-guide改动保留。

### 2026-10-25 账号服务错误分类

登录/注册/退出表单此前将所有异常压成同一提示；账号刷新非 401 的 HTTP 失败被转换成普通 IOException，下游可能误判为连接问题。现在表单根据封闭类别显示简洁提示：账号服务暂不可用、连接未建立、响应超时、登录/访问被拒、限流或请求未接受。复用既有 `mapHttpStatus`，不猜测网关内部原因；HTTP 530 不再提示用户检查自己的网络。UI 不展示状态码、技术异常、响应正文或诊断详情入口。

共享账号 HTTP 与语音授权适配保留安全状态码给原有错误映射，丢弃错误响应正文。刷新结果不确定时仍清除可能已轮换的凭据并要求新的显式登录，不重放刷新或模型生成；错误分类改善不改变身份、权限和取消边界。模拟不可用与恢复使用合成 Wire/内存账号，不停止真实服务。最终验证结果补记于本段。

最终验证：`./scripts/check-all.sh --debug` 退出 0；两渠道 App JVM 1471/1633 项零失败，各保留原有 5 项跳过。新增分类测试覆盖 530/5xx、401/403、429、408/504、连接失败及非网络本地异常；共享刷新/语音回归验证安全分类、无错误正文、未发送模型/打开 socket 与显式恢复。已有刷新结果未知不得复用 token 的测试保留并通过。独立 API36 ARM64 定点 UI 三项全部通过，包含模拟 530 后准确提示、用户显式重试恢复及草稿/查询不丢、不自动发送。首次 UI 断言错误使用 Application 中文资源对比 Compose 英文页面，修正为当前 Compose 资源精确断言后复验，不放宽文案匹配。生产门禁后仅此测试资源取值变更，另经 Spotless/Detekt/AndroidTest 打包通过；未进行 Release/R8 或真机验收。证据位于 `build/memory-log-experiment-2026-10-25/`；所有临时实例已关闭，未关闭实际服务，未推送或发布。

## hilex.me 切换验收（2026-11-15）

初次域名切换执行 `./scripts/check-all.sh --debug` 及 `:app:assembleDeveloperDebugAndroidTest --offline --max-workers=2` 成功。源码门禁 423 项通过；两渠道 App JVM 测试分别 1819、2020 项，零失败/错误，各 5 项既有或未提供真实 profile 的跳过；当时包含官方路径、旧请求目标拒绝、账号签发域隔离、连接拒绝、HTTP 错误与 WebSocket 超时/失败分类。Owner 随后明确令牌通用，签发域隔离已撤销，当前契约见本文开头及后续验收；该初次记录不作为新契约的复测证据。格式、detekt、Debug lint、双渠道 APK/AndroidTest 源码编译、国际化与制品边界检查均通过。初轮新增代码的 Compose smart-cast、静态复杂度和账号 3xx 分类问题均已修正并重跑，没有降低断言或门禁。

API36 日常模拟器 `Helix_API_36` 的 Developer 包已保数据覆盖更新，安装字节与最终成功候选一致。外部一次性 SQLite 事务只更新官方内置 Provider 的 endpoint；第三方 Provider、会话及历史 model_calls 行逐项摘要前后相同，数据库完整性为 ok。没有 App 兼容迁移、卸载、清数据、真机安装或提交推送。模拟器保留用户语言设置，6 项 instrumentation 通过：账号表单 4 项；生产 ASR/TTS 错误状态、部分文本不得冒充最终结果、无音频/离线兜底、主动重试恢复 2 项（分别覆盖 503、超时、鉴权和协议错误）。语音仅用注入的合成数据和静音端口，没有使用麦克风、扬声器或真实账号。

首次外网探测被执行环境网络策略阻止，未发送请求。Owner 随后明确允许新域名网络访问后，通过正式授权路径进行了无凭据 HTTPS GET，未跟随重定向且 TLS 校验成功：账号根路径 200、`/auth/login` 与 `/openapi.json` 401；API `/healthz` 200、`/v1/models` 401；语音文件路径的 GET 及 API `/openapi.json` 404。健康 JSON 为 `ready=true`、`service=inference-gateway`，声明 `access_token + X-Client-ID`，与客户端现有认证模式吻合。

这些结果只证明域名/HTTPS 和网关可达，不能把 GET 404 当作 POST 路径不存在，也不能把 401 或网关 ready 当作登录、LLM、ASR、TTS 引擎验通。此次没有发送账号、密码、令牌、聊天内容或用户音频，没有执行新域的登录/刷新、真实生成或语音质量验收；新域完整协议仍未由公开 OpenAPI 核实。后续可用既有有效会话验证实际请求，无需仅因换域重新登录。源码指纹、成功门禁、安装校验、原始 instrumentation 和无凭据探测记录保存在忽略的 `build/official-endpoints-20261115/`。

### 当前契约整理与保数据复验（2026-11-15）

按 Owner 明确更正，账号令牌不再记录或校验签发域。原有有效令牌无需仅因切换域名而重新登录或改写存储；刷新与退出使用新的账号服务。新增合成存储测试覆盖有效凭据直接复用、过期 access 的刷新、账号身份保持与退出，不读取真实账号秘密。官方请求仍只允许当前受信目标，不因令牌通用而向任意地址发送；自定义 Provider 和语音地址保持独立。

最终源码的完整 `scripts/check-all.sh --debug` 及 App/Browser 测试 APK 打包通过。423 项主机脚本通过；Consumer/Developer App JVM 分别报告 1821/2022 项，零失败/错误，各保留 5 项既有条件跳过。格式、detekt、Debug lint、双渠道构建、AndroidTest 源码编译、依赖锁及制品边界均通过。此次没有运行 Release/R8。

日常 `Helix_API_36` 上保数据覆盖 Developer Debug，安装后 APK 字节与成功构建候选一致；7 项 App 与 12 项浏览器 instrumentation 全部通过。只读语音配置检查确认该实例仍是 automatic 选择，登录状态下派生 ASR `wss://api.hilex.me/ws/asr`、TTS `wss://api.hilex.me/ws/tts`，没有 `/v1/ws` 或旧域名回退。该检查不读取真实账号或证明其当前登录状态。安装及测试前后的第三方 Provider、会话、历史调用、工具可用性和语音设置摘要一致，数据库完整性为 ok；官方 Provider 已是 `https://api.hilex.me:443/v1`，本次无需再改数据库。其余 App 用例覆盖合成语音失败、插件名称 UI 和真实 Room 夹具中的当前插件来源/修订契约。中英文截图仅是合成 UI 展示复核，不冒充真实模型自然发现验收。

证据在忽略的 `build/compatibility-audit-20261115/`，最终主机日志为 `accepted-debug-gate-r2.log`，设备结果和安装身份为 `app-device.xml`、`browser-device.xml`、`daily-receipt.json`。初轮编译与静态问题已修正并重跑，失败日志保留。本轮未卸载、清空用户数据、操作真机、提交、推送或发布；新域真实登录/生成/语音服务仍未进行带凭据端到端验收。

当前内置服务默认模型为 `gpt-6-luna`，不再用目录已不返回的 Qwen 作为新会话默认。服务稳定 ID 不变，已有会话/草稿的显式模型选择不自动替换。Luna 普通文本调用已有主机实测；当前客户端将关闭推理显式编码为 `none` 并使用 Chat Completions，有推理请求预先选用 Responses，不因失败跨协议重试。早期 tools 400 是历史观察，后续两轮真实探针的 Chat `none` 非流/流与 Responses `medium` 流均返回 502 / `unsupported_country_region_territory`，尚未到工具调用或回填，不应再将其描述为未实现路由或已确定的参数缺陷。脱敏状态与请求编号在忽略的 `build/luna-routing-20261115/real-probe.json`、`real-probe-retry.json`；未改变网络出口或绕过地区限制。


### 2026-11-15 当前候选真实账号与额度复验

Owner 明确批准本次将测试账号凭据发送至 `accounts.hilex.me`，并要求登录后不要退出。日常 `Helix_API_36` 已通过正式 `AccountSession.login` 登录并保留登录态；未清除应用数据。临时本地凭据交接和 ADB reverse 已移除，证据不包含密码或令牌。首次尝试因测试输入解析错误未登录，修正后成功，旧失败没有覆盖。

现有登录态下的生产额度读取/解析通过，账户界面也实际显示 Luna、ASR、TTS 对应单位及重置时间/时区。界面证据只保存字段存在性，不导出账号或额度值。真实 Android ASR/TTS 均返回 HTTP 502，播放/识别/真实取消未通过，不能沿用先前主机成功结论；请求编号及边界见[语音当前复验](cloud-speech-integration.md#2026-11-15-当前候选-android-真实服务复验)。安装字节、登录收据及安全阶段结果保存在 ignored `build/cloud-readiness-20261115/`。

普通 Luna 另经真实 Android 界面单次提交纯合成提示，界面发送与 Activity 重建已执行，但唯一 Turn 最终 FAILED，未获得要求的回答。6 条持久 ModelCall 记录的公开身份均为当前 `gpt-6-luna`；这不等于 6 次已证明的网络请求。该轮未留存具体 errorCode、HTTP 状态或 request ID，原因仍未知，不能推断为地区限制；未重发提示。此前零 Turn 的失败来自新测试启动同步及重复标题选择器，修正后重新验收，旧记录保留。脱敏结论见 ignored `build/cloud-readiness-20261115/live-luna-r8/summary.json`，不把失败算作普通对话通过。

同模型文件定位新批次的生产请求进一步得到 HTTP 422：服务拒绝含点号的 function.name。精确持久诊断 request ID 为 `2de1a929ba13ddd9e0844f72cf988b2e33baaecb1b813ab3`；这是本次已确认的 wire 工具名兼容缺陷，不能归为地区限制。修复范围及新候选记录见[当前工具名协议修复](status.md#2026-11-15-真实工具名协议缺陷与新候选)。

### 工具名修复后新候选真实复验（2026-11-15）

新普通 Developer App/test 的包 ID 和 SHA 均与冻结制品一致，保数据覆盖后无需重登即可读取真实额度，最终界面仍为已登录。普通 Luna 仅一次 UI 提交，实际模型 `gpt-6-luna`，最终 FAILED/SERVER_ERROR；6 条 HTTP 502 诊断中前 5 条为生产自动恢复，末条恢复耗尽，未人工重发。末条 request ID 为 `7d4110c065529bf0c462b71f0008e876991a50830cc30c4e`；该普通请求没有独立证明地区原因，不沿用历史未知错误作解释。详见 ignored `build/cloud-readiness-20261115/live-wirefix/summary.json`。

同候选单次真实文件任务的 function.name 已全部合法，不再出现 422；该任务 6 条精确持久诊断明确为地区拒绝，尚无工具执行。没有改网络出口、切换模型或放宽权限。相关 request ID 和完整脱敏诊断在 ignored `build/real-task-live-wirefix-20261115-192002/model-failure-diagnostics.json`，未把服务拒绝算作模型质量分数。ASR/TTS 本轮 502 保留原证据，未为取得成功而重复请求。


### 2026-11-17 账号只读边界复验

日常 API36 的两个只读设备测试通过，覆盖现有登录下的资料、注册配置、三类额度和通知读取，使用保留用户设置模式。未发送验证码、注册账号、绑定邮箱、修改密码或标记通知已读；这些真实副作用闭环仍未验收。账号 revision 冻结、旧请求不作用于新账户及同账号资料排序已有主机和合成设备回归，不能用模拟场景替代真实验证码流程。原始日志位于 ignored `build/account-boundary-retest-20261117/readonly.log`。

11-15 普通 Luna/工具请求的 502 和地区错误是历史失败；后续真实 Luna Chat Completions/Responses 和有界 Mobile Use 模型任务已有成功，但不证明所有地区、服务稳定性或四项旧业务任务已全部完成。最新整合边界以[当前状态](status.md#最新验收及未通过项)为准。


同日受控合成像素服务实验进一步确认 Luna 的 PNG/JPEG/WebP 图片输入，两种协议共六种组合通过，并以不同图片和无图对照核查实际像素消费。客户端按精确模型和已实证格式调整能力声明，不增加账号权限，不绕过图片绑定或披露。服务层验证不代表完整应用视觉任务已验收，详见[模型契约](../adr/provider/001-models-and-connection.md#2026-11-17luna-图片输入按当前部署实证启用)。

### 2026-11-17 原生密码找回与人机验证接入

登录页新增独立“忘记密码”入口，匿名请求重置码及提交新密码，不依赖公开注册开关；成功返回普通登录，不自动登录。注册、绑定、找回发送邮件前统一获取一次性用途 proof，取消验证不发邮件；原生找回固定保留 API Key。验证码、密码和 proof 不持久保存，冷却与迟到结果隔离见[账号契约](../adr/provider/001-models-and-connection.md#2026-11-17邮箱找回与发码前人机验证)。

主机账号聚焦回归 107 项通过；UI 收尾后完整 Debug 门禁通过，源码前后指纹一致，日常 API36 已保数据覆盖安装并校验 APK 字节。合成账号设备 17 项全部通过（43.581 秒），覆盖 proof 单次消费、取消/迟到、邮箱/账号切换、冷却隔离、找回字段精确提交及秘密清理；renderer 测试仅主动调用 crash/killed 回调，不代表真实进程崩溃验收。保留原语言和登录，结束恢复日常前台且无活跃任务。证据位于 ignored `build/account-recovery-debug-r2-20261117/`；该阶段未重验 Release/R8，后续结果见下；不将合成结果计为真实 Turnstile 或实际密码重置。官方网页已完成真实注册、登录及邮箱已验证核查，属于网页路径证据；后续真实原生发码另有下述独立验收，不依据页面存在或模拟 proof 关闭真实流程边界。


同日经所有者批准，opt-in 隔离测试 Activity 承载生产 `AccountPasswordRecoveryForm`，使用匿名内存会话、真实原生 WebView/Turnstile 与生产 TLS，找回发码返回 HTTP 200。随后独立收件列表核验确认邮件实收：主题为 Helix AI password reset code，显示时间 16:48 UTC+8 与本次发码 08:48 UTC 对应。此结论来自独立核验报告，不是原 instrumentation 自身的邮件断言；原批次的 pending 收件字段仅反映当时阶段，追加核验记录与其并存。未打开邮件正文、未读取验证码、未提交密码重置，也未验证新密码登录。证据位于 ignored `build/account-recovery-live-20261117/`。这证明真实验证与发信链路，不是合成 proof；但未从日常已登录界面完整导航，不能替代日常真实 UI 入口、实际重置及新密码登录验收。


含上述账号与 WebView 修改的新候选，完整 Debug/Release 门禁通过、源码前后指纹一致，双渠道隔离 R8 核心各一项通过（Consumer 23.845 秒、Developer 24.318 秒），覆盖普通对话、工具回填、持久历史、取消、不重放及继续；生产匿名入口导航一项通过（3.093 秒），覆盖注册关闭时仍可找回、返回后秘密清空和原草稿保留。未卸载或清数据。证据见 ignored `build/account-final-all-20261117/`、`build/account-final-r8-20261117/`；账号接入前的 `final-integration-all` / `final-r8-isolated` 仅是旧候选证据，不能继承。当前状态见[最新验收](status.md#最新验收及未通过项)。


后续真实密码重置交互批次在 903.244 秒到达等待时限，仅记录 CHALLENGE_OPEN 与 CODE_HTTP_200，未确认重置成功或新密码登录。没有请求开始见证，故不能断言“未提交重置”；未读用户秘密、未在交接后截图、未自动重放，进程结束后恢复日常前台。证据见 ignored `build/account-recovery-reset-live-r2-20261117/outcome.json`。完整门禁之后追加的 opt-in 真实重置测试独立编译验收，未改生产源码；设备核心通过不关闭此真实重置未知边界。
