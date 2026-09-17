# Bug Fix: SGLang UI smoke 表单同步、目标行与真实能力验收

Status: fixed
Date: 2026-09-16
Related HXA: HXA-059, HXA-193
Affected modules: app AndroidTest

## Problem

main 的真实 SGLang smoke 在表单输入后直接点击 cleartext 确认和保存，没有滚动与启用态同步；Provider 行操作又使用全局 tag。测试注释声称五阶段能力探测，实际只点击普通连接检查。

## Impact

键盘遮挡或重组时保存不生效并超时；保留的 managed Provider 行可能影响节点选择和等待。即使连接测试通过，也不能据此证明工具或视觉能力。显式启用后的端点故障曾被 assumeTrue 记为跳过。

## Root cause

对照 v0.0.1 的 2251242c 与当前源码，原 confirmCleartextAndSave、目标行限定与滚动被移除。main 的普通连接与能力检测已经拆分，但 smoke 未同步这条产品契约。本次没有发现需要改动生产 UI 的证据。

## Fix and invariants

- 两处保存复用 performScrollTo → 点击确认 → assertIsOn → assertIsEnabled → 保存 → 等待关闭。
- Provider 状态、按钮、模型列表和 chip 限定到测试的可编辑行，并在需要时滚动。
- 先验证连接快照 CONNECTION_ONLY，再显式点击能力检测，等待其独立结果并断言 PROBED、streaming/toolCalls/vision；记录 reasoning 与 JSON 的实际值，不由模型名称推断。
- 默认仍由 realSelfHosted profile 跳过；显式启用后端点不可达或模型列表无效均断言失败，不能继续用 skip 隐藏故障。
- 无生产代码或授权语义变更。测试 runner 新建独占 AVD，记录自身 PID、APK hash、运行结果与清理；不借用其他会话模拟器。

## Alternatives considered

不通过延长保存超时、降低断言或修改产品布局掩盖测试同步错误；不把普通连接的 Passed 当能力探测成功。没有合并旧分支全部测试或业务代码，仅恢复适用接缝并增加当前契约断言。

## Regression verification

生产基线 f8c93ca2；随后文档提交87f08c17没有改变生产源码。测试APK包含本次修复。

- `./gradlew spotlessApply :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest --console=plain`：BUILD SUCCESSFUL。
- `./gradlew spotlessCheck detekt --console=plain`：BUILD SUCCESSFUL。
- `./scripts/check-all.sh --source` 与 `git diff --check`：通过；434份Markdown、193项HXA、29份当前ADR、6项脚本回归及国际化/秘密扫描。
- `python3 scripts/debug/2026-09-16/run-sglang-closeout.py --api 36` 与 `--api 29`：各自在全新独占AVD安装同一制品。每个API默认profile测试明确跳过1项；本地 ProviderModelDiscoveryUiTest **4通过/0失败/0跳过**；真实 SglangUiSmokeTest **1通过/0失败/0跳过**。
- 真实端点为模拟器宿主桥接的本地SGLang、Qwen3.8-27B。API36真实场景21.75秒，API29为13.17秒；时间只记录实测，不用耗时长短推断是否调用服务。日志中的实际快照均为 PROBED，streaming/toolCalls/vision/reasoning=true，parallelToolCalls/jsonSchemaOutput=false，maxContextTokens=null。
- 两个API的默认条件跳过共2项独立记账，不计入通过。真实场景合计2项、本地回归合计8项通过。
- 原始instrumentation、logcat、命令、制品hash及清理记录：`build/sglang-closeout-20260916-153617-36/`、`build/sglang-closeout-20260916-153910-29/`。两份summary记录自有模拟器退出，测试AVD删除；不把历史手动截图当本次自动化证据。

## Residual risk

这是developer在API29/36的特定真实端点验收，不是全部Provider、账号或API矩阵通过。vision只证明探测图像输入被后端接受，不证明任意图片理解质量；JSON/并行工具能力未证明。consumer没有本developer专属smoke，不伪造consumer真实服务结论。

没有运行24小时长稳、Root真机、远端CI、订阅付费账号或发行验收；HXA-192/193及209仍按各自范围开放。本次只修复SglangUiSmokeTest，其他外部profile需要分别报告执行条件与结果。

## Related records

- [thinking与初始化修复](2026-09-16-main-thinking-and-startup.md)
- [当前任务索引](../development/roadmap.md)
- [大型开发交接](../development/implementation-guide.md)
