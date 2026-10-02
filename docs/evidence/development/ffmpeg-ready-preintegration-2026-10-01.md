# FFmpeg Ready：已完成内容、验证证据与接入交接（2026-10-01）

> 后续裁决（2026-10-02）：所有者已选择复用 Advanced 的 Bash/Linux Job，当前实现与验收看 [HXA-240 接入记录](hxa240-ffmpeg-proot-2026-10-02.md)。本页保留独立候选阶段的历史范围；“未接入”和两版共用建议不覆盖后续任务，也不将旧设备测试转换为当前桥接验收。

## 授权与边界

所有者要求补齐上轮推荐功能，复核并修复相关问题，可以准备接入 Helix，但**不得实际接入**；另有代理正在修复 Helix。本轮只新增 `scripts/debug/2026-10-01/ffmpeg-ready/` 与本记录，生成文件全部位于 ignored `build/ffmpeg-ready-2026-10-01/`。不修改 app/core/runtime/provider、Gradle、Manifest、ToolRegistry、ADR/HXA 当前实施顺序，不提交、不推送，不运行 ADB/模拟器/真机或真实账户。

源码仓库开始基线：`05e910039e03095d98649d96a9e64a3a71bcb078`；并行工作区并不干净。以下结果绑定独立实验配方、源码归档、工具链和产物哈希，不代表该工作区的其他修改已验收。

前序 [Lite](ffmpeg-lite-size-2026-10-01.md) 与 [Plus](ffmpeg-plus-size-2026-10-01.md) 记录继续作为历史，不覆盖原始失败或借用旧设备绿色。本轮唯一候选构建入口是 `ffmpeg-ready/build.py`；旧实验脚本只用于历史复现。新入口仅复用经 SHA 固定的旧依赖构建函数，外围增加独立源码快照、完整缓存身份及产物校验。

## 交付摘要与阅读顺序

**已完成的是独立媒体候选，不是 Helix 媒体功能上线。** Ready 和 Ready-AV1 已形成 Android ARM64 构建、macOS 功能回归、完整依赖测量与可校验候选包；Helix 接入、当前候选设备验收、最终 APK 增量仍未执行。`Ready` 是配置名，不表示已经达到生产发布条件。

| 交付项 | 截至本交接的状态 | 证据位置 |
| --- | --- | --- |
| 常见媒体输入和编辑补齐 | 已编入并完成主机功能回归 | 下方“实际裁剪范围”、`test_media.py` 和 `host-results-ready.json` |
| 可选 AV1 输入 | 已构建 dav1d 解码方案并验证主机 8-bit/10-bit 样例 | `ready-av1` 配方与对应测试记录；没有 AV1 编码器 |
| 构建、缓存、来源、测试、打包问题 | 已修补独立候选工具链 | 下方“复核与修补”、`test_contract.py` |
| 参数、颜色、基础输出检查样例 | 已实现独立纯函数 | `contract.py`；没有生产调用方，不提供授权或完整结果验证 |
| Android ARM64 载荷和交付 ZIP | 已生成并记录精确身份 | 下方体积表、候选包表及 `manifest-*.json` |
| 新候选 Android 实际执行、Helix 接入 | **未执行** | `device_status=not_requested`、`apk_integration=false` |

接手时先读本页的授权边界、完成范围和遗留风险，再读配方及纯函数接口，最后按复现命令核对当前候选。Lite、Plus 仅用于追溯原实验、功能取舍和 MediaCodec 反例，不作为新候选的设备验收。

本页于 2026-10-01 按所有者“将完成内容与交接内容写入文档”的要求补充。**本次文档核对时仓库 HEAD 为 `4d449fb93f8c7aeb77b93300fd719a69663ebd94`，独立实验开始基线仍是上方 `05e91003`，二者不能混用。** 本次只读复核已有候选包、逐文件哈希及历史测试代码绑定，没有重新编译、重跑媒体测试或执行设备任务；其他代理的生产修改不属于本交接的验收结果。

## 实际裁剪范围

