# Bug Fix: 终端覆盖安装恢复与单步关闭

Date: 2026-10-19
Status: fixed
Related HXA: HXA-302
Affected modules: app, runtime/proot-core, runtime/proot-app
Scope: Owner 报告手机覆盖安装后 UNKNOWN / RUNTIME_LOST、重连及新建失败，并要求 Helix 内独立终端的一次关闭操作终止所选终端全部进程。

## Problem

在日常 `Helix_API_36` 的原 Advanced R8 安装上保留现场，两个已有终端均为 RUNNING，只有空闲 shell；相同版本/签名 APK 覆盖安装后，两条记录均变成 UNKNOWN / RUNTIME_LOST。Android 退出历史明确记录原 `:proot` 因 PACKAGE UPDATED 结束。点击重连后出现错误的只读观察连接和输出失败。两个持久名额未结算，无法继续创建终端。

## Impact

覆盖安装保留的旧终端记录无法通过原界面关闭，耗尽终端名额；用户还需要分两步停止/结算正常终端。该缺陷影响独立手动终端，不表示聊天、模型或整个 Linux 安装环境已损坏。

## Root cause

- 聊天 Session 属于 Room/聊天服务，终端入口不传聊天 ID。PTY 使用独立 UUID、generation、execution identity 和默认工作目录；切换聊天不是此故障原因。
- Runtime 的 service/内存 generation、Linux 环境安装目录、PTY identity 是不同概念。APK 更新会结束旧执行进程，保留日志和 RootFS 不代表原 shell 内存或 Binder 仍可连接。
- 原 `ProotTerminalHost.query` 只支持严格递增 boot count 的恢复证明，未处理同 boot APK 更新。旧日志保留是正确边界，缺少可关闭的更新恢复路径才是缺陷。
- `ManualTerminalConnection` 曾将失败 ATTACH 的空 token 当成合法只读连接；ViewModel 的显式重连/切换也会尝试连接 UNKNOWN。界面笼统错误掩盖了具体状态。
- 两终端上限是宿主、持久绑定、Runtime 与 UI 的固定实现预算，不是 Android 平台上限；本轮没有改变该预算或宣称两个是设备测得的最大值。

## Fix and invariants

1. 使用系统原 `:proot` 退出记录、PackageManager 更新时间及同 boot 一致时钟联合建立 PACKAGE_REPLACED 停止证明。API34+ 要求更新退出原因；API30–33 还校验旧系统包终止原因与安装时刻相符。仅安装时间、PID 缺失、租期耗尽均不够。无可信证据时仍保留 UNKNOWN。
2. 旧 v1 PTY 日志可严格读取，v2 保存更新证明；origin/generation/进程身份不改，结果仍未知、没有虚构退出码。显式关闭通过原 ACK 和宿主 CAS 释放对应身份，不清空授权或重置计数。
3. “关闭终端”一次停止所选 PTY 及其子进程、有界等待退出并自动释放名额。失败明确报告未完成；不再要求用户先停止再结算，也不影响另一个终端/Job。控制绑定点击时的原终端，不能由排队操作改指另一个终端。
4. UNKNOWN/已停止终端不再重连；失败 ATTACH 不再冒充观察连接。丢失 START 日志的原身份保持可见，不能隐藏后重复启动。租期/空闲停止意图及 UNKNOWN worker 清理期间继续保留前台生命周期。
5. 保留此前三语言终端命名/帮助修正，状态说明区分“旧终端已结束、原命令结果未知”与仍失联；工作区文件保留，不重发命令。

规则见 [ADR-RUNTIME-002](../adr/runtime/002-terminal-and-jobs.md)。系统依据为 [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo) 与 [AOSP 安装流程](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/InstallPackageHelper.java)，没有复制参考代码。

## Alternatives considered

仅按安装时刻、版本或 PID 缺失释放名额，缺少物理进程结束证据；清空绑定会丢失原执行身份；重新启动旧命令可能重复副作用。采用系统退出记录与原日志联合恢复，用户关闭操作内部完成停止和 ACK，保留未知结果及精确 CAS。

## Regression verification

