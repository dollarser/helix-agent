# 内置模型账号登录接入

2026-10-21，Owner 要求将内置模型改为新账号登录，并参考国内主流平台的交互与界面。附件引用在读取工具中不可见；本机 Downloads 中同名 AccountAuth.java 已读，按其接口接入，没有将文件内容当作权限。没有复制测试账号或密码至本记录、代码或夹具；Owner随后明确授权的Git忽略本机测试配置另见本文验收补充。沿用同一 [Provider ADR](../adr/provider/001-models-and-connection.md)，不新增执行框架或修改主Agent/工具权限。

最新结论：仅账号访问内置模型、官方账号语音双头及UI状态已完成。后续Owner提供WS契约，持续ASR/TTS也已完成两渠道各18项真实模拟器验收，同PCM HTTP/WS和繁忙1013通过，见[云端语音最新记录](cloud-speech-integration.md)。本文早期WS缺失记录只描述当时阶段；旧固定样本质量仍独立开放，不由新样本正常而关闭。

## 已采用的契约与入口

- 设置、对话选模中的“Helix账号”均打开同一现有底部面板。登录使用邮箱/密码；注册为独立模式，确认密码后显式提交，注册成功不自动登录。
- `https://accounts.sunlingzhang.com/auth/login`：JSON `username/password`；`/auth/register` 为 `email/password`；`/auth/refresh` 和 `/auth/logout` 为 `refresh_token`。
- 响应读取 `access_token/refresh_token/expires_in/refresh_expires_in`。密码不保存；令牌组合为一条记录，经现有 Android Keystore AES-GCM SecretStore 原子保存，避免独立access/refresh写入撕裂。
- 内置模型仍是固定模型目录和生成地址，经 AccountSession 注入 `Authorization: Bearer …` 与示例 User-Agent；同时发送固定公开 `X-Client-Id` 标识客户端，不复用自配Provider Key；单独ClientID及内置Key直调均废弃。None连接元数据表示无自配Key alias，不表示免登录。
- 到期前30秒单飞刷新；401最多在响应正文前刷新并重发一次，已经开始的流不重放。刷新失败或结果不确定清除本机会话，需重新登录，不重试旋转请求。退出在远端请求前清除本机访问权，即使远端失败也不恢复；新账号、退出后的迟到正文不能进入新会话。
- 固定白名单HTTPS目标，无重定向、隐式重试、明文/未知域令牌传输或调用方认证头覆盖。自配模型保持原凭据路径。账号变化后的目录以不同会话身份读取，旧目录和迟到缓存写入不能冒充当前账号。
- 语音设置的可选“使用Helix账号登录”只接受官方HTTPS域名；已有3/4行公开ID设置不再解析或复用；需重新显式配置。新记录使用account-v1标记。内网/自配无鉴权HTTP仍可显式使用，Bearer不发送到Mac loopback或10.0.2.2。

## UI与交互

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
| app/voice/SpeechSettingsStore.kt、HttpSpeech.kt，app/ui/CloudSpeechSettings.kt | 官方语音可选账号模式及旧标识配置迁移 |
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