| 分组 | 本次内容 | 不宣称的能力 |
| --- | --- | --- |
| 动画 WebP | `webp_anim` 解码器/解封装器；完整回读、可变时长、局部帧与清除；现有动画输出保留 | 不能只靠 ANMF 计数宣称逐帧解码正确；无通用 ICC/色彩管理验收 |
| 遮挡/处理 | `drawbox`、`gblur`、`pixelize`，配合既有裁剪/叠加 | 不包含人脸检测、自动目标跟踪；模糊不等于可靠隐私擦除 |
| 选帧/总览 | `select`、`tile` | 没有语义关键帧检测或完整视频理解 |
| 时长补齐/调色 | `tpad`、`apad`、`hue`、`lutrgb`、`lutyuv` | 不包含专业 HDR 调色、去抖、补帧或整个滤镜生态 |
| 录音输入 | AMR-NB/WB、ALAC、CAF/AIFF 与必要大端 PCM、G.711 A-law/μ-law | 不添加 AMR 编码；不支持专有/加密语音包，不包含 ASR |
| AV1 可选 | `ready-av1` 另加 dav1d 1.5.4 解码、AV1/OBU/IVF 输入 | 不含 AV1 编码，AVIF/透明 AVIF 不因解码器存在而自动交付 |
| HDR 准备 | 读取传递函数/原色/范围/位深/相关 side data，明确处理 HDR、SDR、冲突和未知；不支持的像素操作拒绝 | 未实现 HDR→SDR 色调映射；10-bit 本身不代表 HDR |

网络仍只有 `file/pipe/fd`。未启用 FFmpeg GPL/nonfree/version3、x264/x265、设备采集。全部请求组件都逐个检查实际 `CONFIG_*`，不是看到 configure 参数就算成功。

## 体积口径

阈值为 25,000,000 bytes。两套都是 Android API 29 / arm64-v8a，使用 NDK 28.2.13676358；包含全部随包依赖代码与 FFmpeg/FFprobe CLI，Android 系统库和系统字体不重复打包。外部依赖静态 PIC 合入共享 FFmpeg 库，测量通过 DT_NEEDED 递归检查，没有漏算非系统动态库。

| 候选 | 完整运行载荷 bytes | MB | 不压缩 16 KiB 对齐 bytes | 纯载荷 ZIP bytes |
| --- | ---: | ---: | ---: | ---: |
| Ready | 9,181,408 | 9.181 | 9,236,813 | 4,218,970 |
| Ready + AV1 解码 | 10,080,568 | 10.081 | 10,138,045 | 4,652,260 |

AV1 可选增量为 899,160 bytes，约 0.899 MB。两者均低于阈值。以上不是最终 Helix APK 增量，多 ABI、JNI/进程封装、合法字体资源及应用分发开销需要后续实际接入后测量。没有把复建源码/静态中间库/编译工具的大小算成手机运行体积，也没有把必要运行库排除。

## 复核与修补

1. **缓存身份不完整**：旧依赖标记只有源码版本和选项，没有绑定工具链、构建脚本和实际安装结果。新缓存键包含配方、构建函数、工具版本/哈希、NDK 身份、归档与传递依赖；每次验证安装目录完整清单。失配时清理本实验的旧对象与安装树，不复用半成品。
2. **归档正确不等于解压源码正确**：新入口从固定归档建立独立源码快照，记录并复验所有文件哈希和内部链接；检测到源码变化拒绝编译。归档 SHA 校验、HTTPS/已公开 SHA/历史 GPG 验证分别记账，不虚构全部依赖经过签名验证。
3. **请求启用不等于实际启用**：对配方每个解码器、封装器、滤镜、parser、协议逐项验宏；检查禁止项与协议精确集合。Python `-O` 不会跳过这些检查。
4. **旧测试可能配给新包**：旧打包器主要检查“17 通过”，没有绑定对应二进制。新打包器核验 host build identity、安装文件哈希、配方、测试及被测检查代码、完整预定用例集合。旧设备记录不能为新 Android 产物赋予通过状态；测试期间源码变化也拒绝出具当前验收。
5. **打包失败可能破坏旧候选**：ZIP 用同目录临时文件完成后刷盘/替换；失败保留旧文件。固定条目顺序/时间戳/权限，重复输入产生同一 ZIP。打包后重新读取 ZIP 验证精确清单与哈希，不从 staging 通配符混入旧文件。
6. **超时只终止父进程**：构建/测试命令建立独立的宿主进程组，超时终止本次组并等待；超时结果不当成功。此保护不等于已经解决 Android OEM/内核不可中断状态。
7. **空输出/非法数值**：准备契约拒绝无流的 MP4、无解码帧、NaN/Infinity/布尔数值、超长或极端指数、非法时间区间与超预算抽帧；码率估算不冒充目标大小已满足，精确裁剪要求实际后置验证。
8. **HDR 测试样例真实性**：首轮生成器的通用色彩选项未写入完整 transfer/primaries，完整版和裁剪版 FFprobe 都缺失字段。改为显式 x265 VUI 参数，并先用独立 FFprobe 确认样例标签，再测候选；未修改期望把未知当作 HDR/SDR，保留首轮失败记录。

