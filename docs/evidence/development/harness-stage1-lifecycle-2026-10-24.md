# Harness 阶段 1 C：中立 Turn 生命周期

日期：2026-10-24；承接本地 `9fe88ca5`。最终源码与两渠道各 103 项独立模拟器通过；最后完整 Debug/Release/R8 主机门禁 7 分 28 秒通过。首轮候选与修正分别记录，不外推未运行项。v0.0.7 标签、Release 与 APK 不变，后续 checkpoint 仅本地提交。

## 实施边界

- `core/agent/runtime` 统一持有 TurnEngine、driver、live registry、取消信号、启动门和观察接口；Core 只接生命周期与 round 原子端口，不接 App、Room entity、Android、工具 Framework 或 UI。
- `core/agent/turn/TurnLifecycleStore` 是闭合准入、停止、review、恢复与结算命令。普通提交、Queue 与 inspection 共用同一准入；移除任意 `freshGuard`/`resolveGoal` 事务回调。
- `app/infrastructure/agent` 唯一实现真实 Room 原子命令。事务内重验会话原始 nullable model、Provider、Queue revision/head/完整配置、恢复前驱及 Goal 意图；重复 receipt 仍先返回，不提前创建 Goal。
- StopTurnCommandHandler 真实迁入 Core，复用同一 engine gate/store/live owner；应用只提供原 Goal 交接和审批取消端口。先持久停止事实再 signal，不把取消当执行退出或副作用回滚。
- 执行异常与结算异常分开处理。提交拒绝/异常不改写成第二次 FAILED 提交、不重启模型；终态已提交但收尾失败时保留终态，队列单独停泊，本地交接撤销。
- Goal edit、迟到 Steer 与 inspection 收尾由真实 Room 实现统一提交，返回 typed receipt；ChatService 仅消费丢弃的 Goal 交接事实并展示/通知。Goal ownership 与 edit 只有一份 writer，原有效手动服务复用它。
- Goal deadline 是中立能力，原 lease 的分配/交接/记账仍为 App 适配；没有第二份计时器或权限状态。共享审批等待取消异常迁入 core:model，旧 App 子类和旧 engine 转发路径删除。

目录迁移随职责完成，不改变应用 ID、签名或用户数据。Room Android 模块不因目录名含 core 就被称为纯 JVM；本阶段没有新增模块、常驻服务或 RPC 框架。

## 当前验证

Core 最终 JVM 354 项、工具框架 261 项通过；App Standard 1467、Advanced 1629 项零失败，各 5 项既有跳过，两个 AndroidTest 编译通过。新增生命周期 13 项、停止 7 项及其他迁移/边界测试均保留。独立 Standard 首个候选真实 Room/模拟器 93 项通过、零跳过，实例关闭。随后为静态规则拆分准入 helper，并将既有恢复测试转入 Core Engine→真实 Room 入口，最终重新构建的 Standard/Advanced 各 102 项通过、零跳过。两次自建实例均关闭，日常模拟器未改动。最终完整 Debug（含 spikes）4 分 49 秒通过，锁、渠道与 CLI Runtime 边界通过；最终包含 spikes 的完整 Debug/Release 主机组成一次通过，6 分 48 秒；独立 Release smoke 测试 APK 编译与 Lint 通过，未在本轮设备执行。不以已编译、内存 fixture 或前一阶段候选替代本轮功能验收。

生成日志、候选身份与原始测试结果保存在忽略的 `build/refactor-stage1-2026-10-24/`。原有指南修改和未采纳知识库前端提案继续排除；无真机、真实账号或外部分享操作。

### 首轮失败与修正

格式门禁发现新代码长行与 wildcard imports，按现行规则修正，没有改全局规则。App 编译暴露迁移后的 review DTO/恢复引用，全部当前调用者直接更新。Core 新集成测试首轮 354 项中 4 项失败：fixture 必需元数据方法仍误留未支持，导致 provider 前 fail closed；手工 UNKNOWN 消息无文本/工具调用，违反真实消息约束。补齐校验与实际内存记录、换成匹配原调用的合法样本后，原断言全部保留并通过。内存网关的合成拒绝只证明 Core 回填行为，完整授权由真实 Dispatcher/Room 模拟器回归验证。

源码 fixture 初轮因设备 eval/健康探针保留旧测试包名失败，选择器随目录一起迁移后 297 项通过；文档/ADR/国际化/秘密扫描通过。初轮完整 Debug 被 Detekt 拦下，原子准入按 fresh admission/期望校验/记录提交拆分；不跳过规则、不把旧模拟器候选当最终源码通过。

### 最终模拟器范围

API 36 ARM64 的独立验证包使用合成资料/loopback/mock provider，无真实账号或外发。覆盖 closed admission、原子 terminal store、review、expert 快照、停止/显式会话提交、Goal lease/分离预算、自动恢复持久事实、round/Steer、真实审批、工具失败结算、定时执行及持久重开恢复。恢复测试通过实际 Core Engine 进入 Room，未把单进程存储重开称为 Android 真进程死亡。

