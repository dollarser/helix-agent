# 云端语音接入与增量回答朗读

保留现有 Agent/LLM、工具权限、离线语音及用户模型文件。HTTP 文件/WAV 模式与账号认证已交付；Owner 随后提供持续 WebSocket 契约，本轮增加显式 WS 模式和一个回答一个连接的持续文本输入。本文较早日期的 HTTP/缺失 WS 记录保留其历史阶段，最新 WS 验证范围见末节。持续语音不等于原生语音大模型或自动全双工轮次。

## 当前可操作入口

- 设置→语音服务：默认关闭云端语音，保留现有离线模式。用户填写 ASR/TTS 完整 HTTP/HTTPS 地址并显式保存后生效；不推断聊天 provider 的地址或 Key，HTTP 可自配地址；WS 选择器固定官方地址并强制账号认证，不内置凭据。设置明确显示 HTTP 明文、分段能力及可选使用 Helix 账号登录（仅官方 HTTPS 目标）。
- 麦克风入口：离线模式先检查本机模型，缺失时显示来源、体积与显式下载入口；下载成功后用户主动开始才申请麦克风，不自动录音。HTTP 模式先确认目标与上传范围，录音结束后仅上传该次完整 WAV，最终文字预览后由用户加入草稿。没有用假 partial 冒充流式转写。
- 正在生成的普通可见回答：用户点击朗读后接收该回答后续文本增量；停止仅停止声音，不取消 Agent。普通完整回答仍可点击朗读/停止。
- HTTP TTS 按提交的文本段请求合成，每段完整 WAV 返回后分块播放。WS 模式在同一个连接内持续追加可见文本，并行接收和播放 PCM；HTTP 分句不是持续 WS。
- `127.0.0.1` 在 Android 上指设备自身。Owner 的 Mac loopback 映射仅用于本轮主机联调，不会自动让手机可访问；客户端不创建 ADB 转发、防火墙规则或持久端口开放。

## 已核实的服务契约

HTTP 阶段已读取 OpenAPI 与 `/docs`；后来 Owner 提供统一客户端接入文档，明确 WS 契约，客户端按下文接入。

- `POST /v1/audio/transcriptions`：multipart 字段 `file`，WAV/FLAC/OGG，最多 20 MiB/120 秒。合成静音探测返回 JSON `text` 字符串及 `language`、`sample_rate`、`audio_seconds`。客户端只取有界 `text`，缺失或类型错误必须失败。
- `POST /v1/audio/speech`：JSON `{"input":"文本"}`，文档限定 1–2000 字符，返回单声道 PCM16 WAV；实际固定中文测试句返回 24 kHz WAV。客户端支持 8–48 kHz 的单声道 PCM16 RIFF/WAVE，校验格式、大小、采样率、时长及数据块，不支持的音频必须报错。
- 公开 ClientID 阶段已按 Owner 新账号要求替换；客户端提供官方 HTTPS 账号模式，不复用用户自配模型 Key。HTTP 是用户明确选择并提示风险的地址，不会将 HTTPS 自动降级。

`CloudSpeechConfig` 保留独立 endpoint、显式协议与超时；HTTP 使用明确协议标识 `http-file-wav-v1`，通过既有 `NormalizedEndpoint` 和 `OkHttpWireClient`。连接复用 Native DNS，禁重定向、自动连接重试与 HTTP follow-up 重放，响应、录音、文本、音频均有界。multipart 需要请求体 Content-Type 与 header 一致，本轮修正 wire seam 并增加真实本地 socket 测试，原 JSON 默认行为保留。

## 增量文本提交与安全边界

`visibleReplyFrame` 只消费现有 `TurnUi.streamingText` 和同 Turn 的普通可见 assistant 最终消息。`ModelStreamState` 原有边界只把 `TextDelta` 追加到 answer；`ReasoningDelta` 不触发 textChanged，工具 JSON/结果及日志没有接入朗读输入。

`LiveReplySpeech` 必须显式 `start`，绑定 session/turn 的独立身份；后续 `update` 永远不会自行开启或重启朗读。累计快照以已提交原文前缀作游标，完整句子或最多约 64 字符的稳定段才提交，未完成尾部继续保留；重复快照不重播。数字中的英文句点、Unicode 代理对和未完成 Markdown 链接有针对性边界，链接按可见标签朗读，不读隐藏 URL。

最终消息可替换尚未提交的尾部，仅补剩余文本；最终消息操作绑定同一 Turn 的活动朗读，完成投影后仍可停止未播完的声音，不误触发整段重播。如果已经提交的前缀被改写，停止并清空旧声音，不重播互相矛盾的回答。终态先于最终消息投影时，最多等待 5 秒，不猜测 final；超时停止。工具等待、取消/失败/中断、会话或 Turn 切换均停止，未提交尾部丢弃；工具之后不自动再开，需要用户对新出现的回答明确点击。用户手动停止后，后续 token/final 同样不能重开声音。

文本队列虽然使用无阻塞投递，但单次累计原文总量由 20,000 字符限制，不能无限积累；音频队列仅一块。`ReadAloudService` 保留 epoch、物理合成 Mutex、焦点及输出 flush；换配置创建服务实例时共享同一物理 Mutex，避免取消尚未退出就重占播放容量。会话切换/离开前台清空旧输出并关闭连接，迟到文字/PCM 不回灌。停止声音不调用 Agent cancel。HTTP 取消只关闭本地待处理请求/读取，不能证明服务端合成计算已回滚；当前没有取消 ACK 契约。

`ContinuousTextSpeechEngine.generateStream(nextText, keepGoing, consume)` 是可替换的持续文本输入端口：生产适配器可在发送文本同时接收 PCM，`nextText` 直到输入结束才返回 null。现有本地/HTTP 引擎按段降级，未来实际 CosyVoice 双流适配可直接实现此端口，不被完整文本字符串接口锁死。真实适配器必须保证跨线程中断连接、解除读写阻塞、同步有序音频回调及幂等关闭；工厂只创建可中断端口，不阻塞握手。

## 录音取消与端侧资源

