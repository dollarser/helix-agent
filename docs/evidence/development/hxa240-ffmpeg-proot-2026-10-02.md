# HXA-240：FFmpeg 复用 Advanced Bash / Linux Job（2026-10-02）

## 范围与裁决

所有者最后明确选择“复用高级版已有的 Bash／Linux Job”。本轮在 `5cfc9e2399d28f2131843ab9961a329cb5b0b59d` 工作区完成接线和主机验证；本记录末节维护实际验证结果，不从历史 Ready/Plus 测试推导当前桥接通过。实现轮次保留其他代理修改，未提交或推送；设备和真实账号未请求。随后所有者于 2026-10-02 追加本地提交授权：固定候选及复建材料与 Bash/Job 接入按依赖分开提交，不推送，不把提交视为设备验收。

此决定替代 HXA-240 最初的独立媒体 Service / 五操作模板。`runtime/media`、新 Service/JNI、`media.capabilities/inspect/process` 注册、共用 App 依赖及无调用方的 workspace 发布草稿已退出生产源码；字节和清单保存在 ignored `build/hxa240/retired-media-runtime/`，不是新的组件或发行包。原 Ready/Plus 文档继续作为编译实验历史。

## 生产接线

| 环节 | 当前实现 | 边界 |
| --- | --- | --- |
| 载荷准备 | `runtime/proot-app/vendor/ffmpeg-9.0.2-ready-av1-arm64-v8a.zip`；构建调用 `scripts/prepare-ffmpeg-proot.py`，校验固定 ZIP SHA、完整条目、逐文件哈希和 25 MB 原生上限 | 只有 developer 依赖该模块；不是在用户设备下载可执行代码 |
| 既有 Job | `ProotJobRunner` 准备桥接绑定和 PATH；原进程、Job 身份、预算、取消、日志与终态归档不变 | 不新增调度、全局锁或媒体进程服务 |
| 既有 PTY | `ProotTerminalLaunch` 使用同一桥接准备入口 | 手动终端写文件不自动冒充聊天 Job 产物；交付产物应走原 Job 收取 |
| 命令表达 | `GuestMedia.script` 提供 `ffmpeg` / `ffprobe`，以 `exec ... "$@"` 原样转发原生 CLI 参数 | 支持编入的多输入/输出和滤镜组合；没有五预设限制，亦不补出未编入组件 |
| 模型发现 | Bash 原工具说明增加 FFmpeg 与 `output/` 约定；可读取 `/opt/helix-media/bin/README.md` | 不增加一批常驻工具或重复 Skills 执行系统 |
| 输入 | `LinuxInputSnapshot` / `LinuxInputStreams` 经原 WorkspaceArtifactStore 流式复制并生成验证归档 | 单文件 64 MiB、总归档 128 MiB；不是原 1 MiB 文本预览上限，也不是无限媒体文件支持 |
| 输出 | `ProotResultStore` 从原成功 Job 的已验证归档收取 `output/`，`ProotProducedFiles` 发布到 app 托管的 `output/jobs/<jobId>/` 并绑定原 session/turn | 保留原归档、ACK、恢复路线；产物字节完整性不代表媒体语义验收 |
| 结果反馈 | 前台结果和后台 collect 返回产物目录、数量及至多 64 个引用，明确截断；全部合法产物仍登记 | 大列表不会撑破原结果响应上限；可按目录/会话继续查看 |

### ABI 与权限：明确的 Bionic-in-PRoot 桥

本轮没有改编译器目标，也没有把 Android 程序当成 Alpine/musl 软件包。guest 包装器调用 `/system/bin/linker64` 加载 APK 中的原始 Bionic CLI；PRoot 显式映射 `/system`、`/apex` 和设备存在的公开系统组件目录，以及 APK 原生库目录。未新增应用凭据目录绑定。可选 ABI 没有媒体载荷时，不因这个入口阻止已有非媒体命令。

PRoot 本来就是应用共享 UID 的可信代码执行环境，不是文件、凭据或网络安全沙箱。命令、字体、字幕和清单访问继续使用原 Bash/Job 权限，不因叫 ffmpeg 获得较低等级的授权。开启网络的其他 Bash 程序与本 FFmpeg 禁网编译范围是不同事实。

主机包装器测试只使用替代 linker 的受控 stub 验证 argv 保真/退出码，**没有运行 Android linker 或当前桥接中的 FFmpeg**。真实 Android 加载、SELinux/系统服务交互、设备 MediaCodec 仍未验收。

