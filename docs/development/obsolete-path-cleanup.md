# 历史路径清理与诊断收尾

承接 `0f69bdd2`，Owner 授权处理可修遗留并删除方案变更后的无效代码/文档；只做主机、模拟器和既有授权测试模型，不操作真机/生产部署、不推送。原有 `implementation-guide.md` 改动保留，不纳入本轮。

## 清理裁决

| 对象 | 无效证据 | 当前替代与边界 |
| --- | --- | --- |
| GoalEffect.RetryWake 与旧注释 | 无构造/消费、无序列化/注册；Goal ADR 已撤销异常自动新 wake | WakeFailed 精确结算 GoalFailed + ReminderCancelled；保留安全技术恢复与持久旧数据字段 |
| LinuxInputSnapshot 可空返回及两调用方 null 分支 | 实现只返回完整 hash 或抛异常 | String/异常契约；仍保留取消、输入限额、显式 snapshot 与原位空 manifest |
| Provider 旧草稿/UI 展示字段与能力徽章状态 | 生产无消费，当前 UI 直接读取 endpoint/模板说明、Provider rows 与选中身份；普通 Kotlin 状态，无 Room/序列化/Manifest/JNI 注册 | 删除重复投影，保留实际表单警示、能力 chips、历史绑定校验和两变体调用者 |
| 一次性宿主改写器 | README 明确禁止源码重放，非当前构建/测试入口 | 从 Git 移除；有复查价值者放忽略本地目录，不保留兼容入口，不删除正式 runner |
| 帮助 Debug 预览交接页 | 过时 APK 哈希与重复发行步骤，仅专题记录入链 | 独有早期失败与非发行边界合入帮助专题，当前发行要求统一到 release-readiness |
| status/ADR 旧阶段措辞 | 与已交付原位执行、失败补读及 Plugin 现行事实冲突 | status 只留能力/开放边界/唯一入口；ADR 正文维护当前规则，历史可追溯，不再维护第二份实时队列 |

UI、Provider、Runtime、工具/扩展、知识库/分享均按实际入口审查，不以 grep 无引用作为唯一依据。Provider 字段核对 main/developer、测试、Manifest/资源/keep/JNI；Goal 死类型无持久协议。未删除反射/JNI 入口或 native 资源，不因此增加本轮 R8 要求。

## 保留而非误删

- PRoot ZIP/PFD 日志/清单、collect/ACK、显式 snapshot 与持久记录读取仍在使用；它们不是旧的整份工作副本强制归档。
- ProviderBindingValidation、独立知识库问答与 Agent 只读工具、KnowledgeCaptureActivity 均有不同有效职责；分享 Activity 持有临时 URI 授权。
- HelixUserGuide/中文资源是 BuiltInSkills 动态注册/资源读取；MCP 协商传输仍为当前锁定 SDK 服务；PluginExecutionContext/TaskHost/NativePluginCatalog 有真实消费者。
- 必要 Android API 分支、外部 Skill 输入格式兼容及既有用户数据保留。没有确认无效的业务模块不为删减数量裁掉。

### 核心审查域结论

| 审查域 | 已确认裁决 | 未确认/未覆盖边界 |
| --- | --- | --- |
| 运行引擎与技术恢复 | TurnEngine/TurnCoordinator/SessionWorkScheduler、Runtime 私有服务、原身份日志/ACK 有生产调用，保留；只删除未产出的 RetryWake 和不可达 null 分支 | 没有证明每个持久旧字段都可删除；Goal.retries 等保留，不借清理做数据库迁移或撤销安全技术恢复 |
| UI 状态与 Provider | 旧徽章负载和草稿重复字段无消费，已删；现行 SelectedModelUi、Provider rows、历史绑定校验及权限/审批状态仍使用 | 不是所有页面状态桥的全量重构；未以命名重复推断其生命周期职责相同 |
| 工具/扩展注册 | 核对内置注册、MCP 协商和动态帮助资源；有效 NativePluginCatalog/PluginTaskHost/Skill 读取保留 | 外部包、平台兼容及所有反射/JNI 路径没有全量删除证明；本轮未动这些入口 |
| 知识库与分享 | KnowledgeCaptureActivity、资料存储/检索、独立问答与 Agent 工具、分享快照/临时 URI 授权职责不同，仍使用 | 本轮没有重跑所有知识库/真实外部 App 分享；19 项中的分享验证是草稿隔离，不能外推真实送达 |
| 脚本和历史文档 | 从 tracked 脚本候选核对实际改写目标，五份确定一次性脚本退出 Git；旧预览页合并，现行说明去冲突 | 其余下载器、探针、运行器及历史证据不因名字相似自动删除；没有对全仓每个声明/脚本宣称不可达或残留清零 |

## 日志与语音遗留

日志结果正文与执行 stderr 是两个不同资源。`tools.read_result` 增加明确 `contentKind=tool_result` 并说明原日志引用的含义；不新增自动读取、规划或重执行。新增 Encoder → persisted TOOL → strict History 的确定性契约验证，模型测试记录同链路实际工具历史，区分保存结果和实际读到 stderr。原失败保留，有限复验结果见下文。