录音开关通过 `remember(screen.openSessionId)` 和 `key(screen.openSessionId)` 绑定会话，旧弹窗销毁会取消旧控制器、关闭网络端口并阻止旧 partial/final 发布。新会话录音创建独立控制器；同一控制器在旧 worker 未退出前拒绝再次 start，清理期间的立即重试不排队，退出后可重试。

本轮没有实施新的端侧权重可选化：`LocalModelService` 原本就是显式按需安装，初始化仅恢复已有资源登记；Melo 权重同样由用户点击下载。该历史阶段保留随包小型 ASR 和本地推理 JNI。2026-10-23 起 ASR 默认权重也改为用户主动按需安装，推理引擎保留；不删除已有模型或用户文件。

## 主机验证与真实服务结果

首次端口测试 8 项覆盖：等待中断、partial/final 分离、final 失败不提升 partial、迟到 partial 拒绝、停止后 PCM 丢弃、音频副本/采样率变化、空/非法音频、安全配置。复核追加 3 项覆盖旧 final 与新录音隔离、跨会话旧音频隔离、上一 TTS 请求返回后回调隔离。

后续 `LiveReplySpeechTest` 覆盖增量在完成前提交、累计重复、最终尾部替换、已读前缀改写、停止黏性、会话/Turn 隔离、工具等待、取消/超限、等待最终投影、持续文本端口在输入完成前返回音频，以及链接/数字/Unicode 边界。`LiveReplyProjectionTest` 检查可见输入、排除工具/其他 Turn/进度消息及缺失 final。`HttpSpeechTest` 检查实际 multipart/WAV/JSON、错误和连接取消；另有显式环境配置才执行的真实服务用例，缺失 profile 记为 skip，提供错误输入必须失败。

主机检查命令：

```sh
./gradlew detekt spotlessCheck :provider:api:test :core:agent:test \
  :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  :app:lintConsumerDebug :app:lintDeveloperDebug --offline --max-workers=1
bash scripts/check-i18n.sh
```

可重跑的真实主机 profile（仅 Mac 本机映射，非手机默认地址）：

```sh
HELIX_SPEECH_HOST_ASR_URL=http://127.0.0.1:18767/v1/audio/transcriptions \
HELIX_SPEECH_HOST_TTS_URL=http://127.0.0.1:18768/v1/audio/speech \
./gradlew :app:testConsumerDebugUnitTest \
  --tests com.helix.app.voice.HttpSpeechTest.configuredHostServiceRoundTripUsesOnlyGeneratedSpeech \
  --offline --max-workers=1
```

真实探测只使用生成静音和固定中文句，不读取用户音频。静音探测得到“嗯”，不能证明 ASR 质量；固定生成 WAV 经 ASR 首次返回多出的“哟”，原始 24 kHz WAV 对照也出现该字；修正夹具为全局线性重采样后，最新严格检查返回“你你好这是语音测试”，仍不匹配“你好这是语音测试”。尚不能定位为 TTS 音频还是 ASR 识别引入。严格文字断言没有放宽，服务质量检查失败不能算通过；客户端不自动剥掉合法汉字。最新执行结果和剩余边界在本轮交付记录补充，原始 OpenAPI、WAV、JSON 与日志保留在忽略的 `build/cloud-*` / `build/speech-*` 路径。

2026-10-20 主机阶段设备验证为 `not requested`；2026-10-21 Owner 追加模拟器验证，当前执行记录见下文。未使用真机、未修改手机、未改防火墙、未推送或发布。主机网络联调不代表 Android 设备可达性、麦克风/AudioTrack 或实际模型双流验收。

## 最终主机结果（2026-10-20）

上述固定源码、串行完整 Gradle 命令成功，日志为 `build/speech-client-final.log`。Consumer JVM 1356 项、Developer JVM 1491 项，均零失败/错误，各 5 项 skip（4 项既有、1 项未提供显式真实服务 profile）；provider API 120 项、Agent 核心 295 项全部通过。新增语音相关四类共 33 项：CloudSpeech 11、LiveReplySpeech 12、HttpSpeech 7、LiveReplyProjection 3；每渠道 32 项本地用例通过，真实服务 1 项在离线门禁中明确 skip。它另行执行的严格失败不能被该 skip 覆盖。

两渠道 Debug APK 和 AndroidTest APK 均编译成功；该主机阶段未安装/运行，追加模拟器执行另记；两渠道 lint、detekt、spotlessCheck、国际化脚本及 `git diff --check` 通过。APK 位于 `app/build/outputs/apk/consumer/debug/`、`developer/debug/` 及对应 `androidTest/` 目录。新设置实现触发的既有 SharedPreferences KTX 门禁已通过复用 `PrefsLineStore` 修复；文本条件/服务方法数量静态门禁已通过拆分条件与移除冗余 helper 修复。并行验收曾出现 Kotlin FIR/UAST 解析异常，最终固定源码串行完整复验通过，没有屏蔽 lint 或跳过设备测试源码分析。

真实服务结果单独记录为 **failed（严格文本质量）**：请求/JSON/WAV 解码实际执行成功，生成短句的转写仍多字或重复字；有限对照后停止重试。保留的对应原始 WAV/ASR JSON、采样率与输入说明见 `build/speech-service-evidence/probes.json`（原始 WAV 为单声道 PCM16 24 kHz、91200 帧），严格失败 XML 为 `build/speech-service-evidence/strict-roundtrip.xml`。严格 16 kHz 夹具本身未留 WAV，原采样率独立对照的 WAV/JSON 已保留，二者不冒充同一个音频输入。该主机阶段没有将真实语音链路质量或设备验收记为通过。

## 仍需的最小联调信息

HTTP 与 WS 的地址、双头认证、ready、PCM、end、final/done、错误和取消均已有 Owner 提供的协议，不再索取这些已知信息。仍需服务端配合确认同样本识别差异、实际繁忙关闭码与排队配置。取消契约仅为关闭连接，不存在服务端推理已停止的 ACK；不能据此立即释放原生执行位。真机声学验收仍未授权。

## 本轮文件与关键类型

原有未提交的 `docs/development/implementation-guide.md` 始终未纳入本轮编辑。

