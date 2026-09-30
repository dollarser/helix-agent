# 开发上下文导航：复用 CodeGraph

更新：2026-09-30。开发 Helix 时使用已有 CodeGraph、精确文本搜索、Git 差异与宿主范围读取，不再维护平行的代码解析器或资料包系统。这不是 Helix 产品的 ContextCompiler，也不增加构建或 CI 的必装依赖。

## 按问题选择入口

先读取当前用户请求、[根 AGENTS](../../AGENTS.md)、适用目录规则及 [status](status.md) 的相关工作项，再按需读取 HXA/ADR。大方案不默认全文加载；ADR 要核对状态与适用的 Decision history；历史研究与完成证据仅在溯源或回归调查时展开。

| 当前需要 | 优先入口 | 结果边界 |
| --- | --- | --- |
| 已知的小范围改动 | 宿主直接读取当前文件范围 | 不强制先跑图查询或整仓探索 |
| 符号、字段、常量 | `codegraph query` | 核对文件及限定名，同名不随意取第一项 |
| 源码与调用关系 | `codegraph node`、`callers`、`callees` | 图是导航线索，不保证解析全部动态调用 |
| 不熟悉的局部任务 | `codegraph explore` 或 `context` | 先限制文件/节点数，必要时再展开；不同时重复调用所有入口 |
| 变更影响与测试候选 | `codegraph impact`、`affected` | 候选不代表测试覆盖完整，不能替代任务要求的门禁 |
| 文档条款、错误码、资源键、未索引内容 | 限定文件/目录的 `rg`，随后读取原文范围 | 零命中只表示此次搜索未找到，不证明内容不存在 |

本机 CodeGraph 1.6.0 的命令已用 `--help` 核对；其他版本以当地帮助为准。已有 MCP 可直接使用；当前助手未暴露其 MCP 时，使用同一宿主的 CLI。WebCodex 某个内置语义导航入口不可用，不等于 CodeGraph 不存在；不因此重新安装或自建替代工具。

```bash
# 在目标项目根目录查询；从其他位置调用时明确 --path
codegraph query DEFAULT_MAX_ACTIONS --limit 3 --json
codegraph node --file tools/automation/src/main/kotlin/com/helix/tools/automation/AutomationTools.kt --offset 241 --limit 12
# 大范围任务先看少量关联结构，不灌入全部代码
codegraph context 'AutomationTools action' --max-nodes 6 --no-code
# 精确文本与文档标题候选；标题命中仍需排除代码围栏中的示例
rg -n -F 'sideEffectFree' tools/automation/src/main/kotlin/com/helix/tools/automation/AutomationTools.kt
rg -n '^#{1,4} ' docs/architecture/harness-refactor-plan.md
```

## 当前源码优先，图索引不是编辑凭据

索引有疑问时检查 `codegraph status`。首次定位或结果矛盾时，将图返回的位置与当前磁盘内容交叉核对；尤其注意未提交、新增、删除或改名文件。不要仅凭工具报告 up to date 或 Git HEAD 未变，认定所有结果都有效。

修改前使用宿主当前原文和 SHA 守卫，必要时读调用者、共享状态和测试。CodeGraph 不可用、滞后或查询缺失时，退回限定范围的搜索与读取，不阻塞局部工作，也不声称已经完成语义验证。索引维护遵循既有配置；不要为每个查询自动 init/index、删除 `.codegraph`、重启 daemon 或创建第二套缓存。

## 差异复核与短交接

先确定本任务起点和修改归属，再看相应 `git diff`；区分提交区间、暂存与未暂存修改，并用 `git status` 单独识别未跟踪文件。CodeGraph 的影响与测试候选辅助扩展调查，不把整个脏工作区都当成本任务改动。

交接在现有任务/会话中保留目标、约束、当前源码身份、改动路径/符号、适用条款、已跑测试及未解决问题即可，不再生成专用 JSON 包或复制完整规范。接手者检查实际差异，不只信任摘要。压缩、换会话或换模型后按需重读；宿主磁盘缓存和“以前读过”的标记不是模型记忆。

## 回传与维护成本

默认返回有界命中、相关源码、差异、增量日志和测试摘要；有截断或来源缺口时继续读取。测试需报告实际命令、退出码、数量与报告位置；零测试、未知和中止不能当通过。保留原始证据，不重复回传整份计划、日志或已加载 schema，也不设固定任务 token/文件数量上限。

新增开发辅助前先盘点已有 CLI/MCP 与实际缺口。优先改善使用方式；本项目不再为同类需求扩建代码图、符号解析、影响分析、资料包格式或 CodeGraph 包装层。CodeGraph 也不负责读取授权或业务决策，工具输出不能覆盖用户指令及有效 ADR。

是否降低总成本，用同任务的读取量、重复回传、往返、耗时与漏改评估；不能从片段字节减少直接推导模型 token、费用或成功率收益。此前自制工具的实验与退役原因保留在[历史记录](../evidence/development/dev-context-2026-09-30.md)，不再作为当前命令入口。
