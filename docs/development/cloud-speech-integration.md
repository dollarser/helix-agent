# 云端语音接入与增量回答朗读

本轮保留现有 Agent/LLM、工具权限、离线语音及用户模型文件，增加显式 HTTP 语音服务配置、实际文件/WAV 适配和用户主动开启的增量可见回答朗读。当前协议不提供实时 ASR partial，也不代表模型内部双流；WebSocket 端口保留可替换接口，尚未冒充已对齐。

## 当前可操作入口

- 设置→语音服务：默认关闭 HTTP，保留现有离线模式。用户填写 ASR/TTS 完整 HTTP/HTTPS 地址并显式保存后生效；不推断聊天 provider 的地址或 Key，不内置服务地址或凭据。设置明确显示 HTTP 明文、分段能力及可选使用 Helix 账号登录（仅官方 HTTPS 目标）。
- 麦克风入口：离线模式保持原行为；HTTP 模式先确认目标与上传范围，录音结束后仅上传该次完整 WAV，最终文字预览后由用户加入草稿。没有用假 partial 冒充流式转写。
- 正在生成的普通可见回答：用户点击朗读后接收该回答后续文本增量；停止仅停止声音，不取消 Agent。普通完整回答仍可点击朗读/停止。
- HTTP TTS 按提交的文本段请求合成，每段完整 WAV 返回后分块播放。当前实现没有连续 WebSocket 选项，也没有声称短句串接就是模型内部双流。
- `127.0.0.1` 在 Android 上指设备自身。Owner 的 Mac loopback 映射仅用于本轮主机联调，不会自动让手机可访问；客户端不创建 ADB 转发、防火墙规则或持久端口开放。

## 已核实的服务契约

Owner 提供并映射到本机的两个服务，OpenAPI 与 `/docs` 已读取。文档入口仅链接 Swagger/OpenAPI，没有 WebSocket 路径、事件示例或另行协议链接；这不证明服务不存在 WebSocket，只表示客户端尚无可采用的 WS 契约。

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

本轮没有实施新的端侧权重可选化：`LocalModelService` 原本就是显式按需安装，初始化仅恢复已有资源登记；Melo 权重同样由用户点击下载。保留随包小型 ASR 和本地推理 JNI，不删除已有模型或用户文件。

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

HTTP 请求/响应结构已有实际证据，不再以完全缺少契约作为阻塞。剩余需要：

1. 模拟器已使用 10.0.2.2 宿主桥验证；未来真机仍需其实际可达地址或 Owner 明确安排的映射，Mac loopback 不能直接替代。
2. WebSocket 完整路径、连接启动/音频帧编码与采样率、partial/final/error/结束事件、转写快照或增量语义、超时/限流规则。
3. TTS WebSocket 输入追加、flush/结束标记、PCM/容器格式、请求 ID、取消 ACK、迟到分片归属、并发/连接复用规则。
4. 固定合成→识别多字/重复字的服务端排查结果；HTTP现已按Owner新要求改为账号Bearer（历史公开ID证据保留），若WS鉴权不同需另行说明，不把真实 Key 写进仓库。

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
| `app/src/main/kotlin/com/helix/app/ui/OfflineVoiceDialog.kt` | 上传确认、目标、HTTP 无 partial 提示 |
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

复现模拟器命令（先设置本机 `ANDROID_HOME`，两渠道分别使用对应 APK 和 runner；每次 output 必须新建）：

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