| 路径 | 关键内容 |
| --- | --- |
| `app/src/main/kotlin/com/helix/app/voice/CloudSpeech.kt` | 配置、ASR/TTS 端口、可中断云端引擎 |
| `app/src/main/kotlin/com/helix/app/voice/StreamingAsr.kt` | 网络上传目标、partial 能力与中断接口 |
| `app/src/main/kotlin/com/helix/app/voice/VoiceCapture.kt` | 网络取消所有权、连接失败状态 |
| `app/src/main/kotlin/com/helix/app/voice/ReadAloudService.kt` | 活动文本输入、音频输出、取消/容量 |
| `app/src/main/kotlin/com/helix/app/voice/LiveReplySpeech.kt` | 可见回答帧、游标、持续文本端口 |
| `app/src/main/kotlin/com/helix/app/voice/SpeechTextStream.kt` | 持续输入和逐段引擎的选择 |
| `app/src/main/kotlin/com/helix/app/voice/HttpSpeech.kt` | 真实文件 ASR、WAV TTS、HTTP 生命周期 |
| `app/src/main/kotlin/com/helix/app/voice/SpeechWav.kt` | 有界 PCM16 RIFF/WAVE 编解码 |
| `app/src/main/kotlin/com/helix/app/voice/SpeechSettingsStore.kt` | 独立显式服务设置，不存凭据 |
| `app/src/main/kotlin/com/helix/app/HelixApplication.kt` | 服务构建、配置更新、共享播放容量 |
| `app/src/main/kotlin/com/helix/app/ui/InlineVoiceInput.kt` | 上传确认、目标、HTTP 无 partial 提示；转写在 composer 预览，完成后原处编辑，取消恢复旧稿，不自动发送 |
| `app/src/main/kotlin/com/helix/app/ui/ReadAloudAction.kt` | 完整/增量朗读的用户入口与上传确认 |
| `app/src/main/kotlin/com/helix/app/ui/LiveReplyReadAloud.kt` | 严格可见输出投影及最终消息等待 |
| `app/src/main/kotlin/com/helix/app/ui/ConversationSection.kt` | 活动回答增量观察与显式朗读入口 |
| `app/src/main/kotlin/com/helix/app/ui/ConversationMessage.kt` | 最终消息绑定活动回答的停止入口 |
| `app/src/main/kotlin/com/helix/app/ui/CloudSpeechSettings.kt` | HTTP 配置与能力说明 |
| `app/src/main/kotlin/com/helix/app/ui/SettingsScreen.kt` | 沿用现有设置结构 |
| `app/src/main/res/values/strings.xml`、`values-en/strings.xml`、`values-zh-rCN/strings.xml` | 对应三套提示文案 |
| `app/src/test/kotlin/com/helix/app/voice/CloudSpeechTest.kt` | 原 11 项端口边界测试 |
| `app/src/test/kotlin/com/helix/app/voice/LiveReplySpeechTest.kt` | 增量、身份、取消、文本提交测试 |
| `app/src/test/kotlin/com/helix/app/voice/HttpSpeechTest.kt` | HTTP/格式/取消及显式真实服务 profile |
| `app/src/test/kotlin/com/helix/app/ui/LiveReplyProjectionTest.kt` | 现有可见投影边界 |
| `provider/api/src/main/kotlin/com/helix/provider/api/wire/OkHttpWireClient.kt` | 正确设置显式请求体 Content-Type |
| `provider/api/src/test/kotlin/com/helix/provider/api/wire/OkHttpWireClientTest.kt` | multipart socket 回归 |
| `docs/adr/platform/004-offline-voice-input.md`、`docs/development/status.md`、本说明 | 同一 ADR、实施事实与证据 |

## 追加模拟器验证（2026-10-21）

Owner 明确请求模拟器测试。使用现有 API36 ARM64 AVD 的独立 `-read-only -no-snapshot -no-window -no-audio` 实例，端口 5598，由 `scripts/run-owned-emulator.py` 校验 APK 包/runner、冻结安装件、记录系统与哈希并只关闭自建进程组；保留既有 emulator-5554。键盘与 IME 由现有 helper 设置。使用注入 PCM、合成文字和合成音频，不采集环境声音或读取用户录音。最终客户端模拟器范围 `passed`；真实服务范围 `failed`（401，等待现行鉴权契约），详见本节末。

首轮在安装前因 runner 参数错误被校验拒绝，没有启动设备；纠正为 APK 实际的 `com.helix.app.HelixAndroidJUnitRunner` 后执行 12 项，8 通过、4 个已有录音 UI 测试超时。原因是“已授权”夹具没有授予新安装应用录音权限，现增加仅 sdk_gphone 模拟器的显式权限准备，保留注入 PCM 与原断言，重跑后的这 12 项全部通过。新增 `CloudSpeechDeviceTest` 两项分别检查 Android 实际 HTTP 合成/上传（传输断言，非严格质量验收）及增量重复/停止/跨会话不再播放。实际服务地址通过 instrumentation 参数明确提供 `speechAsrUrl` / `speechTtsUrl`，不作为应用默认值；Android 模拟器使用 `10.0.2.2` 宿主桥，避免混淆设备自身 127.0.0.1。

Standard 随后真实 profile 运行 14 项，13 通过、实际 TTS 请求因 `SPEECH_HTTP_401` 失败，没有进入 ASR 上传，不能声称完成 Android 云端往返。主机同路径无鉴权合成请求也返回 `401 Unauthorized` / `WWW-Authenticate: ClientID`，证据 `build/speech-emulator-host-tts-headers.txt`；服务契约已与最初“无鉴权”描述不同。已向 Owner 请求现行鉴权头与测试 ClientID 或恢复无鉴权。未猜测凭据、绕过拒绝或修改防火墙。未提供真实 profile 的通用设备套件会明确 skip 该项；提供 profile 后的 401 始终失败，原失败结果不被离线 skip 覆盖。

以下为当时的模拟器复现命令（先设置本机 `ANDROID_HOME`，两渠道分别使用对应 APK 和 runner；每次 output 必须新建）。2026-10-22 UI 改版后旧 `OfflineVoiceDialogDeviceTest` 已由 `InlineVoiceInputDeviceTest` 替代；运行当前代码应替换该测试类。旧结果仅证明当时构建，不是当前 UI 验收：

