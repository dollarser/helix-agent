# main 本轮收尾验收审计

日期：2026-09-08。范围按所有者要求为 M0～M11 合并后功能、非真机/非长稳测试及 HXA-147 统一交互。M13 并行受保护服务验收保持自身状态。最终索引与证据审查已通过，本轮约定范围完成；未创建本地提交或推送。

## 原始要求与证据

原始日志和指纹保存在本机忽略目录 `build/main-verification/`；可复现命令、失败修复及适用限制由链接文档保存。已验收的旧轮次保留其版本，不冒充最终APK全量重跑。

| 要求 | 已核对证据 | 结论与边界 |
| --- | --- | --- |
| M11合入后的范围 | [交接](m11-handoff.md)、main基线4572911、[验证报告](main-merged-verification.md) | 覆盖合并后的当前源码，非仅M11分支编译结果 |
| Goal创建/显式Continue、关联及预算 | [HXA-102](../completion-records/HXA-102.md)、[边界对应表](hxa102-boundary-audit.md)、`final-app-matrix-summary.json` | 生产UI/服务/Room接线及恢复已验证；创建/扩预算不自动执行，副作用不明不重放 |
| ADR-0028完成证据 | [accepted ADR-0028](../adr/0028-goal-criterion-verification-bindings.md)、HXA-102真实write/edit/PRoot、人工复核、证据读取取消/强杀记录 | 用户绑定条件、同Goal来源、完整不可变产物验证与无未决调用后完成；架构接受与实现证据分列 |
| 真实模型固定评测 | [HXA-100](../completion-records/HXA-100.md)与验证报告中的45项统一结果、三项Goal固定评测 | 真实SGLang支持Responses/Chat/Anthropic协议；早期失败保留，当前账号/模型/时点边界明确 |
| PRoot/CLI持久恢复 | [PRoot四项对照](proot-result-durable-recovery-gap.md)、[CLI对照](cli-result-durable-recovery-gap.md) | 原Job取回、私有保存、精确ACK、离线回读、过期/容量、取消/强杀矩阵；无盲目重放 |
| 全仓JVM/静态/构建 | `hxa147-final-host-result.json`、`hxa147-final-host-audit.json` | 2713/2713、34个实际Test任务、零失败/错误/跳过；根Debug/Release Lint、Spotless/Detekt、8个App/Runtime构建产物。不是签名发布验收 |
| 合并后模拟器矩阵 | `final-app-matrix-summary.json` 946/946；`post-private-delete-device-result.json` 30/30；M11与模块专项见验证报告 | 功能阶段完整矩阵，后续生产变更按影响补验；946不是最终UI APK的新全量运行 |
| 非长稳资源边界 | `current-resource-api35-result.json` 14项、API35浏览器2项、`resource-pressure-release-summary.json` 双API真实压力/恢复 | 证明短时资源门控；不把controlled kill当自然LMK，也不把forced idle当自然Doze |
| 统一交互/竞品参考 | [HXA-147完成记录](../completion-records/HXA-147.md)、[官方资料调研](hxa147-interaction-research.md) | 导航/Provider/工具摘要、审批/停止/重试/恢复、空态、小屏/2倍字体/中英文与可访问性分项完成；不声称实际操作竞品或TalkBack语音验收 |
| 最终关键流程 | `hxa147-required-device-audit.json` 34组/50项，另长文本28、审批24、文件40、M11聊天8、恢复4组 | 原始状态码与安装hash核对；API36真实Goal两组截图提取失败保留，补采原文件与原设备证据匹配，没有重跑模型 |
| 权限与并行修改 | accepted ADR-0004/0012/0028、CLI边界/凭据扫描、逐路径暂存审核 | 独立Runtime凭据不进入主App；HXA-125业务验收不标完成；ADR-0009只接受有界架构和非生产Spike |
| Git交付 | `hxa147-git-scope-review.json`、最终索引检查 | 按确切路径暂存当前收尾修改；不执行整目录git add、不提交缓存/下载RootFS/签名/凭据。本地提交和推送尚未执行 |

## 最终快照差异

完整宿主测试期间1185项源码配置指纹不变。之后的代码差异仅为 `ProotConversationNavigation.kt` 等待目标会话及目标工具行的测试修正，已有独立API36复验通过；`HelixDatabase.kt`更正迁移注释版本归属，没有SQL或执行逻辑变化。脚本仅删除一处行尾空格，32个Python/Shell脚本语法检查通过；`.gitignore`新增Python字节码忽略规则。8个生产APK保持宿主验收时的指纹，不为上述注释/测试/忽略规则变动宣称新APK执行。

逐路径审核最初展开584项，其中20项为Python字节码缓存；仅忽略、保留本地缓存，不纳入提交。新增忽略规则和本审计文档后，以最终索引清单为准。此前中间批次暂存不是可独立构建提交；最终完整索引与工作树已核对一致，566个交付文件均已暂存，无额外未跟踪交付文件，缓存不在索引中。

## 后置事项

- API29浏览器/API36应用24小时长稳及浏览器JNI弱引用/Binder累积调查：按所有者决定本轮不启动，保留历史FAIL。
- 物理设备、自然LMK/Doze、OEM热/后台限制、商店签名与发布验收：不在本轮完成条件。
- Claude/Grok直接订阅付费调用、并行HXA-125受保护服务/WorkBuddy真实来源样本与撤销验收：保持未核实，不以公共fixture、匿名调用或Copilot调用Claude代替。

当前只关闭本轮明确范围；未来动态模型目录、新协议、Git Workspace、子Agent生产启用等计划不因本轮验收自动实施。


## 最终核对结果

`main-closure-final-audit.json`重新解析34个任务的原始JUnit XML，2713项零失败/错误/跳过，与汇总一致；对应Test任务均实际执行。8个生产APK hash未变化，1185项原源码配置仅有上文已说明的导航测试和迁移注释差异。566个暂存文件逐字节与工作树一致；`hxa147-git-scope-review.json`覆盖所有交付路径。最终docs279文件/131任务、ADR28、i18n932键、凭据扫描、Runtime边界和cached diff检查通过。最终状态文档修改仅属于该收口记录，随后重新执行文档/ADR/diff门禁。
