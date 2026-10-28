# CI 分层与验证边界

更新：2026-10-28。入口：[workflow](../../.github/workflows/ci.yml)、[范围选择](../../scripts/ci/plan.py)、[共享门禁](../../scripts/check-all.sh)。

## 当前任务图

```text
source → runtime-assets（锁定下载、解包、ELF）→ android（单一 macOS 工作区）
所有终态 → verify（始终执行，拒绝失败、取消及意外跳过）
```

纯文档等 source 变化只执行 source 与 verify，不使用 workflow 级 paths-ignore，避免 required check 永久 Pending。PR 检查完整 merge-base 差异；push 使用最近成功且为 main 祖先的完整日常 Android 基线。取消的代码提交随后跟文档提交不能隐藏未验代码；无可信基线或 API 不可用时执行 Debug。旧分片记录不伪装当前新图成功。手动运行不是日常 push 基线。

| 运行 | 实际检查 |
| --- | --- |
| 日常 PR / main push | Wrapper、全部主机脚本回归、文档/ADR/i18n/密钥、差异空白；需要 Android 时执行锁文件解析校验、全产品 JVM 与 Android Debug 单测、两渠道 Debug APK、产品 AndroidTest 源码编译、Spotless/Detekt/生产 Debug Lint、Debug APK 与 Runtime 安全边界 |
| 仅已知文档、Python 脚本测试等 | source 与稳定 verify；未知变化保守执行 Debug |
| 手动 `workflow_dispatch`，默认 full | source、锁定输入、完整 Debug/Release 分析及单测、两渠道 Debug/Release/R8 与 Runtime 构建、独立 Release smoke 测试 APK 编译/Lint、Debug/Release APK 边界；可显式选 debug，不提供手动 source |

日常 `debugUnitTests` 聚合 JVM `test`、Android library/application 的 Debug 单测及 App 两渠道 Debug 单测，不按测试类筛选；实验模块仍需显式 opt-in。原聚合 `test` 会执行 Release 单测变体，留给完整门禁。单测执行、AndroidTest 源码编译、APK 打包是不同检查，均不代表设备测试通过。完整 Release/R8 是发布前门禁，日常绿色不代替它。

GitHub CI 只做主机验证，不启动模拟器、连接真机、执行 instrumentation、真实账号或付费服务。设备验收仍需本任务明确授权。分支保护、凭据、仓库权限均不由本轮修改。

## 精简及保留理由

- 移除 analysis / release-analysis / tests-build 的三个独立 Android 工作区，选定范围在一个工作区执行，共享配置、编译输出与 Gradle daemon；不用 `clean`、`rerun-tasks` 或搬运整个 build 目录。
- 默认 PR/push 走 Debug，双渠道 Release/R8 与 Release 测试驱动仅在手动 full 发布门禁执行；不靠扩大 60 分钟超时或删失败检查解决问题。
- 语音准备与严格校验并入实际 Android 消费者，移除独立 macOS 排队与语音产物上传/下载。准备缓存仍按 OS/ABI、工具链、locks、配方及校验器失效；命中必须验哈希。Runtime 输入保留 arm64 Linux 的真实 ELF/解包校验及最小产物中转。
- 只由 setup-gradle 管理 Gradle User Home 缓存；保留已启用的 task-output/configuration cache，不叠加缓存者或使用 problems=warn。配置缓存兼容性仍由真实执行验证。
- 锁检查前置，仍真实解析全部声明模块（含可选实验）的依赖、核对解析前后锁文件；不是仅检查文件存在。昂贵构建前先报告依赖错误。
- 移除无消费者的日常 Debug APK 上传及说明步骤；失败才上传限定诊断，二进制中转不做无收益压缩。发布签名包由独立授权发行流程产生，CI 不假装自动发布。
- 日常同 ref 取消过期运行；手动发布检查独立并发组且不被日常 push 取消。只对 main 的 PR/main push 触发，不新增定时全量、设备矩阵或复杂框架。
- 稳定 verify 使用 always 与显式结果核对；source 之外的 runtime/android 必须 success，failure/cancelled/意外 skipped 不能通过。脚本回归保留这些边界，不按数量机械删测试。

## 仓库测试收敛

范围盘点覆盖 Git 跟踪的 JVM、脚本与 AndroidTest 源码集（含可选实验）；按名称/依赖/重复关系筛查，再读取候选断言，不声称逐条重新证明全部测试。支持夹具不按测试文件计；数量按声明的 @Test 类注解/`test_` 方法统计，不等于参数化展开或多渠道运行次数。

