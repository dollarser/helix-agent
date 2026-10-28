# Bug Fix: 综合审查确认缺陷整改

Status: fixed
Date: 2026-10-28
Related HXA: HXA-043, HXA-066, HXA-204
Affected modules: app, core/storage, core/workspace, tools/android, tools/files

## Problem

依据 [综合审查](../../reviews/2026-10-28/2026-10-28-comprehensive-audit.md) 及二次评审，修复确认的实现缺陷并澄清产品验收口径。整改开始于 `e48600e1` 加当时工作树；同 checkout 有并行自动恢复、知识库 UI 等修改。本记录不将那些修改归入本轮，也不把共享工作树的门禁结果归因于 clean HEAD。

不调整产品定位，不引入框架、持久缓存或内部版本升级。未操作设备、真实服务账号或远端，未提交或推送。

## Impact

错误 TLS 身份与过期网络权限影响新请求的授权边界；移动、提醒失败会误导副作用判断；并发目录刷新可能回退展示；历史/来源读取放大随数据增长。

## Root cause

底层 socket 缺少显式主机名验证，网络策略只读取初始快照；提交与投影未分别表达，编辑操作未绑定所见 revision；读与发布分离且关联查询逐行重读，分页消息未约束附属 Turn 查询。

## Fix and invariants

| 发现 | 修复与保留边界 |
| --- | --- |
| HTTPS 主机名未验证 | 保持已校验 IP 直连和原域名 TLS/SNI；开启 HTTPS endpoint identification，握手成功后才发送 HTTP 字节；握手受剩余 deadline 约束。 |
| 新连接复用旧网络授权 | 原调用授权快照作为上限；连接前、实际 peer 校验和发送前重新读取 live 策略。撤权收紧，不因中途授权扩大原调用范围。生产装配仍读取 profileStore 和用户 LAN scope store。 |
| 跨 scope move 部分副作用 | 目标发布后任何异常均转为 `WorkspaceMutationUncertain`；工具要求核查原始两端 scoped reference，不声称“未执行”，不删除已发布目标冒充回滚。 |
| 提醒已保存但投影失败 | `ProjectionFailure` 携带本次已提交记录/revision；编辑器保留草稿并更新自己的提交基线。正常权限降级不等同于异常。 |
| 旧提醒编辑页结束新 revision | 完成/取消必须匹配编辑页 revision 与 ACTIVE 状态；提交终态后投影失败，编辑页禁止重复保存/结束。 |
| 目录刷新旧结果覆盖新结果 | 服务内局部锁覆盖读取和 StateFlow 发布；不锁搜索或其他业务写入，不改变执行器全局并发模型。 |
| Knowledge origins 读取放大 | 每次加载一次读取并校验 origins，按稳定 ID 关联；没有跨操作缓存，外部修改仍可见。 |
| 分页消息仍加载全部 Turn | 消息/工具展示按实际 ID 批量查 Turn；最新可见及 retry target 在 SQL 选择；恢复面板按状态取有限窗口，额外 retry ID 单独读取，成功覆盖判断保留 startedAt/rowid 顺序。 |
| 恢复/进度附属路径重复扫描 | 预算续接用 SQL 存在性检查保留全会话未结算工具边界；进度仅分页候选 ledger Turn，保留较早有效 ledger fallback，不用任意截断隐藏结果。极端无效 ledger 历史仍可能翻多页，不承诺固定总查询次数。 |
| 准入解释重复 | 统一 settled/nonblocking 过滤到 TurnRepository；未知持久状态仍阻断。应用前检与事务内最终准入都保留，不以消重删除正确性边界。 |
| 产品格式/评测口径陈旧 | 区分直接附件拒绝与显式文档插件有界 PDF/DOCX 阅读；承认现有 45 条冻结集，同时不把它自动换算为 40 个用户任务 ≥80% 的验收结果。 |

提醒契约已追加到既有 [ADR-PLATFORM-005](../adr/platform/005-local-reminders.md) 的 Decision history。

## Alternatives considered

不重建架构，不增加持久缓存或内部兼容层；不以删目标文件回滚覆盖移动，不自动扩大网络授权，不以删除事务内复检“消除重复”。进度候选使用分页而非硬截断，保留较早有效记录。

## Regression verification

### 回归覆盖

- 本地可信证书正确/错误主机名、拒绝前无 HTTP 字节、重定向撤权、原授权不扩张、mutable scopes、防 DNS/握手时撤权和 deadline。
- 跨 scope 新建及覆盖目标后的源删除失败；同时断言真实文件状态和工具 requiresReview。
- 提醒提交后调度/撤销失败、保留已提交 revision 重试、并发修订保护、过期完成/取消、终态投影失败、正常通知权限降级与恢复。
- 双刷新读取/发布顺序；Knowledge N+M 读取、缺少/重复/无效 origins 与外部更新。
- 准入状态全集和未知状态、批量 ID 去重/空集；真实 Room SQL 的 superseded 过滤、恢复窗口、同时间戳顺序、ledger 翻页与预算未结算边界。
- 合成负载设备 fixture 改为真实 1000 个 Turn 及关联消息，而非只有 turnId 为空的消息。设备运行不是本轮授权范围。

### 验证状态