```sh
python3 scripts/run-owned-emulator.py \
  --avd Helix_Validation_API36_20261012 --port 5598 \
  --apk app/build/outputs/apk/consumer/debug/app-consumer-debug.apk \
  --test-apk app/build/outputs/apk/androidTest/consumer/debug/app-consumer-debug-androidTest.apk \
  --classes com.helix.app.voice.CloudSpeechDeviceTest,com.helix.app.voice.VoiceCaptureDeviceTest,com.helix.app.ui.ReadAloudUiDeviceTest,com.helix.app.ui.OfflineVoiceDialogDeviceTest \
  --runner com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner \
  --output build/speech-emulator-consumer-20261021-hermetic \
  --raw-results --gpu swiftshader --timeout 600
```

Advanced 使用端口 5596，替换 APK 路径中的 `consumer` / `Consumer` 为 `developer` / `Developer`、runner 包改为 `com.helix.agent.developer.test`、输出目录改为 `build/speech-emulator-developer-20261021-hermetic`。真实 profile 在同一命令追加 `--instrument-arg speechAsrUrl=http://10.0.2.2:18767/v1/audio/transcriptions --instrument-arg speechTtsUrl=http://10.0.2.2:18768/v1/audio/speech`，本轮该命令已真实执行但因 401 失败；当时未取得现行鉴权契约，不重复失败请求；后续得到 Owner 确认后的重跑见下节。测试 APK 重新编译命令为 `./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest detekt --offline --max-workers=1`，完整格式检查另跑 `./gradlew spotlessCheck --offline --max-workers=1`。

Standard 最终 hermetic 执行已完成：API36 / arm64-v8a，13 passed、1 explicit-profile assumption skip、0 failed（原始状态码 `-4` 为 profile 缺失，不能把 runner 的 `OK (14 tests)` 解读为真实服务通过）。证据目录 `build/speech-emulator-consumer-20261021-hermetic/` 包含冻结 APK、`artifacts.json`、系统属性、原始 instrumentation/logcat 与 `closed.json`；自建实例已退出。Advanced 已完成同一套验证，结果同为 13 passed、1 explicit-profile assumption skip、0 failed。新增/修复设备测试两渠道编译、detekt、全量 spotlessCheck 和 `git diff --check` 已通过；源输入本地索引在 `build/speech-emulator-source-inputs.json`，不将其哈希复制到跟踪文档。

最终结果：

| 范围 | 设备/渠道 | 结果与证据 |
| --- | --- | --- |
| 客户端语音/界面 | API36 ARM64 Standard，emulator-5598 | 13 pass、1 profile skip、0 fail；`build/speech-emulator-consumer-20261021-hermetic/` |
| 客户端语音/界面 | API36 ARM64 Advanced，emulator-5596 | 13 pass、1 profile skip、0 fail；`build/speech-emulator-developer-20261021-hermetic/` |
| 真实无鉴权 HTTP profile | API36 ARM64 Standard | 13 pass、1 fail：TTS 401，未上传 ASR；`build/speech-emulator-consumer-20261021-final/` |
| 主机同路径对照 | 本机 loopback TTS | 401 / ClientID，确定拒绝，不是超时或未知执行结果 |

13 项行为为：云端增量句提交、累计重复不重播、停止后后续 token/会话更新不重启（1 项）；捕获最大时长仅 final 一次并释放、取消后迟到识别不发布、音频读取失败释放（3 项）；缺模型只提示显式下载、停止/重播/切换单一所有权、后台/换会话停止不自动恢复、真实 Android 音频焦点丢失停止、仅正常完成 assistant 通用消息入口（5 项）；录音 partial 不改草稿/final 审阅编辑后才加入草稿且不发送、取消保留原草稿、后台不接纳迟到结果、换会话保留双方草稿（4 项）。新增活动回答的独立增量入口仍由主机投影测试覆盖，不将通用消息入口断言误写为禁止活动回答朗读。

两个独立只读实例的 `closed.json` 均确认正常退出；Advanced 的 `show_ime_with_hard_keyboard` 实际读取为 1，硬键盘沿用 helper 的 `hw.keyboard=yes`。既有模拟器未安装/改写，未操作真机。此轮没有麦克风声学质量、真实扬声器听感、其他 API/OEM、WS 连续传输或真实 Agent 模型服务的端到端验收；无声注入 PCM/界面测试不冒充这些结果。该轮结束时真实服务仍等待 ClientID 信息；Owner 随后已明确复用 Qwen 公开标识，后续执行见下节。早前严格转写质量失败仍开放。

## 公开 ClientID 适配（2026-10-21）

Owner 已确认语音与 Qwen 使用同一个 ClientID。经现有 `BuiltInModel` / `PublicClientWireClient` 核实，这是项目公开分发标识，请求头为 `X-Client-Id`，不是 API Key。语音设置增加“复用内置 Qwen 的公开 ClientID”开关，默认关闭；只有显式开启才向设置的 ASR/TTS URL 发送，仍禁止重定向与自动重试。既有三行设置兼容读取为关闭，四行新记录严格校验布尔字段；不复制聊天凭据、不生成凭据。原先“无鉴权”说明已从设置 UI 修正。

本机带该公开标识的合成文字 TTS 请求已返回 200/WAV，证据为 `build/speech-client-id-host-headers.txt` / `build/speech-client-id-host.wav`，不覆盖先前无标识的 401。模拟器真实 profile 使用 `--instrument-arg speechUseBuiltInClientId=true` 明确开启同一接线，进度/结果随后补充。新增主机测试检查默认不发送、显式发送、目标保持、非法标识拒绝和设置开关持久化。

带公开标识的当前固定源码主机回归成功：Consumer 1357、Developer 1492 项，均 0 failures/errors、各 5 skip；新增 HTTP 头边界为第 8 项 HttpSpeechTest。两渠道 Debug/AndroidTest APK 与 detekt 成功（`build/speech-client-id-build.log`，1m21s），国际化通过 2650 keys。完整 spotless/lint 记录另见 `build/speech-client-id-static.log`。

