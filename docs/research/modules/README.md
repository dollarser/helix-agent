# Helix 当前综合研究模块

本目录是 `docs/research/` 的**当前研究结论层**。不同阶段、不同 Agent 的原始报告迁入 `docs/evidence/research-history/`；需要逐字证据、旧基线或当时推理时再读 history。

研究结论不是实现授权。使用顺序仍是：

1. 当前事实/下一步：`docs/development/status.md`；
2. 当前实施范围：active HXA；
3. 长期契约：accepted ADR；
4. 需要理解方案、竞品和候选取舍时：本目录。

## 模块

1. [产品定位与竞品基线](00-product-positioning-and-competitive-baseline.md)
2. [架构与执行引擎](01-architecture-and-execution-engine.md)
   - [专题：进程死亡恢复与 Harness 深度](process-death-recovery-and-harness-depth.md)
3. [上下文、输入与会话交互](02-context-input-and-session.md)
4. [UI、IA 与移动工作台](03-ui-ia-and-workbench.md)
5. [工具、浏览器与扩展生态](04-tools-browser-and-extensions.md)
6. [Runtime、Provider 与端侧模型](05-runtime-provider-and-on-device-models.md)
7. [评估、证据与研究方法](06-evaluation-and-evidence.md)

## 冲突裁决规则

同一问题存在不同阶段结论时，优先级为：

`当前 Helix 源码 / accepted ADR > 最新一手官方资料或维护者仓库 > 可复现项目证据 > 较旧研究判断`。

若两个方案都缺足够证据，采用最新研究作为临时推荐，并在模块里标记不确定性/重新评审条件，不把它伪装成 accepted 设计。

## 维护规则

- 一个问题只在一个模块维护当前综合判断；其他模块用链接，不复制完整结论。
- 产品卡、版本/渠道台账继续由 `docs/product/competitive-*` 维护，本目录只引用，不复制大表。
- 研究进入实施前必须更新/建立 ADR 和 HXA；Research 中的 P0/P1 只是研究优先级。
- 动态竞品能力只在影响 Helix 取舍时复核，避免为了“最新”无边界追版本。
