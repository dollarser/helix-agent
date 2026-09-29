# QuickJS 总开关、结构化反问与提示词资源化

基线：本地 main `7480141b` 加既有未提交修改。所有者于 2026-09-30 授权实施；本轮只做主机验证，不使用模拟器、真机、真实账号，不提交/推送。

## 实现范围

- Runtime 设置新增默认关闭的 QuickJS 原生总开关，开启前显示文件（含应用私有数据）、网络、Android API 的权限说明。执行客户端在绑定前检查设置；运行中检查修订号，关闭再开启不会续用旧授权。解绑回收原生私有进程；已有副作用不作回滚承诺。
- `ask_user` 提供单选、多选、自由回答和跳过。问题/跳过记录进入持久消息，回答使用稳定请求身份和现有输入准入。仅完成的提问调用显示卡片；回答不携带输入框的附件，不清空其草稿，不授予工具权限。重开后从持久记录恢复，收到回答前模型不能假定同意。
- 默认工具面保留 `ask_user`，将 `time.now` 移到按需发现，继续保留两个发现窗口的容量和 64-tool 上限；文件核心工具不受挤压。
- Goal 三段指导、工具 intent、无进展警告/收尾、压缩指令抽离到打包 Markdown，保持原文、触发条件与可信来源。运行时事实、权限和状态机仍在代码中。
- 会话恢复时自动有界收集订阅/PRoot 原任务结果；只查询/收集，不重放任务。订阅明确仍在运行时最多 60 次观察，间隔上限 10 秒；连续查询失败或无可用结果最多三次。PRoot 未提供明确运行态时保持三次边界。沿用已有持久结果与回执身份，自动与手动收集串行；离开会话后延后观察，返回可重新触发。已有输出不会被后续空结果覆盖，不因此解除 UNKNOWN 或获得新执行权限。
- `helix.settings.apply` 将未来输入的会话默认配置修改接入原工具授权，当前 Turn 快照保持不变。LOCAL_MUTATION / DEVICE_SYSTEM_MUTATION 避免借用元数据豁免；执行身份、取消、时限、可选模型及排队输入重新校验。旧建议弹窗保留为可选入口，数据外发审批暂未统一。

## 主机验证

使用 `scripts/with-host-slot.py` 串行运行双渠道 unit/lint/debug APK/AndroidTest APK、model/agent/storage/automation/QuickJS 单元测试、detekt、spotless，以及源码检查。设备夹具只编译，不能写为设备通过。

中间失败保留：首次新增 Compose 导入不兼容；工具默认面容量测试发现超出预留窗口，保持原上限并移动按需工具；注册容器达到静态行数限制，抽离工具注册；测试使用项目未依赖的 coroutine-test，改为现有协程设施。均通过修正实现恢复门禁，没有删除或跳过失败测试。

最终应用批次 `build/quickjs-questions-gates-r7.log` 成功：consumer 934 项通过/4 项跳过，developer 982 项通过/4 项跳过，零失败；跳过仍为既有 Connector 外部材料/账号条件。双渠道 lint、debug APK、AndroidTest APK、detekt 通过。QuickJS 88 项及 model/agent/storage/automation 在 r5 全量批次通过；该批次只有后续已修正的反问 UI ReturnCount 静态检查失败。追加 `spotlessCheck`、源码/文档检查与 `git diff --check` 分别记录于 `build/quickjs-questions-spotless.log`、`build/quickjs-questions-source-final.log` 及最终差异检查；所有日志为忽略的本机产物。

## 后续收口检查

新增生产 descriptor 注册测试发现并修复 `ask_user` 使用不支持的 `uniqueItems`：重复选项仍由持久化前校验拒绝，未放宽该语义。新增设置工具同样通过 schema 注册及 effect/mode 边界测试。自动观察增加慢任务、持续运行上限、会话切换及取消测试。失效设置执行身份的设备夹具只编译。

本轮中间失败包括 schema 注册、Kotlin 跨模块空值收窄和回调引用、静态长度/函数数量检查；通过修正 schema、拆出自动收集组件及共享准入检查解决，不放宽门禁。完整应用主机批次 `build/closeout-host-r7.log` 成功：consumer 940 通过/4 跳过，developer 988 通过/4 跳过，零失败；双渠道 lint、debug APK、AndroidTest APK、detekt、spotlessCheck 通过。model/agent/storage/QuickJS/automation 结果位于 `build/closeout-host-r5.log`（该批次应用静态检查失败，不作整批通过）。最终源码检查日志为 `build/closeout-source-final.log`；设备 not requested，未提交或推送。