实际 Android profile：Standard 14 pass / 0 skip / 0 fail，`build/speech-emulator-consumer-client-id-20261021/` 的冻结 APK、原始 instrumentation、`cloud-speech-result.txt`、键盘设置与退出记录均已保存。合成输入“你好，这是模拟器语音接入测试。”，返回 PCM16 mono 24 kHz、73920 帧；全局插值为 16 kHz 后上传 ASR，返回“你好这是模拟器语音接入测试”。本项判定传输/解码/上传成功，没有替换或放宽此前严格质量测试。

Advanced 并发 profile 为 13 pass / 1 fail，真实 TTS 返回 HTTP409，原始证据 `build/speech-emulator-developer-client-id-20261021/`。这与 Standard 同时请求发生，可能为服务并发限制，但客户端没有错误体证据，尚不认定原因；不自动重放。其他实例退出后仅复验受影响的 CloudSpeechDeviceTest；首次在启动前因5596端口占用退出，未安装/执行，改为5584并保留启动失败日志。串行结果随后更新。

Advanced 串行复验完成：`build/speech-emulator-developer-client-id-20261021-serial/` 中 CloudSpeechDeviceTest 2 pass / 0 skip / 0 fail，保持同一 APK/测试 APK；实际返回 24 kHz、86400 帧，ASR 同为“你好这是模拟器语音接入测试”，原始结果与关闭记录已保存。结合先前同源 13 项客户端通过，Advanced 所有 14 个唯一用例均取得通过证据，但不是一次全套无失败执行；并发 409 的原失败仍保留。串行成功支持并发冲突的推测，尚不能证明其具体限制；需要服务端说明并发/排队契约，客户端继续不自动重试。Standard 一次全套 14 pass。两个渠道真实传输已验证，ClientID 不再是阻塞；WS、声学/外放和先前严格质量对照的边界保持开放。

最终固定源码静态门禁已完成：`./gradlew spotlessCheck :app:lintConsumerDebug :app:lintDeveloperDebug --offline --max-workers=1` 成功（6m53s，`build/speech-client-id-static.log`）。未关闭规则或跳过失败项。最终 `git diff --check` 通过。当前已解除 ClientID/HTTP 传输阻塞；仍开放真实服务并发409规则、WS协议、早前严格转写质量问题、麦克风/扬声器声学质量与其他设备覆盖。未推送或发布。

## 后续账号接入

Owner追加新账号系统后，生产路径已移除语音X-Client-Id开关，替换为显式账号登录模式，按官方HTTPS白名单和同一账号会话鉴权；旧开关不升级为Bearer发送许可。上文ClientID、无鉴权/401/409等均为对应历史执行，不代表当前账号路径已验收。当前账号与UI证据见[账号接入记录](account-login-integration.md)。旧instrumentation参数speechUseBuiltInClientId现在明确拒绝；新speechUseAccountLogin模式只读取已由用户登录的应用账号，本轮没有把真实测试账号持久化到模拟器。

### 移除公开ID兼容

Owner追加要求删除单独ClientID与内置Key入口、不再兼容旧格式。旧语音3/4行设置不再读取端点或标识，当前仅解析account-v1格式；用户需显式重新配置。以上公开ID联调记录为历史证据，不表示当前仍有该开关。内置模型必须登录并同时携带固定客户端标识。

## 账号双头联调补充（2026-10-21）

Owner授权当前测试账号与一次性模拟器联调（不含真机）。官方语音AccountSession现在统一发送Bearer和固定ClientID；登录/刷新本身也带客户端标识，不支持管理员Key或单独ClientID入口。已认证官方OpenAPI确认ClientID+AccountKey/Bearer定义。主机实际TTS/ASR均200，源WAV24kHz、mono、PCM16，ASR报告重采样16kHz；同一合成样本2次转写均多开头“哦”，质量仍未通过。串行2次200、并发2次200/409，客户端使用共享Mutex串行化真实合成生命周期；不把取消信号当作物理容量已释放，也不自动重试409。证据保留在本地忽略的 `build/account-live-host/`，来源仅合成非个人文本/音频。

已认证与loopback文档均仅列healthz、HTTP转写和HTTP合成；无可采纳WS契约。最小联调信息清单仍见本文“剩余服务端信息”和[账号接入记录](account-login-integration.md)，不开放无法工作的WS选项、不虚构生产事件。

账号版最终模拟器：Standard `build/account-live-consumer-20261021-r3/`、Advanced `build/account-live-developer-20261021-r3/` 均为API36 ARM64，账号主链路6项+语音/UI补充12项通过。真实账号TTS24kHz、77760/85440帧，ASR均“你好这是模拟器语音接入测试”；仅作传输结果，固定主机对照仍多“哦”，未关闭质量问题。模拟器使用注入/合成数据，未开麦克风、未操作真机。首次账号语音发现规范化默认443与账号绑定原地址不一致，发送前拒绝；已统一为规范化端点并补无端口/显式443/ASR双头回归。原失败和收集夹具修复见[账号接入记录](account-login-integration.md)。

最终列表通知修正后，两渠道最新证据分别在 `build/account-rows-consumer-20261021/` / `build/account-rows-developer-20261021/`，每渠道18方法通过且安装哈希匹配冻结候选；真实TTS均84480帧/24kHz、ASR均为同一合成测试句。此前r3目录保留其原阶段范围。所有自建实例已关闭，无真机操作；账号退出后客户端UI行和旧绑定均阻断，另一次主机对照确认旧access/refresh均401。HTTP取消只确认本地连接与播放停止，不确认远端物理任务回滚或释放，409仍按服务端容量边界处理。

## WebSocket 持续语音接入（2026-10-21）

采用 Owner 提供的《统一 AI 服务客户端接入文档》§8。ASR 固定 `wss://asr.sunlingzhang.com/ws/asr`，TTS 固定 `wss://tts.sunlingzhang.com/ws/tts`，两者由同一 AccountSession 自动添加 Bearer 与固定 ClientID。建连前检查令牌到期，只有握手 401 最多刷新重连一次；403 不循环刷新。已打开会话、音频帧和文字片段不自动重放。身份与 revision 绑定每次收发，退出或换账号丢弃迟到结果。

