# HXA-235：Plugin 生命周期生产接线与主机验证

日期：2026-10-01。基线为 `main@05e91003` 的未提交工作树；保留已有 R2-A、回放、OAuth 及并行修改。本记录不代表已提交、设备通过或发行。

## 实现范围

- 包解析、不可变内容和安装目录归入 `extensions:plugin` / `app.plugin`；Marketplace、本地导入与模型安装入口共用原安装事务。MCP 协议、Skill 快照和唯一 ToolRegistry 保留各自职责，未增加另一套数据库或 Dispatcher。
- Agent Plugins v1 使用固定 `plugin.json` / `mcp.json` / `skills/<name>/SKILL.md`。与 Claude/Qwen 导出适配分开；无效组件局部报告，归档安全/身份错误整体拒绝。外部元数据不加载原生代码、不导入凭据、不授予审批。
- Mobile Use 的宿主工厂接同一 Room 安装事实；停用、重开、修复、重新启用保留用户选择，已撤销 BindingRef 不复活。缺失注册投影不能仅凭安装记录或版本相同显示就绪。
- 部分可用与全部就绪分开；无效 MCP 不妨碍有效 Skill 使用，但不能把包显示成全部就绪。内置代码随 APK 更新，界面只提供停用/修复，不声称可单独删除 APK 内代码。
- 更新按完整命名端点绑定复用身份，不按相同 URL 共享账号。独立 Skill、其他包引用、在途 MCP 调用及历史继续保护；停用不是卸载或撤销已发送效果。

主要入口：`PluginService`、`PluginCatalog`、`PluginInstaller`、`PluginRegistry`、`RoomNativePluginCatalog`、`PluginEndpointBindings`、`PluginComponentReadiness`、`ConnectorSection`、`ConnectorSessionPanel`。

## 本次重新核对与修复

上一轮摘要中的 `allBindings()/binding()` 和缺失 `PluginService` 方法已经在当前工作树修正，本次不重复修改或计作新发现。

1. 定向命令误选不存在的 `:extensions:mobile-use:testReleaseUnitTest`，任务选择失败，未执行测试；删除错误选择后重跑，不修改产品能力或跳过已有测试。
2. 全仓构建发现 `mobile-use` 的 `debugRuntimeClasspath` 依赖锁缺失。包解析迁移后只锁定了单测运行配置；用 `:extensions:mobile-use:dependencies --write-locks` 生成实际配置锁，保留既有固定依赖版本，不关闭锁校验。
3. 完整主机命令在 900 秒处超时，并已报告 `ConnectorSessionPanel` 圈复杂度超标。抽出只呈现组件就绪事实的 `PluginSessionState`，不改变操作、选择、审批、测试标记或状态优先级；未放宽或抑制复杂度门槛。线程快照显示当时在执行 Kotlin/UAST lint，不能把超时说成 lint 通过。
4. 重跑使用同一组门禁、最多两个 Gradle worker，并将完整输出写到 `build/hxa235-host-verified.log`，避免仅在任务结束后才能定位中途失败。

## 已实际执行的验证

定向命令通过（exit 0）：

```sh
python3 scripts/with-host-slot.py -- ./gradlew \
  :extensions:plugin:test :extensions:skills:test \
  :extensions:mobile-use:testDebugUnitTest :tools:framework:test \
  :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest \
  :app:compileConsumerDebugAndroidTestKotlin :app:compileDeveloperDebugAndroidTestKotlin \
  --console=plain --continue
```

该次报告中 `extensions:plugin` 五组共 43 项，失败/错误/跳过均为 0；App 的组件就绪和端点绑定两组共 10 项，失败/错误/跳过均为 0。其他既有测试保留原有条件性 skip；不把这些报告解读成真实服务验收。

新增真实 Room 安装/启停/凭据边界与 Mobile Use 设备用例仅编译，未执行。

## 最终门禁状态

完整重跑已通过（exit 0，989 个 Gradle tasks，其中 51 executed、938 up-to-date）。执行入口为 `scripts/debug/2026-10-01/validate-r3-host.sh`，包含 test、detekt、spotlessCheck、双渠道 lint、Debug APK 与 AndroidTest APK；输出及退出码保存在 `build/hxa235-host-verified.log` / `.exit`。随后 `check-all.sh --artifacts` 通过双渠道制品和订阅排除检查；`check-all.sh --source` 通过 682 Markdown、218 HXA、35 ADR、1938 多语言键和秘密扫描，`git diff --check` 通过。该结论只覆盖本次 R3 源码候选，不自动覆盖之后 J1 的修改。

设备/真实模型：not requested。当前任务未提交、未推送、未发布；R3 的本地收口不等于 R2-A/回放/自主恢复的设备与真实轨迹验收，更不等于 J1/J2、Project Memory 或发行完成。

## 官方实践依据

当日核对 [Agent Plugins 加载规范](https://agent-plugins.org/client-implementers/loading-and-discovery) 与 [Claude 插件参考](https://code.claude.com/docs/en/plugins-reference)：严格区分不同格式的目录/组件规则、局部失败、版本内容及用户配置。对后续 Job 工作核对 [Codex App Server](https://developers.openai.com/codex/app-server) 和 [Claude 后台命令](https://code.claude.com/docs/en/interactive-mode#background-bash-commands)：沿用原执行身份、分离观察和执行；Helix 继续保留原预算和 Android 生命周期限制，不复制桌面进程或超时策略。
