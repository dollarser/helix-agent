# 离线 ASR 构建输入

`dependencies.lock.json` 固定官方 sherpa-onnx 1.13.8 源码/API、ONNX Runtime 1.28.2 和一段公开测试 WAV 的 URL、大小与 SHA-256。运行 `python3 scripts/build-asr-assets.py` 准备 JNI、归属和测试音频；不再为原生构建下载旧模型整份归档或历史候选。`--verify` 只做离线完整性检查，不访问设备。SDK/NDK/CMake 沿用当前固定版本。

生产 APK 不包含权重。再运行 `python3 scripts/prepare-asr-download-assets.py`，按 `model.lock.json` 固定的 ModelScope 文件准备下载描述、模型归属和 AndroidTest 夹具。当前默认为中英 small streaming FP16 transducer：encoder/joiner FP16、decoder FP32、tokens，共约 80 MiB；不用安装作者 Python SDK，也不下载整个仓库。只有用户显式下载才安装；旧模型文件不自动迁移或删除。生产主 assets 只有描述和归属，模型夹具只在测试包。`--verify` 校验本地准备，`--apk <apk>` 检查无模型权重且保留引擎。

JNI 仍明确关闭 TTS/eSpeak、说话人分离、WebSocket 和可执行程序；应用现有组合 ASR+Melo 构建由 `scripts/build-tts-assets.py` 准备。只用固定官方 Kotlin API，不复制参考项目实现；不改变 JNI/ABI/keep 规则。原生源和传递依赖的固定地址/校验来自上游 CMake，Eigen 保持 MPL-only。选型的固定样本、语言/资源取舍及实验复跑见[ModelScope 对照](../../docs/evidence/development/modelscope-asr-selection-2026-10-26.md)。

`notices/onnxruntime-LICENSE.txt` 和 `onnxruntime-ThirdPartyNotices.txt` 分别来自 Microsoft 官方 `v1.28.2` 的 [LICENSE](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE) 与 [ThirdPartyNotices.txt](https://github.com/microsoft/onnxruntime/blob/v1.28.2/ThirdPartyNotices.txt)，因为对应 Android ZIP 未附归属文本。其余原生归属来自实际下载/构建的依赖源树；一起打包为应用内 `asr-licenses/`。模型许可、移动端比较与后续 TTS 边界见 [ADR-PLATFORM-004](../../docs/adr/platform/004-offline-voice-input.md)。二进制及解压源码均留在忽略的 build 目录，不提交。

设备验证需本轮 Owner 明确授权。`-Phelix.asrValidation=true` 仅给 Debug 附加固定验证包后缀，避免覆盖用户安装；不改变正常 Debug/Release 包名、版本或签名。GitHub CI 只执行准备、JVM/静态检查和构建，不运行设备。