## 本轮一次性收口

- 原订阅/PRoot 执行器先查询，之后通过 Engine 创建最多一个 `auto-recovery:<parent>` 只读核查后继。原前驱、当前 Provider/模型快照、Stop/pause、预算与持久 claim 均再次校验。重复回调或 claim 后中断不重复执行；核查不能递归自恢复。
- Goal 核查保留原绑定，原 run 未结分配保守计费，新核查受原累计剩余额度约束；普通 Turn 扣除自身已用额度。核查最多四次模型调用、八个工具轮。关闭 attempt/未完成 Goal 不改写 ToolCall、result、review 或外部执行所有权，未证明的效果继续 UNKNOWN。
- 队列自动重验证原内容、配置、附件与授权身份；无有效恢复路径或再次失败进入 FAILED，保留原请求并显示持久结束通知。Stop/pause 后不自动投递。目标已结束的 Steer 可以尝试迁入 Queue，但仍须通过原绑定校验。
- 配置写入仅走 `helix.settings.apply` 正常工具授权；旧 propose 配置参数明确拒绝。数据外发和工具复用批准/拒绝交互，原 endpoint/内容/附件绑定不变；不是伪造一个 ToolCall 来绕过模型调用前的数据门控。
- 修复 SYSTEM 恢复通知被会话投影过滤，以及通知被当作模型回复显示重新生成入口的问题。旧查询/重试按钮改为可选操作，自动恢复不依赖点击。
- 补齐 Stop 与自动核查竞态：在 NEEDS_REVIEW/INTERRUPTED 上持久记录 USER_STOP，保留原未知状态；活动执行持久记录停止意图。新增夹具断言停止未知任务后不会启动核查，也不假称副作用已取消。
- 新增 JVM 测试验证停止/递归恢复拒绝、普通 Turn 剩余额度、Goal 累计用量不重置、终态/耗尽不获得新 run。增加设备夹具覆盖持久 claim 跨 coordinator 重建、原请求保留、Stop 不释放队列、缺 runtime 的一次性结束；本轮只编译这些夹具。

主机过程中发现并修复 Kotlin 格式、调度函数复杂度和 HXA 必需章节标题问题；中间失败日志不作为最终通过证据。最终结果见下表。

| 范围 | 结果 |
| --- | --- |
| core:model / core:agent / core:storage JVM | 158 / 195 / 217 通过 |
| QuickJS / automation JVM | 88 / 55 通过 |
| Consumer / Developer unit | 944 / 992 通过，各 4 项既有跳过 |
| 双渠道 lint / debug APK / AndroidTest APK | 通过；AndroidTest 仅编译打包 |
| spotlessCheck / detekt | 通过 |
| 源码检查 | 647 Markdown、215 HXA、35 ADR；806 production sources、1847 字符串键三语言一致；secret scan 通过 |

最终完整主机 gate：`build/all-closeout-host-final4.log`，BUILD SUCCESSFUL，2m27s；896 tasks（57 executed、839 up-to-date）。源码日志：`build/all-closeout-source-stop-final.log`。XML 汇总脚本：`scripts/debug/2026-09-30/summarize-closeout-tests.py`。这些统计只证明主机范围；没有真实模型恢复成功率、设备重复副作用率或实机性能测量。

## 未完成边界

- 设备状态 **not requested**。原生网络/文件/API、运行中撤销、客户端异常退出、反问 UI/重开/附件并行和自动收集仍待后续明确授权的定向验证。
- 原生能力共享应用 UID，仅适用于可信脚本；总开关不是任意原生代码与应用凭据之间的沙箱。
- HXA-232 代码路径已补齐，设备功能验收仍未完成。自动核查结束不自动解除 UNKNOWN，也不保证原任务成功；原执行器未证明退出时不能解锁副作用或重放。核查无模型/授权/额度可用时由 Harness 明确结束，不声称模型作出判断。
- 未启动 HXA-231 R1、发布、账号验收或全量设备基线。

关联：[HXA-232](../../development/tasks/HXA-232.md)、[QuickJS ADR](../../adr/runtime/003-quickjs.md)、[提示词目录](../../../app/src/main/resources/prompts/README.md)。

## 后续设备验证

上述 not requested 为本记录主机阶段的历史范围。所有者随后明确授权 API36，双渠道各 57/57 定向通过，并修复原生普通异常跨 JNI 传递；详见[设备证据](hxa232-api36-2026-09-30.md)。本记录不据此改写为全产品、真实模型或完整进程故障验收。
