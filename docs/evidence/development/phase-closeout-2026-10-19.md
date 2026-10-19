# 三阶段收尾与朗读等待优化（2026-10-19 UTC）

范围：[HXA-311](../../development/tasks/HXA-311.md)。起点 `f260e7b6`，初始工作区干净。先完成本轮自动验收和问题修复；知识库检索问答、自然语言提醒和定时 Agent 后续按顺序推进，不启动 286–288 重构。版本 0.0.6/code 1、工具声明 1 不变，不清用户数据。

## 实现与实际发现

- 原播放器在原生回调中阻塞写整段音频，下一段合成被播放拖延。改为容量 1 的 PCM Channel，合成和音轨输出重叠；写满背压，不缓存整篇回复。取消丢弃排队 PCM，合成/输出失败也取消另一端，互斥边界仍等两个协程及原生执行真实退出再释放。
- 首段优先短子句（约 16 UTF-16 单元）、后续最多 64，保留常规英文词、日期数字组和代理对，超长单 token 以 64 为界。模型、完整 57.8 MiB 资源及许可不变，没有增加常驻模型缓存或静默下载。
- 系统分享面板实际发现普通、Advanced 与历史实验包都叫 Helix，导致一次合成资料投到 Advanced 而 Standard 等不到。新增明确的“收集到 Helix / 收集到 Helix Advanced”接收目标标签，Advanced 应用名也区分；通过精确 ID/token 和原件字节验证清理了误投夹具，没有批量删除资料。
- 新增 Android Sharesheet 跨 UID 合成文件/文字投递与撤权后原件可读测试；实际 Chrome 公开 example.com 页面，经菜单真实分享行、展开系统面板、选择收集目标，核对原始 URL 保留。测试不会接受账号设置或打开社交收件箱，诊断只记录控件 ID，不记录账号/界面文本。
- 参考 [Android 接收分享](https://developer.android.com/develop/ui/compose/sharing/receive) 的 intent/MIME/临时授权路径及固定 sherpa-onnx 1.13.8 VITS 源码的句子回调边界。没有复制参考项目代码。

## 首段延迟的配对测量

API36 ARM64 日常模拟器、CPU 双线程、同一已加载 Melo 引擎，旧/新/新/旧交替；每次只取首次 PCM 后停止原生输出。不并行主机构建或其他 instrumentation。合成内容为自写日期/长句，非私人资料。两次旧/新样本不是统计基准，也不能推导手机或听感结论。

| 内容 | 旧首段首次 PCM | 新首段首次 PCM | 结论 |
| --- | --- | --- | --- |
| 日期、预算等带逗号短句 | 7258 / 6919 ms | 6910 / 6753 ms | 原生本来就会切分该子句，外层切短无明显收益 |
| 无提前标点的 54 字长句 | 25206 / 24453 ms | 8984 / 7614 ms（16 字首段） | 该夹具首段等待明显下降，不代表所有内容 |

模型加载另为 4378 ms，仍会影响冷启动。回调发出取消后原生退出为 0–7 ms；这不等于在 ONNX 推理中途可以抢占。真实界面到首批零音量音轨写入的独立检查为 11573 ms，停止到引擎关闭为 5176 ms；后者包括已在计算的后续片段，并非声音停止延迟。停止输出使用 pause/flush，未测扬声器安静时延或真实外放听感。

日志 `build/followup/paired-latency.log`、`accepted-targeted.log`，配对测试 1/1，100.464 秒；真实模型→有界播放器→零音量 AudioTrack→界面停止 1/1，20.733 秒，系统媒体音量/静音状态前后相同。

## 最终门禁与设备结果

| 范围 | 最终成功结果与本机证据 |
| --- | --- |
| 两渠道普通 Debug、AndroidTest 编译、R8 Release、JVM、Debug lint、spotless/detekt | 通过，`build/followup-host-final.log`；冻结输入后 BUILD SUCCESSFUL，1 分 25 秒 |
| JVM XML | Standard 1313、Advanced 1448（各 4 项既有外部 profile 条件跳过，未算通过）；Model 173、Storage 242；无失败/错误 |
| API36 ARM64 Standard Debug | 36/36 语音/导航（113.764 秒）+ 38/38 知识库/提醒（37.671 秒）+ Chrome 真实公开分享 1/1（7.796 秒） |
| 同一模拟器 Advanced Debug | 36/36（105.996 秒）+ 38/38（38.901 秒）+ Chrome 分享 1/1（5.976 秒） |
| 主机源码门禁 | `scripts/check-all.sh --source`；279 项、文档/ADR/i18n/秘密扫描，以 `build/followup-source-final.log` 为准 |

源码脚本的 FAIL/INCONCLUSIVE 日志属于门禁自身的负面夹具，只有最终脚本 exit 0 才记通过，不冒充设备结果。

36 项为 TTS 控制/真实文件合成 11、资源事务 5、朗读 UI 5、旧 ASR 公开 PCM/注入控制 10、真实静音端到端 1、导航 4；38 项为知识库 Collection/UI/Notes 24 与提醒状态/UI 14。真实 Chrome 各单独执行一项。没有把旧候选或单项初步通过当作最终矩阵。

候选在 `build/followup/deliverables/Helix-{Standard,Advanced}-{debug,test,release-unsigned}.apk`，实际设备包路径 SHA-256 与成功主机候选一致；源码输入/JVM/产物与已安装候选归 `build/followup/evidence/provenance.json`。Release 未签名，仅证明 R8 构建，功能验收为普通 Debug，不代表 Release 运行或商店验收。

两渠道偏好字节（排除进程诊断）、麦克风/通知授予、精确闹钟 app-op、时区/IME 与基线一致；注入 PCM 的旧 ASR UI 检查临时授予麦克风并在 finally 恢复，没有打开硬件录音。测试 APK 已移除，普通应用保留。只按明确身份清理合成源记录和测试缓存；四份原生模型合成 WAV 留在 `build/followup/evidence/synthetic/`，不等同于分段播放听感证明。

## 失败与修正

- 首次配对测量被我过早启动的另一组 instrumentation 中断；ActivityManager 记录 `start instr` 强停，不是 JNI 崩溃。此轮不计，通过设备互斥与串行 runner 重新测量。
- 首次系统分享面板测试误投同名 Advanced，已修产品目标标识，精确夹具清理与修复后面板测试分别 1/1 通过。
- Chrome 使用复合分享行和折叠系统面板；最初测试误点容器或等不到屏外目标。根据实际控件 ID 选择标题按钮，并以有界手势展开/滚动，最后断言原始 URL 实际进入库；未放宽效果断言。
- Advanced 一次取消测试在消费者接管输出前就断言音轨已收到中断；测试增加 SPEAKING/实际输出接管握手，仍断言中断、单引擎容量、丢弃中间请求及最终释放。补正常完成的 FIFO/drain/输出与焦点释放正向覆盖。
- 构建期间补测试曾导致 lint/FIR 输入失配；不升级或抑制 lint，冻结输入后重新完整验证。开发格式/静态失败与主机/设备旧日志归档 `build/followup/history/`，不将失败候选记通过。

## 连接、手测与 GitHub 边界

本轮仅日常 emulator-5554/API36/ARM64 在线。OnePlus 未连接，Owner 已选择稍后真机手测，不再请求连接；最终候选提醒真机、真实口述和 TTS 外放/自然度仍 pending，没有自行录环境声音或播放扬声器。

模拟器微信已安装；对公开 example.com 的外部 VIEW 查询无匹配，不代表微信内部不支持公开文章或系统分享。其内部转发与 Android 系统分享是不同入口，未读取私人聊天、登录账号或绕过登录。X 未安装，未宣称完成原生 X 客户端分享。实际外部公开内容链路由 Chrome 验证；微信/X 客户端自身菜单仍需后续真实条件下确认。

已只读核查 origin/main 和 v0.0.6 标签，向 Owner 报告本地 main 领先及拟更新 main/预览版的目标。现有标签保持原位置；关键手测缺口未补，不 push/发布或替换 Release 资产。测试 APK/临时合成缓存只按本轮身份清理，普通应用与用户设置保留。
