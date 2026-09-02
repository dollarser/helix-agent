# Helix Postmortem 约定

Postmortem 是向后看的系统性事故复盘，不是设计文档、ADR、HXA 完成记录或普通 [Bug 修复记录](../bug-fixes/README.md)。它回答的核心问题是：缺陷为什么能越过已有的测试、审查、设备验收或发布门禁，以及哪些耐久护栏能让同类问题下次明确失败。

## 写入阈值

只有同时具备以下特征才写 Postmortem：

- 缺陷已到达用户、真机验收后的已完成 HXA、已合并基线或发布产物之一；
- 根因不是一次性拼写，而是测试、工具、审查或所有权边界的系统缺口；
- 调试成本、安全/数据影响或重新推导成本足以让未来维护者受益。

没有 Postmortem 不表示没有 Bug；它只表示尚无符合上述阈值且已完成调查的事故。普通非平凡缺陷放入 `docs/bug-fixes/`。

## 命名与格式

文件名为 `NNNN-short-kebab-title.md`，编号递增且不复用。状态只允许 `resolved` 或 `monitoring`。

```markdown
# Postmortem NNNN: <标题>

Status: resolved

## Executive summary
## Impact
## Timeline
## Root cause
## Why existing safeguards missed it
## Guardrails added
## Lessons
## Related records
```

`Executive summary` 用一个短段说明破坏了什么、根因、为什么没拦住和耐久教训。`Timeline` 只写可验证事件，不猜测当事人意图。`Guardrails added` 必须链接测试、脚本、文档规则或 ADR；仅有“更加小心”不算护栏。

`scripts/check-docs.sh` 检查编号文件名、标题、状态和必要章节。
