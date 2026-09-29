# R1 整合版本：API36 与真实 SGLang 回归

日期：2026-09-30。所有者本轮明确要求设备、真实服务测试、修复及提交推送。使用独占只读 AVD `Helix_HXA229_Closeout_API36`，API36 / arm64-v8a / 420dpi / 4096 MiB；不连接个人手机。所有启动实例均已记录关闭，本轮未使用付费或订阅账号。

## 结果

- Consumer：**49/49 passed，0 fail，0 skip**。
- Developer：**50/50 passed，0 fail，0 skip**，含真实 SGLang 设置/连接/能力探测/模型目录 UI。
- 真实 SGLang `Qwen3.8-27B`，OpenAI Chat Completions：固定系统场景 **15/15 passed**（Files 4、JavaScript 4、Skills 4、Goal 3）。保留原 oracle，未通过放宽权限、预算或 UNKNOWN 语义换取通过。
- 完整主机 gate 通过：全部 JVM/Spike tests、双渠道 unit/lint/debug APK/AndroidTest APK、spotlessCheck/detekt。日志 `build/r1-final-host-r7.log`，1032 tasks（39 executed / 993 up-to-date）；不声称全部强制重跑。App Consumer 961 passed / 4 skipped，Developer 1009 passed / 4 skipped；原可选 fixture 跳过不算通过。
- 文档/ADR/国际化/脚本/秘密扫描与差异检查在提交前重跑；最终本地日志 `build/r1-device-source-final.log`。

这是提交前候选版本的完整 **15-case 诊断回归**，不是 clean 正式 P5 锚点，更不是 BFCL/AndroidWorld 全题分数。运行时基点 `7480141bafc677115df63d09a6afa5c7c733d36a` 加工作树，dirty=true；全部 case 使用同一源码与安装 APK。完成提交不能追溯改写运行时身份。

## 本轮修复与失败保留

1. 首轮 Consumer 35/36：Stop 测试夹具要求模型调用 `echo`，却未把它加入实际模型工具面。R1 正确拒绝未曝光工具；夹具先通过 discovery 加载并断言命中，再执行真实审批取消流程，不放宽生产绑定校验。
2. 第二轮 Consumer 36/37：500 项目录的 `tools.search` 超时。搜索此前先读取所有目录项的持久可用性；改为先匹配/排序，再仅检查候选，并在结果窗口满后停止。主机反例证明精确查找仅检查命中项、禁用候选仍被过滤。Consumer 第三轮 37/37、Developer 第四轮 38/38。
3. 第四轮真实 `file-001` 未建立 Turn，输入为 `FAILED / INPUT_REVALIDATION_FAILED`；数据库快照与 NO_TURN evidence 保留。该次未捕获内部异常堆栈，不能仅凭一次重跑确定全部因果；定向第五轮曾通过。源码进一步确认模式/预算异步写入与发送使用不同锁，可在接收和重验证间改变配置。现以 `SessionActionQueue` 固定用户调用顺序，编辑和发送进入同一已有 submission gate；启动时仍检查会话/活动 Turn，保持严格配置、审批和附件校验。
4. 新主机测试覆盖前序修改未完成、取消等待回执、动作异常及服务关闭；新设备测试连续五次不等 UI/Room 刷新就发送，均绑定已选择配置并建立 Turn。完整发送回执组同时覆盖反问/草稿/附件。本轮最终双渠道与 15-case 重跑未再出现重验证失败，不推广为所有异步竞态已消除。
5. 新设备反例：审批等待期间替换实现，再批准旧卡，调用被拒绝、新实现执行次数为零、旧批准不被消费。MCP/A2A 发布、权限、QuickJS 撤销/原生访问/超时取消、Goal 续跑保持既有事实边界。
6. Developer 第三轮因刚释放端口仍不可绑定，启动前拒绝，未算设备失败或通过；改用另一独占端口。第四/第六轮诊断在定位问题后主动中止，保留局部结果，不计作完整基线。最终第七轮完整结束。