收到并校验 `ready` 后才发送：ASR 为 16 kHz、TTS 为 24 kHz，均 mono/s16le。ASR 每个约 100 ms PCM 帧发送一次，最后只发一次 `{"type":"end"}`，必须收到 final；text 整体覆盖，缺少 text 时才追加 delta，不删重复汉字。TTS 每个回答建立一个连接，逐次发送 `{"type":"text","text":"新增片段"}`，输入结束发送 end，同时接收二进制 PCM，必须收到 done。跨消息的奇数字节保留到下一片，终态仍有半个样本则失败。1000 关闭不能替代终态；1013 单独报告繁忙，1011 是服务异常，未知关闭均失败。error 内容不直接展示，避免泄露远端日志。

SocketWire 复用既有 OkHttp 依赖，有界入站队列与出站积压检查；收发及增量文字等待均可中断。ReadAloudService 继续共享物理合成 Mutex，取消旧任务退出前不开始新生产者。客户端不自动忙碌重试，界面提示稍后手动重试，避免取消后原生推理仍占位时形成重连风暴。服务端文档说明 HTTP FIFO 等待队列上限 8、45 秒，队满/超时 503 + Retry-After:2；WS 不排队、忙关闭 1013。仓库没有相应服务端源码，本轮不修改远端队列或网关；文档声称透传关闭码仍须实际对照，不以文档代替实测。

设置沿用现有语音服务面板：默认离线；启用云端后可明确选择 HTTP 或 WS。WS 强制账号登录及官方地址，HTTP 自配无鉴权地址保持独立。新 account-v2 格式记录模式，当前 account-v1 HTTP 配置可读取；退休的单独 ClientID/内置 Key 格式不恢复。不下载或删除用户本地模型。

真实双头握手、ready、持续ASR/TTS及主机/模拟器结果见下节。未声明真实麦克风、外放听感或真机通过。

### WS 最终验证结果

最终源码的 `GRADLE_OPTS='-Dorg.gradle.workers.max=1 -Dorg.gradle.parallel=false' scripts/check-all.sh --all` 退出0，日志 `build/ws-accepted-host.log`：283主机脚本、文档/ADR/国际化/秘密扫描、格式/detekt、Debug/Release lint、全仓JVM测试、两渠道Debug/Release/R8、release-smoke测试APK编译、42依赖锁和制品/Runtime边界通过。两渠道应用JVM1392/1527项零失败、零错误，各5既有skip；provider API117项通过。单独构建两渠道应用/AndroidTest APK及相关测试、格式/detekt的成功日志为 `build/ws-corrected-candidate.log`。最终文档修改后另跑source门禁，不把历史失败记录作通过。

当前API36 ARM64 Debug两渠道各18个实际方法，共36项通过，无skip：真实账号登录/目录/短模型回复/进程重启恢复/刷新/退出失效，加4项登录UI、增量朗读及12项取消/录音/界面边界。账号测试调用生产AccountSession，表单交互使用fake，未在截图/日志输入真实凭据。冻结与实际安装APK哈希独立一致，两实例closed均退出0，硬键盘及IME设置符合owner偏好；既有模拟器和真机未操作。最终证据分别为 `build/ws-accepted-consumer-20261021/`、`build/ws-accepted-developer-20261021/`，方法核对为对应 `build/ws-accepted-*-methods.json`；不同flavor不能合并作同一APK身份。

每渠道 `ws-speech/` 保存合成TTS WAV、Android实际发送的16kHz PCM及WAV、逐帧序号/长度/散列、整样本SHA256、WS原始事件/partial显示、HTTP原始JSON及结果。直接截取HTTP multipart真实WAV音频段与WS发送字节逐字节相等。两渠道两协议最终均为“你好这是模拟器语音接入测试”；end只发一次，结束刷新后收到final，重连使用新连接且不重放旧帧。TTS同一连接接受两个增量text并返回PCM和done，AudioTrack无声输出实际运行；真实首音频可能晚于end，不宣传固定首包延迟。fake用例额外证明输入未结束即可接收音频，不能称为原生语音大模型或自动全双工。

有限双TTS连接探测两渠道均实际返回1013，网关本次保留繁忙码；客户端停止发送/播放并关闭连接，不自动重试。未制造远端1011故障，1011区别处理由本地socket/fake覆盖。HTTP FIFO队列上限8/45秒来自服务文档，未做队深压力验收或修改服务端；取消不表示原生推理已经停止，不存在取消ACK。新样本正常不关闭旧固定样本开头多字/重复字问题，原WAV/JSON与16kHz有限对照继续保留，须服务端同样本排查。无删字或放宽严格质量断言，本轮真实服务断言为传输正确性，quality明确为not-asserted。

新增19项主机用例（均无skip）：

| 测试 | 数量 | 覆盖 |
| --- | --- | --- |
| WebSocketSpeechTest | 10 | 累计快照覆盖、delta保留真实重复、final一次刷新/缺失终态失败、ready格式与先后顺序、取消/新会话隔离、同连接并行收发、接收失败解除输入等待、奇数PCM截断/缺done、设置与账号目标、取消后迟到音频 |
| AccountSpeechSocketTest | 5 | 双头与固定目标/未登录拒绝、仅握手401刷新一次/403不重试、退出中迟到结果、切账号使已开与未开调用失效、鉴权deadline分类 |
| SocketWireTest | 3 | 实际本地socket的1013/1011、超大首消息安全关闭、握手失败类型与静默读取消 |
| HttpSpeechTest新增 | 1 | 服务deadline成为IO失败而不是用户取消；既有主动取消测试继续通过 |

复核修正：Advanced首次在既有HTTP夹具 `audio.size * 16000` Int溢出，尚未执行WS，失败保留于 `build/ws-final-developer-20261021/`；HTTP与WS测试重采样均改Long运算，随后最终两渠道18项重新通过。首轮Standard通过记录及较早候选仍是历史证据。新增socket测试首次漏启动MockWebServer，正确启动后通过；取消测试接受明确的关闭IO异常或取消异常，仍断言及时退出、零迟到音频。另修HTTP请求与WS鉴权内部deadline到SocketTimeoutException的边界，防止界面停在等待；用户主动取消不转换。所有失败/修正均未通过删除测试或降低质量标准掩盖。

