# 文档、审查与遗留内容收敛

2026-09-22；源码基线 `9a9b25dd`。所有者授权整理并提交未提交文档和审查材料，判断遗留内容是否需要合并，复核执行引擎审查。本轮不改生产代码，不实施新的引擎功能，不推送或发布。

## 整理结果

- 删除三份已完成交接指令，独有提交定位与验收边界保留在[历史交接汇总](completed-handoffs-2026-09-22.md)；旧原文在Git历史中可查。
- 压缩状态页和结构审查，移除重复调用链、过时源码行数及已失效的214/216实施顺序。当前入口、HXA-199和历史准备材料的链接/时间边界同步更新。
- [深度复审](../../research/execution-engine-deep-review-2026-09-22.md)按当前源码重写：关闭原R1/R5/R9，收窄R4/R8，保留R2/R3/R6/R7及各自证据限制和最小验收。
- 删除未提交的一次性 `consolidate-review-docs.py`；其效果已在文档diff中，重复执行不安全。保留可复跑的诊断探针和静态依赖清点工具，增加独立输出目录、拒绝覆盖旧基线和新XML检查。
- 整理前27路径已快照至ignored `build/document-review-convergence/before/`，含原始内容、删除状态及SHA；历史审查正文没有未经备份丢弃。生产/测试/构建文件1944项在探针前后SHA一致。

## 暂存区与stash核对

开始时暂存区为空。下表按固定SHA标识，避免后续stash序号变化；核对包括base→stash diff、当前文件内容/替代路径，不能仅凭Git祖先关系声称内容已合并。

| stash SHA | 内容 | 当前判断 |
| --- | --- | --- |
| `6c4c442f` | 最近main文档备份 | 本轮整理提交覆盖其有效材料；原UI头/状态顺序已过时，不应整包apply |
| `55f478f8` | 对话收敛WIP | 附件测试、处理器和市场页面有字节一致项；其余已被216/218/219演进吸收，无需重复合并 |
| `f7aa8d25` | 旧196后台命令原型 | 旧LinuxDetachedTools被DetachedJobRegistration/Tools的start/status/cancel/collect及所有权/预算路径替代；旧文件缺失不等于漏交付 |
| `1d9ec18b` | PRoot网络探针格式 | 当前文件字节一致，无需合并 |
| `0dc20628` | Root修复及调试WIP | 三个核心文件与五个调试脚本字节一致，其他差异是后续真实授权/恢复验收加强；不恢复旧版 |
| `5ed83257` | 旧UI和Autofill测试处理 | 当前readiness polling/socket取消处理替代旧JS workaround，AdaptiveFileControls被重构取代；无独立生产补丁待合并 |
| `bc9b7741` | M11重叠备份 | 诊断字节一致；LAN scope和Exif在当前模块/装配中保留，旧ADR/Runtime术语不能覆盖现行设计 |

未发现必须再合并的独立生产修复。文档整理已提交为 `b808d8b6`；随后经所有者授权，逐个核对上述完整SHA并删除全部7份冗余stash，清理后stash列表为空，未apply任何旧内容。不应用“当前blob不同”作为恢复旧实现的理由。详细本地核对记录在ignored `build/document-review-convergence/stash-audit.md`，该记录描述删除前的审查状态。

## 分支与工作区

已执行 `git fetch --all --prune` 后复核：已知远端工作分支ci-gate-performance、ci-sdk-bootstrap、conversation-convergence、hxa-214-ui、marketplace-catalog、marketplace-ui-tests、worktree-harness-2.0均无超出本地HEAD的提交。没有待补合并的这类分支；未删除远端引用。

继续保留main、BioHelix、browser-redesign三个worktree，以及历史v0.0.1本地分支。BioHelix为独立产品；browser-redesign的两项提交仍有[本轮合并审查记录](branch-convergence-2026-09-22.md)列出的未闭合问题，不因清理文档而盲合。v0.0.1是历史基线，不以其不属于main祖先为理由恢复旧行为。

## 验证与边界

5个定向探针实际执行，0失败/错误/跳过；其中R1验证修复不变量，其他四项验证诊断现象，不能称为5项缺陷修复。命令、输出身份和限制统一见[深度复审](../../research/execution-engine-deep-review-2026-09-22.md)。本次没有重新执行全量主机或Android设备验收；上一轮920项仍是固定制品的历史证据。

`./scripts/check-all.sh --source`通过：521份Markdown、202项HXA、36份当前ADR、1523个多语言资源键及密钥扫描均通过；审查Python脚本编译检查与 `git diff --check`通过。输出存于ignored `build/document-review-convergence/`。提交只包括本轮已审阅的docs与审查工具；不提交build证据、绝对主机路径、RootFS或账号数据。当前推送/CI仍未完成，不将本地提交当作远端验收。
