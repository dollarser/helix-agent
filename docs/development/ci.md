# CI 分层与验证边界

更新：2026-10-27。入口：[workflow](../../.github/workflows/ci.yml)、[范围选择](../../scripts/ci/plan.py)、[主机门禁](../../scripts/check-all.sh)。

## 当前任务图

```text
source ─┬─ runtime-assets（arm64 Linux：锁定下载、解包、ELF）
        └─ speech-assets（macOS：命中即验，未命中只构建一次）
             │
两类输入就绪 ─ android（macOS：analysis / tests-build；full 增加 release-analysis）
所有终态 ─ verify（始终执行，校验成功及计划内跳过）
```

source 档只执行 source 与 verify。GitHub CI 始终只做主机/构建/静态检查，不启动模拟器、不连接真机、不执行 instrumentation、真实账号或付费服务。设备验证仍需当前任务的所有者明确授权。

## 范围与失败语义

| 变化 | 档位 | 保留检查 |
| --- | --- | --- |
| docs、指定根文档、scripts/README、项目开发技能 SKILL.md、历史 scripts/debug、scripts/tests 中的 Python | source | Wrapper、主机脚本回归、文档/ADR/i18n/密钥、差异空白；文档检查含项目技能链接；scripts/tests 的构建脚本、技能执行脚本/元数据及其他未知类型仍 full |
| Kotlin/Java 与 Android 资源/测试代码 | debug | source、锁定输入、Spotless/Detekt、生产 Debug Lint、JVM 测试、AndroidTest 源码编译、双渠道 Debug APK、锁与 APK 边界 |
| Workflow、构建/依赖/Manifest、native、Release 路径、未知类型 | full | Debug 检查加生产 Release Lint、双渠道 Release/R8、Release smoke APK 编译和 Release APK 边界 |
| 手动 workflow_dispatch | 默认 full，可选 debug | 不提供手动 source 放行 |

Push 先检查最近完整 push 基线以来的累计差异：存在尚未通过完整门禁的构建/native/Release 变化，就继续 full。否则可使用更新的完整 Debug push 基线判断普通代码/文档差异，使已通过 Debug 的代码不会让后续纯文档重复构建。基线必须成功且是 main 祖先；source-only、手动运行、取消或失败结果不作 Android 基线。一次规划复用相同 API 响应，不重复查询两个基线的相同记录。PR 检查完整 merge-base 差异，包括删除及移动两端。无完整可信基线/API 失败时保守 full。新增 speech-assets 后，缺少该成功作业的历史运行不作为新基线，首次执行会 full。

稳定的 verify 检查要求 source 成功；只有 source 档允许 runtime-assets、speech-assets、android 全部按计划 skipped。失败、取消、空 scope 或意外跳过均失败。没有 workflow 级路径过滤，文档提交也会产生 verify 结论。分支保护和 required-status 属于远端配置，不能从本文件推断当前状态；本地验证不修改它们。同一 ref 的新运行取消旧运行，已有并发控制保留。

## 省去的重复工作