核心文件：provider API `wire/SocketWire.kt` 定义有界duplex传输；app `account/AccountSpeechSocket.kt` 绑定身份与双头；`voice/WebSocketSpeech.kt` 实现PCM、ASR及ContinuousTextSpeechEngine；`SpeechSettingsStore`选择明确HTTP/WS模式，`HelixApplication`接入朗读，`SpeechTextStream`/`VoiceCapture`负责可中断等待，现有设置/朗读/录音弹窗提示模式及繁忙。`WebSocketSpeechDeviceCheck`只用合成数据，现有账号runner通过 `--with-ws` 显式启用，不新增设备框架。

复验入口：

```sh
python3 scripts/run-account-service-emulator.py \
  --profile scripts/local/profiles/account-test.json --channel consumer \
  --avd Helix_Validation_API36_20261012 --port 5576 \
  --output build/ws-consumer-recheck --with-speech --with-ws
```

Advanced对应developer/5574，串行执行。profile必须由owner提供、Git忽略且0600，不含于源码、测试夹具、日志或提交；应用只保存加密令牌。复跑需当前owner设备/账号授权，命令示例本身不提供授权。当前用户入口为先登录→设置语音服务→选择持续WebSocket并启用云端→保存；录音仍先确认上传范围，final预览后才进草稿；朗读需主动开启，停止不取消Agent。同一回答停止后后续token不重启。

## 2026-10-23：离线 ASR 默认模型按需安装

Owner 确认保留离线能力，将原随包 Zipformer 权重改为用户显式下载。Standard 与 Advanced 的生产 assets 均只包含下载锁和归属，原生 sherpa/ONNX 引擎保留。模型没有更换；固定逐文件来源与完整性见 [ASR 模型锁](../../config/asr/model.lock.json)。首次下载约 58 MiB，安装后无需云端语音服务或账号；聊天是否离线仍取决于用户选用的聊天模型。

入口为设置→语音服务→离线语音识别，以及离线模式下点击输入框麦克风。模型未就绪时先提供下载、状态、进度、取消和显式重试；完成下载不自动申请麦克风或录音，需主动开始。退后台/关闭下载面板取消传输，校验失败或中断不安装半成品。云端语音选择保持原配置，不在云端失败后自动下载、上传或切换模式；识别文字仍在原输入框编辑，不自动发送。

`LocalSpeechModel` 是原 Melo 下载实现的共享版本，ASR/TTS 使用独立锁和安装目录，原 TTS 文件位置不变。`OfflineAsrModel` 只协调一个受控下载，文件校验决定可用性；`OfflineAsrFactory` 从已校验文件打开引擎，不发起网络请求。准备链见 [ASR 构建说明](../../config/asr/README.md)，正式 APK 无权重检查纳入变体门禁；模型夹具只进入 AndroidTest。

验证证据保存在本地忽略目录 `build/asr-ondemand-2026-10-23/`。`OfflineAsrDownloadDeviceTest` 覆盖显式下载、下载后仍需主动开始、错误哈希、明确重试、取消及连接错误；`LocalSpeechModelDeviceTest` 继续覆盖共享下载器及 TTS 默认安装路径。`OfflineAsrDownloadLiveDeviceTest` 仅在独立验证包且显式传入 `asrDownloadAcceptance=true` 时访问公开锁定模型，以公开 WAV 验证下载后的本地识别，不申请麦克风。真实下载与模型夹具推理的结果必须分开记录，不能互相替代；本轮不做真机或真实麦克风体验验收。

本轮实际结果：`scripts/check-all.sh --debug` 通过，包含 289 项主机脚本测试、JVM 测试、静态/资源检查、两变体 Debug 构建及 APK 边界校验；最终 AndroidTest APK 也编译通过。API36 ARM64 模拟器的独立 Advanced 验证包中，下载 UI/共享下载器 9 项通过，语音与 TTS 回归 25 项通过。实际公开模型下载第一次在传输中以 `SocketException: Software caused connection abort` 失败，暂存清理；仅追加一次明确重试后，完整下载、逐文件校验、本地重新打开及公开音频识别通过（1 项）。这不证明源站/网络稳定性，也不外推识别准确率。首次取消测试的失败证据保留，修复取消与连接错误交错的处理后复验通过。模型夹具识别不用于替代真实下载结果。

日常 Standard/Advanced 使用成功 Debug 候选覆盖更新，保留数据；验证包的下载不复制到日常包。无真机/真实麦克风测试，无云端部署变更。未执行 Release/R8，不将 Debug 通过称为发行验收。本轮代码保留在可审查工作区，未自动提交或推送。

## 2026-10-26：账号默认路由、ModelScope 与提示精简

### 提示审查范围与交付核对

42 是主 App 修改的既有字符串数量，不是页面数或逐页设备验收数量；另删除 4 项重复说明。补充静态盘点了主 App 23 份字符串资源（2476 项，其中按提示/错误等名称筛出 383 项候选），并核对 Advanced 覆盖资源、浏览器、CLI/PRoot 伴随应用及 Mobile Use 的提示入口。名称筛选与长文本筛选仅用于定位，不证明每项动态状态已现场触发。主 App 常规语音、资源、账号/Provider、知识库、文件、上下文、权限、设置/连接器提示已分类复核；审批、删除、明文传输、订阅资格/风险、系统无障碍声明和开源许可等必要告知保留，不能按字数一刀切。

最终补查修复两个遗漏：Git 原始异常从结果展示状态中移除，只呈现本地化失败提示，16 项真实临时仓库 JVM 测试通过；Advanced 两项 All-files 提示去掉 ADR/Policy/scope 术语，并纠正“Standard 只支持私有文件”这一过时说法，明确系统目录选择器仍可用。中英文同步，未改变权限范围；Advanced 资源与代码编译通过。这两项发生在完整 Debug 门禁之后，分别有针对性验证，不宣称其已完成设备视觉回归。

明确未覆盖：所有动态错误的逐页设备注入、系统/OEM 自带权限文案、第三方网页与 OAuth 授权页、模型生成内容，以及仅供开发者使用的工具结果/审计/日志/许可全文。没有逐个触发真实外部分享、订阅登录或云端故障，不能将静态审查和本地化检查描述为这些服务已通过。Model-facing 失败信息继续保留可查询身份和必要事实，不因普通用户提示精简而删掉 Agent 所需结果。

