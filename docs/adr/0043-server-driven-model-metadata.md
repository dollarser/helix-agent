# ADR-0043: 服务端模型元数据驱动推理配置

Status: accepted
Date: 2026-09-10
HXA: HXA-190
Deciders: Project owner（明确要求服务端返回信息自动配置、保持灵活，不将实测目录写死到代码）
Supersedes: none
Superseded by: none

## Context

原有推理强度为固定枚举，能力快照只对应 Provider 默认模型。实际订阅目录按模型返回不同的推理选项、视觉能力和上下文窗口。仅把一次实测的模型名和选项加入常量，无法正确响应服务端变化，也会导致切换非默认模型后推理选项不可选。

## Decision

扩展 ModelProvider 的公开元数据查询契约，以精确模型 ID 返回可空的推理选项、视觉能力与上下文窗口。推理强度使用有界 token 值类型，保留旧持久化的 OFF/LOW/MEDIUM/HIGH 表示；服务端合法的新 token 无需升级 App 即可保存、展示、传输。OFF 仅表示本地默认设置，不发送 effort；服务端 none 是独立选项。

在用户触发的 Provider 连接测试中获取、校验并替换目录；按 Provider/endpoint 保存版本化公开元数据。未知字段不等同不支持，明确空推理列表表示不可选。没有逐模型目录的旧 Provider 继续对已验证的默认模型使用既有低/中/高兼容选项，不据模型名称推断能力。UI 与请求组装读取同一目录，发送前校验当前选择；模型变更重置为默认。上下文窗口仍保留 ADR-0037 的用户上限与自动压缩设置。

Codex 的目录请求、认证和刷新仍由 ADR-0021 的独立 Runtime UID 执行。主 App 只接收有界公开元数据；被动启动和 Registry 刷新不冷启动 Runtime。此次不改变 OAuth、工具授权或渠道边界。

Codex 工具请求使用请求内确定性名称映射，满足实际服务端函数名限制；历史调用和返回调用使用同一映射，返回后恢复内部名称。未知或当前未声明的名称不能因映射进入 Dispatcher；调用 ID、参数、授权绑定和审计中的本地名称保持原样。

## Alternatives considered

- 写死六个模型及各自选项：与所有者明确要求冲突，且服务端变化后需要发布补丁。
- 给所有模型展示相同强度：无法表达明确不支持或未来新增选项，只适合作为无元数据旧 Provider 的有限兼容方式。
- 修改全部内部工具名为下划线：会改变既有授权、Skill 和审计语义；名称转换应限于协议边界。

## Consequences

新增元数据存储和 IPC 字段边界需要回归；旧安装在首次连接测试前没有这份缓存，沿用已有保守能力快照。目录更新不证明全部模型调用、视觉或工具行为已通过端到端验收。服务端语义变化和旧 Runtime 兼容仍需观察，主 App 与 companion 应同步更新。

## Verification

真机授权账号目录返回逐模型不同的选项；独立合成请求已证明低/高推理 HTTP 200，带点工具名 HTTP 400，而下划线对照 HTTP 200。适配层名称修复后同一合成诊断 3 项通过。逐模型 UI、主 App 模式回归与完整门禁仍按 HXA-190 记录，不能由本 ADR 的 accepted 状态推断完成。

## Reconsider when

服务端提供版本化能力订阅、不同参数类型或需要无连接测试的自动刷新时重新评估缓存与生命周期策略。

## References

- [ADR-0021](0021-third-party-subscription-protocol-adapter.md)
- [ADR-0037](0037-context-window-and-compaction.md)
- [HXA-190 真机记录](../development/hxa190-physical-update-2026-09-10.md)
