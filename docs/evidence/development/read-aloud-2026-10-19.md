# 本机回复朗读验收（2026-10-19）

范围：[HXA-310](../../development/tasks/HXA-310.md)、[ADR-PLATFORM-004](../../adr/platform/004-offline-voice-input.md)。起点 `639223bd`，在知识库 `655bd2f2`、提醒 `639223bd` 后继续 Owner 明确授权的第三阶段。本地提交，不 push/发布；版本 0.0.6/code 1、工具声明 1 不变。

## 实际实现与模型取舍

完整普通 assistant 回复操作提供主动朗读/停止/重新朗读；不读流式半成品、用户/工具消息。冻结正文，最多 20000 字符，按不超过 64 UTF-16 单元且不拆代理对的片段合成。单物理合成互斥保持到原生退出和音轨/焦点释放；切换消息/会话、离开聊天、后台、启动语音输入、焦点丢失/duck、耳机断开即停止，无自动恢复、提醒自动播报或环境录音。

MeloTTS Chinese INT8 单音色，模型 53,517,430 字节，词表/分词/数字日期电话规则和 MIT LICENSE 合计后完整必需下载为 60,569,075 字节（57.8 MiB）。固定 revision 与逐文件 SHA-256 见 `config/tts/model.lock.json`。当前 sherpa 1.13.8 的 Melo 不再使用旧 jieba dictDir；不下载旧字典、FP32 模型或其他音色。权重体积不等于 RAM。

首次点击缺资源明确说明来源/体积/本机处理，用户点击下载才访问固定公开 Hugging Face URL。HTTPS/重定向限域、逐文件限长/哈希、私有暂存后安装；失败/取消清理，中断残片下次显式下载时清理，读取前完整校验。不自动下载、云端合成或发送正文；下载完成也不自动播放。

双 ABI 专用 JNI 复用已锁定 ASR/sherpa/ORT 依赖，只保留 ASR+VITS/Melo，删除默认 Piper/eSpeak 依赖和其他 TTS 实现。模型/前端 MIT、sherpa Apache、ORT MIT、既有其他归属随 APK 保留；没有将框架许可当成所有权重的许可。`scripts/build-tts-assets.py` 可在冷构建下载固定模型/测试输入，CI 仅构建不启动设备。

豆包、ChatGPT、Edge 的显式朗读/中断与 Android 焦点是交互参考；Melo、Kokoro、Matcha、系统 TTS 的完整依赖、许可和 OEM 可用性取舍见 ADR。未复制参考项目代码。没有同设备 Kokoro 对照或主观自然度评分，不宣称达到豆包听感；Melo 的英文依赖词表，生僻混合词和多音字仍有边界。

## 验证记录

| 检查 | 本次结果与日志 |
| --- | --- |
| 双 ABI 专用 JNI、固定输入/产物完整性、动态符号排除 eSpeak/Piper | 通过，`build/tts-build.log`；`build/tts/manifest.json` |
| 两渠道 JVM/Debug/AndroidTest/lint、Core Model/Storage、spotless/detekt | 通过，`build/tts-debug-final.log`；Standard 1313、Advanced 1448（各 4 项既有外部 profile 条件跳过），Model 171、Storage 242，无失败/错误 |
| 两渠道 R8 Release | 通过，`build/tts-release-final.log`；mapping 检查 typed callback 名称和 `Integer invoke(float[])` 保留。未签名，不是 Release 运行验收 |
| `scripts/check-all.sh --source` | 279 项与文档/ADR/i18n/秘密扫描通过，`build/tts-source-final.log`；负面夹具内的 FAIL/INCONCLUSIVE 不算设备结果 |
| API36 ARM64 日常模拟器 Standard Debug | 32/32 语音与导航（108.939 秒）+ 37/37 知识库/提醒（37.286 秒） |
| 同一模拟器 Advanced Debug | 32/32（93.459 秒）+ 37/37（31.465 秒） |
| 显式真实公开资源下载（Standard） | 1/1，124.058 秒；独立测试目录安装/逐文件校验/清理，不写用户模型目录。`build/tts/public-download.log` |

32 项由 TTS 控制/真实合成 8、资源事务 5、朗读 UI 5、旧 ASR 公开 PCM/注入控制 10、导航 4 构成；37 项是知识库 Collection/UI/Notes 23 与提醒状态/UI 14。使用真实 JNI 生成文件、真实音频焦点竞争、0 音量独立 AudioTrack、注入引擎/PCM 分别验证相应边界，不将注入测试当实机录音。真实取消只在原生回调边界停止后续输出，底层单次推理并非可抢占；物理容量不因取消提前释放。

