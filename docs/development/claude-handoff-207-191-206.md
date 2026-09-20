# Claude Code / 小模型实施交接与历史计划：207 / 191 → 206

更新：2026-09-20。
> [!IMPORTANT]
> **历史状态更新**：[HXA-207](../completion-records/HXA-207.md)（扩展添加到使用闭环）与 [HXA-191](../completion-records/HXA-191.md)（深色主题与会话搜索）已全部完成并通过双 API 四象限验证合入 `main`。
> 当前正在执行的小模型工作包由 [小模型工作包指导（small-model-handoff.md）](small-model-handoff.md) 统驭（包含 HXA-206 场景与校验脚本、HXA-199 终端报告契约、文档收敛及 HXA-198 UI 准备）。
> **请勿重新开发已完成的 207 与 191**。

## 顺序与边界演进

1. **已交付**：[HXA-207](../completion-records/HXA-207.md) 扩展添加到使用闭环（含安装、启用、权限与三语言）。
2. **已交付**：[HXA-191](../completion-records/HXA-191.md) 深色主题与会话搜索（含系统日夜切换与双 API 验证）。
3. **当前准备阶段**：[HXA-206](tasks/HXA-206.md) 核心产品闭环综合验收目前处于场景映射、结果校验/统计脚本与报告契约准备阶段（见 [HXA-206 准备文档](../evidence/development/hxa-206-preparation.md) 与 `scripts/verify-product-journeys.py`），全量产品运行与最终验收待协调者统一调度。

本文件保留下方 207/191/206 原始设计规格作为历史证据与验收基线对照，新接手者请直接遵循 [small-model-handoff.md](small-model-handoff.md)。

## 接手前基线


- 阅读 README、AGENTS、status、对应 HXA 及 ADR 主题入口。先确认新会话授权快照、Room v23 迁移、权限事务、Runtime 失败结算修复已在所选基线；不得恢复旧默认权限跟随、累计响应截断或旧三态工具权限。
- 执行 `./scripts/check-all.sh --all` 和 `git diff --check`；解决已知强制主机/设备失败后才能开始下一 HXA。历史绿色不能代替修改后验证。
- 设备脚本先保存在 `scripts/debug/YYYY-MM-DD/`；使用现有 owned runner，记录 APK 哈希、测试数量/失败/跳过与 finally 退出。外部 profile 缺输入可明确 skip，已提供但无效的输入必须报错。
- 具名本地提交，按路径暂存；保留其他执行者 WIP。交接本身不授权 push、合并、发行或真实服务外部写入。

## 207：先让现有扩展真正可用

切片一核实并复用当前 Skill 导入、MCP 配置和 Connector 应用入口，明确预览、确认、安装/连接、用户启用、授权与可用状态。不要新增每个服务专用的规划器、Agent 循环或工作流；配置、查看与修复属于应用能力，除已有必要安装工具外，不为每个按钮添加 Agent Tool。

切片二接通“添加 → 准备/修复 → 明确启用 → 一次实际工具任务 → 可读结果”。复用 202/204/205 导航、恢复与准备投影，复用统一注册、发现、Policy/授权、执行与审计。连接成功不代表启用；安装成功不扩大权限；Skill 的实际全局范围不能描述成会话范围。禁用后检查发现列表、模型 schema 和执行入口三处一致。

切片三新增标准 app androidTest 的 `ExtensionJourneyDeviceTest`：预览后内容变化、导入取消/重复点击、无效配置、连接但未启用、启用后调用失败、工具禁用、CUSTOM/ASK、重启后的真实范围与原地修复。受控 fixture 验证执行链，真实受保护服务仍归 125，不提前开发 126/129/130 的 OAuth、市场或版本所有权。

验收执行 [公共 P1/P2/P3](verification-matrix.md) 中适用项及：

```sh
./gradlew :extensions:skills:test :extensions:mcp:test
./gradlew :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest
./gradlew :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest
```

API29/36 × consumer/developer 四象限运行新增旅程，保存实际任务结果与失败路径，不以“设置保存成功”替代实际调用通过。

## 191：只补主题，回归已有搜索

切片一核对并统一跟随系统的主题来源；若增加手动选择，持久化并明确“系统/浅色/深色”的实际行为。覆盖主页面、对话框、系统栏、文件/任务/准备/授权页面以及浏览器的应用外壳；不强制改写外部网页配色，不把主题切换做成模型工具。

切片二补实际 UI 验证：系统日夜切换、旋转、进程重启、三语言、大字体与可读对比度；授权卡和恢复入口仍可操作。搜索沿用现有只读 repository、候选边界和截断提示，回归标题/正文/归档命中、空态、清空与打开结果。主题与搜索不发起模型请求，不扩大跨会话 Agent scope。

执行公共 P1/P3，若改查询/存储再追加 P2 与实际迁移。执行现有 `SessionSearchDeviceTest`、`SessionSearchQueryDeviceTest`，新增具名主题测试并在双 API 双 flavor 实际运行。不得以已有搜索切片的历史四象限结果宣布主题通过；两部分都完成才收口 HXA-191。

## 206：在整合代码上验收产品

开始前确认 191、192、209、202～205、207 的范围已完成，列出本次确切提交和制品。复用各任务 fixture，新增统一 `scripts/verify-product-journeys.py`，严格检查非零执行、失败/跳过和 owned 进程退出，不复制第二套产品状态机。

固定旅程包括读取资料形成结果、修改文件并打开产物、ASK 批准、DISABLED/CUSTOM DENY 拒绝、预设/CUSTOM ALLOW 免卡及显式 rm 规则、取消、重启对账不重复副作用、能力失效修复后用户继续，以及主题/搜索/Plan/扩展完整链路。纯应用查看、准备和搜索不得额外调用模型。普通任务无需强制 Goal、PRoot 或子 Agent。

保留本轮修复回归：会话默认只影响新会话、v22→v23 与进程恢复、权限修改/审计事务、SAF 当前来源移除、标签上限、目录截断反馈；涉及 developer Runtime 时运行既有集成 Runtime 测试。另按任务规格补只读 Git 恶意配置/filter/transport 的 debug/release 实际证据，不增加 remote Git 或写操作。

执行 P1/P2/P3、`./scripts/check-all.sh --all`、新增全部设备类和四象限产品旅程。按固定 fixture 记录步骤数、重复弹窗数、产物可打开性、恢复是否重复副作用、实际成功/失败/跳过；不以测试数量代替任务成功率。真实账号、物理设备 OEM/Doze/热压、长稳及发行独立记账。未通过不写完成记录，不将 fixture 冒充真实服务通过。

## 每项交付清单

- 代码与测试、实际命令及结果、制品身份、失败修复经过与剩余边界。
- 同步该 HXA 完成记录、status、roadmap、索引；未完成只记切片，不提前删除任务规格。
- 一段可复用交接摘要：基线/提交、修改范围、验收范围、未闭合项、下一个独立任务。分别报告本地 commit、合并与远端 push。
