# 离线语音输入：2026-10-19 有界验收

当前状态：本轮实现及主机/指定设备有界验收完成；真实用户口述体验待 Owner 主动进行。范围仅 ASR 输入草稿与手机模型取舍；[设计](../../adr/platform/004-offline-voice-input.md)。保持 0.0.6 / code 1，未推送/发布，无真实账号或云端 ASR 调用，无用户环境录音。

## 预检与隔离

起点 `113c253c`，工作区干净。原实现是 `SpeechRecognitionLauncher` → 系统 Activity → composer 草稿，仅最终文字，没有本地流式引擎。当前按钮改用 `OfflineVoiceDialog` → `VoiceCapture` → `StreamingAsrFactory`，不调用该系统路径；现有系统语音诊断页保留。

日常 `Helix_API_36` ARM64 模拟器原前台为 Advanced 终端；OnePlus 6T API34 原前台为 Advanced 主界面，Standard 仍是旧 0.0.5。为避免覆盖安装/数据库重建/关闭未知会话，使用 `-Phelix.asrValidation=true` 构建独立 Debug 包 `com.helix.agent.asrvalidation`，只操作本轮创建的验证包与测试包。原两渠道安装和用户数据不改；不重启现有模拟器，保留 IME/键盘配置。模拟器生产录音接口验证先执行 `emu avd hostmicoff`，不路由宿主真实麦克风。OnePlus 不请求麦克风权限、不打开 AudioRecord。

## 初轮模型结果（公开 WAV，非真实麦克风）

实际命令：`scripts/local/debug/2026-10-19/run-asr-device.sh`，仅调用 `OfflineAsrDeviceTest` 和 `AsrModelComparisonDeviceTest`；两台均通过 4 项。日志保存在忽略的 `build/asr/emulator-asr-first.log` / `oneplus-asr-first.log`。原生库为本轮 ASR-only 初轮构建；后续 MPL-only 构建和最终候选需另记复测结果，不把初轮结果直接移给最终产物。

三段相同公开夹具分别为中文 5.611 秒、中文 5.153 秒、中英混合 10.053 秒。每 100 ms PCM 推送一次；以尽快处理模式喂入，不等待墙钟，所以吞吐/第一字对应音频位置并非真实麦克风端到端延迟。比较循环采样 `Debug.getPss()`，耗时包含该采样开销；PSS 是完整测试进程的近似观测，不是模型独立峰值内存。顺序运行及 allocator 缓存也影响该值。

| 设备/模型 | 加载观测 | 三段处理耗时 | 三段第一字音频位置 | 测试进程 PSS 最大观测 |
| --- | --- | --- | --- | --- |
| API36 ARM64 / 双语小模型 | 1469 ms | 895 / 1023 / 1645 ms | 800 / 800 / 1100 ms | 229381 KiB |
| API36 ARM64 / 中文 CTC INT8 | 2190 ms | 925 / 694 / 1611 ms | 800 / 800 / 1500 ms | 213202 KiB |
| OnePlus 6T API34 / 双语小模型 | 1929 ms | 1346 / 1270 / 2412 ms | 800 / 800 / 1100 ms | 245525 KiB |
| OnePlus 6T API34 / 中文 CTC INT8 | 2983 ms | 1283 / 1151 / 2240 ms | 800 / 800 / 1500 ms | 234556 KiB |

双语小模型独立单次识别测试：OnePlus 加载 1978 ms，10.053 秒音频首次非空解码处理开销 135 ms，总计 3120 ms、13 次文字变化；模拟器加载 2833 ms、首次非空解码 284 ms、总计 3577 ms、14 次变化。这里首次解码开销也不是“开口后 135 ms 出字”。

双语对纯中文样例给出“对我做了介绍我想说的是大家如果对我的研究感兴趣”“重点呢想谈三个问题首先就是这一轮全球金融动荡的表现”；CTC 保留了更多“那么/呢”等口语词。双语混合结果为“昨天是 MONDAY TODAY IS THEY AFTER TOMORROW是星期三”，已含英文误识别；CTC 则出现“昨天是妈的<unk>…礼拜二…是星期三”，有明显未知词和语义退化。没有用三段样例计算或宣称总体 CER/WER。当前保留双语默认，CTC 只在 test APK 中比较，不随正式 APK 额外预装。

初轮模拟器另通过 2 项注入 PCM 的 UI 测试：流式预览→结束→编辑→加入原草稿；取消→原草稿不变、引擎释放。此时 UI 使用假的识别文本，证明交互/状态，不证明真实模型消费。

## 最终主机与设备结果

最终源码、构建参数、ASR-only 依赖与 APK 的操作性哈希保存在忽略的 `build/asr/evidence/provenance.json`；两台安装后的 `base.apk` 哈希均与 `build/asr/candidate/asr-validation.apk` 相符。对应成功构建是 `device-candidate-build.log`（4 秒），不是任何失败构建残留 APK。R8 只完成主机构建和归属/资源检查，未安装到设备。正常 Standard Debug 另存 `build/asr/deliverables/Helix-Standard-debug.apk`，Advanced Debug 和两渠道 unsigned R8 在正常 `app/build/outputs/apk/` 目录；不发布、不推送。