## 交接文件与证据定位

以下源码链接相对本页；所有 `build/` 路径相对项目根。生成目录被 Git 忽略，换机器、换工作树或执行清理后不保证仍存在，不能仅凭仓库代码存在就认为候选二进制和原始日志已随 Git 转移。

| 文件 | 具体职责 | 接手注意事项 |
| --- | --- | --- |
| [`recipe.json`](../../../scripts/debug/2026-10-01/ffmpeg-ready/recipe.json) | 来源版本/URL/SHA、组件白名单、目标工具链、两套配置 | 功能或来源变更后必须重建并重验，不能只修改文档中的支持列表 |
| [`build.py`](../../../scripts/debug/2026-10-01/ffmpeg-ready/build.py) | 源码准备、独立源码快照、依赖和 FFmpeg 构建、缓存身份 | 当前入口支持 `host` / `arm64-v8a`，`ready` / `ready-av1`；不是全平台构建器 |
| [`kit.py`](../../../scripts/debug/2026-10-01/ffmpeg-ready/kit.py) | 原子记录、进程组超时、文件清单、身份和实际组件核验 | 这些是构建/测试宿主保护，不是 Android Runtime 故障处理 |
| [`contract.py`](../../../scripts/debug/2026-10-01/ffmpeg-ready/contract.py) | 参数、词法路径、时间范围、码率初值、色彩和基本结果检查 | 只是后续移植参考，不应原样当作完整权限或媒体引擎 |
| [`test_contract.py`](../../../scripts/debug/2026-10-01/ffmpeg-ready/test_contract.py) | 非法输入、证据错绑、缓存、超时和打包失败反例 | 保留所有反例；`python3 -O` 仍应执行关键检查 |
| [`test_media.py`](../../../scripts/debug/2026-10-01/ffmpeg-ready/test_media.py) | 合成媒体、像素/帧/样本/时间戳回归、AV1 对比 | 完整 FFmpeg 只生成部分输入；被验证的是独立 companion，不是 Android 执行 |
| [`artifacts.py`](../../../scripts/debug/2026-10-01/ffmpeg-ready/artifacts.py) | 实际载荷闭包测量、ELF/ZIP 检查、证据绑定和候选打包 | 拒绝旧测试、变更后的配方和错绑的二进制；不要跳过校验强行打包 |
| [`NOTICE.txt`](../../../scripts/debug/2026-10-01/ffmpeg-ready/NOTICE.txt) | 配置、平台、许可和未验收边界声明 | 继续随候选分发；不得删除未接入、未设备验收和无字体说明 |
| [`build-ffmpeg-plus.py`](../../../scripts/debug/2026-10-01/build-ffmpeg-plus.py) | Ready 复用的外部依赖构建函数 | `build.py` 固定其 SHA；虽位于旧实验目录仍是必要依赖，不能当垃圾脚本删除 |
| [`with-host-slot.py`](../../../scripts/with-host-slot.py) | 与其他工作树/代理共用的重型构建互斥 | 重型构建和测试保留该入口，不并行抢占或终止他人的验证 |

