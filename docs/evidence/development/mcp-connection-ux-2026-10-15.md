# MCP连接体验收尾

日期：2026-10-15。范围：[HXA-126](../../development/tasks/HXA-126.md)、[OAuth决策](../../adr/connectors/002-oauth.md)。Owner要求优化连接体验、完善文档并完成重构前可执行收尾，明确允许设备、模型和真实MCP。起点9465376d；仅使用日常API36/Developer和既有Notion、Linear指定对象，不复制其他客户端凭据、不重复注册、不执行业务写入。未连接实体手机。

## 改动与参考

常规入口改为“连接账号”，Client ID、scope、精确回调默认折叠于高级连接设置。用户点击后发现元数据并复用已保存的客户端，再打开系统浏览器；无身份时显示配置方式，不自动POST。手动身份从原SecretStore服务选择记录复用，显式选择优先于共享注册；issuer/redirect变化拒绝，断开账号保留客户端，明确忘记才删除配置。Linear官方主端点默认read，不枚举全部服务scope。

参考[Codex MCP](https://developers.openai.com/codex/mcp)、[Claude Code MCP](https://code.claude.com/docs/en/mcp)、[OpenCode MCP](https://opencode.ai/docs/mcp-servers/)的独立登录、自动发现与特殊配置入口；未复制实现。Helix保留Android精确回调、显式兼容注册确认及原工具选择/Dispatcher审批，不采用桌面loopback或厂商托管身份。

## 实际验证

所有构建/设备命令经with-host-slot串行；原始日志在忽略的build/oauth-ux-2026-10-15。

- OAuthClientRegistrationsTest：12项JVM，0失败/跳过；新增手动身份重启复用、共享注册优先级、绑定变化及忘记/空身份拒绝。既有未知POST、迟到结果、取消/替换和CIMD重验保留。
- API36实际OAuthClientSetupDeviceTest：4项，0失败/跳过；高级字段默认隐藏、折叠后保留编辑值、兼容注册先选择再确认、取消不注册。最终生产APK81f9157ff6de2ce615a9d8cd2e4218ded069d74a6afa9ccb847f07e1894627e5，测试APK7776b67f97def37708c5b792b4dd15f8c8f40215da157633c0c02c19cf74f072；原始JUnit流见device-final。较早device-v2单独保留，不继承为最终字节制品通过。
- 两家官方服务各1项真实只读/审批拒绝方法重新执行，均通过；新增直接复用本服务已保存的客户端并用空配置resolve，与现有账号绑定client一致。没有为了证明新入口而撤销正常账号或重复注册。原始结果在build/oauth-official-read-2026-10-15/run-v2。
- 同schema覆盖更新前备份，六张用户表不变、数据库完整性通过；没有清数据或数据库迁移。

首次完整主机门禁all.log退出0；最终审查收紧取消连接、忘记客户端或修改高级参数后的意图失效，防止旧请求随后打开浏览器，all-final.log再次退出0，不继承旧绿。228项主机脚本、完整JVM、spotless/detekt、双渠道Debug/Release lint/APK、41份依赖锁和隔离/Runtime/渠道边界通过；日志中的受控FAIL_FUNCTIONAL/INCONCLUSIVE为报告器负例，不是本轮设备失败。两渠道AndroidTest编译通过，最终精确APK上的两家只读/拒绝复测也通过（run-final），自然刷新均not_due。

六张原用户表hash与integrity/FK检查通过，清理自有测试残留，没有回写旧凭据。不能把构建或已有账号读取记为新的完整浏览器重连。自然刷新仍按实际到期；厂商撤销/旧Token失效需Owner方便重连后执行，已询问是否方便，本轮未收到答复前不撤销正常授权。浏览器取消没有错误回调的历史事实保留，不伪造拒绝通过。

## 失败和修正

第一次误用不存在的模块级spotlessApply，未产生通过结论；改根格式化任务。新增UI意图使函数参数/复杂度触及既有门禁，拆分连接意图、折叠组件和确认/发现效应，未放宽阈值或新增生产抑制。i18n默认资源和新增文档夹具长行随后修正，最终命令另记，不继承失败轮结果。

## 边界

本轮不是自有CIMD托管、发行签名/App Link、所有服务/OEM或真机验收。当前只有模拟器，实体设备请求仍pending；账号授权、MCP连接和工具启用保持独立事实。长稳及提前结束专项按主会话继续暂停。
