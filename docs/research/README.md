# Research 当前入口

`docs/research/` 现在只保存**模块化当前综合研究**。不同阶段、不同 Agent 的原始报告已迁入 [`docs/evidence/research-history/`](../evidence/research-history/README.md)，保留当时基线和逐字证据。

Research 不是实现授权，也不是 current backlog。使用顺序：

1. 当前事实/下一步：[`development/status.md`](../development/status.md)；
2. 当前工作范围：active HXA；
3. 长期契约：[`ADR`](../adr/README.md)；
4. 方案、竞品、冲突裁决：[`modules/`](modules/README.md)；
5. 需要追查旧基线/旧 Agent 原始报告：[`evidence/research-history/`](../evidence/research-history/README.md)。

## 当前模块

- [产品定位与竞品基线](modules/00-product-positioning-and-competitive-baseline.md)
- [架构与执行引擎](modules/01-architecture-and-execution-engine.md)
- [进程死亡恢复与 Harness 深度](modules/process-death-recovery-and-harness-depth.md)
- [上下文、输入与会话交互](modules/02-context-input-and-session.md)
- [UI、IA 与移动工作台](modules/03-ui-ia-and-workbench.md)
- [工具、浏览器与扩展生态](modules/04-tools-browser-and-extensions.md)
- [Runtime、Provider 与端侧模型](modules/05-runtime-provider-and-on-device-models.md)
- [评估、证据与研究方法](modules/06-evaluation-and-evidence.md)
- [Agent 能力决定因素与提升指引](modules/07-agent-capability-determinants-and-improvement-guide.md)

## 当前最重要的跨模块裁决

- **Harness**：推荐“浅策略、深不变量”；模型主导工作流，Harness 主导事实、权限、执行与副作用边界。
- **Crash recovery**：已提升为 ADR-AGENT-001 accepted 方向：old Turn execution-terminal + successor Turn continuation；保留 effect UNKNOWN/review，不再恢复 same Turn/same GoalRun。HXA-220 负责代码迁移。
- **UI**：conversation-first + grouped drawer + in-place result；Workspace 是操作上下文，不把 Files/Git/Terminal 全塞进 Chat。
- **Browser/Tools**：保留 typed tools 和安全 token，优先补 tool-result multimodal；不把 QuickJS/任意 Node bridge 变成万能权限绕过。
- **Local model**：本地模型是一等 Provider，可直接驱动完整 Agent loop；能力等级由 probe/eval/设备资源决定，摘要/分类只是低风险验证入口。

## 冲突裁决与维护

同一问题有不同阶段结论时，优先：

`当前 Helix 源码 / accepted ADR > 最新一手官方资料或维护者仓库 > 可复现项目证据 > 较旧 research`。

无法判断时采用最新研究作为临时推荐并标记不确定性。研究进入实现前必须更新/建立对应 ADR/HXA；不要在 research 文件里维护实施进度。
