# 离线 ASR 构建输入

`dependencies.lock.json` 固定官方 sherpa-onnx 1.13.8 源码/API、ONNX Runtime 1.28.2 和两组模型的 URL、大小及 SHA-256。运行 `python3 scripts/build-asr-assets.py` 准备 `build/asr/`，`--verify` 只做离线完整性检查；不启动/访问设备。Android SDK/NDK/CMake 版本沿用项目现有固定版本。

生产 APK 不包含模型权重。先执行上述准备，再执行 `python3 scripts/prepare-asr-download-assets.py`，生成独立生产 assets（下载锁元数据和归属）与 AndroidTest 模型夹具。`model.lock.json` 固定同一双语模型的逐文件 HTTPS 来源、大小和 SHA-256；运行时由用户确认下载。`--verify` 校验本地准备结果，`--apk <apk>` 检查 APK 无权重且保留原生引擎。中文 CTC 候选和公开 WAV 同样只进入 AndroidTest assets。构建 JNI 明确关闭 TTS/eSpeak、说话人分离、WebSocket 和可执行程序；只用通用 AAR 的 Apache-2.0 Kotlin API，不复制其默认 JNI。原生源码及每项传递依赖的固定源地址/校验来自该版本上游 CMake，Eigen 启用 MPL-only；不修改第三方源代码。

`notices/onnxruntime-LICENSE.txt` 和 `onnxruntime-ThirdPartyNotices.txt` 分别来自 Microsoft 官方 `v1.28.2` 的 [LICENSE](https://github.com/microsoft/onnxruntime/blob/v1.28.2/LICENSE) 与 [ThirdPartyNotices.txt](https://github.com/microsoft/onnxruntime/blob/v1.28.2/ThirdPartyNotices.txt)，因为对应 Android ZIP 未附归属文本。其余原生归属来自实际下载/构建的依赖源树；一起打包为应用内 `asr-licenses/`。模型许可、移动端比较与后续 TTS 边界见 [ADR-PLATFORM-004](../../docs/adr/platform/004-offline-voice-input.md)。二进制及解压源码均留在忽略的 build 目录，不提交。

设备验证需本轮 Owner 明确授权。`-Phelix.asrValidation=true` 仅给 Debug 附加固定验证包后缀，避免覆盖用户安装；不改变正常 Debug/Release 包名、版本或签名。GitHub CI 只执行准备、JVM/静态检查和构建，不运行设备。