## 修复的衔接问题

1. 输入快照误用 `MAX_IMPORT_BYTES=1 MiB` 且先 `readAll()` 整份加载，阻挡普通视频。现在使用原 Job 已有文件/总量限制和流式散列；超限、中断、取消时清除本次部分归档及暂存，不提交半成品。
2. 多输入时 manifest 排序与 ZIP 写入顺序不一致。现在两者同序；输入别名仍保持已存在的“第一项 basename、后续完整 basename 加 -2/-3”约定，文档明确而不静默改名。
3. 原成功结果大多停在归档或 result.txt。现在显式 `output/` 下多个文件可按原调用、会话和 Turn 登记，输入和 stdout 不误当新产物；失败 Job 不走成功产物发布。
4. 收取后的用户编辑不能被重试覆盖。发布采用私有同目录临时文件和 create-if-absent；重收取只接受相同哈希。注册失败保留文件/原归档供同身份重试，不能重执行命令补收取。
5. 多文件结果摘要有界。响应限制不通过漏登记文件实现；超过 64 项明确返回总数和截断标记。
6. 原五操作草稿及额外进程接线已撤回，避免同一安装包维护两套 FFmpeg 和两套媒体生命周期。
7. 产物发布前重新读取原调用所属会话、工具可用状态和当前文件权限；延迟收取不沿用已经被 DENY 或工具禁用撤销的权限。首次写目录前、完成私有复制准备发布时、登记前均重验；失败保留原成功归档，不通过重执行命令恢复。

文件发布调用创建新 app 托管文件，现有用户目标不覆盖；默认不将 Job 临时文件发布。原任务归档上限包含输入、输出及日志，多段视频任务仍须规划原传输预算。上述行为不是任意文件系统上的原子覆盖承诺，也不是自动媒体质量评估。

## 使用入口

从高级版已有 Bash 工具发现，先按需运行 `ffmpeg -version`、`ffmpeg -filters`、`ffmpeg -encoders` 或 `ffmpeg -h filter=...`。复杂命令用原工具的脚本参数，避免碰到已有 argv 数量限制。读取 `README.md` 了解当前构建与输入别名；授权文件通过原工具 `files` 快照进入 `/workspace`。

```bash
mkdir -p output
ffprobe -v error -show_streams -show_format -of json input.mp4
ffmpeg -nostdin -n -i input.mp4 -frames:v 1 output/preview.png
ffprobe -v error -count_frames -show_streams -of json output/preview.png
```

示例表示模型可表达的标准命令，不是当前设备执行记录。短命令仍有原默认预算；长任务使用 `code.linux.job.start`、原 `jobs.await`/观察与结果收取。用户或模型负责核对输出格式、尺寸、时间、音画同步与语义，不因进程退出 0 就自动认为编辑目标已达成。

H.264/HEVC 的 MediaCodec 路径继续依赖设备，历史异步参数只是候选用法。没有 x264/x265/AV1 软件编码；没有 HDR 色调映射验收。已有字幕/文字功能需要系统可访问字体或获授权字体输入；不随包、不导出字体文件。

## 体积与验证证据

原始 Ready-AV1 候选 ZIP 为 4,737,982 bytes，SHA-256 `7fc77f64f27bcd56e1283b9e1233cc743da7b9ed3015accecd7ad7366bef2185`。完整 ARM64 原生运行载荷为 10,080,568 bytes，继续保持 25,000,000 bytes 条件；最终 APK 增量必须另测，不能用 ZIP 替代。

当前验证命令及结果文件：

- `bash scripts/debug/2026-10-02/validate-hxa240-host.sh`：联合单测、detekt/Spotless、双渠道 lint/APK/AndroidTest；原始日志 `build/hxa240/host.log`。
- `python3 -O scripts/debug/2026-10-02/test-ffmpeg-proot-package.py`：候选身份、完整闭包、重复提取、损坏拒绝与旧输出保留。
- `python3 scripts/debug/2026-10-02/verify-hxa240-apks.py`：实际两渠道 APK、原生字节/16 KiB 静态检查、移除旧 Service、Standard 排除和同基线包体增量；结果 `build/hxa240/apk-media-verification.json`。
- 原 `scripts/check-all.sh --source` 和 `--artifacts` 继续适用。所有设备状态为 **not requested**。