未保存手动语音配置时，已登录账号使用官方 WebSocket ASR/TTS；打开输入前先恢复本地账号状态，再决定路由。显式离线或自配 HTTP 配置不会被登录覆盖。损坏配置、账号不可用和账号失效分别报错，不静默改为下载或上传。打开后的账号身份固定，切换账号不复用旧识别或朗读实例。

首次在线使用保留必要发送告知，确认按识别/朗读、准确服务地址和账号身份保存；再次使用同一授权不反复询问。录音仍需系统麦克风权限，识别结果直接进入原输入框，不自动发送；停止朗读仍不取消 Agent。下载面板只展示必要体积、进度、状态和动作，技术恢复计数不再展示。

TTS 下载改用 [ModelScope 镜像](https://modelscope.cn/models/AdamLee/vits-melo-tts-zh_en_copy)，全部七个文件实际下载后的大小和 SHA-256 与原可信模型锁一致，模型、许可及离线推理未更换。重定向只允许明确的 ModelScope 域，并校验固定字节。最初未找到旧 ASR 的字节一致镜像；Owner 随后授权更换模型，现已改为 [pkufool small streaming](https://modelscope.cn/models/pkufool/zipformer-small-streaming) 的 FP16 transducer（chunk16/left64），只下载四个必要文件，约 79.4 MiB。固定版本、许可及完整性记录在模型锁；无需作者 Python SDK，不新增模型选择系统，不删除或读取旧下载作为兼容路径。

[选型记录](../evidence/development/modelscope-asr-selection-2026-10-26.md)保留五个候选的同样本结果和限制。独立 API36 ARM64 模拟器完成新旧模型各 31 条与各 3 条实时节奏输入；新模型在此中文/混合样本上错误较少，但加载和内存代价更高，并非全面更快或英语更好。Advanced 最终候选另外实际从 ModelScope 下载并通过默认工厂识别公开音频（1 项通过，`build/asr-modelscope-2026-10-26/live-download-advanced-1/`）；不是麦克风或手机验收。APK 不包含这些模型权重，运行引擎保留。

全 App 提示按账号/Provider、语音/资源、知识库、文件授权、能力启用、连接器、存储删除与上下文反馈分类复核，精简中英文重复说明及内部流程；保留权限范围、明文传输、费用和不可逆删除等必要后果。未改变 Dispatcher、审批、计费或数据清理权限。模型输入提示、协议错误以及开发诊断不因普通 UI 文案审查而机械改写。

本轮主机与独立模拟器结果以 `build/voice-routing-2026-10-26/` 和 `build/document-first-2026-10-26/` 对应成功候选为准。未做真实麦克风、账号网络调用、云端服务恢复或真机体验验证；文档优先存储重构仍在实施，不能由本次语音检查推断其整体完成。


本轮提示复核示例（中英文同步，42 项既有提示调整，4 项重复/无效提示删除）：

| 位置 | 原先问题 | 当前行为 |
| --- | --- | --- |
| 语音下载 | 来源站点、内部流程和隐私说明重复出现 | 必要模型体积、状态、下载/取消动作 |
| 在线识别/朗读 | 每次询问发送；身份变化缺少统一绑定 | 首次按目标/账号确认；保存失败不连接；身份变化停止旧实例 |
| 语音失败 | 统一要求检查用户配置，夹杂原生收尾说明 | 暂时无法连接与服务繁忙分别简短提示，不自动重传 |
| 上下文用量 | 百分比、窗口和实现过程大段解释 | 最近已确认输入、估算边界和压缩消耗；不暴露缓存术语 |
| 文件预览/分享 | 原始异常可能展示私有路径或厂商信息 | 文件不可用、访问受限和操作失败分别提示；开发日志只留异常类型 |
| 设置/能力启用 | 反复解释模式切换不授予各种权限 | 保留一次简短范围说明，具体授权仍由原系统处理 |
| 删除/明文传输/定时执行 | 属于必要后果或执行范围 | 保留真实影响与取消/恢复边界，不为了短而取消告知 |

后续路由回归从首批 8 项扩为 12 项并通过：账号失效/不可用保留在线错误，不能变成离线下载；显式离线即使保留云端账号字段也独立可用。首次授权保存失败或不可读时不打开传输。当前 ASR/TTS 下载均使用 ModelScope 固定文件，不向用户显示站点与内部恢复计数。

最终生产源码完整 `scripts/check-all.sh --debug` 已通过（第五轮，退出 0）；两渠道 APK 均通过无权重、实际模型描述和引擎归属检查。Standard 独立大字体批次实际执行 28 项通过，另有一个测试类包名填错导致未执行，不能计为通过。补跑暴露六项下载夹具仍用旧 HF 地址、被新可信域校验拒绝；仅将合成夹具改为 ModelScope，保留校验与取消断言。最终测试包编译/Spotless 通过，补跑六项下载与一项保存恢复共 7 项全部通过（1.5 字体、640 dpi，`build/asr-modelscope-2026-10-26/final-consumer-device-4/`）。实际查看了稳定窗口截图，恢复弹窗与阅读正文/操作可读、未遮挡；不将纯页面夹具推断为整 App 系统栏验收。失败记录未删除，测试未降低要求。

Advanced 实际下载/识别、Standard 假传输边界、实验模型比较分别属于不同验收。尚未验证真机内存/热量/实际麦克风、云端账号服务与其他 App 外部编辑；未更新日常包、未上传音频、未推送或发布。APK 和原始结果保存在各自成功构建对应的本地证据目录。

语音新路由/授权 JVM 8 项通过；文件错误展示 JVM 3 项、输入框文案契约 3 项通过。独立 API36 最终恢复/语音批次 11 项通过：首次授权前不开连接、重复使用不再询问、授权保存失败无连接/不改输入，以及真实进程退出后的计划身份恢复。此前 43 项文档/语音和 42 项计划/文档批次分别通过；测试条件及候选不同，不合并成唯一测试数量。后续错误文案更新由定点主机测试验证，没有实际向其他应用分享文件。首轮完整 Debug 的 KTX Lint 错误和随后输入框文案契约失败均保留，已修复；最新完整门禁结果仍以本地对应最终日志为准。日常 APK 未覆盖，未进行真实录音、线上账号/语音调用或真机验收。