| 生成目录内的路径 | 作用 |
| --- | --- |
| `build/ffmpeg-ready-2026-10-01/downloads/`、`source/`、`source-proof/` | 固定源码归档、独立解压结果及校验记录 |
| `proof/<profile>-<target>.json`、`proof/deps-<target>.json` | 构建身份、工具链与安装文件清单；位于同一实验根目录下 |
| `obj/<profile>/<target>/configure-argv.json`、`config_components.h`、`capabilities.json` | 实际编译选项与组件事实，不能用模型猜测代替 |
| `manifest-ready.json`、`manifest-ready-av1.json` | Android 载荷身份、每个运行文件大小/哈希和静态检查 |
| `host-results-<profile>.json`、`tests/<profile>/<run-id>/results.json` | 当前指针与每次不可混淆的历史测试记录，包括失败样例 |
| `package-<profile>.json`、`packages/*.zip` | 最终 ZIP 的大小、SHA 及实际文件；包内 `MANIFEST.json` 校验精确条目 |

## 接入准备交接：仅规划，不执行

### 产物与目标平台

`manifest-ready.json` / `manifest-ready-av1.json` 提供候选身份、每个文件 SHA、ABI/API、编入组件与独立设备状态。候选 ZIP 的 `lib/arm64-v8a` 与 `tools/arm64-v8a` 是待消费载荷，**不是 AAR/已安装插件**。它们链接 Android Bionic，不能当作 Alpine/musl 包直接复制到 PRoot。

正式接入前必须决定采用 FFmpeg 库接口还是受控 CLI 封装，验证 APK 原生目录、加载/执行限制、16 KiB、JNI/资源生命周期与实际增量。不得从此前 `/data/local/tmp` 的 ADB 运行推导正式 App 已能加载/执行。不得把 FFmpeg `main` 直接塞入 UI 进程；沿用既有执行生命周期接口，具体进程/权限方案由后续接入任务裁决，不在本任务创建新执行域。

### 模型、宿主和执行器职责

模型选择目标、编辑策略和参数；宿主提供真实能力、授权文件、资源预算、取消与结果反馈。不要创建第二个媒体任务引擎，不把全部 FFmpeg 参数拆成大量工具。

准备的 `contract.py` 是纯函数样例，当前没有被任何生产模块导入。它不解析 Android URI、不授予权限、不发出外部调用。未来入口可复用已有工具发现、Scope、Dispatcher、执行 owner、产物、恢复机制；显示“编入”“设备可用”“本配置通过验证”为三种事实。

| 已有准备函数 | 已实现边界 | 后续宿主仍须补齐 |
| --- | --- | --- |
| `number` / `positive_int` | 有限数值、整型、范围与极端指数校验 | 具体设备/编码器参数范围与资源准入 |
| `relative_reference` | 相对路径词法检查，保留中文名称 | 实际 scope 解析、链接逃逸、PFD/URI 授权及输入身份冻结 |
| `trim_request` | 明确 `accurate_reencode` / `fast_stream_copy`，验证时间范围 | 编码器选择、VFR/关键帧语义、实际输出切点与音画同步 |
| `target_video_bitrate` | 根据目标字节、时长、音频和封装预留计算初值 | 支持码率模式、质量权衡、输出大小检查；额外重试须符合原预算 |
| `color_facts` / `guard_color` | 根据传递函数及相关元数据区分 SDR/HDR/未知/冲突 | 逐帧动态元数据、完整色彩变换、设备能力与最终输出验证 |
| `bounded_frame_plan` | 输入时间点、帧数和累计像素预算 | 实际解码峰值、采样时间误差、运行中取消与产物引用 |
| `verify_result` | 正字节数、存在期望流、视频尺寸/解码帧有效、可选字节上限 | 编码名称、精确尺寸/时长、完整内容、音频样本、字幕、同步与原子发布 |

上述保护只有调用这些函数时才生效；**没有修改 FFmpeg 二进制让任意 CLI 命令自动遵守它们，也没有给 Helix 增加对应的生产拦截器。**

### 文件、字体与隐含读取

只有宿主验证过的 scope/Artifact/文件描述符进入后端；词法路径检查不提供真实文件沙箱。非可 seek 的 SAF/PFD 输入需决定是否在授权范围内暂存，说明复制开销与失败清理。输入身份与预检/执行绑定，不能查完元数据后静默换文件。

