# FFmpeg 裁剪构建与体积实验（2026-10-01）

## 结论与范围

所有者授权：实际编译裁剪 FFmpeg，低于 25M 再考虑接入；超过则保留 Android 常见图片/视频能力路线。本次仅为构建/体积实验，不启用生产 Provider、Tool、Runtime 或 APK 依赖。

**Android arm64-v8a 完整运行文件实测 6,082,536 bytes（6.08 MB / 5.80 MiB），低于严格 25,000,000 bytes 门槛。** 六个共享库加 ffmpeg/ffprobe 全部计算，未漏算随包动态依赖；系统公共库不随应用重复分发。未压缩、16 KiB ZIP 对齐后的完整载荷为 6,137,773 bytes，仍明显低于门槛。

基线 HEAD：`05e910039e03095d98649d96a9e64a3a71bcb078`。工作区存在另一任务的 HXA-233 等并行变更；本实验未修改、提交或覆盖它们。Android/MediaCodec 设备运行：**not requested**。没有安装设备、启动模拟器或调用真实账号。

## 来源与工具链

| 项目 | 实际值 |
| --- | --- |
| FFmpeg | 9.0.2，固定官方发布源码，未修改其源码 |
| 源码归档 SHA-256 | `8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e` |
| 发布签名 | GPG 验证通过；固定指纹 `FCF986EA15E6E293A5644F10B4322F04D67658D8` |
| 目标 | Android arm64-v8a / API 29 |
| NDK | 28.2.13676358，复用已安装项目工具链 |
| 编译器 | Android Clang 19.0.1 |
| 构建方式 | shared libraries；size optimization；去除调试符号；保留架构汇编优化 |
| 平台库 | libandroid、libmediandk、libz、libdl、libm、libc，由 Android 提供 |
| 许可证构建选择 | 禁用 GPL / nonfree / version3；保留 LGPL-2.1 文本与 FFmpeg LICENSE.md |

