# 文件工具提示审查

Date: 2026-10-16
Scope: Owner 指定审查 `app/src/main/resources/prompts/files.md`；提示与说明修正，不启动架构重构

## 核实与调整

在 `main` / `6bbff42d` 上检查当前源码，保留上一轮会话工具及文档的未提交改动。本轮改动为 `files.md`、`WriteTool.kt` 的两处说明、HXA-286 的具体回归缺口与本记录；不修改执行、权限、目录或存储实现。

- 相对路径/scoped 引用、原文保真、前置哈希保护继续保留；将不存在的短名 `stat` 改成 `files.stat`，普通路径与归档/解压的 scope 内 `work/` 目标要求分别说明。
- 补充 `read` 字节分页与工具结果二次截断的不同游标，防止将片段作为全文覆盖；按需读取，不要求所有任务读完整文件。
- 明确新建与 `overwrite=true` 的整文件替换，优先精确编辑并保留无关内容；父目录缺失时沿已有 `files.mkdir`。文本写入不等于二进制解码。
- 冲突时保留当前文件、读取新内容再合并；只剩用户选择时询问该决定并继续独立工作。移除一律结束任务的旧要求，与 `base.md` 的继续工作/具体阻塞规则一致。

采用 [Claude Code 官方实践](https://code.claude.com/docs/en/best-practices#write-an-effective-claude-md) 的精简、具体且可验证的指导原则；只写 Helix 易混淆的工具语义，不复制第三方提示、桌面路径或权限规则。没有以文字修改宣称模型完成率或 token 成本已改善。

## 发现但未补齐的工具契约

后续更新：Owner 随后明确要求局部问题当前解决，[HXA-296 实施记录](prompt-alignment-2026-10-16.md) 接续修复读取哈希与编辑链路。下文“未补齐”描述本次提示审查结束时的发现，不覆盖后续实施状态。

`ReadTool.output` 仅包含内容窗口、编码及分页信息，`FilesStatTool` 无哈希；`ToolModelResult` 不为它们补文件身份。`EditTool` 要求完整文件 SHA-256，`WriteTool` 原说明却要求使用“read 返回的 hash”。这是静态确认的链路缺口，不能靠提示凭空生成。

本轮修正错误说明并要求缺失时报告真实能力边界；不放宽现有保护。成功写入/复制等结果可带文件哈希，但不证明任意旧文件或最新内容已能通过 read 获得哈希。完整读取、版本凭据与修改的闭环接入 [HXA-286](../../development/tasks/HXA-286.md)，当前仍未实现，后续须覆盖 Standard 与 Developer，并同步去除本轮关于现状的提示。不得混用窗口、工具结果或截图哈希。

## 验证

执行 `./gradlew spotlessCheck :tools:files:test :app:testDeveloperDebugUnitTest --tests com.helix.app.chat.PromptEnvironmentSectionsTest --tests com.helix.app.chat.FileToolArgumentsTest --tests com.helix.app.chat.ToolModelResultTest`，exit 0：文件模块 121/121、提示装配/参数路径/结果投影 26/26，共 147/147，0 skipped；格式检查通过。日志位于忽略目录 `build/file-prompt-review-2026-10-16/host-targeted.log`。

`./scripts/check-docs.sh`（896 Markdown / 278 HXA）、`./scripts/verify-adr.sh`（37 ADR）和 `git diff --check` 通过。本轮未重跑全仓构建，也未新增重复文案的测试；沿用相关行为/失败边界测试。提示词测试仅能验证装配与已有行为，不能代替真实模型 A/B；本轮设备/真实 Provider 为 not requested，未提交或推送。
