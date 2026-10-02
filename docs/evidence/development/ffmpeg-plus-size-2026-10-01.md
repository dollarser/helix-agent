# FFmpeg 增强裁剪版：前三优先级构建与 Android 实测（2026-10-01）

## 授权、结论与范围

所有者在 [Lite 体积实验](ffmpeg-lite-size-2026-10-01.md) 后要求“帮我补前三优先级试试”：验证 Android H.264/HEVC MediaCodec 编码，补常用拼接/分段/滤镜，并补 MP3/Opus/WebP 编码及文字/字幕。阈值继续按严格 **25,000,000 bytes** 计算。

**增强版 Android arm64-v8a 的完整运行载荷为 9,033,648 bytes（9.034 MB）；不压缩、16 KiB ZIP 对齐后为 9,105,309 bytes。17 项有限 Android 实测全部通过。** 体积条件满足；本轮仅构建/测量/验证候选，不改 Helix 生产依赖、Tool、Runtime 或 APK，不提交、不推送。

基线 HEAD 为 `05e910039e03095d98649d96a9e64a3a71bcb078`。工作区的 HXA-233/HXA-126/R2-A 等并行修改保留，本实验不覆盖它们。构建与设备步骤通过现有 `scripts/with-host-slot.py` 串行，未动用真实账户或个人真机。

## 三套配置的真实体积

MB = 1,000,000 bytes。每套完整载荷均计算六个共享库和 `ffmpeg`、`ffprobe`；不是只计算 CLI 入口。外部依赖静态 PIC 链入相应 FFmpeg 共享库，DT_NEEDED 复核未发现遗漏的非系统动态依赖。

| 配置 | 完整运行文件 bytes | MB | ZIP 压缩载荷 bytes | 不压缩且 16 KiB ZIP 对齐 bytes |
| --- | ---: | ---: | ---: | ---: |
| 原 Lite，历史固定基线 | 6,082,536 | 6.083 | 2,783,573 | 6,137,773 |
| Common：常用拼接、分段、音视频滤镜与字幕读写 | 6,326,216 | 6.326 | 2,877,222 | 6,384,877 |
| **Plus/Extended：另加编码器与文字/字幕渲染** | **9,033,648** | **9.034** | **4,123,889** | **9,105,309** |

增强版比原 Lite 的未压缩载荷增加 2,951,112 bytes，约 2.95 MB。带许可证、来源锁、构建配方、实测记录与清单的候选 ZIP 为 **4,219,028 bytes**，SHA-256 为 `9c7b73bcc080920e20312b9a266925b19ad435f242980797e7195817bd1dc540`。

以上均为独立实验制品，不是最终签名 Helix APK 的实测增量。只编译 ARM64，不外推多 ABI 大小；系统库与系统字体不重复打包，不把源码、构建对象和编译工具算作运行载荷。

## 实际新增组件

- 常用输入/输出：`concat` demuxer、`segment` / `stream_segment` muxer、SRT/ASS/WebVTT 字幕读写、MP4 `mov_text` 编解码、原始 PCM 输出。`concat` 滤镜与文件清单 demuxer 的差异已明确，不再混称。
- 视频：`fade`、`xfade`，保留原缩放、裁剪、旋转、叠加与抽帧。
- 音频：`acrossfade`、`silencedetect`、`silenceremove`、`loudnorm`、`dynaudnorm`、`equalizer`、`acompressor`、`alimiter`。
- 编码：`libmp3lame`、`libopus`、`libwebp`、`libwebp_anim`，保留原 AAC/FLAC/PCM 等。
- 字体/字幕：`drawtext`、`subtitles`、`ass`；包含 FreeType、HarfBuzz、FriBidi、libunibreak、libass 所需实现。
- H.264/HEVC：软件解码保留；MediaCodec 编码使用本轮证实的异步配方，不加入 x264/x265 软件编码，也不承诺设备一定提供硬件实现。

仍未加入 AV1、VP8/VP9 软件编码、全套专业编解码、网络/直播协议和设备采集。网络协议仅 `file`、`pipe`、`fd`；这不是文件沙箱，正式接入仍需要输入、输出和权限边界。

## 来源、构建与许可

