# 偶发问题增量收口

日期：2026-09-28；基于 `49d3bd40` 的未提交工作树，归 [HXA-231](../../development/tasks/HXA-231.md)。这不是正式 clean P5，也不关闭 R1。

## 变更及证据边界

- SAF 空 live-source 列表现在提供就地重试。重试仍调用实时授权查询，不缓存旧来源或恢复已撤销权限。新增 UI 场景验证面板保持打开时刷新发现新来源，以及撤销后刷新不会恢复来源；双渠道 AndroidTest APK 编译验证与设备执行分别记账。此变更解决恢复入口缺失，不证明历史空列表根因。
- CapabilityProbe 的工具阶段遇到 `length` 时返回限额、推理/文本字符数、工具开始/结束数和可用的 output token 数。仅记录计数，不复制生成内容；能力判定、256 token 探测预算、失败语义不变。新增确定性测试验证安全诊断内容。
- Goal 固定评测保存该 Turn 每次持久模型调用的状态及 usage；不再只靠 Goal 累计 token 反推截断。未增加预算、自动续写、重试或接受截断工具调用。

## 历史归因

归档 `build/worktree-cleanup-20260928/p5-evidence/root-build/p5-tool-discovery-diagnostic-20260928/sglang-goal-goal-001/device/goal-001.json` 记录两次模型调用、一次已完成 get_goal、Turn FAILED / OUTPUT_TOKEN_LIMIT、Goal PAUSED。累计 token 为 20872，不能拆出每次输出和推理消耗，不能据此认定唯一原因。旧能力探测只留下 CONNECTION_ONLY 与失败断言，缺少阶段明细。后续 15/15 不抹去这些失败。

偶发多余调用尚无新的复现证据；现有充分证据提示、工具暴露收敛和无进展检测保留，不凭推测增加禁止正常长程任务的限制。

## 验证

全部 JVM tests、双渠道 app unit/lint/debug APK/AndroidTest APK、spotlessCheck 与 detekt 通过（`build/intermittent-final-host-r2.log`，992 tasks）；CapabilityProbe 24/24。首次检查发现测试方法长度和断言行长超限，拆分后重验通过，保留 `build/intermittent-final-host.log`。源码/文档门禁通过（`build/intermittent-source.log`），`git diff --check` 通过。本轮 API36 与本机 SGLang 有界诊断已申请当前授权，尚未执行；不沿用前置发布/权限/取消/UNKNOWN 的通过结果。真实账号、真机未请求。所有四项历史根因继续保留，不能将可恢复性与取证改进写成全部根治。


## 2026-09-29 API36 追加验证与修复

所有者本轮要求模拟器测试，并要求中间发现问题直接修复。独占 API36 arm64、4 GiB / 4 cores / 420 dpi；本机 SGLang `Qwen3.8-27B`，无真实账号或真机。源码为未提交工作树，仍不是正式 clean P5。

### 发现与改动

1. 新 SAF 测试最初在文件首页寻找目录内按钮，修正为先进入 Workspace。初轮 4/5，属于夹具导航错误。
2. 新测试进一步确定：移除来源已撤销底层授权，但 `revokeScope` 没有触发 SAF 面板刷新，旧来源仍显示。成功撤销后递增刷新版本，重新查询实时授权；新增测试覆盖空列表刷新、移除、再次刷新不恢复旧授权。
3. 已有首次打开面板测试也复现空列表；失败时 registry 和实时查询均存在来源。临时计数日志显示查询完成后在后台 effect 线程发布 Compose 状态，按文件预览已有模式显式切回主线程发布。结合撤销刷新修复后双渠道测试通过；临时日志已移除。该结果限定本次 Compose/API36 路径，不外推所有 OEM 或历史事件的唯一根因。
4. Goal 一轮失败是 4 次测试调用预算耗尽，并非输出截断。系统提示强制最终回答前上报，与 fixture 的仅规划/不调用工具要求冲突。补充仅为未完成 Goal 工作制定计划时直接回答，不发起生命周期工具调用；执行请求仍遵循原报告协议，durable settlement、权限和预算不变。

### 当前通过结果

- `build/intermittent-api36-20260929-final-r2/`：consumer SAF 5/5、developer SAF 5/5，共 10/10；覆盖此前两条失败路径。
- 同目录 `model-round-1..3`：goal-001 3/3，逐轮 Turn COMPLETED / Goal PAUSED，每轮 1 次模型调用、0 次工具调用；output tokens 分别 991、913、510。原 fixture、4096 单次输出上限与 4 次调用预算不变。最终运行期间冻结源码；每轮 source manifest、APK hash、原始结果均保留。
- `build/intermittent-api36-20260929-diagnostic/`：能力探测 3/3；skill-003 三个 case 均 PASS，工具调用数 0/1/1，后两轮仍有 skills.list。它们使用修复前诊断 APK，不冒充最终 APK 的全量基线。
- 最终源码全量主机门禁通过：`build/intermittent-20260929-final-host-r3.log`，992 tasks，包含全部 JVM tests、双渠道 unit/lint/debug APK/AndroidTest APK、spotlessCheck、detekt。
- 源码/文档/ADR/国际化/敏感信息检查通过：`build/intermittent-20260929-source.log`；`git diff --check` 通过。
- 最终独占模拟器 `closed.json` 退出 0，结束后 ADB 无连接设备。未提交、未推送。

### 保留的失败与限制

`intermittent-api36-20260929`、`-r2`、`-r3`、`-diagnostic`、`-final` 下保留导航、撤销不刷新和首次加载失败；`-final-r2` 为最终通过版本。原三轮 SAF 诊断后仅为新定位缺陷和修复追加验证，没有用原样无限重跑刷通过。

诊断旧 Goal 三轮 case 为 1 FAIL / 2 PASS；两次 PASS 仍有生命周期调用。第三轮诊断期间源码被编辑，脚本报 `P5 suites did not use one source/APK identity`，因此不能作为一致版本基线，不能把 case PASS 抹成整轮 PASS。最后三轮在冻结源码后全部通过。

历史 OUTPUT_TOKEN_LIMIT 和能力探测偶发失败本轮未复现；新增计数/usage 可用于后续定位，但不宣称根治。额外只读调用本轮仍有证据，继续开放。SAF 修复通过本次定向验收，不是长稳或全产品通过；R1 仍未实现。