源码基线 `ddb4f78d`；候选成功构建时冻结完整源码输入清单和补丁，最终源输入比对通过。保持 Advanced 包名、开发签名、versionName 0.0.6 / versionCode 1，插件声明版本未动。候选、实际安装摘要、日志、逐阶段 JUnit、元数据与数据保留核对均在忽略目录 `build/terminal-recovery-2026-10-19/`；不把原 Release APK 或失败构建残留当成候选。

| 项目 | 实际结果 |
| --- | --- |
| `./scripts/check-all.sh --all` | exit 0；source/JVM/lint/Debug/Release/R8/依赖与制品边界通过 |
| 新增主机回归 | 11/11；更新证据、旧日志、CAS/不可回退、错误 ATTACH 与未确认 START |
| 应用 Debug JVM | Standard 1315 通过 / 4 条件跳过；Advanced 1443 通过 / 4 条件跳过 |
| PRoot JVM | core 178/178、app 25/25 |
| AndroidTest 编译 | Advanced Debug 与独立 Release 验收包通过 |
| 日常 ViewModel 生命周期 | 同版本 Debug 保数据安装；8/8，使用假执行边界，不冒充物理进程验证 |
| 最终 R8 界面 | 4 种方法在 11 个阶段执行均通过；旧终端关闭、新建、返回重连、单步关闭 |
| 同 boot 更新 | 两真实 shell/长子进程存活后覆盖同一 R8 候选；boot count 前后相同，原 identity 保持，获得 PACKAGE_REPLACED 证明，两个旧子进程结束 |
| 正常关闭 | 一次关闭终端 D 后其长子进程消失，C 的长子进程及环境变量仍保留且可写；再一次关闭 C 后进程消失，两条记录均为 PROCESS_TREE_EXIT 并已 ACK |
| 效果/数据核对 | 四个启动标记各仅一次、无命令重放；全部 Room 表及原 UI 偏好与最初基线完全相同；宿主准入通过正常 CAS 归零 |

临时测试 APK、四个逐项核验的具名夹具目录已删除；旧终端日志/工作区/用户数据保留。日常模拟器保持可见，安装最终 ARM64 R8 修复包，停留终端启动页。未发送聊天或调用模型，未操作 GitHub Release。

Owner 随后追加顶部布局反馈：`c4f95a06` 删除正常运行时独立的标签、目录/状态与操作行，合并为单行返回、终端选择、键盘、关闭和更多；新建入口在终端选择菜单，关闭仍直接绑定点击时的终端身份。普通元数据放入可滚动“终端信息”，异常及未连接处理直接显示；帮助补充后台保留、独立 Job 和起始目录语义。参考设计与取舍记录在原 ADR，三套语言资源同步，既有 UI 验收路径改为按需打开信息并核对原 Runtime 状态，未削弱退出/数据断言。首阶段仅编译及静态检查，随后 Owner 明确要求主机与日常 ARM64 验收，以下为追加的实际结果，不沿用上表旧界面证据。

