# 产品文档入口

本目录保存产品需求、用户任务体验、市场/商业分析与竞品研究。产品研究可以影响优先级，但**不直接授权实现**；当前开发范围仍由 status/HXA/accepted ADR 决定。

核心入口：

- [v0.0.6 版本说明](releases/v0.0.6.md)（[English](releases/v0.0.6.en.md)）：开发预览候选、安装条件和已验证边界；尚未创建对应 GitHub Release。
- [requirements.md](requirements.md)：Android 单机版产品需求与能力边界。
- [workspace.md](workspace.md)：会话工作目录、后端能力与保留文件边界。
- [projects.md](projects.md)：本机项目、共享上下文、成员生命周期与文件/任务/成果的关系。
- [task-experience.md](task-experience.md)：用户任务、工作区、产物与恢复体验。
- [image-reading.md](image-reading.md)：附件、自主图片读取与浏览器截图的使用和支持边界。
- [document-processing-plugin.md](document-processing-plugin.md)：统一PDF/DOCX阅读插件，图文读取、解析隔离、来源和验收；HXA-284已完成主机、API36原生文档及Qwen合成文件验收；范围见[收尾证据](../evidence/development/pre-refactor-closeout-2026-10-15.md)。
- Helix 自身功能查询：两渠道随内置 `helix-docs` Skill 提供中英文离线主题，模型按需读取，版本与渠道事实由宿主提供；不是静态帮助页面，构建来源与签名等尚未暴露的事实不推测，见[当前验证边界](../development/release-readiness.md#4-模型服务与评测边界)。
- [memory.md](memory.md)：长期记忆使用与作用域；完整 Project 能力与 Global 首版分开。
- [local-models.md](local-models.md)：设备内模型的准备、使用与验证边界。
- [market-users-and-commercialization.md](market-users-and-commercialization.md)：用户、渠道、商业化假设与实验。

竞品研究采用“总览 + 支撑材料”结构，不合成一个巨型文件：

- [competitive-landscape.md](competitive-landscape.md)：决策总览和阅读路径，是竞品主入口。
- [competitive-doubao-phone-gap.md](competitive-doubao-phone-gap.md)：豆包手机助手/Roubao 定向核验、Helix 差距、首任务入口改进和后续验收协议。
- [competitive-direct-agents.md](competitive-direct-agents.md)：直接移动 Agent 档案。
- [competitive-alternatives.md](competitive-alternatives.md)：替代方案与技术参照。
- [competitive-platform-ecosystems.md](competitive-platform-ecosystems.md)：系统/OEM/平台生态。
- [competitive-evaluation.md](competitive-evaluation.md)：横评方法与待执行协议。
- [competitive-evidence.md](competitive-evidence.md)：来源、核验批次与材料校正。

竞品文件应保持事实、判断和待验证项分离；研究中的优先级只有进入 status/HXA 后才成为工程任务。
