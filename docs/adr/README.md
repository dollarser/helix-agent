# Helix 架构决策记录约定

ADR（Architecture Decision Record）保存代码、测试和规范文档不适合承载的决策理由：为什么选择当前方向、比较过哪些替代方案、付出什么代价，以及什么证据出现后应重新讨论。ADR 不替代 HXA 任务、架构规范、测试或 `docs/development/status.md`。

## 1. 权威性和状态

- 当前行为以生产代码、测试以及 `docs/` 中明确声明的规范性章节为准。
- ADR 记录决定及其理由，不是“功能已经实现”的证明。`accepted` 只表示决定被接受；实现状态和验收证据写入 HXA 完成记录及 `docs/development/status.md`。
- ADR 与当前代码或规范冲突时，小模型必须停止并报告具体冲突，不得自行选择一边或静默改写 ADR。
- 不使用生命周期目录移动文件。移动会破坏引用；所有记录保留稳定路径，状态写在文件头。

允许的状态是：

| 状态 | 含义 |
| --- | --- |
| `proposed` | 候选决定，仍需授权者评审；小模型新建 ADR 的默认状态 |
| `accepted` | 决定已被明确接受，但不表示代码已经实现 |
| `rejected` | 候选决定被否决；正文必须保留理由和重新提出条件 |
| `superseded` | 已被另一 ADR 完全取代；必须链接新 ADR |

不使用 `implemented` 作为 ADR 状态。实现是否完成必须由真实代码、测试、设备证据和 HXA 验收决定。

## 2. 稳定文件名

文件直接放在 `docs/adr/`：

```text
NNNN-short-kebab-topic.md
```

- `NNNN` 是四位、单调递增且不复用的编号，例如 `0001`。
- 文件创建后不因状态变化而改名或移动。
- 标题、文件编号和 `ADR-NNNN` 必须一致。
- ADR 之间及 ADR 到其他仓库文档只使用相对 Markdown 链接；不得写本机绝对路径。
- `README.md` 不是一条 ADR，不占编号。

新建前执行：

```bash
rg -n "<机制、模块、协议或候选方案关键词>" docs/adr docs
find docs/adr -maxdepth 1 -name '[0-9][0-9][0-9][0-9]-*.md' | sort
```

先确认没有覆盖同一决定的现有 ADR，再选择下一个编号。不得并行猜号；发生冲突时重新编号尚未合并的记录。

## 3. 什么时候必须写 ADR

出现下列任一情况时，当前 HXA 必须新增或取代一条 ADR：

- 改变安全、权限、审批、凭据或信任边界；
- 选择或更换 native runtime、RootFS、模型协议、持久化方案或跨进程执行底座；
- 改变跨模块公开契约、数据格式、IPC、签名或更新策略；
- 引入新的 Maven repository、重要第三方组件，或形成许可证兼容性决定；
- Spike 在多个可行方案中形成正式结论，尤其是 HXA 原文要求“产出 ADR”时；
- 推翻、部分替代或完全取代现有 ADR；
- 作出会约束多个后续 HXA、难以回滚或需要发布者承担长期成本的决定。

下列变更通常不需要 ADR：

- 在既有契约内实现一个 HXA；
- 不改变行为的格式、拼写、链接或机械重命名；
- 不改变测试策略的局部测试补充；
- 不改变架构或外部契约的普通 bug 修复；
- 只更新 `docs/development/status.md` 中有命令证据支持的进度事实。

拿不准时，小模型应在完成记录中写明疑点并保持 `proposed`，不能为了省事写“无需 ADR”，也不能擅自接受架构决定。

## 4. 小模型权限边界

- 小模型可以搜索、引用和起草 `proposed` ADR。
- 只有 HXA 明确要求形成决定，或项目所有者/授权审查明确给出结论，并且 ADR 写入相应证据时，才能设置 `accepted` 或 `rejected`。
- 小模型可以随实现同步事实性引用，例如路径、符号、版本和测试命令；不得借“更新事实”改写原决定、替代方案或历史理由。
- 如果实现要求改变已接受决定，小模型必须停止实现，创建或建议 `proposed` 的取代 ADR，并等待确认。
- 不得把设计文档中的未来时态转换成“已实现”结论，也不得为尚未完成的 QuickJS、PRoot、CLI、Root 或 Accessibility 能力伪造验收证据。

