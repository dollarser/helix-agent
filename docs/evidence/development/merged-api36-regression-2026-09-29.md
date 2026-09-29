# main 整合后的 API36 回归

日期：2026-09-29。所有者明确要求模拟器测试、直接修复问题，并追加 SGLang 多接口兼容性测试。起点 `a243dfda`；本轮修复在工作目录，未提交或推送。

## 环境与边界

- 独占 AVD `Helix_HXA229_Closeout_API36`，API36 / Android16 / arm64-v8a，4096 MiB，2 cores；只读 AVD、无快照保存。正式最终批次分别使用 5560/5562/5564/5566 端口，均由 runner 启动并关闭。
- consumer/developer Debug APK 与 AndroidTest APK；每次安装的 SHA-256、设备属性、进程所有权和退出事实分别在输出目录 `artifacts.json`、`device-properties.json`、`owner.json`、`closed.json`。
- 不操作已连接真机，不访问订阅账号或付费服务；本机 SGLang 使用合成问题和只读 `time.now`。视觉测试验证像素准备、授权、协议编码及持久关系，不调用真实视觉模型，不证明图片识别质量。

## 修复和失败记录

输入缓存的 Activity 恢复缺少会话身份校验，修复及单元测试见[缺陷记录](../../bug-fixes/2026-09-29-composer-restored-session.md)。其他修改是测试首次启动准备、缓存夹具、渠道权限页预期与旧恢复 oracle 对齐，没有放宽权限或未知副作用语义。

- 首轮 developer 38 项中 6 项失败：4 项首次启动挡住导航、2 项旧缓存夹具预期。
- 第二轮 developer 39 项中 1 项失败、consumer 39 项中 2 项失败；发现间歇性输入恢复与 consumer 不提供 developer 文件权限子页的问题。
- 修复后 developer 两轮均 38/38；修正 consumer 渠道预期前曾为 37/38。
- 旧恢复脚本先因默认入口改变失败，再在实际进程恢复后因工具 `INTERRUPTED` 旧预期失败。当前测试要求 Turn `INTERRUPTED`、运行中工具 `NEEDS_REVIEW`、未启动调用 `CANCELLED`，并验证没有新执行/模型调用、重复恢复不变。
- 一次 consumer 启动在上一实例退出后因 ADB 暂存 serial 被独占检查拒绝，未执行任何测试；后改用独立端口，不绕过所有权保护。一次测试编译因项目不生成 BuildConfig 失败，改为检查实际包名。

失败原始日志保留在 `build/merged-api36-*`；不将失败覆盖或计为通过。

## 最终设备结果

developer：`build/merged-api36-r5-developer-suite/` 38/38；`build/merged-api36-r5-developer-recovery/` seed 1/1、实际普通 Activity PID 3386→4454、恢复 verifier 1/1、320/360/412dp × font scale 1.3 表单及错误恢复各 4/4（合计12/12）。取消 fixture 是预置 durable 边界，随后真实 SIGKILL/reopen；不是在用户点击停止的随机时刻杀进程。

consumer：`build/merged-api36-r5-consumer-suite/` 38/38；`build/merged-api36-r5-consumer-recovery/` seed 1/1、实际普通 Activity PID 3123→4424、恢复 verifier 1/1、同样三档宽度与1.3倍字体12/12。恢复过程中两次短暂 accessibility 空 root 经有界重试取得新快照，最终所有断言通过。

两渠道功能用例合计76/76，大字体24/24，seed与恢复verifier共4/4；另有两个普通进程SIGKILL/重启闭环。最终批次协调器退出码0，四份 `closed.json` 均记录本轮模拟器进程退出；ADB最终仅保留原有真机，本轮未操作它。上述定向范围不代表全设备测试类或全产品验收。

## SGLang 三协议

当前服务 `localhost:30008`，模拟器通过 `10.0.2.2:30008/v1` 访问，模型目录为 `Qwen3.8-27B`。`/openapi.json` 的 title 为 `SGLang compatibility proxy`；没有据此宣称三协议都是 SGLang 原生实现。此表只证明该部署链路与 Helix Android 正式 adapter 的互操作。

| 协议 | 固定用例 | 结果 | 工具闭环 |
| --- | --- | --- | --- |
| OpenAI Responses | chat-001、provider-001、provider-002 | 3/3 PASS | time.now 经正式 Dispatcher，持久结果存在，回填后文本完成 |
| OpenAI Chat Completions | chat-002、provider-003、provider-004 | 3/3 PASS | 同上 |
| Anthropic Messages | chat-003、provider-005、provider-006 | 3/3 PASS | 同上 |

总计9/9，九项终态均为 `Completed(finishReason=stop)`，单项端到端1.983–3.543秒，不是纯模型 tokens/s。证据 `build/merged-api36-r5-developer-recovery/sglang-protocols/` 含逐项 JSON、安装包哈希、源码 manifest、instrumentation 与9项汇总。测试入口只有一个 JUnit method，不将其误写为9个 JUnit methods；也不是完整P5或公开榜单。未覆盖此服务的视觉、长上下文、多工具并行及所有错误响应。

## 主机验证

双渠道 app unit、Debug APK、AndroidTest APK 编译通过；双渠道 debug lint、spotlessCheck、detekt 通过。相关日志 `build/merged-api36-binding-build.log`、`build/merged-api36-final-lint.log`、`build/merged-api36-recovery-oracle-build-r2.log`。最终源码门禁通过（631 Markdown、214 HXA、35 ADR、1811 三语言键及 secret scan），日志 `build/merged-api36-final-source-r2.log`；`git diff --check` 通过。首次新增 bug-fix 记录缺少规范章节导致文档检查失败，已补齐并完整重跑源码门禁。