普通 APK/测试 APK/未签名 Release 归档 `build/tts/deliverables/`。`build/tts/evidence/provenance.json` 记录实际源码输入、JVM 报告和产物；每次安装从实际包路径核对 SHA-256。APK 检查确认两 ABI JNI/许可存在、57.8 MiB 权重与测试模型不进入普通包；两 ABI JNI 未压缩合计增加 498192 字节，整个 APK 还受 DEX/对齐/打包影响，不将总 APK 差值等同于模型大小。

本轮 runner 与归档工具在忽略目录 `scripts/local/debug/2026-10-19/`，设备日志 `build/tts/devices/`。两渠道偏好（排除进程诊断）、麦克风/通知授予、精确闹钟 app-op、时区/IME 与基线一致；没有清库或覆盖设置。旧 ASR UI 期间临时麦克风授权已恢复，实际输入为注入 PCM。仅删除本轮自有测试 APK、合成 WAV 缓存和首轮崩溃留下的已知 UUID 夹具；普通应用保留。

## 合成性能和主动试听边界

以下为 Advanced 最终候选、CPU 双线程、API36 ARM64 模拟器单次文件生成，两个样本共用一个引擎。记录文件为 `build/tts/evidence/Advanced/{synthetic-0.wav,synthetic-1.wav,metrics.log}`；Standard 同名目录保留当次候选输出。WAV 为 44.1 kHz 单声道 16-bit，只检查有限非零 PCM 与文件格式，未评价发音准确或自然度。

| 合成夹具 | 加载 | 首次 PCM（生成开始后） | 总生成 | 音频长度 | PSS 快照 |
| --- | --- | --- | --- | --- | --- |
| 中文、2026/137、银行行长、重庆 | 4.265 秒 | 6.965 秒 | 19.020 秒 | 6.666 秒 | 生成前约 133.5 MiB，结束约 379.0 MiB |
| 中文与 Helix / Hello world 等英文 | 复用上述加载 | 5.685 秒 | 13.268 秒 | 4.794 秒 | 结束约 381.0 MiB |

这不是点击到出声、峰值 PSS、手机性能或实时速度承诺；该模拟器约 2.8 倍音频时长的生成耗时，仍有明显等待。Standard 测量与 R8 主机负载重叠，中文首 PCM 16.762 秒/总 32.645 秒，不能隐去，也不拿来证明渠道差异。原生取消夹具只输出一次回调；总时长 Standard 14.106 秒、Advanced 18.050 秒，包含加载及取消前合成，不是停止音轨到安静的延迟测量。输出停止用音轨 pause/flush 与控制测试验证；原生 CPU 退出与音轨停止是不同边界。

完整体积、许可与实现目标符合本阶段选择，真实手机速度、自然度、数字/多音字正确发音仍待 Owner 主动试听；没有将 PCM 非空或 upstream CPU realtime 描述当成质量通过。收尾移除了新构建脚本对 macOS NDK 工具路径的硬编码，双 ABI 重建及六个 APK 重新构建通过，六份产物与设备验收归档逐字节相同；见 `build/tts-recipe-closeout.log`。

## 失败与修正

- 首轮真实合成崩溃：sherpa JNI 查找 `invoke(float[]) -> Integer`，Kotlin indy lambda 只有擦除入口，触发 NoSuchMethodError/CheckJNI abort。改为显式对象实现 typed invoke，R8 精确保留回调，重新构建/实测。旧崩溃日志保留在本机 `build/tts/`。
- 初轮旧 ASR 弹窗四项失败：测试依赖已授予麦克风权限，但设备基线关闭；注入 PCM 本身不录音。runner 仅该组临时授予、finally 恢复基线；不以失败假称通过。
- 编译期格式、导入/依赖和短文本分段断言问题已定位修正；不削弱非空 PCM、资源校验、取消容量或 UI 效果断言。

## 保留边界

本阶段实现无外部依赖阻塞，当前仅日常 API36 ARM64 模拟器有 TTS 实际执行结果。OnePlus 在提醒最终候选阶段已断开，未恢复；不将旧候选通知通过推定为最终提醒真机通过。没有真机 TTS 外放、自然度主动试听、私人内容、麦克风录音、其他 OEM、长稳或正式商店验收。合成 WAV 仅为本次公开/合成夹具，正常应用不缓存朗读音频。
