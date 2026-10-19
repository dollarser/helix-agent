# 本地知识库检索问答（2026-10-19 UTC）

范围：[HXA-312](../../development/tasks/HXA-312.md)，起点 `37a74df8`，初始工作区干净。Owner 授权继续第二阶段，真机手测已明确延后。不升级版本、不 push/发布、不清用户数据；无向量库、同步账号或新依赖。

## 实现与边界

- 原始来源、本次生成回答、可编辑知识笔记分开。问题先在本机进行有界词法检索，英文词/中文双字片段；最多展示 16 份候选，用户最多选八份，没有默认模型调用或默认上传全库。
- 命中附近最多三个窗口，正文总预算 24000 字符，另有片段位置/不完整标记。发送确认展示问题、实际所选片段和模型/目标；只发送该快照，不带其他来源、历史问答或笔记。源码仍复用现有 Provider/EgressDisclosure，源内容是不能授权工具的不可信数据。
- 模型必须正常完成、有至少一个有效所选来源 ID，拒绝伪造引用、无引用、拒答、截断、工具调用。引用身份有效不代表内容事实正确；用户可展开冻结片段核对。
- RUNNING/READY/FAILED/INTERRUPTED 存入独立问答记录；取消/重启不自动重发。确认前来源版本和 Provider 配置变化使发送失效。
- 显式保存原子创建 DRAFT 笔记和冻结引用并登记笔记 ID，重复保存幂等；后续来源删除不破坏已保存引用，不自动标已核对。
- `library.db` schema 2 只新增问答表；已检查 Room 生成的 1→2 操作仅 CREATE TABLE，没有删表、重建或清数据；五份既有来源/笔记/FTS 表导出结构逐项相等（`build/knowledge-qa/evidence/schema-additive.json`）。此为保留当前已收集资料的加法变更，没有历史预览兼容层；既有会话数据库不改。

## 主机验证

冻结输入后 `build/knowledge-qa-host-final.log`：双渠道 Debug/AndroidTest、R8 Release、JVM、Debug lint 和 detekt 全部成功，4 分 4 秒；`build/knowledge-qa-static-final.log` 格式化与静态门禁通过。JVM Standard 1317、Advanced 1452，各四项既有外部 profile 条件跳过；Storage 242，无失败/错误。新增检索单测两渠道各四项。

`scripts/check-all.sh --source` exit 0，日志 `build/knowledge-qa-source-final.log`：279 主机脚本测试、950 文档/295 HXA、40 ADR、国际化 2365 键及秘密扫描通过。门禁自己的失败/恢复夹具输出不是设备故障。首次迭代曾有未使用且不存在的 Compose 测试导入及格式/静态长行失败，修正后重新完整执行，未删弱断言。lint 效应分析耗时较长，线程快照确认仍在计算，最终正常结束；没有抑制 lint 或更换版本。复核补上重复确认的并发幂等，曾触发 ReturnCount 静态门禁；简化分支后先过静态再完整重跑，失败日志在 `build/knowledge-qa/history/return-count-build.log`，未以失败构建产物验收。

## 设备与真实模型

本轮指定日常 emulator-5554/API36/ARM64，最终成功候选实际覆盖安装，安装哈希与留存 APK 一致：

| 渠道 | 新增问答/引用/保存/防重/取消/恢复/离线 UI | 知识库与提醒回归 | 真实内置 Qwen 检索问答 |
| --- | --- | --- | --- |
| Standard Debug | 12/12，9.487 秒 | 38/38，39.848 秒 | 1/1，5.234 秒 |
| Advanced Debug | 12/12，8.849 秒 | 38/38，41.187 秒 | 1/1，8.539 秒 |

真实模型使用现有内置 Qwen3.8-27B，隔离数据库仅收集合成 Harbor 预算、负责人和无关 Zephyr 材料。用户问题不含答案；本地检索排除第三份标记材料，模型实际正文保留 137、TEAM_SYNTH_42 并引用两份来源 ID；正文没有应用追加引用清单。生成后笔记仍为空，显式保存才出现 DRAFT 和两份冻结引用。专用日志只留输入/输出摘要与效果，不记录私人内容，没有新账号或付费 fallback。

恢复测试由预置 RUNNING 记录后重建服务验证；未强杀正在调用的真实模型进程。原始材料变更使待确认请求失效，取消/错误/伪造或缺失引用不能保存为知识，来源删除后冻结引用仍可核查，重复确认不重复模型执行且重复保存只得一份笔记。UI 验证搜索、选择、未选模型不发送、无结果及显式保存。

两渠道偏好字节（排除进程诊断）、麦克风/通知授予、精确闹钟 app-op、时区和 IME 均与本轮基线一致；测试 APK 移除，普通应用保留。所有问答 fixture 使用独立数据库并清理；不使用私人资料、麦克风、扬声器或真实通知。

实际候选、源码/测试输入摘要及 JVM XML 汇总：`build/knowledge-qa/evidence/provenance.json`；双渠道 APK 归 `build/knowledge-qa/deliverables/Helix-{Standard,Advanced}-{debug,test,release-unsigned}.apk`。安装与 instrumentation 日志在 `build/knowledge-qa/devices/emulator-5554/`。Release 未签名，仅证明 R8 构建，设备功能验证使用普通 Debug。

## 保留限制与后续

词法检索不等于语义召回；同义词/大库分页和 OCR 没有因此实现。网页原文/登录/完整性边界沿用来源收集阶段。其他设备/OEM 和真机最终手测 pending，不能用模拟器或引用校验声称模型事实全对。自然语言提醒与最小定时 Agent 仍按已授权顺序推进，不能从本阶段完成推定它们已实现。
