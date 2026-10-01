# HXA-234：纯 Core Loop 与上下文等价迁移验证

日期：2026-10-01。基于 `05e910039e03095d98649d96a9e64a3a71bcb078` 与已有 HXA-233/HXA-126 工作树继续实施；对应未提交候选，不是旧提交的验证。所有者授权依次实现剩余工作及修复 bug；本轮没有指定设备、账号或真实模型输入，没有提交、推送或发布。

## 生产接线与修复

唯一 `AgentLoop` 已迁到 `core:agent`，经中立模型、日志、预算、执行、输入和事件端口运行。`AgentLoopHost` 仅保留 Android 装配与 Goal 计时，原 App Loop 删除。Core 无 App R、ProviderService、Room Entity 或 UI；协议继续复用 ModelProvider，没有另一条 Loop、权限系统或持久数据库。

`AgentToolGateway` 接收 session/Turn、调用及冻结配置，不接收 TurnEntity/TurnCoordinator。ChatToolCalls 复用原调度/授权/结算；ChatToolMessages 独立承接原消息编码。结果核对原本地调用顺序，再使用原协议 ID 回填。

终态 AgentTurnStore 命令进入原 Room 事务，一次处理正文、Turn、ModelCall、GoalRun；区分 Applied/AlreadyApplied/Conflict/Unavailable，存储异常不伪装成功。Steer/最终回答的父事务、用户停止优先级及 UNKNOWN 不变。修复了写正文前缺少显式 session/Turn/ModelCall、phase/step/活跃调用校验的边界；重复返回原持久结果，错归属/旧步骤不更新任何行。

首个及后续 ModelCall 发布时，数据库步骤原本可能落后于内存直到流开始。现将调用身份与步骤同事务发布，避免发送前取消被新严格校验误拒绝。

上下文采用宿主有界快照 → 纯 ContextCompiler 选择/容量 → Core 压缩周期 → 原日志事务。保留来源、恢复过滤、工具配对；正常请求的权限/附件/存储 I/O 仍在宿主。原 Prompt、预算、保留顺序和收益阈值未调整，App 旧选择算法已删除。迁移中补上解码失败事实：选中的损坏行仍拒绝，恢复规则排除的前序执行协议不会因提前解析阻断有效请求。

八组原纯逻辑测试完整迁入 Core；包含 App 历史编解码的 LocalToolCallBatchTest 保留在 App。评测清单四处类名迁移，八个 case 与方法断言保留。十一处历史源码链接固定到迁移前 Git 文件，未改写旧结论。Core JSON 直接依赖仅补齐已有 1.9.0 锁记录，没有升级依赖或放宽全局规则。

## 主机验证

```bash
python3 scripts/with-host-slot.py -- ./gradlew spotlessApply test detekt \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  --continue --console=plain
bash scripts/check-all.sh --source
bash scripts/check-all.sh --artifacts
git diff --check
python3 scripts/with-host-slot.py -- ./gradlew spotlessCheck --console=plain
```

上述命令均退出 0。联合 Gradle：54s，989 tasks，52 executed / 937 up-to-date；是增量整合验证，不声称所有测试在最后一次强制重跑。最终源码门禁 678 Markdown、217 HXA、35 ADR、1933 个三语言键；增加本记录后文档数量可变化。两渠道组件、进程/UID、payload、launcher 和 consumer 订阅排除检查通过。

| 当前 XML 范围 | suites | tests（含 skipped） | failures/errors | skipped |
| --- | ---: | ---: | ---: | ---: |
| core:agent / test | 32 | 289 | 0/0 | 0 |
| tools:framework / test | 17 | 221 | 0/0 | 0 |
| app / Consumer | 165 | 996 | 0/0 | 4 |
| app / Developer | 183 | 1058 | 0/0 | 4 |

共享渠道用例不相加当独立场景；四项原条件性跳过分别保留，不计通过。新增独立 JVM **35/35**：TurnCommitPolicyTest 14、AgentLoopHeadlessTest 9、ContextCompilerTest 12。无界面测试直接运行实际 Core Loop，覆盖两种分块的工具/结果/续答、停止、UNKNOWN、错序回执、manifest 失败、恢复预算、无进展及截断；纯上下文固定来源/边界，涵盖损坏历史、配对、过大段、无收益、Unicode。

日志：`build/hxa234-full-host-final.log`、`hxa234-source-final.log`、`hxa234-artifacts-final.log`、`hxa234-spotless-final.log`；统计 `build/hxa234-host-summary.json`。早期 r2～r10 的锁文件、夹具编译与静态失败保留并分别修复；源码清单和历史链接失败也保留，不删除失败证据。

## 验收边界与后续

新增 TurnCommitStoreDeviceTest 九个实际 Room 用例以及原压缩/分叉回归已编译：错归属/步骤/phase、活跃调用冲突、重复、取消、事务回滚、下一步骤身份。**设备执行 not requested**，不能记为运行通过。真实 P5、真实模型轨迹、OEM、输入/UI 背压及资源压力仍需独立验收。

这是 R2-A 本地主机交付，不是 R2-B 策略优化或整个剩余清单完成。不从类迁移推导 token、延迟或任务成功率收益。下一项按 [status](../../development/status.md) 推进 R3；设备/账号缺口不阻塞独立本地开发。

2026-10-01 核对官方 [Codex App Server](https://openai.com/index/unlocking-the-codex-harness/)、[Anthropic 长任务 Harness](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)、[上下文工程](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)。采用共享核心/宿主适配、持久真实结果、分阶段回归和有界上下文方法；未复制外部代码，未据此引入桌面服务部署或强制业务流程。