| 检查 | 实际结果 |
| --- | --- |
| `./scripts/check-all.sh --source` | 279 项主机测试通过；935 份文档、38 个 ADR、三语言 2350 key 一致与 secret scan 通过。负面夹具日志中的 FAIL/INCONCLUSIVE 是被测试的输入，不是真实设备失败 |
| `gradlew spotlessCheck detekt :app:lintConsumerDebug :app:lintDeveloperDebug :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleConsumerRelease :app:assembleDeveloperRelease --offline` | 最终冻结输入一轮完整成功，1 分 40 秒，见 `build/asr/frozen-host-gate.log`。Consumer 1321 项、Developer 1456 项，零失败/错误；各 4 项既有外部输入检查因未提供当前输入跳过 |
| `scripts/check-lockfiles.sh` / `verify-variant-boundaries.sh` | 42 份依赖锁未变化；两渠道边界通过 |
| `scripts/build-asr-assets.py --verify` / 主机归属与 ELF 检查 | 固定输入、生成物、18 项许可/来源材料通过；两 ABI JNI 不含 TTS JNI/eSpeak 合成符号，JNI/ORT 装载段为 16 KiB 对齐；这不是 16 KiB 设备实测 |
| API36 ARM64 最终候选 | 7 项公开音频/控制器 + 4 项注入 UI + 3 项实际权限/录音/焦点测试，共 14 项通过，见 `build/asr/emulator-closeout.log` |
| OnePlus 6T API34 最终候选 | 7 项公开音频/控制器测试通过，见 `build/asr/oneplus-closeout.log`；麦克风权限仍为 denied，未执行该机 AudioRecord/真实口述 |

边界检查覆盖模型缺失不录音、加载中取消、迟到解码不能覆写终态、错误录音读取、120 秒 PCM 样本上限、取消/切会话保持两份草稿、后台停止、编辑后显式加入草稿、真实系统权限拒绝/允许、静音录音结束与真实系统焦点丢失释放。模拟器实际 AudioRecord 检查全程先关闭宿主麦克风路由，只检查静音、结束、拒绝、焦点和释放，不称作真实麦克风语音识别。最终 `RECORD_AUDIO` AppOp 有结束时长而无 running；仅本轮 instrumentation 包/临时 XML 已清理，独立验证应用留下用于 Owner 自主体验。两个设备最终前台分别回到原 Advanced 终端/主界面，原有任务未被关闭，原两渠道安装未覆盖。

修复与复测：首轮源门禁发现新增 key 未同步 `values-en`，lint 另要求使用 `toUri()`；已修复，未降低 lint/测试规则。R8/lint 并发占用主机期间，一次模拟器“允许后等待监听”在 10 秒模型加载等待处超时；日志有明显掉帧/GC，未将该次记为通过。冻结主机验收结束后无构建争用地重跑完整设备序列，该方法 6.588 秒通过，另外真实焦点丢失检查通过。没有延长/跳过该测试以掩盖失败。

最终同音频比较仍支持保留双语默认，观测协议和局限与初轮相同：

| 设备/模型 | 加载观测 | 三段处理耗时 | 最大测试进程 PSS 观测 |
| --- | --- | --- | --- |
| API36 ARM64 / 双语 | 2048 ms | 849 / 732 / 1444 ms | 230475 KiB |
| API36 ARM64 / 中文 CTC | 2031 ms | 788 / 802 / 1365 ms | 217455 KiB |
| OnePlus / 双语 | 1944 ms | 1399 / 1331 / 2463 ms | 244945 KiB |
| OnePlus / 中文 CTC | 3000 ms | 1314 / 1166 / 2280 ms | 234105 KiB |

独立单模型测试的 10.053 秒混合音频：模拟器加载 2290 ms、总计 2954 ms、14 次文字变化；OnePlus 加载 1941 ms、总计 3073 ms、13 次变化。这些仍为直接 PCM 注入的处理测量，不是真实开口首字延迟。中英未知词退化与初轮一致，未以文件名或样例吞吐承诺整体准确率。

## 边界

真实用户口述、噪声/口音、长时热状态、其他 OEM/API 尚未验收；本轮无实时对话、常驻监听、TTS 实施或云端 provider。TTS 仅完成官方候选/许可核查，未做手机合成性能测试。


## Owner 追加安装与方案复核

2026-10-19 按 Owner 最新要求，再次用 `adb install -r` 将已通过上述测试的独立 `com.helix.agent.asrvalidation` 安装到 OnePlus 6T 与 Helix_API_36 ARM64 模拟器，两次均返回 Success；读取设备实际 base.apk 的 SHA-256 均与冻结候选一致。两台均核实 versionName 0.0.6 / versionCode 1，并用显式 MainActivity Intent 打开入口；未触发录音、清数据或覆盖原渠道包。此次复装没有重跑功能测试，沿用同一 APK 的上述证据，不能算新模型实测。模拟器宿主麦克风路由保持关闭，最终真实说话体验建议由 Owner 在手机中手动触发。

最新官方方案复核及未实测候选见 [ADR 同主题补充](../../adr/platform/004-offline-voice-input.md)。当前框架仍为官方最新稳定版本；模型定位为已经验证的基础方案，不宣称截至今日全面最优。
