# ADR-PLATFORM-004: 本地语音草稿输入

Status: accepted
Date: 2026-10-19
HXA: Owner-authorized ASR (2026-10-19)
Deciders: Project owner（本轮 ASR 设计实施及后续 TTS 候选评估授权）

## Context

原语音按钮依赖系统识别 Activity，国产机可能没有可用服务，也不能保证离线或实时预览。Owner 授权设计实施 ASR；追加移动端模型评估与后续 TTS 建议。实施与验收见[本轮证据](../../evidence/development/offline-voice-input-2026-10-19.md)。

## Decision

Standard 和 Advanced 均提供完整离线语音输入，不依赖 OEM/Google 的 SpeechRecognizer 或识别 Activity。用户按语音按钮后才申请麦克风权限、加载模型并录音；后台、会话切换、取消或音频焦点丢失停止录音。每次最多 120 秒，单个录音/推理循环，不建立无界音频队列、不保存录音。

实时结果在输入面板预览。结束后允许修改，用户点击“加入草稿”才追加到当前会话原草稿，之后仍需主动发送。失败、取消、丢失前台或迟到推理结果均不修改原草稿；没有后台常驻监听、实时语音对话或自动发送。结束后的编辑面板允许暂时切后台，保持当前进程内编辑内容；进程重建不恢复录音/尚未接受的预览，原有持久化草稿沿用现有机制。

本轮采用 sherpa-onnx 1.13.8 + CPU 双线程、小型中英双语流式 Zipformer（2023-02-16，INT8 encoder/joiner、FP32 decoder），约 58 MiB 模型随包，两渠道开箱离线。两个 supported ABI（arm64-v8a、x86_64）从固定上游源码构建 JNI；关闭 TTS、eSpeak、说话人分离、WebSocket 等无关功能，不直接打包默认带 TTS 的通用官方 AAR。Kotlin API 来自同版本官方 AAR；原生依赖、模型均固定大小/SHA-256，构建产物与归属材料由 `scripts/build-asr-assets.py` 生成并由 `:app:verifyAsrAssets` 校验。版本保持 0.0.6 / code 1。

sherpa-ncnn 和 sherpa-onnx 均有官方 Android CPU/真流式路径。ncnn 的依赖较少；onnx 提供更广的 ASR 模型及后续 TTS 候选，并有同一套受控 Kotlin/JNI 接口。为后续模型比较选 onnx，代价是较大的通用推理库。没有复制 Operit 代码。采用的 sherpa/模型 Apache-2.0、ORT MIT、KissFFT BSD、未修改 Eigen MPL-2.0 等归属、许可与固定源代码入口随 APK 放入 `asr-licenses/`；Eigen 启用 `EIGEN_MPL2_ONLY`。不把框架许可推导为所有模型均可商用。

2025-04-01 中文 CTC INT8 候选约 25 MiB，在同音频移动 CPU 实测中更省空间、部分中文口语词更完整，但中英混说明显退化并出现未知词，未证明总体更优，不替换双语默认模型。更大双语模型约 190 MiB 及整段识别模型不作为本轮默认：没有足够移动端质量收益证据支持扩大包体/运行内存，也不通过反复重跑整段音频冒充原生流式。少量公开夹具只用于有界回归，不外推中文准确率、噪声、口音或所有 OEM。

`StreamingAsrFactory` / `StreamingAsr` 是 PCM→部分/最终文本的窄接口。录音、焦点、生命周期及草稿接纳不依赖某个聊天 provider。未来云端 ASR 必须显式选择、配置凭据并清楚告知上传范围；当前模型不可用明确报错，绝不静默上传。当前聊天 provider 不是 ASR provider，首轮不增加云端接入、Key 或模型下载框架。

## Alternatives considered

### 后续 TTS（本轮不实施）

TTS 是后续独立输出能力，可复用固定 ORT/JNI 构建经验，不能借本次 ASR 许可自动播放或扩大到实时通话。优先评估 [Kokoro-82M-v1.1-zh](https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh) 及 [sherpa Kokoro INT8](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html)：82M 参数、中英、Apache-2.0 权重；量化候选需实际测中文数字/多音字、首段延迟、RTF、峰值内存及短句自然度，并单独解决中文/英文前端和 eSpeak 的许可边界，不能直接启用整个默认 TTS 构建。权重体积不等于运行内存或声音质量。

[Matcha Baker 中文](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/matcha.html) 官方说明训练数据仅限非商用，且声学模型约 72 MB 外加约 51 MB vocoder，不作为商店默认候选。更大生成式/克隆模型在没有手机算力、内存、许可和体验实测前也不预装。首轮没有下载/运行 TTS 模型或生成合成语音，尚无 TTS 手机性能结论。

### 2026-10-19 官方方案复核