FFmpeg 保持已验证签名的 9.0.2 源码，NDK 28.2.13676358，目标 Android API 29 / arm64-v8a。外部组件均从具名上游版本构建，不使用宿主 Homebrew 媒体库冒充 Android 依赖。

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| LAME | 3.100 | MP3 编码 |
| Opus | 1.6.1 | Opus 编码 |
| libwebp | 1.6.0 | 静态/动画 WebP 编码 |
| FreeType | 2.14.3 | 字形渲染，选择 FreeType License |
| FriBidi | 1.0.16 | 双向文字处理 |
| HarfBuzz | 14.3.0 | 文字整形 |
| libunibreak | 6.1 | 字幕换行 |
| libass | 0.17.5 | ASS/SRT 字幕渲染 |

所有归档 URL 与 SHA-256 固定在 `ffmpeg-plus-sources.py` 和 `build/ffmpeg-plus-size-2026-10-01/source-lock.json`。FFmpeg 是此前 GPG 验签的固定归档；Opus 核对发布 SHA；其余来源是上游 HTTPS 下载后固定哈希，不冒充独立签名验证。未修改上游源码。

构建工具 Meson 1.9.1/Ninja 1.13.0 安装在实验目录自己的 venv，不改全局工具或项目 Gradle 依赖。关闭 FFmpeg GPL/nonfree/version3，最终 configure 报 LGPL 2.1 or later；随候选保留各依赖许可和归属。完整源码仍在本地实验目录，配方可重建；未来正式分发仍需对照实际链接/封装满足各许可义务，此实验不是商店发行许可结论。

### 字体不藏在体积之外

没有随包字体。实测 `drawtext` 指向模拟器现有 `/system/fonts/NotoSansCJK-Regular.ttc`，字幕使用 `/system/fonts` 和明确字体族。没有复制或分发系统字体。

这证明该设备的中文绘制链路可用，不保证所有 OEM 都存在相同文件/字体族。生产接入应发现系统字体或接受用户合法选择的字体，缺失时明确报错；不能将来悄悄加一套大字体却继续沿用 9.03 MB 数字。

## Android 验证及中间反例

设备为 `emulator-5554`，API **36**，`arm64-v8a`，实际页大小 **4096 bytes**。指纹：`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`。

运行的是本次编译的 Android ELF，通过独立合成目录与 NDK MediaCodec 验证；不是 macOS companion 的结果。每个命令有 30 秒截止与 3 秒清理窗口，不操作 Helix 用户数据。ELF LOAD/ZIP 的 16 KiB 静态检查已通过，但当前设备不是 16 KiB 页设备。

### 默认同步 MediaCodec 没有通过，已找到有效配方

初始默认同步调用中，H.264 返回 0 却只生成 261-byte 无视频流 MP4，HEVC 无帧并在预算内被终止。没有把退出码或存在文件当成成功。

对相同合成输入比较同步与异步模式：同步仍失败；异步模式配合显式 `extract_extradata` 成功，各输出 30 帧并由 FFprobe 软件解码计数验证。最终全套测试使用下列配方：

```text
-c:v h264_mediacodec -ndk_codec 1 -ndk_async 1
-flags -global_header -bsf:v extract_extradata
```