- ASR/Melo 原生输入由独立 speech-assets 准备一次，各 Android 分片接收同一份产物；不再每个分片重复下载和编译。PR 不写共享缓存。
- 准备缓存按 OS/架构、固定 NDK/CMake、ASR/TTS locks/notices、三个配方与下载校验器失效；目标 ABI 清单也在配方内，改变它会改变配方哈希。缓存只含所需 AAR、manifest、licenses/descriptor 和测试资源，不含 native/source 构建树、账号、运行环境 home 或用户数据。命中仍执行现有三套输入/输出哈希校验；损坏不放行。App preBuild 再校验资源，缓存不能替代锁。
- 不再在每个分片额外启动一次 Gradle 资源验证；实际 Lint/构建依赖的 preBuild 保留原验证。Runtime 下载仍按锁检查长度、SHA、ELF/ABI。
- Android Lint 只分析生产源码。测试仍由 Spotless/Detekt、JVM 实际运行与所有模块 AndroidTest 源码编译检查；不编译成 APK也不等于设备通过。该取舍省掉测试组件 Lint，而不是忽略失败测试或关闭生产错误。参见 [Android Lint 配置](https://developer.android.com/reference/tools/gradle-api/8.3/null/com/android/build/api/dsl/Lint)。
- 普通产品门禁不再强制 includeSpikes；研究实验可显式以 `-PincludeSpikes=true` 重现。没有删除实验源码或通过跳过生产测试凑绿。
- full 继续 Release/R8、Release Lint、锁和双渠道 APK 边界。source/debug 不声称 Release 已验收。未更换 runner、JDK、依赖或堆参数；Gradle task graph 自行去重，不把根 Lint selector 和 flavor 任务简单算成重复执行。

## 测试精简裁决

按 app、core、feature、provider、runtime、extensions、tools 与脚本 source set 扫描候选后，本轮删除 14 个可明确判定的低价值测试方法，整文件删除 4 份，另收敛一份枚举测试：

| 删除/合并对象 | 理由与保留保护 |
| --- | --- |
| RequestContextManifestBenchmarkTest（4） | 容量/Unicode 场景与 RequestContextManifestTest 重复；累计体积只是单条乘 100，墙钟阈值混淆 runner 性能与正确性。保留真实编码/解码及极端体积边界。旧测量记录改指向当时 Git 源码。 |
| EnumsContractTest 的五个枚举清单断言 | 只是重复声明顺序/值，没有独立序列化行为。保留 review-mode 的真实操作分类约束，授权、schema、存储编解码边界测试不删。 |
| ProotInstallationLabelTest（1） | 机械复制资源 ID 映射；不等于恢复/失败分类验收。保留安装/修复及终端真实生命周期回归。 |
| test_historical_matrix_exit（1） | 只验证已退出当前流程的 9 月历史矩阵脚本。当前 owned runner/失败传播测试仍保留。 |
| test_conversation_fixture_history（3） | 对测试 helper 的固定滑动/轮询调用断言，重复当前历史/导航真实旅程的保护。产品历史、草稿、搜索和返回测试不删。 |

不按文件名或测试数量判定重要性。保留启动、模型/工具执行、权限与凭据、数据/文件/引用一致性、导航，以及近期终端容量/回收/覆盖安装、Mobile Use、共享存储故障的最小回归；已报告过实际问题的主题/键盘等测试不会只因属于 UI 就删除。未跟踪的唯一文件不为清理而删除，没有新增 Ignore、降断言或隐藏失败。

新增准备缓存的完整性/输入变化回归，以及 Debug 成功后文档跳过但完整门禁变化不得隐藏的回归；这不是以测试总量为目标的删减。

## 耗时证据与收益边界

真实远端基线：

| 运行 | 实际结果 |
| --- | --- |
| [40376682031](https://github.com/dollarser/helix-agent/actions/runs/40376682031) | full 总计约 61 分钟；source 51 秒、Runtime 资产 2 分 04 秒；三 Android 作业分别约 31 分 32 秒、49 分 53 秒、57 分 46 秒。 |
| 同次运行的步骤 | 各分片重复语音准备约 10～12 分钟；Debug 分析命令约 34 分 57 秒，Release 分析约 18 分 34 秒，测试/打包约 43 分 20 秒。任务重叠，不能相加当总时长。 |
| [40103558495](https://github.com/dollarser/helix-agent/actions/runs/40103558495) | 另一完整运行约 54 分 31 秒，也重复三份语音构建，说明不是一次偶发下载慢。 |
| [40115198462](https://github.com/dollarser/helix-agent/actions/runs/40115198462) | 文档运行总计约 15 分 20 秒，但 source 开始前调度/排队约 14 分 31 秒；source 本身仅约 40 秒。排队不能靠删除代码测试解决。 |

旧图为 source → Runtime → 三份语音准备及 Android gate → verify；新图将语音准备变为共享、可复用的前置输入，并省去测试 Lint。冷缓存仍需要一次原生构建，不承诺首次节省整段时间；暖缓存预期省掉每个分片重复的约 10～12 分钟，但下载/传输和最终关键路径收益需远端实测。删减 14 个方法主要降低维护成本，不把它宣传为数十分钟提速来源。

本轮没有推送或触发 GitHub，不能声称远端已提速。本地缓存条件、执行时长、输入搬运及当前检查结果记录在忽略的 `build/ci-optimization-2026-10-27/`。历史分层测量见 [此前记录](../evidence/development/ci-scoped-gates-2026-09-20.md)，不与本轮不同覆盖/缓存条件直接计算加速比。

## 本地使用与收尾

日常 `./scripts/check-all.sh --debug`；发布前显式 `--all`。按修改范围先做定向验证，未变源码的已通过结果可复用，不在每个小提交上重复 R8。CI 启用 Gradle `--profile` 保存失败和耗时；设备验证和真实服务仍是单独的 opt-in 边界。

本轮本地验证：完整 Debug 门禁通过，包含 JVM 门禁、双渠道 APK、所有产品模块 AndroidTest 源码编译、42 份依赖锁与最终 APK 边界。本机缓存条件下分析段 1 分 42 秒、测试/编译段 20 秒；未变输入允许 Gradle 缓存复用，不能换算为远端冷缓存加速比。最后仅改 CI 策略，全部主机脚本回归已重新执行 300 项通过，Android/JVM 段复用相同输入的成功结果。actionlint 1.7.12、YAML 语法、范围/失败聚合矩阵和真实精选资产搬运后的三套校验通过；缓存测试覆盖损坏字节和改变配方输入后拒绝命中。

首轮 source 检查发现删除基准后历史证据仍指向旧相对路径，改为真实 Git 历史链接后，完整门禁重新通过。最终 22 项 CI 策略/门禁/缓存定向检查通过，覆盖 scripts/tests 仅 Python 可走 source、混合代码、未知类型、双基线、失败和取消等情况。Release/R8 与 release-smoke 的保留任务图已检查，本地没有实际重跑发行或设备测试。所有者现已授权提交推送测试 CI，远端运行结果待本轮结束后补充；不创建 Release。AGENTS、产品版本、签名与用户数据不变。
