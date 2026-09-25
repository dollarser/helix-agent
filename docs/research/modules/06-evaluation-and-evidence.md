# 评估、证据与研究方法

> 更新：2026-09-25。用于约束后续 research/eval，不是发布验收替代品。

## 1. 为什么需要统一方法

早期竞品/重构研究曾混用 README 声明、GitHub stars、设备结果、架构推断、模型自报完成、主观评分。后续研究必须把这些证据等级分开，否则“功能更多”“架构更严密”会被误写成“任务更可靠”。

## 2. 证据优先级

从强到弱：

1. 当前 Helix 源码 + fresh test/device evidence；
2. accepted ADR / completion record（注意日期和范围）；
3. 上游官方文档、维护者 repo/tag/release；
4. 可复现测试/源码审查；
5. 第三方文章/视频；
6. 社区评论/宣传语/下载量/star；
7. 模型推测。

动态能力必须记录版本/日期/平台；“桌面支持”不能自动算到移动端。

## 3. 竞品横评维度

不要给一个单一“总分”。至少分：

- 首次可用时间；
- task completion / result correctness；
- 人工步骤/approval；
- context/恢复；
- workspace/file/browser/device ability；
- permission/scope clarity；
- model/provider openness；
- resource cost（tokens、时间、电量、存储）；
- failure/recovery/duplicate effects；
- mobile UX。

按具体 persona/task 给权重，而不是宣布“谁最好”。

## 4. 推荐任务集

### 文件/工作区

- 整理一组合成文件；
- 修改小型项目并跑测试；
- 生成 Artifact 并打开；
- diff 与恢复。

### Web / Browser

- 固定归档页面研究；
- 表单/页面操作；
- screenshot + visual inspection；
- 下载/来源引用。

### Android 能力

- 测试 App 的受控 Accessibility/Intent/Shizuku/Root 操作；
- permission revoke；
- foreground/background；
- sensitive input。

### 中断恢复

至少包含：

- model request 前 crash；
- read-only tool 前/后 crash；
- side-effecting tool dispatch 后 crash；
- result durable 前 crash；
- user continuation；
- unresolved effect review；
- duplicate request/retry。

同时比较 same-Turn 与 successor-Turn 两种恢复策略的成功率、人工确认、重复副作用、上下文成本和实现复杂度。

## 5. Request context / cost

HXA-217 的旧预评估结论可保留：request manifest 应保存 stable identity/来源，不复制全部正文；成本测试要覆盖大 message count、长 identifier、导出单行/文件上限。

新的原则是：

- token cost 与 storage cost 分开；
- manifest 不成为第二历史源；
- source identity 可以诊断“模型当时看到了什么”；
- crash continuation 的 RecoverySummary 也应有大小/内容上限。

## 6. “模型说完成”不是结果验证

Harness eval 必须分开：

- agent/model reported complete；
- expected file/state/artifact exists；
- tests/checks actually ran；
- external effect happened once；
- user can locate/use result。

Goal completion 同样应该基于 evidence，而不是只看一段 final answer。

## 7. 移动端必须单独测

Desktop/remote Harness 的结论不能替代：

- API/OEM background kill；
- IME/small screen；
- SAF/Android permission；
- native ABI；
- memory/thermal；
- app process death；
- foreground-service/device automation。

设备验证仍遵守项目 owner-explicit 规则；Research 可以定义 eval，但不能因为写了设备命令就授权 AI 自动运行。

## 8. 当前公开竞品证据使用规则

- Codex/Claude/OpenCode/DSH 的公开 Harness docs 可用于机制对照；
- Operit/PalmClaw README/release 可用于公开产品能力，但不推断未公开内部状态机；
- 没看到某机制只能写“公开资料未显示”，不能写“产品一定没有”；
- star/download/社区热度不用于可靠性排名。

## 9. 研究进入开发的门槛

一个 research recommendation 至少要有：

- 用户问题；
- 当前 Helix gap；
- 候选方案与不采用方案；
- security/data/runtime boundary；
- 可测收益；
- 明确重新评审条件。

然后才能进入 ADR/HXA。没有这些条件时继续作为 research，不把“建议”塞进当前实现。
