# ADR-0031: Connector 版本所有权、会话 scope 与安装 journal

Status: proposed
Date: 2026-09-08
HXA: HXA-129
Deciders: pending
Supersedes: none
Superseded by: none

## Context

ConnectorService 目前先提交 Skill snapshots，最后原子发布单个 Connector JSON；部分失败可能留禁用快照。remove 已检查其他 Connector 对相同 SkillKey 的引用，但尚无独立 Skill 安装与 Connector 引用的完整 owner 记录。端点级启用与 Skill 全局/会话 override 仍分别维护，尚不能保证跨存储更新、统一 scope 与恢复的原子视图。

HXA-129 要求共享 Skill 所有权、会话 scope、更新 diff、安装 journal、原子视图与 rollback。这是持久化格式和启用契约扩展，不只是给安装器新增按钮；ADR-0023/0029 的已完成首版不能替代此决定。

## Decision

提议采用现有快照及原子文件上的版本清单和可恢复 journal，不把 Skill/MCP 合并成另一套执行引擎。本记录未批准；原有安装和启用语义在批准实施前保持不变。

### 1. 身份和所有权

Connector 有稳定 connectorId 与不可变 revisionHash；版本清单引用准确的 SkillKey（source/name/snapshotHash）和 endpoint 配置。owner 记录区分独立用户安装与 Connector revision，不能因包被删除而撤销另一个 owner 的快照或启用意图。

同名不同 hash 的 Skill 并存；共享只发生在完全相同 SkillKey。移除包先移除其 owner，只有无其他引用的快照才可转入可恢复清理区；不在同一次 remove 中永久擦除潜在独立用户资产。旧快照保留用于显式 rollback，不代表继续启用。

### 2. Scope 与实际执行

用户可以选择全局或指定会话启用 Connector revision。有效集合由仍有效的 owner/scope 绑定计算；用户显式全局禁用优先于所有 scope，重新启用时由用户选择 scope。会话结束/删除撤销该会话绑定，不影响全局或其他会话。

必须在 Skill list/read 与 MCP schema 曝光、实际发送前从可信 sessionId 检查绑定，不能只在 UI 隐藏。继续使用现有 Dispatcher/Capability/Policy/Approval，不让包或 Skill 自己写 scope。Connector enablement 仅控制可用性，不能作为执行批准。

端点不按 URL 自动跨 Connector 合并；保留独立 serverId、用户选择与凭据别名，避免相同地址意外共享账号。停用取消未发送的本地排队调用；已发送的远端结果继续对账，不重发、不声称撤回副作用。

### 3. 更新预览和提交

预览绑定 connectorId、旧 revisionHash、新 contentHash、新增/删除/修改的 Skill、endpoint、所需 scope 与凭据变化。确认后输入或当前 revision 变化则预览失效，重新生成差异；同一 transactionId 重复提交只返回原事务结果。

保持 endpoint 及 origin、认证绑定未变的配置可保留旧别名；改变任一认证/目标绑定必须建立新禁用端点并重新登录/选择工具，不能复制 token 到新 origin。新增 Skill/MCP 默认不自动启用。

### 4. Journal 与原子视图

使用 app-private 版本 2 清单、owner/scope ledger、单调 generation 与 journal；不迁移 Room。文件写入遵守同卷临时文件、flush/sync 和原子 rename。journal 的非敏感字段为 transactionId、connectorId、expectedOldRevision、targetRevision、stage、已创建的快照/端点引用和恢复结果；不保存 Secret。

状态为 PREPARING → PREPARED → COMMITTED → CLEANED，另有 ROLLED_BACK / NEEDS_REVIEW。准备阶段完成快照校验和禁用端点准备；所有可用性读者以同一个已提交 generation 为事实源。只有准备完整后才原子替换 active manifest 指针。实际调用前还要核对当前 generation，防止旧曝光窗口或排队调用跨过更新。

崩溃在提交前：根据 journal 清理仅由本事务创建且未被引用的暂存项，旧 revision 继续生效。崩溃在提交后：幂等完成清理，不重新执行远端 Tool。缺文件、损坏 journal 或无法证明所有权时进入 NEEDS_REVIEW，停用受影响包，保留证据和可恢复快照。

跨 Skill/MCP/文件写入没有假定的底层 ACID 事务；需要以 generation 读屏障和幂等恢复实现一致可用性。实现必须先验证该屏障覆盖所有生产入口，不能只让 Connector 列表看起来原子。

### 5. 迁移和回滚

v1 记录升级前保留原件；扫描现有 Skill/Connector 状态，既有全局启用无法归属时保守登记独立用户 owner，避免迁移撤销已有用户选择。不存在的快照/端点明确列为需修复；迁移不连接网络或运行 companion。

用户 rollback 是新的明确事务，引用保留的旧版本内容并重新校验；不恢复已撤销 Secret、旧审批 proof 或已经过期的会话绑定。文件/端点配置回滚不代表撤销远端历史副作用。

实现范围为 app/connector、app/mcp 与 extensions/skills 的所有权/会话读取接线、测试和文档。任何必须修改 core/storage schema、公共 Tool 格式或 Approval 语义的发现，先补本记录评审。

## Alternatives considered

- 只增加补偿 catch：不能解决进程被杀、跨存储可见窗口或共享 owner，因此不足以满足完整生命周期。
- 全部改用 Room：事务表达清楚，但 Skill 文件和外部服务仍不在 SQL 事务内，并扩大迁移范围；首版不选择。
- 每个 Connector 复制全部 Skill：所有权简单，但同内容重复、编辑/启用混淆与空间成本增加；可作无法共享时的兼容回退，不作为统一模型。

## Consequences

新增持久化版本、恢复阶段与生产可用性读屏障，需要明确维护成本。用户得到差异预览、可理解恢复、共享 Skill 不误删与 scope 管理；不会得到任意效果可回滚或自动授权。

## Verification

已核对当前 ConnectorService.install/remove、v1 encode/decode 与 SkillRepository 的全局/会话 overrides。尚未实现上述 journal、迁移和原子视图。

required before acceptance：所有者审查此持久化/启用契约。批准后先实现故障注入模型，再做生产接线；双 flavor JVM、Skill 单测、构建及质量门禁；API29/36 在每个 journal 阶段强杀、重复提交、低空间/写入失败、丢文件、旧版迁移、两个包共享 Skill、独立安装与包并存、跨会话隔离、schema/凭据变更、提交后恢复和显式 rollback。每阶段必须观察真实重启结果，不以 catch 内单测代替设备恢复。

## Reconsider when

无法让所有现有 Skill/MCP 入口读取同一 generation、跨进程所有权需要数据库事务、保留版本空间过大或实际用户需要更简单 scope 时，重新审查，不牺牲既有独立安装或批准边界。

## References

- [ADR-0023](0023-connector-portable-bundles.md)
- [ADR-0029](0029-skill-and-mcp-authoring-installation.md)
- [Connector 架构](../architecture/connector-portability.md)
- [当前 roadmap HXA-129](../development/roadmap.md)