`concat` 清单、字幕、`drawtext` 的 textfile/fontfile 等能打开其他本地文件；禁用网络不等于阻止越界文件读取。未来模板应由结构化参数生成，禁止未经验证的 filtergraph/path 拼接；任意脚本仍服从已有代码执行权限。字体由系统发现或用户合法选择，不硬编码某个 OEM 路径，不随包字体。

### 输出与任务后置条件

先写私有临时产物，核查目标流、解码帧、尺寸/时长、音画同步、最终字节及哈希后再走既有产物发布流程。退出码 0、文件存在或模型说完成都不够。默认另存；替换既有目标必须走既有权限/冲突机制。

`target_video_bitrate` 仅给出码率初值；不自动消费额外预算重试，不承诺 MediaCodec 严格达到字节目标。快速无损裁剪标明关键帧/时间戳限制，精确裁剪需要重编码与输出时刻检查。遮挡图像也不自动清除音频、字幕或元数据。

### HDR 和运行时能力

当前只识别并拒绝不支持的 HDR/未知色彩像素操作；保留观察和不改像素的对账路径。动态 HDR/逐帧变化、ICC/Ultra HDR gain map 并未因单个 stream 元数据检测而全部支持。不能用“把 HDR 标签改成 SDR”代替转换。

MediaCodec 编入包装器不等于设备有对应编码器。此前异步配方是历史候选；正式接入还需按 codec 名称/API/分辨率/位深/像素格式/进程条件验证。既不伪装同步路径已修复，也不在失败时静默更换输出格式。

### 后续接入阶段与退出条件

以下是交接时准备的**条件性验收提纲**，不是现在的执行任务，也不改变 `docs/development/status.md` 的 HXA 排序。每一阶段开始前先确认所有者当次授权，并检查其他代理改动；生产路径以接手时的实际代码/CodeGraph 为准，不能照抄重构前类名和接线。

| 阶段 | 后续工作 | 该阶段退出条件 |
| --- | --- | --- |
| 0．候选复核 | 选定 Ready 或 Ready-AV1，核对包与源码身份；确认目标渠道、ABI、执行封装及许可处理 | 原始证据可追溯，所有必需配方/依赖齐全；不存在“文档换版本、包未换”的错配 |
| 1．加载与能力 | 按当时现有 Runtime 接口接入库/JNI 或受控 CLI；建立只读能力查询 | 正式 App 进程条件下能够加载；编译清单和实测设备编码能力分开，不因打开页面自动编码 |
| 2．资源与参数 | 接入 Scope/Artifact、SAF/PFD、不可 seek 输入暂存、附属文件和字体 | 中文路径、授权撤销、链接逃逸、清单/字幕/fontfile 隐含读取均有测试；没有以禁网冒充文件隔离 |
| 3．执行生命周期 | 复用任务身份、共享执行占用、取消、进度、结果收取和原任务恢复 | Stop 在各阶段可投递；取消请求不冒充退出，未知状态不提前释放占用，重开不重放原动作 |
| 4．验证与发布 | 临时输出、媒体后置检查、默认另存及受控覆盖 | 无流 MP4、半成品、错误格式/时长/字节和发布冲突不能记为成功；用户原文件不被破坏 |
| 5．设备与分发 | 获得明确授权后完成指定设备范围和制品测量 | 当前二进制的设备日志、型号/API/页大小、准确测试结果齐全；实际 APK 增量和完整依赖通过 25 MB 条件复核 |

设备验收矩阵至少覆盖下列内容；范围和素材须在当次授权中确定，不据本表自行启动设备：

| 检查组 | 必须观察的实际结果 |
| --- | --- |
| 新增编辑与输入 | 动画 WebP 局部帧/透明度/VFR；遮挡像素；选帧/总览；尾帧/静音；AMR/ALAC/CAF/AIFF/G.711 回读；选择 AV1 配置时补 8-bit/10-bit |
| 编码与字体 | 对实际 codec 名称、像素格式、尺寸和输入组合测 H.264/HEVC 输出；验证缺字体、系统字体回退及字幕时间轴；不把编码器存在视为支持任意参数 |
| 时间与结果 | 精确/快速裁剪、旋转方向、时长、A/V 同步、尺寸、音轨和字幕保留/删除、实际大小；隐私遮挡不遗漏音频/字幕/元数据 |
| 取消与恢复 | 提交前取消、执行中 Stop、完成/取消竞争、宿主或执行器死亡与重开；查询原身份，不重复执行，不将半成品发布为完成 |
| 文件与资源故障 | SAF 撤销、输入变化、输出已存在、不可 seek 管道、磁盘不足、有限长视频和峰值内存；错误反馈明确且清理仅作用于本任务 |
| 平台与制品 | 最低支持 API 与指定当前 API、真实 16 KiB 页设备、计划支持的 ABI/OEM；比较同基线/同渠道/同压缩配置的 APK，记录安装后体积与运行内存为不同指标 |

