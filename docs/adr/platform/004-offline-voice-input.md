# ADR-PLATFORM-004: 本地语音输入与回复朗读

Status: accepted
Date: 2026-10-19
HXA: Owner-authorized ASR (2026-10-19), HXA-310, HXA-311
Deciders: Project owner（ASR、模型评估及知识库/提醒后继续 TTS 实施授权）

## Context

原语音按钮依赖系统识别 Activity，国产机可能没有可用服务，也不能保证离线或实时预览。Owner 授权设计实施 ASR；追加移动端模型评估与后续 TTS 建议。实施与验收见[本轮证据](../../evidence/development/offline-voice-input-2026-10-19.md)。

## Decision

Standard 和 Advanced 均提供完整离线语音输入，不依赖 OEM/Google 的 SpeechRecognizer 或识别 Activity。用户按语音按钮后才申请麦克风权限、加载模型并录音；后台、会话切换、取消或音频焦点丢失停止录音。每次最多 120 秒，单个录音/推理循环，不建立无界音频队列、不保存录音。

实时转写直接在当前输入框预览，结束后留在同一输入框编辑，用户仍需主动发送，不另弹确认编辑窗口。失败、取消、丢失前台或迟到推理结果恢复录音前草稿；没有后台常驻监听、自动发送或自动全双工轮次。进程重建不恢复录音，已接受草稿沿用现有持久化机制。

离线 ASR 采用 sherpa-onnx 1.13.8 + CPU 双线程，当前选用 `pkufool/zipformer-small-streaming` 的 chunk16/left64 FP16 transducer（FP16 encoder/joiner、FP32 decoder），所需资源约 80 MiB，由用户按需下载，两渠道保留推理引擎而不预装权重。固定来源、逐文件大小与 SHA-256 见 `config/asr/model.lock.json`；ASR/TTS 模型下载均来自 ModelScope。首次只显示必要体积和操作，下载完成仍需主动开始输入才申请麦克风；下载不录音。复用有界传输、暂存、校验和安装机制，取消、退后台或失败不发布半成品，不自动改用云端。不可恢复错误、校验失败及主动取消不自动重试；固定不可变文件 GET 的合法有限技术恢复仍保留。选型样本与内存/加载代价见[对照记录](../../evidence/development/modelscope-asr-selection-2026-10-26.md)，不宣称它是全面速度升级或所有英语场景更优。

sherpa-ncnn 和 sherpa-onnx 均有官方 Android CPU/真流式路径。ncnn 的依赖较少；onnx 提供更广的 ASR 模型及后续 TTS 候选，并有同一套受控 Kotlin/JNI 接口。为后续模型比较选 onnx，代价是较大的通用推理库。没有复制 Operit 代码。采用的 sherpa/模型 Apache-2.0、ORT MIT、KissFFT BSD、未修改 Eigen MPL-2.0 等归属、许可与固定源代码入口随 APK 放入 `asr-licenses/`；Eigen 启用 `EIGEN_MPL2_ONLY`。不把框架许可推导为所有模型均可商用。

2025-04-01 中文 CTC INT8 候选约 25 MiB，在同音频移动 CPU 实测中更省空间、部分中文口语词更完整，但中英混说明显退化并出现未知词，未证明总体更优，不替换双语默认模型。更大双语模型约 190 MiB 及整段识别模型不作为本轮默认：没有足够移动端质量收益证据支持扩大包体/运行内存，也不通过反复重跑整段音频冒充原生流式。少量公开夹具只用于有界回归，不外推中文准确率、噪声、口音或所有 OEM。

`StreamingAsrFactory` / `StreamingAsr` 是 PCM→部分/最终文本的窄接口。录音、焦点、生命周期及草稿接纳不依赖某个聊天 provider。云端 ASR 必须显式选择、按实际服务契约处理鉴权并清楚告知上传范围；离线模型不可用明确报错，绝不静默上传。当前聊天 provider 不是 ASR provider，云端及增量接入采用下述独立边界。

### 本机回复朗读