| 类别 | 有测试的文件，前 → 后 | 声明用例，前 → 后 |
| --- | --- | --- |
| JVM / Android 单测 | 715 → 711 | 5193 → 5186 |
| AndroidTest | 458 → 458 | 1753 → 1752 |
| Python 脚本测试 | 41 → 40 | 302 → 291 |

直接删除 5 个文件、14 个低价值用例：EmptyConversationHint 的固定建议资源清单（2），ComposerPresentationContract 的固定英文/已删除错误码与禁词（3），PreviewToolVersions 的局部常量清单（1），GoalToolNamespace 的旧别名不存在清单（1），MemoryLogExperimentPlan 的历史实验 fixture 固定形状（4），两个脚本 --help 文案（2），ToolTimeline 的 360dp 重复中间点（1，保留 320/412 边界）。不是把它们改为 skip/ignore。另将旧双基线/分片 CI 策略的 11 个用例换成现行单基线/聚合的 6 个行为用例，净减 5 个；取消、失败、未知变化与累计差异保护仍覆盖。

| 保留核心保护 | 代表性回归 |
| --- | --- |
| 认证/TLS/凭据与外发 | JgitTlsBoundaryTest、SessionExportCredentialsTest、OfficialGatewayContractTest、OAuth 与 HTTP 协议回归 |
| 权限与可信执行 | core/policy、Dispatcher/Approval、ToolRegistry 的真实路由/版本绑定；权限警示对比度和拒绝/未知状态呈现保留 |
| 持久化/损坏/并发/不重放 | RecoveryCoordinatorTest、RequestManifestRecoveryDeviceTest、ToolSettlementRecoveryDeviceTest、TurnCancellationRaceDeviceTest |
| 终端容量/残留/重建 | RuntimeExecutionCapacity 与 ProotPackageReplacement、残留回收及 PreviewPrivateStateGate 回归 |
| Mobile Use/导入/导航 | Mobile Use 权限/边界、ImportExportFacadeDeviceTest、真实返回来源与大字窄屏可达性 |
| 语音及输入 | HTTP/WS 契约、取消/资源退出/迟到结果隔离、草稿保留与不自动发送；模型发现和专家/插件真实接线 |

没有按规模裁掉核心模块、删除功能或清理用户数据。设备测试本轮只做保留源码编译，不冒称执行；先前已授权的设备证据仍按其原候选身份保留。

## 实测证据

旧图 [40734321933](https://github.com/dollarser/helix-agent/actions/runs/40734321933) 第二次尝试：源码、Runtime、语音和两项分析成功；tests-build 主 Gradle 构建成功但耗时 58 分 32 秒，独立 Release 测试驱动编译/Lint 成功耗时 18 秒，随后锁检查遇整项 60 分钟上限取消，汇总正确失败。Release 分析约 23 分钟，Debug 分析约 27 分钟，任务并行不能相加当总耗时。首次 runner 未分配以及查询 HTTP 502 是外部调度/查询故障，不是测试失败。第三次过时排队重试已请求取消。

本地暖缓存完整 Debug 门禁通过，实际 108.27 秒，含 291 项脚本回归、38 个 JVM/Debug 单测任务、两渠道 APK、AndroidTest 源码编译、42 份锁及最终 Debug 边界。新入口任务图与完整 test 的 JVM/Debug 子集相同且无 Release 任务。actionlint、范围与失败聚合回归通过；首轮新 Gradle 任务格式失败已按 Spotless 修正并重跑成功。远端新图结果待实际运行核对，不承诺提速倍数。冷/暖缓存与排队时间必须分开报告。此前优化、删测裁决与历史测量见 [当时记录](https://github.com/dollarser/helix-agent/blob/865456960cc6674122c1691e3a73d355336bf8ed/docs/development/ci.md)，不把历史结论当本轮结果。

## 依据与本地使用

采用 Android 官方的[减少不必要构建工作并按实际 profile 定位](https://developer.android.com/build/optimize-your-build)，以及 Gradle 的[复用相同输入任务输出](https://docs.gradle.org/current/userguide/build_cache.html)。不顺便升级 AGP/Gradle、依赖或修改产品功能。

GitHub 官方说明：[跳过 workflow 可能留下 Pending，依赖汇总应用 always](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)、[并发组与取消](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)、[setup-gradle 缓存约束](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md)。以上支持本轮取舍，不证明任意仓库必然加速。

日常 `./scripts/check-all.sh --debug`，发布前 `--all`；失败即时停止，完整门禁不因 CI 默认精简而删除。CI 保留 --profile 与步骤耗时，只有成功源码/实际制品可绑定验收。