### 并行协作与转交边界

当前可转交的是此页、独立源码目录、固定依赖构建脚本，以及两份校验过的候选包/原始证据。源码和文档仍可能未跟踪，`build/` 为 ignored；应由获授权的集成人员按精确路径归档或提交，**本次不执行 Git 提交/推送，也不 `git add .` 混入其他代理工作**。

准备工作不会自动授权生产接线。收到后续接入指令后，先核对最新工作区和执行/权限/产物接口，再消费本候选；不需要重复建设媒体编解码库，也不能声称纯 Python 样例已经替代 Helix 服务。提交、发布、真实账号和设备验证各自遵守当次授权。

## 复现

已验证的宿主是 macOS，Android 目标为 API 29 / ARM64。准备环境需具备支持安全 `tarfile` data filter 的 Python 与 venv、clang/make、curl、CMake、pkg-config，以及项目固定 NDK 28.2.13676358 和 ZIP 对齐工具；Meson 1.9.1/Ninja 1.13.0 由 `prepare` 放入本实验 venv。未验收 Windows/Linux 或 x86_64，不能将现有入口描述为跨平台通用构建。

所有命令从项目根目录执行。接手已有候选时先检查下方两条 SHA 命令和包内 `MANIFEST.json`，只需要文档/产物核对时不必重新构建。需要复建时保留下面完整顺序及 `build-ffmpeg-plus.py` 固定依赖；不要为绕过 SHA 不一致而更新常量。

```bash
shasum -a 256 build/ffmpeg-ready-2026-10-01/packages/ffmpeg-9.0.2-ready-arm64-v8a.zip
shasum -a 256 build/ffmpeg-ready-2026-10-01/packages/ffmpeg-9.0.2-ready-av1-arm64-v8a.zip
```

```bash
python3 scripts/debug/2026-10-01/ffmpeg-ready/build.py prepare
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/ffmpeg-ready/build.py build --target arm64-v8a --profile ready
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/ffmpeg-ready/build.py build --target arm64-v8a --profile ready-av1
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/ffmpeg-ready/build.py build --target host --profile ready
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/ffmpeg-ready/build.py build --target host --profile ready-av1
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/ffmpeg-ready/test_media.py --profile ready
python3 scripts/with-host-slot.py -- python3 scripts/debug/2026-10-01/ffmpeg-ready/test_media.py --profile ready-av1
python3 -O scripts/debug/2026-10-01/ffmpeg-ready/test_contract.py
python3 scripts/debug/2026-10-01/ffmpeg-ready/artifacts.py package --profile ready
python3 scripts/debug/2026-10-01/ffmpeg-ready/artifacts.py package --profile ready-av1
```

配方和工具链固定是可复建基础，不宣称不同 NDK/宿主路径下天然产生相同字节。所有复建材料放在用户项目中；生产公开分发仍需核对各依赖实际链接、对应源码、替换/重链接及归属义务。没有分享或复制系统字体。

## 已完成构建轮的验证记录

最终结果以本实验的 `host-results-ready.json`、`host-results-ready-av1.json`、`manifest-*.json`、`package-*.json` 和逐次 `tests/<profile>/<run-id>/results.json` 为准。历史 Plus 的 17 项 Android 用例不计入本轮。

