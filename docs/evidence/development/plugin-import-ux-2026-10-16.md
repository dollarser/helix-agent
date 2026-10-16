# 插件导入、更新与完成引导（2026-10-16）

范围：[HXA-300](../../development/tasks/HXA-300.md)。Owner 要求实施导入评审；原始工作区为 `main` / `6bbff42d`，184 个既有修改文件已保存到忽略的 `build/hxa300/`。本轮主机验证；设备、真实模型与业务账号 not requested，不提交、推送或发布。

## 实施

- 本地包与市场候选共用简洁预览：组件名称/数量、连接配置需求和无法自动运行的组件先展示，hash、URL、文件清单及正文放详情。依赖/stdio/安装脚本的限制说明直接使用解析诊断，不猜测账号或运行时已可用。
- 本地及市场安装结果保留真实安装 ID；配置与会话选用定位该项，原本导入后 `onUse(null)` 的遗漏已修正。安装仍不自动登录、执行脚本或选中会话。
- 导入异常映射为固定中英文恢复提示；区分市场目录、冲突、格式/技能、容量、访问及存储问题。失败前清除旧候选，取消仍抛出；异常正文不进入 UI。
- 更新摘要接到实际 `InstalledPlugin`/`PluginPackage`，删除仅测试调用的平行快照 DTO。展示技能和连接的增删、完整端点绑定变化；相等规则与安装时保留端点身份一致，包含 URL 路径、认证要求和认证绑定指纹，不展示指纹内容。
- 相同本地身份/相同内容复用；同名本地包仅提供候选，明确选择更新或独立安装。不按同名/hash 认领市场或原生项；原本明确从管理卡片发起的更新仍可更新该项。旧 canonical 身份已被后续版本占用时，独立安装使用新的身份，避免覆盖。
- 主动重新预览读取目标最新已提交版本，随后锁定捕获的 revision；目标被删、确认前并发更新仍失败。安装继续复用既有 Room 发布、端点重绑定、引用清理和取消边界，不新增权限或存储事实源。

竞品采用与取舍见[研究](../../research/topics/plugin-guidance-and-management-2026-10-16.md#导入体验优化hxa-300)，契约补入原 [Connector ADR](../../adr/connectors/003-ownership-and-installation.md)。

## 验证

原始日志和 XML 位于忽略的 `build/hxa300/`；各项通过仅覆盖其记录范围。

| 检查 | 结果 |
| --- | --- |
| 定向 JVM：更新摘要、导入目标、错误/限制 | 两渠道各 18/18，0 skip；`targeted-final.log` 与 `targeted-xml/` |
| 原始 QwenWork、WorkBuddy 和固定公开配置离线测试 | `python3 scripts/connector-samples.py test offline --output build/hxa300/offline-final`；两渠道各 3/3，0 skip；前两项增加实际包更新摘要断言 |
| 两渠道 Debug AndroidTest APK | 已编译；最终接线编译复核日志 `android-test-final.log`。新增三个 UI/Room 方法尚未执行，不作为设备通过 |
| 完整主机门禁 | `./scripts/check-all.sh --all`，`check-all-complete.log` exit 0；275 Python、全部适用 JVM/lint/spotless/detekt、双渠道 Debug/Release、Release smoke 测试 APK 构建、42 个锁文件和制品边界通过 |
| 最终 App JVM | Consumer 1269 项：1265 pass/4 skip；Developer 1390 项：1386 pass/4 skip，均无失败。三类输入已有上方离线专项；第四项匿名联网 MCP 本轮 not requested，不继承 HXA-299 的网络通过 |

定向构建首轮被新增代码的复杂度/格式规则阻断，拆分展示/错误分类函数后静态通过；设备夹具首次编译错误为把 Compose 成员断言当扩展导入，移除无效 import 后编译通过。未删除、跳过或弱化断言；失败日志保留。首个 formatter 调用使用相对路径未生效，随后仅对明确的本轮绝对路径执行格式化，完整门禁再检查实际结果。

首次完整门禁的 Python 275 项通过，随后文档检查发现新任务缺少规范的“验证入口/交付”章节名；已修正任务结构，再执行完整门禁。主机 runner 单测输出中的模拟设备失败/恢复片段来自测试 fixture，不是本轮操作了模拟器。

补齐市场来源加载错误后，该函数再次触及 Detekt 长度阈值；将本地/市场重复错误展示提成共用组件，单独 Detekt 和最终完整门禁均通过。未放宽规则或扩大异常消息展示范围。最终文档、ADR、国际化、secret 与差异复核记录在 `source-final.log`；主机通过不代表 R8 已安装运行。

## 边界

本轮不运行包内脚本，不连接包内业务服务，不安装 CLI/浏览器依赖，不自动探测并运行市场中缺失的组件。纯文档/Skill 可读与业务可执行继续分开；长期 stdio、依赖管理和完整资源契约仍属 HXA-286/288。新增 UI/Room 场景只有编译证据；已有历史模拟器验收不覆盖本轮交互变化。

本次保留既有工作：184 个原修改文件中 178 个内容不变，6 个重叠项仅为导入页面、扩展导航及相关任务/ADR/研究文档。本轮 25 个路径包括生产 Kotlin 10 个、测试 6 个、三套本地化资源和文档 6 个；具体清单见本地 `preservation.json`。关键实现为 `PluginImportReview`、`PluginUpdateSummary`、`PluginImportFailure`、`PluginImportPreview` 及本地/市场页面接线。没有修改 Mobile Use、Provider、会话存储或执行引擎，没有提交推送发布。