ASR 使用上一轮同一合成 PCM/WAV 整理定位包，包含音频、帧序号、WS 原始事件、预期与实际、音频校验及调用方式；不包含凭据。HTTP/WS 字词差异只列质量未通过，不用删字/替换规则掩盖，不改服务端部署。

## 验证

证据统一保存在 ignored `build/cleanup-2026-10-22/`。验证区分针对性 JVM、两变体 Debug/AndroidTest 编译、模拟器、有限真实模型及完整 Debug 主机门禁；编译不等于实际设备或模型通过。

首轮定向验证：GoalReducer 26 项、App Standard 165 项 / Advanced 191 项 JVM 均零失败/跳过；包括 Encoder→History 的真实结果类型/原日志引用/退出码与原位空 manifest。两次 AndroidTest 编译准备失败分别发现旧 RowUi 位置参数，已核对全部 11 处构造并同步，未恢复旧字段或减弱断言。旧七栏 status 门禁也随新的四栏职责更新，完成记录索引及全仓链接检查保留；文档和 ADR 校验通过。

五份一次性源码改写器已保留到 ignored `scripts/local/debug/2026-10-22/retired-rewriters/`，逐份与 HEAD 原内容校验一致，并确认忽略生效；原跟踪路径删除，正式 runner 保留。两份另被怀疑的 `2026-10-10/local/fix-*` 经 Git 核实本来就是忽略文件，保持原处未修改、不计入删除。

ASR 定位包位于 ignored `build/cleanup-2026-10-22/asr-same-sample-repro.zip`。PCM/WAV 同一内容、逐帧序号/offset/hash 已核对；WS 原始 partial/final 保留。原 HTTP JSON 当时未留存，包内明确仅有原 final 文本观测，不伪造原始响应；提供当前双头 HTTP/WS 复现步骤，交由有权服务端人员继续定位。

正式请求装配核对：`FileToolArguments.modelSchema` 将工具描述和 inputSchema 发给模型，outputSchema 用于本地验证，不会自动教给模型结果字段含义。旧 read_result 描述把结果正文笼统称为 diagnostics，未解释独立日志引用；本轮在实际可见描述与返回 contentKind 中明确二者区别。Encoder → strict History 的定向测试确认引用/退出码不会仅停留在 UI 或存储信封。

候选两变体 Debug/AndroidTest、Spotless/Detekt 已通过。API36 ARM64 独立 Advanced 包的 19 项确定性设备回归通过：同会话结果/失败补读、跨会话拒绝、取消与期限、Provider 改名和绑定恢复、切会话与分享草稿隔离、选中模型实际进入下一请求，以及 PRoot 原位文件/日志/Room 重开/不重发。使用本地合成/loopback 测试，不操作真实外部分享。

有限模型复验一轮：真实账号登录、目录、短回复、重启恢复、刷新、退出与退出后拒绝均通过。自然日志任务 2 次模型请求、1 次 tools.read_result、0 次 read_log，Turn COMPLETED 但未取回 stderr 标记，仍失败。真实 strict 工具历史中保留 contentKind、logRef、logReadTool 和 exitCode；模型回答已引用正确退出码及原日志工具名，且承认可以进一步读取，但额外询问用户，未完成已要求的诊断。没有权限拒绝、网络失败或原命令重发；原命令提交一次。不能把契约改善归因为模型稳定性修复，也不追加请求追全绿；未强制流程或降低断言。

最终完整 `scripts/check-all.sh --debug` 退出 0，包含源/文档/i18n/秘密检查、两变体静态/Lint、JVM、Debug 构建、42 份依赖锁及渠道/Runtime 边界。专项 AndroidTest 编译和独立模拟器证据绑定本轮成功构建候选，来源记录与 APK 保存在本轮 ignored evidence；Standard 测试 APK 已编译，但未覆盖安装既有日常 Standard 包。清理只删普通 Kotlin 死类型/展示字段，不涉及反射/JNI/native 资源删除，故不增加本轮 Release/R8；Debug 通过不表示发行通过。

本轮临时 Advanced 主/测试包已卸载，账号退出、refresh 失效与目录/旧绑定拒绝已核实，凭据桥关闭、reverse 移除；保留既有三包和模拟器，不清用户数据。无审批或执行环境阻塞。原有开发指南 diff 保持不变。

本地分组提交：`1e710530`（结果/日志语义与模型历史证据）、`6426e772`（Provider/UI 负载及全部当前调用者）、`18227e89`（运行时死分支与现行目录说明）；文档/脚本清理独立提交，未 push。仍开放的是模型自主诊断未完成、ASR 字词质量与 TTS 服务端排队；本轮没有把这些服务/行为条件记为已修复，也没有通过删测试、强制工具调用或继续加模型轮次伪造成功。