Advanced 首次启动在 emulator 创建前因端口仍占用退出，未执行测试；核实后改空闲端口，最终执行通过。没有全局杀进程或重启现有构建。所有最终源码输入与冻结验证候选一致；本轮无 Manifest、应用 ID、版本、签名或数据库 schema 变更。独立验证 suffix 是既有测试构建能力，不属于正常发行包。

## 最终门禁与交付边界

- 主机全门禁组成：`spotlessCheck detekt lintDebug lintRelease lintConsumerDebug lintDeveloperDebug lintConsumerRelease lintDeveloperRelease test`，并构建两渠道 Debug/Release 与现行 CLI/PRoot Debug/Release；同一单次 daemon、6 GiB、单 worker，全部通过。日常迭代使用 Debug；阶段稳定候选集中检查，最后发现本地化回归后重新验证受影响的最终源码。
- `includeReleaseSmoke=true` 的 `tests:release-smoke:assembleDebugAndroidTest` / `lintDebug` 通过。该结论仅为编译，不是 Release 设备通过。
- 锁文件 42 份、正常 Debug 两渠道边界、CLI Runtime 边界及真实 Release APK 的集成 Runtime/进程/资源/许可证边界全部通过。正常 Release 为本地未签名 R8 构建产物，未发布、未使用新签名；不继承 v0.0.7 发布签名/设备结论。
- 导入/退役路径架构检查 7 项，297 项宿主 fixture，文档/ADR/国际化/秘密扫描通过；文件移动同步当前调用者、测试包、eval 与健康探针。历史源码导航固定到已公开基线，当前状态不再沿用“未实施”旧结论。
- 最终两渠道独立 Debug 候选均为 102 项、零失败/跳过。冻结源码输入与最终工作树一致；真实 Room 与内存测试分别举证，模型/网关为合成，未做真实服务、真机、OEM/长稳、正式签名或跨版本升级验收。

旧 App Engine、stop handler、重复 lifecycle/review DTO 和平台取消子类均已删除，无旧包 alias。核心权限/原序结算/UNKNOWN、真实资源退出与副作用事实不因目录调整改变。状态与配置仍各有单一权威，没有新业务恢复状态机、执行进程或 RPC。

本片不替换 Memory 默认策略，不增加日志搜索、远端宿主、知识库同步或未采纳的 UI 原型。未完成能力/策略实验继续由原 HXA 管理，不把结构通过当模型必然找回正文或日志。

### 最后复核：恢复提示按当前语言发出

Root 发现新增 Room composition 将恢复失败文案过早解析为容器创建时的字符串，且直接使用 application Context 会忽略应用自选语言。现改为窄文案 resolver，在实际写入时调用既有 `resolveLocalized` / strings；它只提供本地显示文本，不是准入谓词或事务写入 hook。新增真实 Room 回归验证切换 resolver 后发出的提示使用当前值、重复收尾不改写历史。该修复的格式/Detekt、两渠道单元与 AndroidTest 编译通过；最终新独立候选 Standard/Advanced 各 103 项通过、零跳过，两次实例退出确认；完整 Debug/Release/R8 主机组成 7 分 28 秒通过。此前通过不冒充修复后的结果，必要重跑由此具体新回归触发。

最终主机日志为 `c-locale-full-gate.log`，实际源码/四份独立 APK 输入身份为 `c-locale-candidates/identity.json`，原始设备结果为 `c-locale-consumer-device/` 与 `c-locale-developer-device/`。这些位于上述忽略的证据根目录。先前 93/102 项结果仍仅对应其原候选；最终 103 项额外覆盖文案时机/历史幂等。

Release 产物来自成功构建的 output-metadata 指向的未签名 APK，不使用旧签名包或失败构建遗留文件。构建时基于 `9fe88ca5` 加本片工作树，后续提交只固定同一源码内容；构建内 VCS 元数据不冒称是尚未产生的提交。没有为提交重建字节未变的候选。

本片源码与测试固定为本地 `f7de1dfbfa6e77d66cb2882a6bdda672ef84e70b`；未推送。文档跟随下一独立本地提交。原有指南两行改动、未采纳知识库原型未纳入本片。

### 后续语义复核更正

后续效果实验准备发现 Goal continuation 资格查询会间接调用持久 budget blocking，故本文关于终态 hook 仅本地交接的表述过强；不是由既有测试数字证明不存在该路径。本轮按同一生命周期目标实际修正，范围、回归与结果见[后续记录](../../development/memory-log-effect-experiment.md)。Core 无 App/Room 依赖、当次真实测试通过与旧包删除仍是有效事实，不扩大为整个应用命令均纯中立。

后续修正验收：Goal 资格查询与 Room 结算相关的独立 Advanced/API 36 模拟器共 33 项通过、无跳过；本地证据为 `build/memory-log-experiment-2026-10-24/goal-device/`。两渠道单元检查、AndroidTest 编译与静态检查通过。此结果只覆盖该补丁，不将此前 Release 候选的通过结果转记为补丁的发行验收。