## 5. 必需格式

每条 ADR 使用以下模板：

```markdown
# ADR-NNNN: 简短决定标题

Status: proposed
Date: YYYY-MM-DD
HXA: HXA-NNN
Deciders: pending
Supersedes: none
Superseded by: none

## Context

描述不依赖候选方案也成立的问题、约束和已验证事实。区分官方事实、仓库事实、实验结果和推断。

## Decision

写明提议或已接受/否决的决定及适用边界。`accepted` 使用现在时描述决定，不声称尚未实现的能力已经可用。

## Alternatives considered

至少列出一个真实替代方案、没有选择它的原因，以及它在哪些条件下可能更合适。

## Consequences

同时记录收益、代价、迁移影响、后续约束和仍然存在的风险。

## Verification

列出支持决定的真实命令、测试、设备、artifact 或外部依据。尚未执行的项目标为“required before acceptance”，不能写成通过。

## Reconsider when

列出重新讨论的可观察条件，例如关键 Spike 失败、平台政策变化、依赖停止维护或性能/体积超过已批准门限。

## References

- [相关规范](../architecture/overview.md)
```

规则：

- `Status`、`Date`、`HXA`、`Deciders`、`Supersedes`、`Superseded by` 六个字段必须存在。
- `accepted`/`rejected`/`superseded` 的 `Deciders` 不能是 `pending`。
- `Alternatives considered` 不能只写“无”，除非正文解释为什么问题没有可比较方案。
- `Verification` 必须区分已执行证据与未来验收要求。
- `rejected` 必须在 `Decision` 中直接说明否决理由，并在 `Reconsider when` 写明重新提出条件。

## 6. 取代和更新

写新 ADR 前必须执行同主题检索：

1. 只是路径、符号、版本或命令漂移：在原 ADR 中最小更新事实性引用，并与代码变更同一任务提交。
2. 部分改变决定：保留旧 ADR，新增 `proposed` ADR，双方在 References 中交叉链接并写明未被改变的范围。
3. 完全取代决定：新 ADR 的 `Supersedes` 指向旧 ADR；批准后把旧 ADR 状态改为 `superseded`，并设置互相一致的 `Superseded by`。
4. 不删除仍有独特理由、替代方案、后果、验证方法或重新提出条件的旧 ADR。

不得通过重写旧 ADR 让历史看起来从未发生过。Git 历史是补充证据，不代替文档中的显式取代关系。

## 7. HXA 交付和门禁

每个 HXA 完成记录必须包含：

```text
决策记录：ADR-NNNN（链接和状态），或“不适用：<为什么本任务没有形成架构决定>”
```

HXA-002 提供 `scripts/verify-adr.sh` 并接入 CI，至少检查：

- 文件名、编号和标题一致且编号唯一；
- 状态属于封闭集合，拒绝 `implemented`；
- 必需字段和章节存在；
- `accepted`/`rejected`/`superseded` 不使用 `Deciders: pending`；
- 仓库内相对链接可解析；
- `superseded` 和取代方字段互相一致；
- ADR 不包含本机绝对路径。

脚本只检查机械契约，不用关键词猜测决策质量，也不以禁止 `Plan`、`Acceptance criteria` 等词替代人工审查。

## 8. 当前回填原则

不为了填满目录而批量制造历史 ADR。只有在能从现有规范、代码、测试或项目所有者决定中恢复真实理由时才回填；否则等待对应 Spike/HXA 产生证据：

- HXA-050 的 QuickJS/Zipline 结论在 Spike 后记录，不能提前标为已实现。
- HXA-111/112 的 CLI 原生/PRoot 底座在 arm64、ABI、运行时和工具拦截证据完成后记录。
- 已写入 `AGENTS.md` 的禁止项仍是规范约束；rejected ADR 可补充理由和翻案条件，但不能取代这些规则。