| 验证 | 当前真实结果 |
| --- | --- |
| ARM64 Ready / Ready-AV1 | 两套 configure/make/install 通过；全部请求组件验宏、动态依赖与 ELF/ZIP 16 KiB 静态检查通过 |
| macOS 同配置 companion | 两套均实际编译；Android 专用 MediaCodec 不计入 host 功能验证 |
| Ready 功能/约束测试 | **39 项通过，0 失败，0 跳过**；最新目录 `tests/ready/85c77508f4404c95bd35f61d3b805d81` |
| Ready-AV1 功能/约束测试 | **40 项通过，0 失败，0 跳过**；最新目录 `tests/ready-av1/6bd2c2c3abbd4caaa82cbd6761056f7e`，包括 8-bit/10-bit AV1 生成样例的候选解码与抽帧 |
| Python `-O` 独立边界回归 | **17 项通过**；这是两套 suite 中的共享测试子集，不另算17项独立功能 |
| 六个新 Python 脚本语法 | `python3 -m py_compile` 通过 |
| 文档检查 | `./scripts/check-docs.sh` 通过；执行时681个 Markdown、218个 HXA，仅证明文档门禁，不是全量 Helix 代码验收 |
| 两套候选打包 | 精确二进制/测试身份校验通过；写出后逐条核对清单、长度、哈希与无字体条件 |
| Android/MediaCodec 当前设备执行 | **not requested**，不沿用 Plus 17项设备绿色 |
| Helix 生产接入与实际 APK 增量 | **not performed** |

39/40 项共享大部分定义，按测试定义去重为 **40 项**，不能相加成79项不同功能。AMR 使用合成合法编码帧做解码链路检查，不是自然语音质量评测；AV1/HDR 等输入由宿主既有完整 FFmpeg 仅生成素材，解码/探测使用本轮 companion。真实 Android 性能、OEM、动态 HDR、长视频和加载生命周期仍需未来单独授权验收。

最终候选包（位于 `build/ffmpeg-ready-2026-10-01/packages/`）：

| 文件 | bytes | SHA-256 |
| --- | ---: | --- |
| `ffmpeg-9.0.2-ready-arm64-v8a.zip` | 4,303,760 | `1ccd4f438b7dde531b29d355d89f81dcfe84c5adfdcab7737402454c5d58c73b` |
| `ffmpeg-9.0.2-ready-av1-arm64-v8a.zip` | 4,737,982 | `7fc77f64f27bcd56e1283b9e1233cc743da7b9ed3015accecd7ad7366bef2185` |

包内附配方、许可、脚本、编译组件清单与明确绑定的 host 结果；没有源归档、字体或 APK。源归档、完整源码和构建对象保存在实验目录，可按固定来源复建。工作区其他代理的验证结果与本候选无关，本轮不宣称全量 Helix 门禁已通过。

## 本次文档交接的只读核对（2026-10-01）

在 `4d449fb9` 工作区核对两套候选：实际 ZIP 字节数/SHA 与上表一致；包内条目无重复且与 `MANIFEST.json` 精确匹配，每项长度与哈希通过；当前 staging 的每个运行文件与载荷清单一致，未发现字体文件。历史测试的构建身份与 host proof 一致，Android 载荷身份与 Android proof 一致；测试、契约、配方和打包校验代码的 SHA 与结果绑定相符。

本次仅将已完成工作及条件性接入验收整理到同一文档，并在开发证据库入口增加链接。**没有重跑上表 39/40 项媒体测试、没有重编译或重新打包，因此候选包 SHA 不变；没有运行 ADB/设备或接入生产。** 历史 681 Markdown / 218 HXA 是构建轮的文档检查数量，不是本次工作区的数量或全量代码通过证明。

## 外部依据

- [FFmpeg 滤镜](https://ffmpeg.org/ffmpeg-filters.html)：选帧/拼图/遮挡/像素化及音视频处理。
- [Google WebP RIFF 规范](https://developers.google.com/speed/webp/docs/riff_container)：ANMF、时间、局部帧、混合与 disposal；测试使用自生成像素，不使用用户素材。
- [VideoLAN dav1d](https://images.videolan.org/projects/dav1d.html) 与 [固定 1.5.4 源码目录](https://download.videolan.org/pub/videolan/dav1d/1.5.4/)：本轮只增加 AV1 解码，核验官方公开 SHA256。
- [Android HDR/SDR](https://developer.android.com/media/media3/transformer/tone-mapping)：解释 HDR 为 SDR 与真正 tone mapping 的边界。
