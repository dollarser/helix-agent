# FFmpeg linker 与模拟器输入修复（2026-10-04）

## 范围

所有者报告终端 `ffmpeg` 启动时缺失 linker 配置与 `libicu.so`，并要求后续模拟器默认支持电脑键盘及中文输入。本轮明确授权模拟器验证，不涉及真机、真实账号或外部媒体。

依据：[终端与 Job ADR](../../adr/runtime/002-terminal-and-jobs.md)、[HXA-240](../../development/tasks/HXA-240.md)。这是既有 PRoot 媒体桥的修复，不新增执行域或重建 FFmpeg 载荷。

## 原因与修复

在原有 API36 arm64 模拟器、应用 UID 与已安装 RootFS 下复现原始错误：

```text
WARNING: linker: Warning: failed to find generated linker configuration from "/linkerconfig/ld.config.txt"
CANNOT LINK EXECUTABLE "/opt/helix-media/lib/libhelix_ffmpeg.so": library "libicu.so" not found: needed by /system/lib64/libharfbuzz_ng.so in namespace (default)
```

- 应用 UID 无法查询 `/linkerconfig` 目录，但可直接读取其中的 `ld.config.txt`；原目录检查遗漏了配置绑定。改为白名单内的精确文件绑定和存在性检查。
- guest `/opt/helix-media` 的程序位置未匹配 Android 配置中的系统执行路径。仅补配置文件仍缺 ICU；媒体包装脚本需要显式包含 ICU 所在 APEX 库路径。
- 固定 `LD_LIBRARY_PATH` 为媒体 APK 库目录、`/apex/com.android.i18n/lib64` 和 `/apex/com.android.runtime/lib64`。不继承外部库注入，不改父 shell 环境，不复制平台库，继续原样传递 argv。
- 对照探测：原行为失败；仅配置文件仍失败；仅 APEX 路径可执行但保留配置警告；两者同时修复后启动成功且无警告。

## 模拟器输入默认值

- 当前电脑的 31 个 Helix AVD 配置已设 `hw.keyboard=yes`；修改前配置备份在 ignored 构建证据目录。
- 两个 owned emulator runner 在启动前应用硬件键盘默认值，启动后设置 `show_ime_with_hard_keyboard=1`，共用 `scripts/emulator_input.py`。
- 当前日常模拟器 `Helix_API_36` / `emulator-5554` 的既有 Gboard 已添加“简体中文 / 拼音”，保留原英语键盘。通过键盘事件输入 `nihao`，观察到“你好”候选，空格提交后输入框实际文字为“你好”；不是直接注入 Unicode 文本冒充拼音验收。
- 仓库 AGENTS 记录后续启动的输入偏好：新 AVD 交付前须配置可用的中文输入法并验证候选与提交。硬件键盘开关不会自动给没有中文输入法的镜像安装语言包；新镜像仍需完成该准备步骤。

## 当前验证

| 检查 | 当前结果 |
| --- | --- |
| `:runtime:proot-core:test` | 152 通过，0 失败/跳过 |
| `:runtime:proot-app:testDebugUnitTest` | 13 通过，0 失败/跳过 |
| Python 输入默认值 / owned 验收测试 | 11 通过 |
| Developer Debug APK / AndroidTest APK | 构建通过 |
| Spotless / detekt / 文档 / `git diff --check` | 通过 |
| API36 arm64 Developer，实际手动 PTY 媒体用例 | passed：ffmpeg/ffprobe 版本、中文文件名 WAV→FLAC、ffprobe 核查 16 kHz，四步退出码 0、stderr 为空 |
| 同一设备终端输入回归 | passed：中文 InputConnection、Python REPL、旋转后原 shell 状态保留 |
| 日常 API36 Gboard 拼音 | passed：物理按键组词和中文提交 |

设备回归使用独立只读 AVD `Helix_Main_Verify_API36`、端口 5576；`OK (2 tests)`，模拟器已关闭，未保存测试数据。首次误用标准测试 runner，在测试开始前失败；更正为项目 runner 后又遇到旧镜像数据库 9→1 的历史不兼容。仅清空独立临时实例中的旧应用测试数据后重跑通过；日常模拟器数据没有清空。这些前置失败不能算通过，也不代表历史数据库升级问题已修复。

修复版已覆盖安装到日常 `emulator-5554` 并确认启动，安装前备份 APK；未清空应用数据。安装后 APK SHA-256：`e6ecdd70e7451a8d64f335e6e684d0808d85b46d39f6e5d06acaad501117c3e0`。新开终端会生成修正后的媒体入口；旧终端需结束后重开。

原始构建、对照探测、中文输入截图、设备测试与 APK 身份保存在 ignored `build/ffmpeg-input-2026-10-04/`。当前工作区还有其他未提交工作，未提交/推送此次修改。

边界：本轮没有验证 API29、真机、全部编码器、视频 MediaCodec、字幕字体或生产发布。中文输入的实际拼音验收在日常 API36；独立回归实例验证的是终端 InputConnection 与 UTF-8 链路，不能替代输入法组词验收。