Owner 在知识库/提醒之后明确启动 TTS，优先几十 MiB、中文可用和移动维护成本。选用 [MeloTTS Chinese](https://huggingface.co/myshell-ai/MeloTTS-Chinese) 的固定 [sherpa INT8 导出](https://huggingface.co/csukuangfj/vits-melo-tts-zh_en/tree/a0d5c6a264c0ef92d70d8661d8cc502d79627cd6)：单音色、中英词表、MIT 模型/转换资源。按当前 1.13.8 源码，`dictDir` 已不用，不下载旧 jieba 字典或 FP32 权重。完整必需资源约 57.8 MiB，精确文件与完整性归 `config/tts/model.lock.json`；此体积不代表运行 RAM 或自然度评分。

`scripts/build-tts-assets.py` 从同一已锁定 sherpa 源码、ORT 与 ASR 依赖生成 `build/tts/speech.aar`，保留 ASR 和 VITS/Melo 前端，移除其他 TTS 实现、Piper 音素前端及 eSpeak 下载/链接；未知词/转换失败日志不输出正文。普通 APK 包含专用 JNI、资源元数据与 `tts-licenses/`，不含大模型。`verifyTtsAssets` 校验构建输入及产物；现有 ASR 的各项 Apache/MIT/BSD/Eigen MPL 归属继续保留，新增 Melo MIT 与构建源/变更说明，不引入 GPL 音素依赖。框架许可不替代模型/词表/规则许可。

用户第一次点回复朗读时，若无资源，只显示必要模型体积和下载操作，用户主动下载。锁定 HTTPS URL、文件白名单、逐文件预算/哈希、暂存后安装；失败/取消可重试，不自动下载/恢复或调用云端。下载完成不自动出声，用户需再次点朗读；正文从不进入资源请求。进程中断留下的下载不视为可用模型，加载前重新校验资源。

普通完整 assistant 回复仍可点击冻结当次正文朗读；当前亦允许用户主动开启正在生成的可见 assistant 回答增量朗读，按下述云端/增量边界提交稳定文本，不朗读 reasoning、工具结果、私密会话列表或提醒。上限 20000 字符；首段优先约 16 UTF-16 单元的短子句，后续最多 64 单元，不拆代理对或常规英文/数字组（超长单 token 仍以 64 为界）。合成与输出使用容量 1 的 PCM 队列重叠，回调送满后背压，不累积整篇音频；互斥覆盖两个协程真实退出及清理。按可见链接标签朗读，不抓 URL；原消息不修改。一个物理合成/输出资源，由互斥边界持有至原生调用退出、音轨释放，取消 Job 不提前让位。焦点失败或合成/输出失败明确结束为错误，不冒充完成。

采用停止/重新朗读，不做伪暂停/定位。切换内容先停止旧音轨，单次只播一条；会话切换、离开聊天、退后台、启动语音输入、音频焦点丢失/duck 或耳机断开即停止，不自动恢复。无常驻媒体服务或全双工监听；通知权限不授权音频，提醒不自动播报。合成内容不持久缓存；仅合成验收可输出文件。真机外放只由 Owner 主动试听。

交互参考 [豆包官方功能](https://www.doubao.com/legal/feature_intro) 的自然输出与可打断（官方描述不证明离线或具体按钮布局）、[ChatGPT 回复操作](https://help.openai.com/en/articles/8142208-chatgpt-android-app-faq)、[Edge 朗读](https://explore.microsoft.com/zh-cn/edge/features/read-aloud)；采用消息明确入口与简单停止，不复制实时通话/多音色复杂面板。生命周期/焦点采用 [Android Audio focus](https://developer.android.com/media/optimize/audio-focus)。

### 云端与增量回答边界（2026-10-20）

Owner 授权云端 ASR/TTS 轻客户端，并提供实际 HTTP 服务与 Mac 本机映射。现有 Agent/LLM、工具权限和离线能力保持。`CloudAsrConnection` / `CloudTtsConnection` 为替换端口；实际 `http-file-wav-v1` 按提供的 OpenAPI/样例实现 multipart `file` 转写和 JSON `input`→单声道 PCM16 WAV。HTTP ASR 在用户结束录音后整段上传，只提供 final，不伪造 partial；HTTP TTS 为稳定文本分段请求，不宣称实时 WebSocket 或模型内部双流。服务模型名称不能从此前推荐推断。

在现有设置中显式配置独立语音地址，默认关闭 HTTP，保存后才选择该服务；每次录音/朗读另确认目标与上传范围。HTTP 明文与无鉴权契约明确提示，地址遵循既有 NormalizedEndpoint/Native DNS；禁重定向、自动重试/重放、静默回退及聊天 provider 凭据复用。Mac loopback 不作为手机可达性证据，不自动创建转发、防火墙或公网监听。

用户主动开启某个活动可见回答后，增量游标只消费其累计可见文本，提交完整句或有界稳定段，保留尾部，重复快照不重播；最终消息仅补未提交尾部。已提交前缀改写、工具等待、取消或跨会话/Turn 时停止并丢弃旧尾部，不自动恢复；用户停止后后续 token 不重启。最终投影最多等待 5 秒，不猜 final。`ContinuousTextSpeechEngine` 保留持续文本输入端口，使未来实际双流适配能同时发送追加文本和接收音频，当前 HTTP/本地方案按段适配。

停止声音清空输出、增加 epoch、关闭连接，不取消 Agent；物理合成 Mutex 覆盖旧执行器真实退出，配置替换实例也共享该容量。文本与音频有界、迟到文字/PCM 不回灌，具体超时/消息/响应格式按服务契约校验。端侧聊天模型和 Melo 权重原本已按需安装，本轮不删除用户文件、保留小型 ASR 推理引擎，权重按需下载。实际测试与未通过的服务质量检查见[接入说明](../../development/cloud-speech-integration.md)，不能把 host 请求成功或 mock 当作设备/模型质量验收。

## Alternatives considered

### TTS 候选取舍

[Kokoro-82M-v1.1-zh](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh) / [sherpa INT8](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html) 是先前建议，并非既定最优。完整方案包括权重、音色、词表/文本前端；官方 sherpa 路线使用 eSpeak 数据，默认构建还链接 Piper/eSpeak，不能只按 Apache 权重采用整套组件。Melo 的单音色与词表限制换取约 57.8 MiB 完整包、无该依赖链及更窄的维护范围；不宣称自然度胜过 Kokoro 或豆包。中文多音字、数字、混合词及延迟/内存由合成测试记录，实际听感待 Owner 主动试听。

[Matcha Baker 中文](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/matcha.html) 官方说明训练数据仅限非商用，且声学模型约 72 MB 外加约 51 MB vocoder，不作为商店默认。其他 VITS 中文社区权重虽可较小，但上游有[原始权重许可待明确](https://github.com/k2-fsa/sherpa-onnx/issues/4023)的问题，不推导框架许可为再分发许可。系统 TTS 不增加自有权重，但依赖 OEM 已安装中文/离线音色和独立引擎信任，不能保证普遍可用或悄悄使用网络音色，因此不作为本次默认。更大生成式/克隆模型未证明满足本次手机资源目标，不预装。

### 2026-10-19 官方方案复核

本次重新检查官方发布页，当前最新稳定 sherpa-onnx 为 [v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)，与已安装实现一致。框架继续保留；58 MiB 双语模型是已有设备证据支持的基础方案，不称为最新或全面最优模型。ncnn 有成熟 Android 离线流式路径，但没有本项目同设备对比证据支持更换；[MNN](https://github.com/alibaba/MNN) 有移动端语音应用，框架支持不能直接证明相同模型更快，新增转换/算子/JNI 维护成本目前缺少收益证据。

官方资料筛选时确定的比较范围如下；这份资料清单不等同于产品接入，随后实际执行的十模型范围与结果以下节及测量证据为准：

| 候选 | 官方能力与体积口径 | 对 Helix 的判断 |
| --- | --- | --- |
| [中文 streaming Zipformer INT8 2025-06-30](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html) | 原生流式，官方文件列表 encoder 154M + decoder 4.9M + joiner 1M；不是运行 RAM | 第一优先比较的中文增强模型；须验证中英混说，不能预设中文模型适合作为双语默认 |
| [Streaming Paraformer 中英](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-paraformer/paraformer-models.html) | 原生流式，INT8 encoder 158M + decoder 68M；官方注明无时间戳 | 第二优先；同框架可比较，但模型显著大于当前基础包 |
| [SenseVoice INT8](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html) | 有 Android/Kotlin 路线及 2024-07-17、2025-09-09 导出；VAD 分段识别 | 评估停顿后识别或结束后的本地复核；不能把 VAD 分段结果冒充当前逐帧流式预览 |
| [Qwen3-ASR-0.6B](https://github.com/QwenLM/Qwen3-ASR) | 2026 新系列，官方支持流式/离线、30 语言及 22 中文方言；常规部署为 Transformers/vLLM | 值得增强质量实验。sherpa 最新源码已有 offline Qwen3 适配，不能因此宣称 Android 已有相同流式能力；没有本机 RAM/发热/延迟证据前不预装 |
| [Fun-ASR-Nano GGUF](https://github.com/QwenAudio/Fun-ASR/tree/main/runtime/llama.cpp) | 官方已有 C++ CPU/edge 路线，说明包含 phone，量化整套约 1.3 GB；VAD 分段 | 修正“只能服务器”的过强判断；仍不是当前低包体流式输入的直接替代，尚缺 Android 集成与 OnePlus 实测 |
| [whisper.cpp stream](https://github.com/ggml-org/whisper.cpp/tree/master/examples/stream) | 官方示例按窗口重复识别，另有 VAD 模式 | 可作为离线质量参考；不优先替换支持持续增量解码的基线 |

按此方向完成了下节的有界对照。保留用户按需安装的小双语模型；只有质量收益与手机预算证实后，再决定是否增加用户主动下载的增强模型。ASR 增强模型下载尚未实现；独立 TTS 按需资源以上述决定为准。新一轮应使用同一批有许可的普通话、中英技术词、数字、口音、噪声及静音样本，分别记录中文 CER、英文 WER、关键术语、静音误识别、首次可见文字墙钟延迟、停止到最终文字延迟、冷启动、进程峰值 PSS、连续使用发热和失败率。流式录音按真实时间节奏喂入；吞吐测试单列。模型权重、分词器、前端与测试音频分别核对许可，框架 Apache 不能代替模型授权。

[Qwen3-TTS](https://github.com/QwenLM/Qwen3-TTS) 的 0.6B 路线仅作质量参考；更大参数和官方流式能力不能推导出老款手机可实时运行，不在当前几十 MiB 资源目标中采用。TTS 当前选择以上 Melo，而非将旧建议固化为默认。

### 十模型有界实测后的取舍

Owner 进一步要求全部候选的准确率、速率与真机比较；十个具体模型已在同一 31 段公开/合成 PCM 上完成主机、日常 ARM64 模拟器和 OnePlus 6T 的吞吐测量，结果与实现约束见[对照证据](../../evidence/development/mobile-asr-comparison-2026-10-19.md)。Owner 的几十 MiB 体积偏好优先；权重体积、进程内存、新建加载和流式处理速度分别衡量，不用模型年份或参数量代替移动端效果。

约 30.79 MiB 的 Moonshine 中文 Tiny Streaming 是值得继续验收的轻量候选：真机该批中文 CER 15.59%，当前双语基线 16.64%；新建加载中位数约 0.22 秒和 3.13 秒。它的中文计算 RTF 约 0.200，基线约 0.110，进程采样 PSS 也没有比基线更低；独立英文片段 WER 达 72.73%。这些事实支持中文启动体验评估，不支持据小样本自动替换中英默认。其 SDK 是另一套部署边界，采用前仍须自然中英技术词/真实口述验收及仅 ASR 的依赖/许可/生命周期集成。

约 228.45 MiB 的 SenseVoice 中文 CER 11.39%，但本轮裸模型两段静音均出非空文本；需要静音/VAD 策略，并属于分段/结束后识别方向。约 159.61 MiB 中文 Zipformer 真机新建加载中位数约 12.34 秒。Streaming Paraformer 的体积与处理成本也未显示足够优势支持直接换默认。纯中文小 CTC、14M、Vosk、WeNet、Whisper 的取舍依据完整矩阵，不笼统否定其其他语言或部署场景。

当前保留已交付的双语 Zipformer 默认与窄 provider 接口，ASR 产品中不加入上述实验 SDK、额外识别模型、模型选择或 VAD 功能；默认双语模型的按需下载不属于增强模型选择。新测量的证据边界与真实麦克风验收独立，不能把直接 PCM 注入叫现实环境录音识别。

## Consequences

原生库增加安装包体积，按需模型占用安装后存储、冷加载时间和运行内存，换取离线可用、明确的数据边界与不依赖 OEM。每次录音退出释放原生对象，避免常驻模型；首次加载以 UI 明示等待。独立验证包用于保护设备原有安装，不扩展应用正式包名或版本。

## Verification

主机检查及设备证据按[本轮记录](../../evidence/development/offline-voice-input-2026-10-19.md)更新。模型注入测试与真实麦克风识别体验分开；不把公开夹具吞吐或测试进程 PSS 当作普遍准确率、实时首字延迟或模型独立内存。

## Reconsider when

有同设备、同音频的质量/速度/内存证据证明更好的移动模型，或 Owner 明确扩展云端语音/朗读能力时，更新本 ADR 的同主题边界并追加历史；不因新增候选而自动增加下载服务、监听或通话。

## References

- [sherpa-onnx Android](https://k2-fsa.github.io/sherpa/onnx/android/index.html)、[sherpa-ncnn](https://k2-fsa.github.io/sherpa/ncnn/)。
- [双语 Zipformer 模型与精度/体积说明](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)、[上游训练模型许可](https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t)。
- [中文 CTC 官方说明](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-ctc/zipformer-ctc-models.html)、[训练模型 Apache-2.0](https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01)。

## Decision history

- 2026-10-19：Owner 授权本地中文优先 ASR；依据官方 Android 路线和两台 ARM64 设备公开音频结果接受上述范围。后续 TTS 保留独立评估边界，不扩大本轮实现。

- 2026-10-19：按 Owner 追加要求复装独立验证版并复核最新官方候选；保持现有实现，明确基础模型并非全面择优结论，补充增强模型比较顺序及移动端验收条件。

- 2026-10-19：按 Owner 追加模型测评授权，记录十个候选同语料的移动端质量与资源取舍；保持既有默认，将 Moonshine 的中文启动体验与 SenseVoice 的结束后质量作为后续有条件候选，不自动扩大产品实现。

- 2026-10-19：Owner 明确要求使用当前方案正式接入项目；确认现有 sherpa-onnx + 小型双语 Zipformer 为 Standard/Advanced 默认随包能力，沿用正式 main 源集和普通构建，不将独立验证包或十模型实验作为产品入口。保留真实口述待验收边界，不新增 TTS 或云端能力。

- 2026-10-19：Owner 按知识库→提醒→TTS 顺序授权回复朗读实施；复核完整资源/许可后选 Melo INT8 单音色按需安装，专用 ASR+Melo JNI 排除 eSpeak/Piper。回复主动朗读、停止/重播、前台/焦点边界与真机主动试听独立验收，不扩为实时对话。

- 2026-10-19：Owner 授权补三阶段验收并诊断首段等待；在不常驻额外模型或改变许可/权重的前提下，缩短首段并加入有界合成/输出流水线。真机口述/外放由 Owner 稍后主动手测，不用模拟器替代结论。

- 2026-10-20：Owner 授权开始云端流式 ASR/TTS，先交付无真实服务依赖的适配骨架和主机验证；上传确认、取消/迟到数据边界沿用现有 UI/播放路径，真实协议与端到端适配待服务信息，本轮未授权设备验证。

- 2026-10-20：Owner 追加完成主动增量可见回答接线，并提供已部署 HTTP 文件/WAV 服务与 Mac loopback 映射；采用显式 HTTP 配置、按段 TTS 与持续文本替换端口，保留无 WS 契约和严格合成→识别文字比对失败的边界，不扩为原生全双工或自动轮次管理。

- 2026-10-21：Owner 追加模拟器验证并确认语音服务与 Qwen 共用公开 ClientID；采用显式复用开关，向配置的 ASR/TTS URL 添加 X-Client-Id，默认关闭且不改聊天凭据，仍禁用重定向/重试。旧三行设置读取时保持关闭，新增四行记录严格校验；真实 401 历史与最新联调结果分开记录。

- 2026-10-21：Owner追加账号登录访问模型；官方语音的显式可选模式改用同一账号会话，Bearer只允许确认的官方HTTPS目标。旧公开ClientID三/四行设置保留地址但不自动授权发送账号令牌，新的account-v1记录区分该许可。内网自配无鉴权HTTP仍独立，WS事件协议未因此获得定义。此前公开标识与模拟器证据按历史阶段保留。

### Decision history — 2026-10-21：账号语音双头核验

Owner授权测试账号在一次性模拟器及官方语音服务验证。已认证ASR/TTS文档确认固定ClientID与Bearer，官方账号语音统一经AccountSession发送两者；应用不接受管理员Key或单独ClientID模式。HTTP仍为整段上传与完整WAV响应。真实主机串行成功、并发409及固定样本开头多字分别记录传输、容量与质量边界；缺失WS协议不能由HTTP成功推断。

### Decision history — 2026-10-21：持续 WebSocket 语音

Owner提供统一客户端WS契约并授权非真机收尾。沿用既有可见回答持续文本端口，ASR单次听写一个连接、TTS同一回答一个收发并行连接；双头账号鉴权、ready校验、原始PCM、end与final/done强终态、关闭即本地取消。WS模式仅官方WSS账号目标，HTTP保留独立模式；不增加语音大模型、自动全双工轮次或工具权限。全局单模型执行位与服务端队列由服务端负责，客户端有界串行且不自动重放；1013与1011分别处理。验收见同一云端接入说明，不以契约采用代表已验证实现。

- 2026-10-23：Owner 确认保留无需云服务的离线 ASR，将默认双语模型改为显式按需下载。两个渠道只打包引擎、锁元数据及归属；公开测试权重仅进入 AndroidTest。沿用 TTS 下载实现，取消/失败不安装，成功不自动录音，不删除用户已有资源。

- 2026-10-23：Owner 授权日常体验收尾，ASR/TTS 资源状态、占用及确认删除统一呈现；删除限定锁内文件，ASR 从加载到关闭持有资源使用身份，安装和删除等待其释放；TTS 等待既有物理所有者退出。关闭页面可取消尚未执行的等待，不把取消当失败或承诺已完成删除回滚。公开固定文件下载仅保留可观察、有界且可取消的技术恢复，不自动重放语音请求或 Agent 动作。

- 2026-10-26：Owner 要求登录后默认线上及全 App 提示精简。无显式配置时登录账号走官方 WS；显式离线保留，不用云端失败触发下载。首次发送授权按目标与账号保存，不逐次弹出；录音系统权限与 Agent 授权边界不变。TTS ModelScope 镜像经固定字节校验替换来源，小型 ASR 镜像仍待确认，见[接入记录](../../development/cloud-speech-integration.md)。

- 2026-10-26：Owner 进一步授权在 ModelScope 选择替换模型。经固定公开样本、既有 sherpa 1.13.8 host/Android 和实时节奏对照，采用 small FP16 transducer；拒绝仅因体积小采用错误率更高的 CTC。承认额外加载/内存代价，不改线上默认或增加模型选择框架。移除旧默认权重归档及过时 App 内比较路径，依赖和产品版本不升级；最终工厂/下载与完整门禁独立验收。