| 布局追加项目 | 实际结果 |
| --- | --- |
| 完整主机 | `./scripts/check-all.sh --all` exit 0；276 项 Python、源码/全部 JVM/分析/Debug/Release/R8/依赖及制品边界通过 |
| JVM 新执行 | 对四个测试任务分别使用 `--rerun`，Standard 1315 / Advanced 1443 通过、各 4 条件跳过；PRoot core 178、app 25 通过。跳过仍是显式外部 MCP、配置或本地样本条件，不作为功能通过 |
| 新设备测试编译/分析 | `OwnerTerminalLayoutUiTest` 四个方法；既有生命周期与 UI 测试 APK、Spotless/Detekt/Lint 通过；GitHub CI 仍不运行设备 |
| 同源 R8 | `:app:assembleDeveloperRelease -Phelix.abi=arm64-v8a` 成功后冻结并签名，0.0.6/code 1、原开发证书与包名；安装字节与冻结候选一致。生产源码为 `c4f95a06`，构建后只调整独立测试源码，生产输入逐项比对一致 |
| 布局/输入 20 次 | 单终端竖屏一组；双终端竖/横屏各正常字体与两倍字体四组，每组四方法全部通过，无跳过。检查单行顶部、至少 48dp 触控区、正文空间、按需信息/帮助、切换/新建入口及真实 IME 显隐窗口；实际旋转与截图核对，不只改资源配置 |
| 既有 R8 控制 3 次 | 可写连接检查一次、一次关闭当前终端两次通过；没有误显示只读或要求分步停止/结算 |
| 生命周期 8 项 | 日常 Debug 的真实 ViewModel、假执行边界 8/8，不能据此证明进程效果；两次方法执行均过，收尾脚本误报另列下文 |
| 真实进程/目录 | 两个独立 shell/长子进程；返回重连及旋转/字体变化后原 identity/generation/PID、环境和 `cd` 后位置保留。检查点使用唯一值证明新输入实际执行，每个启动标记仅一次；关闭 B 后 B 子进程消失，A 仍可写且环境/目录保留，再关闭 A 后子进程消失。两条记录均为 PROCESS_TREE_EXIT 并 ACK，宿主 CAS 正常归零 |
| 保留/清理 | 全部 Room 表和用户偏好文件、所有原终端记录保持；仅 `process-diagnostics-v1.xml` 的启动/心跳记录按设计更新。原字体/旋转、硬件键盘 IME、原输入法和应用语言核对通过。同 boot 完成，无清库或设备重启；只删除两份逐项核验过的具名临时目录及本轮测试 APK。日常实例留下新 R8 包，停留无活动终端的启动页 |

完整日志、逐方法 JUnit、前后数据库/设置指纹、源码输入和候选位于忽略目录 `build/terminal-layout-2026-10-19/`，摘要为 `final-result.json`；候选只保留一份。没有发送聊天、调用真实模型、推送或更改 Release。本次没有重新执行覆盖安装时两个运行 PTY 的旧恢复场景，其证据仍归上表。

追加过程的失败与处理：首组 4 方法中弹窗关闭后的立即断言过早失败，增加明确 UI 状态等待后通过，未放宽原布局/状态断言。下一组 4 方法因前台离开 Helix 到微信而全部超时；原因未确认，暂停设备输入，Owner 确认可以继续占用模拟器后才恢复。首次环境写回未生成文件，不作为 shell 丢失；增加每次输入前的严格终端前台/可写/IME 就绪守卫，随后唯一标记、原进程与环境核对通过。这两组失败日志分别保留，未混入最终 23 次 R8 通过。生命周期收尾先后将原本不存在的语言文件按 XML 解析，以及将动态进程心跳误判为用户偏好变化；修正判定并独立核对用户表/设置后通过，没有回写旧诊断或改动用户数据。

## Residual risk

- 布局追加只验中文界面的日常 API36/ARM64 Advanced；其他 API/OEM/真机、英文实际界面、真实聊天 Job 并发、长时间租期/idle 等待未在这次重跑。模型未参与本轮，手动输出不代表模型消费结果。

- 开发阶段曾有新增 CAS 测试遗漏合法中间状态、跨模块可空属性编译、格式/复杂度和新 API 常量 lint 失败；逐项修正后重新通过，没有删除或放宽断言。
- 首次 R8 启动期间，日常 AVD 控制台随后整体 ADB 离线，独立日志/截图读取也阻塞。保留终端现场和主机采样，重连无效后 SIGTERM 正常退出并保数据冷启动同一 AVD；没有 wipe、强杀、旧快照恢复或替换实例。旧日志已持久保存更新证明，但没有以重启恢复冒充更新回归：随后重新创建两个真实终端，在同一 boot 内重新覆盖安装并验证 PACKAGE_REPLACED。离线的确切原因未证实，不归因于终端或设备硬件。
- 手机真机未连接，本记录不证明其已复现或修复通过。API29–33、OEM、两小时租期/三十分钟 idle 实际等待和长期稳定性本轮未跑；API29 或系统退出证据缺失时可由设备重启提供退出证明，不能假设旧命令成功或 shell 内存恢复。

## Related records

- [HXA-302](../development/tasks/HXA-302.md)：原手动终端界面与生命周期接入。
- [终端开发说明](../architecture/terminal.md)：独立入口、工作目录和单步关闭。
- [当前状态](../development/status.md)：本轮交付与未发布边界。