### 最终主机与制品结果

最终联合命令 **BUILD SUCCESSFUL / exit 0**：`1024 actionable tasks: 38 executed, 1 from cache, 985 up-to-date`。这是增量联合验证，不把缓存复用写成所有测试再次强制运行。普通根 test、detekt、Spotless、双渠道 Android lint、Debug APK、两渠道 AndroidTest APK 与 PRoot AndroidTest APK 构建全部通过。

| 精确报告目录 | 用例总数（含跳过） | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: |
| App Consumer Debug | 1039 | 0 | 4 |
| App Developer Debug | 1136 | 0 | 4 |
| PRoot Core | 151 | 0 | 0 |
| PRoot App | 12 | 0 | 0 |
| PRoot Client | 31 | 0 | 0 |
| PRoot IPC | 45 | 0 | 0 |

本轮新增 **23 项独立 JVM 回归 + 4 项 Python 打包回归 = 27 项主机检查，全部通过、无跳过**。JVM 分别为 GuestMedia 8、LinuxMediaTransfer 4、ProotProducedFiles 8、ProotArtifactReferences 3；其中 shell 参数测试是宿主 stub，不是 Android FFmpeg 实跑。App 两渠道共享用例不重复解释为独立场景，既有跳过项不计为通过。新增 **3 项真实 Room/收取/权限 AndroidTest 已编译但未执行**。

两渠道制品均不含退休媒体 Service/Dex；Advanced 实际 APK 内完整 8 个 ARM64 FFmpeg 文件与原候选逐文件 SHA 相同，ELF 16 KiB 静态检查通过。`scripts/check-all.sh --artifacts` 已通过，FFmpeg 载荷/来源/许可与 Standard 排除已加入原 `verify-integrated-runtime-apks.py`，并非仅临时检查。

| 实际 APK 对比 | 保留的接入前制品 bytes | 当前 bytes | 净变化 bytes |
| --- | ---: | ---: | ---: |
| Consumer | 86,965,130 | 86,965,130 | **0，SHA 也相同** |
| Developer | 142,313,354 | 145,879,387 | **+3,566,033（约 3.57 MB）** |

以上对照是保留的接入前 APK，不是新建两个 worktree 强制构建的对照实验；净变化含当前接线/打包差异，不应当作 FFmpeg 原生文件展开大小。完整 FFmpeg 原生载荷仍为 10,080,568 bytes；这两个指标均低于 25 MB 门槛。当前 Consumer SHA 为 `c52b837f3915a831c3ee7958841b7d7d068b37e8533bbf3f6e86e93596c2ce59`，Developer SHA 为 `20733ac4f165038bec3256c5b2ab93ad2028e4e82c9578ee535f049048e70d20`。

精确 XML 汇总、命令统计、APK/对照哈希与对照来源保存在 `build/hxa240/host-summary.json`，原 APK 分析位于 `apk-media-verification.json`。最终 `scripts/check-all.sh --source` 已通过（exit 0）：695 个 Markdown、223 个 HXA、35 个当前 ADR、多语言 1941 个资源键一致，秘密扫描通过；`git diff --check` 通过。该结果只覆盖实际执行的源码/文档与制品门禁，不包含设备或真实账号验收。

中间失败均保留其语义：Gradle applied script 的 AGP DSL/classpath不匹配改为原模块新 DSL 配置；新测试错误使用不合法 Job ID，修正测试数据并另保留非法 ID 拒绝用例；静态检查通过抽取流式复制/校验助手和调整测试方法命名收敛，未放宽公共阈值。发布前权限复查在首次联合通过后新增，当前表对应再次执行的最终源代码，而非第一次通过的旧代码。

## 本地提交范围（2026-10-02）

提交保留必要的独立 Lite/Plus/Ready 构建与回归源码、来源/许可证、固定 Ready-AV1 ZIP、HXA-240 生产接线、JVM/AndroidTest 及主机和制品验证入口。普通构建直接消费已固定的 vendor ZIP；复建时使用已记录的源码 SHA 和工具链。`build/` 中的 APK、RootFS、构建对象、原始日志和退休的媒体 Service 草稿不进入 Git；其他代理的评审与一次性改写脚本留在原工作区。具体提交身份以 Git 日志为准，本文保留上述实现阶段的精确验证结果。

## 仍需单独授权的设备验收

