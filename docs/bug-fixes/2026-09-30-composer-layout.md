# Bug Fix: 会话输入区与模型选择收敛

Status: fixed
Date: 2026-09-30
Related HXA: HXA-219, HXA-190

日期：2026-09-30。范围：所有者追加的输入布局、模型/推理入口与上下文圆环；不改变执行、审批或模型用量账本。

## Problem

消息输入独占一行，推理与模型入口分离，上下文圆环隐藏在三点菜单，模式与交付信息分散。非零小占用显示 0%。

## Impact

编辑区重复占高、设置路径割裂，上下文占用容易被误认为始终为零。

## Root cause

输入按钮与文本为独立纵向节点；独立推理菜单和上下文二级入口沿用旧布局；百分比转换使用整数截断。

## Fix and invariants

- 顶部一行：当前模式靠左，输入交付计数靠右，上下文圆环与三点入口直接可见。窄屏/大字体的交付计数可横向滚动，不挤占编辑区。模式仍用 `/chat`、`/plan`、`/act`、`/goal` 修改。
- 语音、消息文本、加号、发送共享编辑行；执行中保留停止和继续输入/提交能力。消息可增长到五行。
- 底部保留模型入口。会话模型展开页包含推理选择；模型切换尚未应用时隐藏旧模型的推理选项，实际模型变化后重建推理菜单。生成期间仍禁止更换模型/推理设置。
- 模型切换延迟或失败时，可点击当前实际模型恢复推理设置，不重复提交模型切换、不重置当前推理值；增加相应回归夹具。
- 交付详情使用独立展开面板；排队/引导选择放入三点菜单，保留失效绑定提示。缺失附件提示与处理入口仍可见。
- 上下文圆环使用既有持久化的最近请求输入用量和实际配置窗口。原整数截断会把不足 1% 的非零占用显示成 `0%`，现显示 `<1%`，圆环本身继续使用真实浮点比例。未知用量仍为 `?`，压缩估算仍带 `≈`；不累计计费用量或把未发送文本当成已测量输入。

## Alternatives considered

不把用量向上伪增为 1%，不累计计费 token，不在模型切换提交完成前显示旧模型推理能力。保留未知和压缩估算区分。

## Regression verification

- 主机通过：双渠道单元测试、lint、detekt、APK 与 AndroidTest APK 构建（`build/composer-layout-host-r3.log`，876 tasks），另行 `spotlessCheck` 通过。单元测试 XML：consumer 1029、developer 1082，均 0 failure / 0 error，各 4 skipped；不把跳过算通过。
- `check-all.sh --source` 与 `git diff --check` 通过；文档格式初次检查失败后已按仓库模板修正并复验（`build/composer-layout-source-r2.log`）。本地秘密扫描通过不表示此前 GitHub OAuth 推送保护已解除。
- 新增/更新设备夹具覆盖 320/360/412dp、大字体、同排编辑、模型切换到推理选择、生成锁定、上下文入口与手动压缩路径。AndroidTest APK 编译不代表这些界面测试已运行。
- 所有者随后明确授权 API36，定向验证与追加修复见[后续收口](../evidence/development/composer-oauth-closeout-2026-09-30.md)。未安装到手机。
- 提交、历史清理及推送状态以后续收口记录为准，原始推送拦截保留历史记录。

## Residual risk

设备实际结果以后续收口记录为准，未跑的键盘/OEM路径不算通过；真实服务不返回 usage 时依然显示未知，不伪造占用。

## Related records

- [当前状态](../development/status.md)
- [Provider 契约](../adr/provider/001-models-and-connection.md)
- [上下文契约](../adr/agent/002-context-compaction.md)