本次重新检查官方发布页，当前最新稳定 sherpa-onnx 为 [v1.13.8](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8)，与已安装实现一致。框架继续保留；58 MiB 双语模型是已有设备证据支持的基础方案，不称为最新或全面最优模型。ncnn 有成熟 Android 离线流式路径，但没有本项目同设备对比证据支持更换；[MNN](https://github.com/alibaba/MNN) 有移动端语音应用，框架支持不能直接证明相同模型更快，新增转换/算子/JNI 维护成本目前缺少收益证据。

后续比较优先级如下，全部是候选，不表示已经下载、接入或通过手机验收：

| 候选 | 官方能力与体积口径 | 对 Helix 的判断 |
| --- | --- | --- |
| [中文 streaming Zipformer INT8 2025-06-30](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html) | 原生流式，官方文件列表 encoder 154M + decoder 4.9M + joiner 1M；不是运行 RAM | 第一优先比较的中文增强模型；须验证中英混说，不能预设中文模型适合作为双语默认 |
| [Streaming Paraformer 中英](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-paraformer/paraformer-models.html) | 原生流式，INT8 encoder 158M + decoder 68M；官方注明无时间戳 | 第二优先；同框架可比较，但模型显著大于当前基础包 |
| [SenseVoice INT8](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html) | 有 Android/Kotlin 路线及 2024-07-17、2025-09-09 导出；VAD 分段识别 | 评估停顿后识别或结束后的本地复核；不能把 VAD 分段结果冒充当前逐帧流式预览 |
| [Qwen3-ASR-0.6B](https://github.com/QwenLM/Qwen3-ASR) | 2026 新系列，官方支持流式/离线、30 语言及 22 中文方言；常规部署为 Transformers/vLLM | 值得增强质量实验。sherpa 最新源码已有 offline Qwen3 适配，不能因此宣称 Android 已有相同流式能力；没有本机 RAM/发热/延迟证据前不预装 |
| [Fun-ASR-Nano GGUF](https://github.com/QwenAudio/Fun-ASR/tree/main/runtime/llama.cpp) | 官方已有 C++ CPU/edge 路线，说明包含 phone，量化整套约 1.3 GB；VAD 分段 | 修正“只能服务器”的过强判断；仍不是当前低包体流式输入的直接替代，尚缺 Android 集成与 OnePlus 实测 |
| [whisper.cpp stream](https://github.com/ggml-org/whisper.cpp/tree/master/examples/stream) | 官方示例按窗口重复识别，另有 VAD 模式 | 可作为离线质量参考；不优先替换支持持续增量解码的基线 |

推荐保留开箱可用的小双语模型，先在测试端比较中文 Zipformer、Paraformer、SenseVoice；只有质量收益与手机预算证实后，再决定是否增加用户主动下载的增强模型。下载不是本轮已实现能力。新一轮应使用同一批有许可的普通话、中英技术词、数字、口音、噪声及静音样本，分别记录中文 CER、英文 WER、关键术语、静音误识别、首次可见文字墙钟延迟、停止到最终文字延迟、冷启动、进程峰值 PSS、连续使用发热和失败率。流式录音按真实时间节奏喂入；吞吐测试单列。模型权重、分词器、前端与测试音频分别核对许可，框架 Apache 不能代替模型授权。

未来 TTS 保留 Kokoro INT8 作为小模型优先候选，同时纳入 [Qwen3-TTS](https://github.com/QwenLM/Qwen3-TTS) 的 0.6B 路线做质量参考；更大参数和官方流式能力都不能推导出老款手机可实时运行。此次没有新增 TTS、模型下载或云端调用，没有新的模型手机性能结论。

## Consequences

模型与原生库增加安装包体积、冷加载延迟和内存占用，换取离线可用、明确的数据边界与不依赖 OEM。每次录音退出释放原生对象，避免常驻模型；首次加载以 UI 明示等待。独立验证包用于保护设备原有安装，不扩展应用正式包名或版本。

## Verification

主机检查及设备证据按[本轮记录](../../evidence/development/offline-voice-input-2026-10-19.md)更新。模型注入测试与真实麦克风识别体验分开；不把公开夹具吞吐或测试进程 PSS 当作普遍准确率、实时首字延迟或模型独立内存。

## Reconsider when

有同设备、同音频的质量/速度/内存证据证明更好的移动模型，或 Owner 明确启动云端/TTS 时，更新本 ADR 的同主题边界并追加历史；不因新增候选而自动增加下载服务、监听或通话。

## References

- [sherpa-onnx Android](https://k2-fsa.github.io/sherpa/onnx/android/index.html)、[sherpa-ncnn](https://k2-fsa.github.io/sherpa/ncnn/)。
- [双语 Zipformer 模型与精度/体积说明](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html)、[上游训练模型许可](https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t)。
- [中文 CTC 官方说明](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-ctc/zipformer-ctc-models.html)、[训练模型 Apache-2.0](https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01)。

## Decision history

- 2026-10-19：Owner 授权本地中文优先 ASR；依据官方 Android 路线和两台 ARM64 设备公开音频结果接受上述范围。后续 TTS 保留独立评估边界，不扩大本轮实现。

- 2026-10-19：按 Owner 追加要求复装独立验证版并复核最新官方候选；保持现有实现，明确基础模型并非全面择优结论，补充增强模型比较顺序及移动端验收条件。