来源与声明：[官方发布与验签说明](https://ffmpeg.org/download.html)、[9.0.2 源码](https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz)、[FFmpeg 许可说明](https://ffmpeg.org/legal.html)。签名使用实验目录内独立 keyring，不修改用户默认 keyring。

## 体积实测

本节 MB = 1,000,000 bytes；MiB = 1,048,576 bytes。压缩 ZIP 不是最终签名 Helix APK 的测量，原生库打包方式及宿主封装仍需接入后另测。

| 文件/载荷 | bytes | 十进制 MB |
| --- | ---: | ---: |
| libavcodec.so | 3,018,992 | 3.019 |
| libavformat.so | 876,176 | 0.876 |
| libavfilter.so | 334,112 | 0.334 |
| libavutil.so | 572,992 | 0.573 |
| libswscale.so | 673,080 | 0.673 |
| libswresample.so | 74,784 | 0.075 |
| **六个共享库合计** | **5,550,136** | **5.550** |
| ffmpeg CLI | 363,288 | 0.363 |
| ffprobe CLI | 169,112 | 0.169 |
| **共享库 + 两个 CLI** | **6,082,536** | **6.083** |
| 仅共享库，ZIP Deflate | 2,551,924 | 2.552 |
| 全套运行文件，ZIP Deflate | 2,783,573 | 2.784 |
| 仅共享库，不压缩且 16 KiB ZIP 对齐 | 5,605,128 | 5.605 |
| 全套运行文件，不压缩且 16 KiB ZIP 对齐 | 6,137,773 | 6.138 |
| 交付 ZIP，包含许可证与来源/配置记录 | 2,796,284 | 2.796 |

不是 Alpine ffmpeg 软件包依赖投影，不携带 Python、RootFS、SDL、X11、x264/x265 或其他第三方编解码大库。最终接入也不需要照搬现有 PRoot 环境。

## 保留功能与明确删除范围

实际配置与启用宏列表分别记录在 `build/ffmpeg-lite-size-2026-10-01/obj/arm64-v8a/configure-argv.json` 与 `size-arm64-v8a.json`。

- 软件解码：H.264、HEVC、MPEG-4、VP8/VP9、AAC、MP3、FLAC、Opus、Vorbis、常用 PCM、JPEG、PNG、BMP、GIF、WebP、rawvideo。
- 编码：AAC、FLAC、PCM、JPEG、PNG、BMP、GIF、MPEG-4、rawvideo；H.264/HEVC 通过 MediaCodec 包装层，不包含 libx264/libx265 软件编码器。
- 容器：常见 MP4/MOV、Matroska/WebM、AVI、MPEG-TS、AAC/MP3/WAV/FLAC/Ogg、图片序列与原始流；保留编码流直接复制。容器支持不表示其中任意编码都可读写。
- 滤镜：缩放、裁剪、旋转/翻转、补边、帧率、截取、时间戳、拼接、缩略帧、overlay、GIF 调色板，以及重采样、音量、混音、淡入淡出和变速。
- 输入协议仅 file / pipe / fd；关闭网络、采集设备、播放器、文档、调试信息与自动外部库发现。禁用网络不等于文件沙箱，正式工具仍需要 scope 与宿主授权。
- 未包含 AV1 软件编解码、VP8/VP9 软件编码、MP3 编码、WebP 编码、字幕烧录/字体渲染、复杂外部滤镜和完整格式生态；H.264/HEVC 硬编码可用性依赖设备。

MediaCodec 仅完成编译与静态符号/依赖检查，不能宣称已在手机上完成压缩。其 Java/NDK 模式与系统服务依赖见[官方编码器说明](https://ffmpeg.org/ffmpeg-codecs.html#MediaCodec)。Android ELF/ZIP 对齐与真实设备运行是不同验收，见[16 KiB 官方说明](https://developer.android.com/guide/practices/page-sizes)。

## 本轮验证

| 验证 | 结果与界限 |
| --- | --- |
| 官方源码签名 | 通过，固定 key 指纹及源码 SHA-256 |
| Android ARM64 configure / make / install | 通过；实际交叉编译，不是预编译下载包 |
| 必需编解码/容器/缩放启用宏 | 通过；H.264/HEVC 软件解码与 MediaCodec 编码实际编入 |
| 八个 ELF 的架构、LOAD 对齐、DT_NEEDED | 通过；全部 LOAD 至少 16 KiB，依赖均为六个随包库或 Android 系统库 |
| 不压缩 ZIP 的 zipalign -P 16 检查 | 通过；这是原生载荷 ZIP，不是可安装 APK |
| 同源码软件组件白名单的 macOS companion | 已重新编译；11 个合成主机检查通过，0 失败 |
| Android/MediaCodec 运行、速度/发热/内存峰值 | not requested，未测 |
| x86_64 / 多 ABI 安装包增量 | 未编译、未测，不据 ARM64 数字宣称通用 APK 大小 |
| 完整 Helix APK 接入/设备验证 | 未进行，生产包体不变 |
| 全仓库 `./scripts/check-docs.sh` | 未通过：并行工作新增的 `docs/模拟器全量设备测试问题记录-2026-10-01.md` 尚未归入文档分类；本实验未移动、删除或修改该文件。该文档组织问题不影响上述编译、ELF、载荷体积和 11 个主机功能实测，但不能宣称全仓库门禁通过 |

11 个合成检查：实际组件与禁网清单、MPEG-4/AAC MP4 编码探测、裁剪缩放旋转 JPEG、指定位置 PNG 抽帧、抽音轨并重采样 WAV、MP4→MKV 无重编码、GIF 输出、损坏输入拒绝、已有产物保留、H.264 软件解码 20 帧、HEVC 软件解码 20 帧。

H.264/HEVC 样例由机器上原有完整 FFmpeg 生成；**解码使用本次源码编译出的 host lite 二进制**，不是拿已有完整 FFmpeg 的结果冒充裁剪构建测试。host build 不包含 Android MediaCodec，此结果不替代 Android 运行。

中途修正了两项实验脚本问题：tar 根目录条目不带末尾斜线时的过严拒绝；以及错误假设 FFmpeg `-n` 拒绝覆盖必然退出非零。源码 `fftools/ffmpeg.c` 将 `AVERROR_EXIT` 映射成 0，最终测试同时校验明确拒绝文本与输出哈希完全未变，未仅凭进程返回值断言文件操作成功。实际同名 JPEG 未被覆盖。

## 产物与复现

产物目录：`build/ffmpeg-lite-size-2026-10-01/`（ignored）。

候选 ZIP：`packages/ffmpeg-9.0.2-lite-arm64-v8a.zip`。其中包括六个 Android .so、两个 Android CLI、许可证、配置和源码来源记录；它不是 Android App，也不是已接线的 Helix 插件。

构建入口：

```bash
python3 scripts/debug/2026-10-01/ffmpeg-lite-experiment.py prepare
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/ffmpeg-lite-experiment.py build --target arm64-v8a
python3 scripts/debug/2026-10-01/measure-ffmpeg-lite.py
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/ffmpeg-lite-experiment.py build --target host
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/smoke-ffmpeg-lite.py
```

详细证据：`source-provenance.json`、`size-arm64-v8a.json`、`host-smoke-results.json`、各目标 `obj/*/build-result.json`、`logs/` 与 `payload/arm64-v8a/*.elf.txt`。脚本从项目根推导路径，不要求修改项目依赖或全局 SDK。

## 下一步裁决建议

体积条件已满足，可以继续评估将该裁剪库作为 Android 基础媒体 API 的补充，而非改为完整 Alpine FFmpeg。正式接入前仍要授权并完成应用内任务/文件/取消/产物边界、共享库加载、设备 MediaCodec、16 KiB 设备、不同 ABI 与许可分发核查。尺寸实验本身不代表接受新执行域或完成生产功能。