最终共享工作树 `ANDROID_HOME=… ./scripts/check-all.sh --debug` **通过，exit 0**。包括 300 项宿主脚本测试、文档/ADR/i18n/secret 检查、Spotless、Detekt、Debug Lint、全量 JVM 测试、双渠道 Debug APK 构建、AndroidTest 源码编译、42 份依赖锁以及渠道/Runtime 边界检查。HTTP 模块 75 项（桥接专项 12 项）、提醒专项 18 项、Knowledge 专项 32 项的最终 JUnit 报告均为零失败、零错误、零跳过；workspace/files/目录回归也包含在通过的 JVM 门禁中。

主日志位于忽略的 `build/audit-fixes-debug-acceptance.log`。本地 `build/audit-acceptance-source-inputs.json` 记录 3462 个构建源码输入；最终核对无变化。`build/audit-acceptance-artifacts.json` 记录此次成功门禁产出的 consumer/developer APK 身份；未安装到设备。记录补齐后的文档检查另行重跑，不把较早专项日志代替最终结果。

第一次集成 Debug 门禁在 Detekt 失败，涉及本轮代码的行长、wildcard import、复杂条件与目标发布后异常处理；采用局部格式化、显式 import、条件拆分和有理由的异常边界标注修复，没有降低门禁阈值或删除测试。后续结果以最终追加记录为准。

- Device：**not requested**；新增 AndroidTest 源码/编译不代表设备通过。
- Release/R8：未执行；本轮采用 Debug 迭代门禁。
- 真实 Provider、竞品 APK 横评、任务成功率、能耗/时延：未执行，不宣称达标。
- 历史审查中的失败仍是历史证据，不用新结果覆盖原记录。

### 提交后取消与页面恢复补充整改

二次修复复核确认：前述异常分支已携带提交 revision，但 `CancellationException` 仍可能在条目落库后、编辑器收到结果前传播。配置重建保留的原始条目/旧 revision 没有自动对账，导致保存重试冲突，或终结后仍显示旧操作按钮。数据库版本保护仍有效；此前 18 项提醒宿主结果不覆盖这个页面恢复边界。

补充修复在写入开始前检查取消，只把单次条目写入与提交回执发布置于最小不可取消段，随后继续响应取消；不把平台投影或整个操作移入不可取消域。当前编辑页 ViewModel 保留回执，重建后保留草稿，采用自己的提交 revision/终态；明确重新打开才重置回执。回执不是数据库最新状态，也不证明投影完成；并发其他编辑仍由服务版本校验拒绝，进程死亡后从持久条目重新打开，不依赖内存恢复权限。

新增宿主回归覆盖写入返回前取消、投影中取消、投影完成返回前取消、取消发生在提交前、失败写入无回执、保存恢复重试、两种终态恢复、并发修订拒绝及明确重新打开。新增 3 项 Compose fixture 覆盖保存/完成/取消后重建、草稿保留和按钮禁用，双渠道 AndroidTest 编译通过，未执行设备测试。

补充修复后的完整 `scripts/check-all.sh --debug` **通过，exit 0**；最终日志为忽略的 `build/reminder-cancellation-debug-acceptance.log`。包含 300 项宿主脚本测试、文档/ADR/i18n/secret、Spotless、Detekt、Debug Lint、全量 JVM 测试、双渠道 Debug APK 与 AndroidTest 编译、42 份依赖锁及渠道/Runtime 边界检查。最终提醒 JUnit 报告共 24 项（服务 14 项、工具 10 项），零失败、零错误、零跳过。

`build/reminder-cancellation-source-inputs.json` 记录的 4029 个仓库源码/配置输入在最终门禁完成后核对无变化；该核对不涵盖所有忽略文件或本机环境。`build/reminder-cancellation-artifacts.json` 记录本次成功构建的双渠道 APK 身份，未安装。补齐记录后另行执行文档/ADR 与 diff 空白检查。Device：**not requested**；未使用设备、未执行 Release/R8、未提交或推送。

## Residual risk

提交前整体复核（2026-10-28）：当前合并工作树重新执行 `ANDROID_HOME=… ./scripts/check-all.sh --debug`，exit 0；日志为忽略的 `build/closeout-2026-10-28/debug-approved.log`。302 项主机脚本测试、源码检查、格式/静态分析、Debug Lint/JVM、双渠道 Debug APK、AndroidTest 源码编译、42 份依赖锁及最终渠道/Runtime 边界均通过。首次复验因沙箱不能打开用户 Gradle 缓存锁文件而未进入构建，保留为独立失败记录；获准缓存访问后的成功运行才是本次构建证据。核对前轮 4029 个输入，Android 源码未变，差异限于开发指引收敛的四个主机脚本/测试文件；新门禁已执行这些测试。提交前另核对工作树指纹并补验文档，不将其他工作线历史设备结果归为本次执行。本轮未使用设备、真实账号或 Release/R8。

源码与宿主测试不证明设备 TLS 实现、提醒平台投影、Compose 操作和实际性能均通过。设备未请求；真实模型评测、竞品横评和产品成功率没有本轮结果。进度历史大量损坏候选时仍可能逐页查找，不承诺常数成本。

## Related records

- [原综合审查](../../reviews/2026-10-28/2026-10-28-comprehensive-audit.md)
- [提醒 ADR](../adr/platform/005-local-reminders.md)
- [产品要求](../product/requirements.md)
- [竞品评测口径](../product/competitive-evaluation.md)