指定 ARM64 设备上验证 PRoot 内 Android/Bionic 动态加载、普通多输入滤镜/字幕/AV1 操作、系统字体、H.264/HEVC MediaCodec、旧 Job 停止与重开、多文件收取及原会话归属；真实 16 KiB 页设备和性能/热/内存结果均独立记录。新增真实 Room 产物回归本轮仅编译，不把 stub、APK 或历史 Plus 模拟器记录计为当前设备通过。

## 依据

- [HXA-240 任务](../../development/tasks/HXA-240.md)、[现有终端/Job ADR](../../adr/runtime/002-terminal-and-jobs.md)、[独立候选历史](ffmpeg-ready-preintegration-2026-10-01.md)。
- [Termux PRoot-Distro](https://github.com/termux/proot-distro) 的 Android 系统目录/Bionic 执行设计仅作参考，没有复制其 GPL 实现。新增包装器/映射为本项目实现；实际平台兼容以本候选设备证据为准。
- [FFmpeg CLI](https://ffmpeg.org/ffmpeg.html) 用于参数/流映射和编入能力的操作参考，不把 FFmpeg 全部上游能力等同于本裁剪包。

历史材料维护（2026-10-10）：本页提及的`verify-hxa240-apks.py`已退役；以上为当时执行记录，现行入口与删除来源见[整理摘要](historical-version-retirement-2026-10-10.md)。

## 2026-11-12：日常 API36 实际媒体任务验收

本轮只使用已核实的 `Helix_API_36` / `emulator-5554` / API36 arm64 Developer；没有访问真机、外部账号或下载媒体/可执行文件。使用当前 App 的手动 PTY 与真实 Dispatcher → detached Job → 原结果收取链，输入为测试现场生成的原始 YUV/PCM 与字幕。

### 新发现与修复

- 原 guest `/opt/helix-media` 路径不匹配 Android linker 的系统 namespace 路由。软件转码可用，但四种 H.264/HEVC NDK sync/async 探测均在 gralloc 初始化处 abort；平台日志明确显示不存在 sphal namespace、无法加载 mapper。相同已安装载荷在 app UID 下直接运行能输出 H.264，不能把这个差异归为硬件不支持。
- 将 guest 根改为 `/data/helix-media`，匹配平台已有 linker 路由；仅映射本 APK 原生库和临时命令目录，未绑定宿主 `/data`，未修改 linker 配置或扩大凭据映射。工具描述随新路径更新，不保留旧入口别名。固定 vendor ZIP 与编译组件未变。
- libass 没有自动系统 font provider。只给 `fontsdir=/system/fonts`，默认 Arial 缺失时可返回成功但不画出字幕。明确选用实际存在的 Roboto 后字幕出现。随包 USAGE 增加 `force_style=Fontname=...` / ASS family 与 drawtext `fontfile` 指引，设备断言比较解码后的像素，不能只看退出码或文件存在。
- MediaCodec 探测逐模式有界，且分别检查 ffprobe 的 codec、帧数及实际解码。H.264 sync 的 exit 0 空文件不计成功；HEVC sync 曾阻塞，原 150 秒测试 deadline 终止并完成终端清理，后续改为单模式 15 秒加 2 秒强制终止上限。这是条件能力探测，未将不可用模式静默替换成其他编码器。

### 验证覆盖与边界

软件用例包含 2 秒 MPEG-4/AAC、中文/空格路径、视频流复制与多输出音频抽取、FLAC 解码、crop/scale/transpose、系统 Roboto drawtext/字幕、缺失输入失败及未编入 HTTP。另一真实 detached Job 对 1,200,000 bytes PCM 输入执行显式 snapshot，产出 FLAC、ffprobe JSON 和 32,000 bytes 解码 PCM；两次收取同一结果只登记三份媒体产物，不重跑命令。

最终结果：`scripts/check-all.sh --debug` exit 0（390 项 Python 主机测试、Debug 单测、Spotless/detekt、双渠道 lint/APK/AndroidTest 编译与制品边界）；新增/既有 3 项媒体设备用例全部通过，无失败/跳过。API36 上 H.264 与 HEVC 的 NDK async 模式均产出 8 帧、codec 正确且可完整解码；H.264 sync 为无流空文件，HEVC sync 在 15+2 秒期限内被终止（137），这两项模式不计媒体能力通过。字体断言比较实际解码像素。

最终覆盖安装 Developer Debug SHA-256 `4c732c9b42088f48225743747d756ff05ce86a9804552432cf77f453dd73cac4`，AndroidTest SHA-256 `f571265ede346432f1c47ca86471e03b684c04571f79a78d47ec23e79f6434ef`；ADB 已分别核对安装字节。未清空日常模拟器数据；测试终端均 stop/settle，直接 app-UID 对照的临时文件已清理。旧终端须重新打开才使用更新后的 guest 映射。初次 Gradle socket 沙箱失败、格式门禁失败、字体无变化失败和未加单模式 deadline 的 HEVC timeout 保留为前置失败，不计入最终绿色结果。

原始日志、对照探测、源码/安装制品身份保存在 ignored `build/hxa240-media-2026-11-12/`。此次不包含真机/OEM、API29、完整编码器/色彩空间或真实模型选择命令的用户旅程，也不将 synthetic fixture 当成真实用户媒体。没有提交或推送。

## 2026-11-12 后续：修复同步 MediaCodec，而非绕到异步模式

依据 [Android MediaCodec 状态契约](https://developer.android.com/reference/android/media/MediaCodec)，encoder 收到 EOS 后再 `flush()` 的行为未定义；该回退只为 decoder 定义。[FFmpeg MediaCodec 文档](https://ffmpeg.org/ffmpeg-codecs.html#MediaCodec)区分 sync/async 与 global-header 行为，并说明 NDK 路径仍依赖平台服务。对照固定 9.0.2 源码 `mediacodec_generate_extradata` 可见：sync 为取得全局头先送 dummy frame/EOS，再 flush 同一个 encoder；async 跳过这段。MP4 设置 global-header 后进入该路径，外部 `extract_extradata` 没有阻止初始化探测。

在同一 API36 app UID、相同原始 8 帧输入下，原载荷 H.264→MP4 为 exit0/0帧，HEVC→MP4 超时137；同一同步模式换 MPEG-TS 则两者均为8帧。独立重编的窄补丁仅移除 dummy-EOS/encoder-flush 预取，保留真实输入、同步队列与现有 extradata BSF；两种 codec 的 MP4、MPEG-TS 对照均恢复8帧。没有把 `ndk_async=0` 偷换为1，也没有替换 codec。

补丁已进入 `ffmpeg-ready/recipe.json`，固定 patch SHA 与目标文件修改前/后 SHA。`source_patches.py` 验证原始来源不变，单独生成并校验补丁源码树；非目标路径、源码漂移、patch 漂移和输出 SHA 不符均拒绝。分发 ZIP 含 patch、helper、recipe 与 LGPL说明；应用准备器和独立 APK verifier 的精确身份锁均显式更新，未放宽字节/ABI/组件断言。原生载荷 10,080,568 → 10,080,024 bytes（减少544），仍低于25,000,000 bytes；ZIP 4,737,982 → 4,740,668 bytes，增加部分为可复建材料。

重建的 arm64 与 macOS companion 均来自同一固定来源/补丁。原40项host媒体回归、11项补丁与制品检查通过。新的固定ZIP SHA-256：`e438d5fdfcb427586d1040d0f70c948dc982923ac0e61f32b19a9355c8be0640`。原始对照、重建、完整门禁与设备结果在 ignored `build/hxa240-sync-2026-11-12/`；前一节的同步限制属于修复前观察，不代表新候选结论。


后续最终结算：完整 `scripts/check-all.sh --debug` exit0，398项主机脚本检查通过，Debug单测/静态检查/双渠道lint/APK/AndroidTest编译与独立制品边界通过。第一轮独立APK verifier因旧artifact identity拒绝新载荷，显式更新为新精确身份后整门重跑通过，未移除该校验。

- API36 / `Helix_API_36` / `emulator-5554` / arm64-v8a：严格版3项设备用例全通过，无跳过。四种 H.264/HEVC sync/async 都必须有正确codec、8帧及成功完整解码；旧的“条件失败也算测试通过”分支已移除。保留字体像素、软件媒体、1.2MB snapshot、多产物及原结果重复收取断言。
- API33 / `Helix_ProductionEval_API33_20261109` / `emulator-5582` / arm64-v8a：同一APK覆盖安装成功，但3项完整集成用例均在业务初始化前被 `PreviewResetRequest: Preview state requires explicit reset` 阻塞。没有清数据、reset或修改门禁。另用该已安装APK的独立app-UID媒体子进程完成四种模式：均8帧、96×64、codec正确、完整解码通过；此证据不等于App/PRoot/Dispatcher旅程通过。
- 两个模拟器报告的 AVC/HEVC encoder 均为软件组件（API36实际创建 `c2.android.avc.encoder` / `c2.android.hevc.encoder`，hardware=false）。验证的是MediaCodec API路径，不是真实手机硬件加速性能或OEM兼容性。
- 主机检查时可用空间8.6GiB，现有API33模板数据分区10G、RAM2G；为不额外占用存储及第三个模拟器的运行资源，本轮保留API33完整旅程边界而不新建实例。不访问已断线真机。

最终Developer Debug APK SHA-256 `b83bb96bf7671202edc505a4dbd37a0d0be5a14c6becd7dc8ea218811f991dd6`，AndroidTest SHA-256 `ec2a996ddafadf60ddb53ba054176e1af5daa5c21ac047a664bf3eb21c672fb0`；两个目标均由ADB核对安装字节。日常API36已更新，未清应用数据。实验性覆盖库与两台设备上的媒体层临时文件已清理，正式用例只消费APK内载荷。没有提交或推送；API33旧状态的完整旅程、API29、真机/OEM和真实模型媒体任务仍需单独结算。


## 2026-11-12 后续：API33 完整 App/PRoot 旅程闭环

在同一 `Helix_ProductionEval_API33_20261109` / `emulator-5582` / API33 arm64 上使用 Goal/工具网关重构后已通过完整 Debug 门禁的候选，覆盖安装并核对主/测试 APK 字节。先 force-stop，再将旧 `databases` 与 `files/execution-admission` 同文件系统移动至应用私有保留目录；旧字节仍可恢复，未执行清数据、卸载或永久删除，RootFS、工作区与凭据未改动。新实例按当前格式正常初始化，生产版本门禁与 reset 契约保持原样，不新增兼容迁移或测试旁路。

相同三项正式设备用例全部通过，耗时 22.732 秒，无失败或跳过：

- `ProotMediaWorkflowDeviceTest`：真实 PTY → PRoot → APK 内 Bionic 媒体载荷；软件音视频、多输出、裁剪转换、中文路径、字体实际像素、缺失输入及无 HTTP 断言通过。H.264/HEVC 的 `ndk_async=0/1` 四种模式各自严格验证 codec、8 帧及完整解码，不以替换异步模式或空文件判成功。
- `ProotMediaTerminalDeviceTest`：实际 FFmpeg/ffprobe 版本与中文路径音频转换，无 linker 错误。
- `ProotMediaJobDeviceTest`：正式 Dispatcher → detached Job，1,200,000 bytes snapshot、多份媒体产物、原任务重复收取不重放且保持字节。

此结果替代上一节 API33 完整旅程 blocked 的当前结论，历史失败保留。仍仅为模拟器软件 MediaCodec 组件路径，不代表真实硬件/OEM/API29、真实模型媒体旅程或最终 Release/R8 验收。没有修改产品源码、提交或推送。原始日志、JUnit、安装字节与可恢复状态位置记录在 ignored `build/hxa240-api33-clean-2026-11-12/`；复用候选的完整门禁来源为 `build/goal-gateway-refactor-2026-11-12/summary.json`，没有把复用门禁描述为本次重跑。

## 2026-11-13：0.0.11 OnePlus 真机接续

ONEPLUS_A6013/API34/ARM64/4 KiB 页在本轮明确授权下完成相同三项 App/PRoot 媒体检查；最终 API36 模拟器也重跑三项通过。硬编输出为 320×240/NV12，H.264/HEVC sync/async 四模式分别核对 8 帧和解码。96×64/yuv420p 的原 Qualcomm 配置失败、单变量对照及测试清理竞态均保留；该设备的输入限制不能外推所有 Qualcomm/OEM，也没有证明 320×240 是最低尺寸。生产媒体逻辑未为通过测试而缩放用户内容或切换 codec。

完整主机 Debug/Release R8 门禁及测试签名 R8 基础入口检查另有版本绑定证据，见 [0.0.11 本轮记录](v0.0.11-preparation-2026-11-13.md)。此前本页“未使用真机”等表述保留为各历史批次范围，不再代表当前缺口。API29、其他 OEM、16 KiB 页真机、真实模型媒体旅程及长期资源行为仍未由这些测试证明。