原始结果分别在 `build/r1-api36-consumer-r1`、`r2`、`r3`、`r7` 及对应 Developer `r4`、`r5`、`r6`、`r7`。最终取证以 `r7` 为准；`closed.json` 证明该 runner 的模拟器进程已退出。每题 delivery 日志补充输入准入异常，位于忽略的 build 目录，不提交真实上下文。

## 真实模型逐题结果

| Case | 结果 | 取证时 Turn | elapsedMs |
| --- | --- | --- | ---: |
| `file-001` | PASS | `COMPLETED` | 3303 |
| `file-002` | PASS | `COMPLETED` | 1796 |
| `file-003` | PASS | `COMPLETED` | 3327 |
| `file-004` | PASS | `COMPLETED` | 4538 |
| `js-001` | PASS | `COMPLETED` | 2850 |
| `js-002` | PASS | `COMPLETED` | 16832 |
| `js-003` | PASS | `COMPLETED` | 4188 |
| `js-004` | PASS | `NEEDS_REVIEW` | 3527 |
| `skill-001` | PASS | `COMPLETED` | 13840 |
| `skill-002` | PASS | `COMPLETED` | 6873 |
| `skill-003` | PASS | `COMPLETED` | 5187 |
| `skill-004` | PASS | `COMPLETED` | 5409 |
| `goal-001` | PASS | `COMPLETED` | 未记录 |
| `goal-002` | PASS | `FAILED` | 未记录 |
| `goal-003` | PASS | `RUNNING_TOOL` | 未记录 |

PASS 按各场景目标判定，绝不意味着所有 Turn 都 COMPLETED：Goal 仅规划保持目标 PAUSED，额度场景保持预算边界，待批准写入场景验证审批阻塞且无未批准写入。超时/取消保持 UNKNOWN/review 事实。12 项提供 elapsedMs：median 4363ms、mean 5972.5ms、max 16832ms；3 个 Goal 未记录该字段，不算零。指标是 Harness 端到端耗时，不是 TTFT 或模型 token/s。

## 可复现与身份

```sh
python3 scripts/debug/2026-09-30/run-r1-device.py consumer <unique-run>
HELIX_R1_PORT=5568 python3 scripts/debug/2026-09-30/run-r1-device.py developer <unique-run>
```

仅在本轮设备与真实服务授权下使用；Developer runner 调用 `scripts/debug/2026-09-28/run-p5-sglang-suites.py`。服务须已位于本机 30008，模型 ID 必须匹配；不可达或输入不正确会失败，不静默 skip。脚本清除的仅是独占、临时模拟器上的测试包数据。

源码 manifest SHA-256：`a02527e4b97f2305ec76f4051d57def02f199d49f967f874dadc25b024b48261`。固定语料 SHA-256：`f27bf8b51e61be248a6e642c22cefc3e5045d0d35e37518377eb9b8cdf85e795`。完整本地汇总 `build/r1-api36-developer-r7/p5-sglang-summary.json` 保存逐 case、执行命令身份与 host 时间。

| 已安装产物 | SHA-256 |
| --- | --- |
| consumer app | `6b9cc2e6db8954c736ae10f67da7d061b6da75cf7b0244fa3b80aa7f669bb321` |
| consumer test | `dac89d26b4431e1ec093526b6cac412a1397fea3abe0525780a42b76e159a2c8` |
| developer app | `c67956ed1617c7305433c1f9fa429927de0314bc96e2d8158ff8e068df5ebb58` |
| developer test | `503fc8adbad95cc1544aebee1e8c0a489cff4799bb350bbfa6fa2dc4753f1ce7` |

## 未覆盖边界

真机/OEM、付费订阅账号、原生 Responses/Anthropic 服务端、MCP/A2A 真实远端重新部署、完整进程故障矩阵和 HXA-232 的真实恢复完成率仍未由本轮证明。历史输出截断、模型偶发多余调用与 BrowserDownloaderTest 偶发 redirect 404 保留原边界；不启动 R2/J1。

关联：[HXA-231](../../completion-records/HXA-231.md)、[前轮主机证据](hxa231-atomic-binding-2026-09-30.md)、[HXA-232](../../development/tasks/HXA-232.md)。