HEVC 使用 `hevc_mediacodec`。这符合 [FFmpeg MediaCodec 官方选项](https://ffmpeg.org/ffmpeg-codecs.html#MediaCodec) 的异步/码流头说明。本轮未修改 FFmpeg 来假装同步模式已修复，不声称已定位全部上游/OEM 根因，也未证明同一设置在其他设备必然成功。

### 最终 17 项检查

| 检查 | 真实后置条件 | 结果 |
| --- | --- | --- |
| MPEG-4/AAC 基础样例 | 两路流、2 秒合成文件 | PASS |
| H.264 MediaCodec | 编码后软件解码 30 帧，320×240 | PASS |
| HEVC MediaCodec | 编码后软件解码 30 帧，320×240 | PASS |
| concat demuxer 无重编码拼接 | 60 帧、约 4.021 秒 | PASS |
| segment 分段 | 两段可解码，总计 30 帧 | PASS |
| 视频 fade | 首帧黑、后续画面恢复，30 帧像素验证 | PASS |
| 静音检测/删除 | 检出前静音，删除后约 1.120 秒 | PASS |
| loudnorm | 合成样例输出 -16.00 LUFS，与目标一致 | PASS |
| 视频/音频交叉淡化 | 视频 53 帧；音频 3.5 秒 | PASS |
| 动态响度/均衡/压缩/限幅 | 实际组合输出可解码 PCM 文件 | PASS |
| 异常/覆盖/协议边界 | 损坏输入拒绝；原文件不变；无 HTTP/TCP | PASS |
| MP3 编码/解码 | 精确 codec 与有效 PCM 回读 | PASS |
| Opus 编码/解码 | 精确 codec 与有效 PCM 回读 | PASS |
| WebP 无损 | 回解 RGBA 与源像素完全相同 | PASS |
| 动画 WebP | 10 个不同无损帧，RIFF 长度及 666ms 时长校验 | PASS |
| 中文 drawtext | 使用系统字体，输出像素确有改变 | PASS |
| SRT/ASS 与软字幕 | 两类烧录均有像素变化；MP4 有 mov_text 字幕流 | PASS |

中途修正了两个真实裁剪配置遗漏：`movtext` 是 configure 的名字（CLI 名字是 `mov_text`）；仅有 PCM 编码器并不包含 `pcm_s16le` 输出 muxer。最终新增必需宏断言，避免“参数请求过”冒充“实际编入”。另修正了动画测试：有损的相似帧可以合并，不应要求容器必定保留每一帧；最终用不同原始帧的无损编码保留严格 10 帧和时长校验。

初始失败、四组 MediaCodec 模式比较与最终通过记录分别保留在 `android-probe/*/results.json`；`mediacodec-mode-results.json` 明确保留 2 成功/2 失败，不用最终配方覆盖历史反例。最新全套记录为 `android-extended-results.json`（17 PASS / 0 FAIL），目录尾部 `helix-ffmpeg-plus-80d6c48d506e`。

## 产物与复现

实验根目录：`build/ffmpeg-plus-size-2026-10-01/`。

候选包：`packages/ffmpeg-9.0.2-plus-arm64-v8a.zip`。包含八个运行文件、许可、构建/测试脚本、来源/配置与验收记录；不包含字体或 APK。

```bash
python3 scripts/debug/2026-10-01/ffmpeg-plus-sources.py
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/build-ffmpeg-plus.py --profile common
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/build-ffmpeg-plus.py --profile extended
python3 scripts/debug/2026-10-01/measure-ffmpeg-plus.py --profile common
python3 scripts/debug/2026-10-01/measure-ffmpeg-plus.py --profile extended
# 只有针对本次设备验证明确授权后才执行；不能把此命令当作后续任务的授权。
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/probe-ffmpeg-plus-android.py --serial emulator-5554 --profile extended
python3 scripts/debug/2026-10-01/package-ffmpeg-plus.py
```

首次使用需先按 Lite 记录准备固定 FFmpeg 源码。两套配置均实际交叉编译；共享依赖使用具名缓存，不声明每轮全量清空重建。完整日志、ELF 检查、实际启用宏和 SHA 分别位于 `logs/`、`payload/`、`obj/`、`size-*.json`。

## 收尾检查

六个新增实验脚本 `python3 -m py_compile` 通过；固定上游归档 SHA 检查复跑通过；候选包按清单核验每个运行文件哈希，明确不含字体。导出的候选包与本记录 SHA 一致。

全仓库 `./scripts/check-docs.sh` 本次未通过：并行 R2-A 迁移期间，`docs/evidence/research-history/conversation-context-and-steering.md` 指向旧 `app/.../ChatContextRequest.kt`，`docs/evidence/research-history/execution-engine-comparison.md` 指向旧 `app/.../TurnBudgetTracker.kt`。失败条目不属于本实验文件，未擅自覆盖并行重构的文件或映射；不能写成全仓库门禁通过。该问题不改变已按哈希绑定的 FFmpeg 构建、体积、ELF 对齐和 Android 17 项实测结果。

## 仍未验收的范围

- Helix App/JNI 加载、权限、任务取消恢复、产物发布与最终 APK 增量：未接入/未测，本轮不扩展生产范围。
- 真机与多个 OEM、真实硬件编码加速、长视频、4K/HDR、发热、内存和质量：未测。模拟器的小样例耗时不是性能基准。
- 实际 16 KiB 设备、x86_64/多 ABI：未测。静态对齐 PASS 不等于设备矩阵 PASS。
- MediaCodec 同步路径仍有明确失败观察；只接受本轮通过的异步配方作为继续接入的候选。

结论：前三优先级的裁剪构建与有限设备试验完成，增强版低于 25 MB，推荐保留该配置继续评估正式接入；不再把所有媒体功能依赖完整 Alpine FFmpeg，也不将本次证据扩大为通用媒体生态全部支持。
